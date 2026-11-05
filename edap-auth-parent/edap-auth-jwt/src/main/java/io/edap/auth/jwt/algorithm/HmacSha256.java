package io.edap.auth.jwt.algorithm;

import io.edap.auth.jwt.Algorithm;
import io.edap.crypto.CryptoRegistry;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 统一入口。
 *
 * <p><b>实现走 edap-crypto 的 {@link CryptoProvider} 抽象</b>：本类只负责
 * 「持有 key + 每线程一个 {@link MacSession}」，具体是 native（OpenSSL JNI）还是
 * JCE（{@code javax.crypto.Mac}）由 {@link CryptoRegistry} 按开关解析：</p>
 * <ul>
 *   <li>显式注册过（{@code CryptoRegistry.registerMac("HmacSHA256", ...)}）⇒ 用注册的；</li>
 *   <li>{@code edap.crypto.mac.native=false} 或 {@code edap.crypto.mac.provider=jce} ⇒ JCE；</li>
 *   <li>默认 ⇒ native 优先；native 不可用（classpath 无 edap-native / 当前平台无预编译 .o）
 *       ⇒ 打<b>一次</b> WARN 后降级 JCE。</li>
 * </ul>
 *
 * <p><b>旧开关仍然生效</b>：{@code -Dedap.jwt.hmac.native=false|disable|off}
 * ⇒ 本类强制走 JCE（<b>只影响 JWT</b>，不改全局系统属性，其它模块不受影响）。</p>
 *
 * <p><b>线程安全</b>：{@link MacSession} 是<b>线程绑定</b>的单位（JCE 的
 * {@code Mac} / native 的累加器都不可跨线程共享），所以本类用
 * {@link ThreadLocal} 每线程持一个 session。key 不可变（构造期拷贝），
 * 本类实例本身可跨线程安全发布。</p>
 *
 * <p><b>会话复用</b>：{@link MacSession#doFinal()} 之后 session 回到初始状态
 * （key 保留），所以同一个 session 可以连续 sign 多次，无需重建。</p>
 *
 * <p><b>兼容</b>：{@link HmacSha256Native} 这个 public 嵌套类保留（外部可能直接引用），
 * 它仍然是一条独立的、直接绑 JNI MethodHandle 的路径，输出与本类的 native 路径逐字节一致。</p>
 */
public class HmacSha256 implements Algorithm {

    /**
     * JCE 侧的 MAC 算法名。
     */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final byte[] keyBytes;

    /**
     * 每线程一个 MAC 会话 —— session 独占 JCE {@code Mac} 实例 / native 累加器，
     * 不能跨线程共享。
     */
    private final ThreadLocal<MacSession> sessions;

    public HmacSha256(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
        final MacProvider provider = resolveProvider();
        this.sessions = ThreadLocal.withInitial(() -> provider.newSession(keyBytes));
    }

    @Override
    public byte[] sign(byte[] data, int offset, int len) {
        MacSession session = sessions.get();
        session.update(data, offset, len);
        // doFinal() 之后 session 回到初始状态（key 保留）⇒ 下次 sign 不用重建
        return session.doFinal();
    }

    /**
     * 解析本次实例要用的 MAC provider。
     *
     * <p>顺序：</p>
     * <ol>
     *   <li>旧开关 {@code edap.jwt.hmac.native=false|disable|off} ⇒ 强制 JCE
     *       （<b>不走注册表</b>，这样显式禁用 native 时不会被注册表里的 native 覆盖）；</li>
     *   <li>否则走 {@link CryptoRegistry#mac(String)}：显式注册优先，
     *       没注册则按 {@code edap.crypto.mac.*} 开关解析（native 优先，不可用则降级 JCE）。</li>
     * </ol>
     */
    private static MacProvider resolveProvider() {
        if (legacyNativeDisabled()) {
            return new JceMacProvider(MAC_ALGORITHM);
        }
        return CryptoRegistry.mac(MAC_ALGORITHM);
    }

    /**
     * 旧开关 {@code edap.jwt.hmac.native} 是否<b>显式</b>禁用了 native。
     *
     * <p>注意区分「显式禁用」与「native 不可用」：前者要强制 JCE，
     * 后者交给 {@link CryptoRegistry} 解析（它会打 WARN 提示降级）。</p>
     */
    private static boolean legacyNativeDisabled() {
        String prop = System.getProperty("edap.jwt.hmac.native", "true");
        return "false".equalsIgnoreCase(prop) || "disable".equalsIgnoreCase(prop)
                || "off".equalsIgnoreCase(prop);
    }

    /**
     * HMAC-SHA256 native 实现（OpenSSL via {@code edap-native}）。
     *
     * <p>本类是<b>独立</b>路径：直接绑 JNI MethodHandle，不经过 edap-crypto 的
     * provider 抽象。{@link HmacSha256} 默认走 provider 抽象（由开关决定 native/JCE）；
     * 本类保留给「明确要 native、且不想引入 edap-crypto 语义」的场景，
     * 以及历史 API 兼容。两条路径的输出逐字节一致。</p>
     *
     * <p>默认行为：edap-native 在 classpath 且当前平台 .o 加载成功时，
     * {@link io.edap.auth.jwt.AlgorithmRegistry} 静态初始化会用本类替代
     * {@link HmacSha256} 作为 HS256 的默认实现 — 无需任何系统属性。
     * 仅在以下情况 fallback 到 Java：
     * <ul>
     *   <li>显式禁用：{@code -Dedap.jwt.hmac.native=false}（用户明确要走 JCE）</li>
     *   <li>classpath 缺 edap-native</li>
     *   <li>当前平台无对应 .o（{@link io.edap.jni.Native#ENABLE_NATIVE}=false）</li>
     * </ul>
     *
     * <p><b>MethodHandle 加载</b>：避免 edap-auth-jwt 对 edap-native 的硬编译依赖。
     * 构造时一次性 findConstructor + findVirtual + bindTo，{@link #sign} 路径走
     * {@link MethodHandle#invokeExact}（绕过 {@code Method.invoke} 的参数 boxing /
     * 访问检查 / 异常拆包），较反射 ~50-150ns/调用 提升。</p>
     *
     * <p><b>线程安全</b>：key 不可变（构造时拷贝 byte[]），{@link #sign} 把 key + data
     * 一起交给 native 单次调用，无共享可变状态；与 Java 实现的 ThreadLocal&lt;Mac&gt; 等价。</p>
     */
    public static class HmacSha256Native implements Algorithm {

        private static final String NATIVE_CLASS = "io.edap.jni.crypto.NativeHmacSha256";
        private static final String NATIVE_API_CLASS = "io.edap.jni.Native";

        /**
         * 检测 native 是否可用。
         *
         * <p>优先级：
         * <ol>
         *   <li>{@code -Dedap.jwt.hmac.native=false|disable|off} → 显式禁用，false</li>
         *   <li>classpath 缺 edap-native / 无对应平台 .o → false（自动 fallback）</li>
         *   <li>其余情况 → true（默认走 native）</li>
         * </ol>
         */
        public static boolean isAvailable() {
            String prop = System.getProperty("edap.jwt.hmac.native", "true");
            if ("false".equalsIgnoreCase(prop) || "disable".equalsIgnoreCase(prop)
                    || "off".equalsIgnoreCase(prop)) {
                return false;
            }
            try {
                // 顺序：先触发 NativeHmacSha256 的 static init → 它会调 Native.loadLibrary() →
                // 只有 loadLibrary() 跑过之后 ENABLE_NATIVE 才被设成 true。
                // 旧顺序先读 ENABLE_NATIVE 会拿到初始 false，永远到不了这里。
                Class.forName(NATIVE_CLASS);
                Class<?> apiCls = Class.forName(NATIVE_API_CLASS);
                java.lang.reflect.Field f = apiCls.getField("ENABLE_NATIVE");
                return f.getBoolean(null);
            } catch (Throwable t) {
                return false;
            }
        }

        // MethodHandle 缓存：构造时一次性 findConstructor + findVirtual + bindTo，
        // sign() 路径只走 invokeExact（绕过 Method.invoke 的参数 boxing / 访问检查 / 异常拆包），
        // 比反射 ~50-150ns/调用快。
        private final MethodHandle signHandle;

        public HmacSha256Native(String key) {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            try {
                Class<?> cls = Class.forName(NATIVE_CLASS);
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle ctor = lookup.findConstructor(cls,
                        MethodType.methodType(void.class, byte[].class));
                Object nativeInstance = ctor.invoke(key.getBytes(StandardCharsets.UTF_8));
                // sign(byte[] data, int offset, int len) 内部会调
                // sign0(key, key.length, data, offset, len) —— 显式传 keyLen
                // 是为了让 native 端省掉 GetArrayLength 一次 safepoint
                // （详见 edap-native/doc/NATIVE_DESIGN.md §6.2）
                MethodHandle sign = lookup.findVirtual(cls, "sign",
                        MethodType.methodType(byte[].class, byte[].class, int.class, int.class));
                this.signHandle = sign.bindTo(nativeInstance);
            } catch (Throwable t) {
                throw new UnsupportedOperationException(
                        "failed to bind native HMAC-SHA256 via MethodHandle; "
                                + "isAvailable() should have caught this", t);
            }
        }

        @Override
        public byte[] sign(byte[] data, int offset, int len) {
            try {
                return (byte[]) signHandle.invokeExact(data, offset, len);
            } catch (Throwable t) {
                throw new RuntimeException("native HMAC-SHA256 sign failed", t);
            }
        }
    }
}
