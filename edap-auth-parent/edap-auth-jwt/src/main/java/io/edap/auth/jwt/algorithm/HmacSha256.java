package io.edap.auth.jwt.algorithm;

import io.edap.auth.jwt.Algorithm;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 统一入口。
 *
 * <p><b>native 自动委托</b>：构造时一次性检测
 * {@link HmacSha256Native#isAvailable()}（即 edap-native 在 classpath 且当前平台 .o 加载成功），
 * 可用时委托 {@link HmacSha256Native}（OpenSSL JNI，手工展开 HMAC-SHA256，无 provider dispatch），
 * 否则 fallback 到内嵌 ThreadLocal&lt;Mac&gt; 的纯 JDK 路径。算法名 "HS256" 解析、
 * {@link io.edap.auth.jwt.DefaultJwtBuilder} 显式 {@code new HmacSha256(key)} 等所有
 * "要 HS256" 的入口都用本类，自动获得 native 加速，调用方无需感知。</p>
 *
 * <p>显式强制某条路径：
 * <ul>
 *   <li>JVM 启动参数 {@code -Dedap.jwt.hmac.native=false} 关闭 native → 强制 JDK</li>
 *   <li>直接 {@code new HmacSha256Native(key)} 跳过 fallback → 强制 native</li>
 *   <li>{@link io.edap.auth.jwt.AlgorithmRegistry#register(String, java.util.function.Function) AlgorithmRegistry.register}
 *       用 {@code HmacSha256Native::new} 替换默认工厂 → 强制 native</li>
 * </ul>
 * </p>
 *
 * <p><b>线程安全</b>：native 路径下 key 不可变（构造期拷贝 byte[]），{@link #sign} 把
 * key + data 一起交给 native 单次调用，无共享可变状态；JDK 路径下每线程持有独立
 * {@link Mac} 实例（{@link ThreadLocal}），与 native 等价。两种路径下本类实例本身不可变，
 * 可跨线程安全发布。</p>
 *
 * <p><b>进程级缓存</b>：{@link #NATIVE_AVAILABLE} 在类初始化时检测一次（任何 HmacSha256 实例
 * 构造前都已确定），后续每个实例构造零额外开销。</p>
 */
public class HmacSha256 implements Algorithm {

    /**
     * 进程级 native 可用性缓存。类初始化时调一次 {@link HmacSha256Native#isAvailable()}，
     * 后续所有 HmacSha256 实例共用此结果。
     *
     * <p>{@link HmacSha256Native#isAvailable()} 内部会做 {@code Class.forName} + 读
     * {@code io.edap.jni.Native.ENABLE_NATIVE}，不是 free，但仍然很便宜（μs 级）；
     * 缓存到 static final 避免每次 {@code new HmacSha256} 都重做。Native 库装载结果本身
     * 在 {@code io.edap.jni.Native.ENABLE_NATIVE} 是 final static，JVM 生命周期不变，
     * 缓存安全。</p>
     */
    private static final boolean NATIVE_AVAILABLE = HmacSha256Native.isAvailable();

    private final Algorithm delegate;

    public HmacSha256(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        // native 可用 → 委托 HmacSha256Native（手工 HMAC-SHA256 JNI）；）
        // 否则 → JDK ThreadLocal<Mac> fallback（内嵌 private 类，原 HmacSha256 行为）。
        this.delegate = NATIVE_AVAILABLE ? new HmacSha256Native(key) : new HmacSha256Java(key);
    }

    @Override
    public byte[] sign(byte[] data, int offset, int len) {
        return delegate.sign(data, offset, len);
    }

    /**
     * 纯 JDK fallback 实现：每线程持有独立 {@link Mac} 实例。
     *
     * <p>这是 HmacSha256 的原始实现，从外部类内嵌进来作为 native 不可用时的兜底。
     * 行为与之前独立 {@code HmacSha256.java} 完全一致 —— 仅做包内可见性收敛，不引入新逻辑。</p>
     */
    private static final class HmacSha256Java implements Algorithm {

        private final byte[] keyBytes;

        private final ThreadLocal<Mac> macHolder;

        HmacSha256Java(String key) {
            this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
            // 必须放在 keyBytes 赋值之后：lambda 在执行期通过 this.keyBytes 访问，
            // 字段初始化器阶段 keyBytes 尚未赋值，编译器会报"可能尚未初始化"
            this.macHolder = ThreadLocal.withInitial(() -> {
                try {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
                    return mac;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public byte[] sign(byte[] data, int offset, int len) {
            Mac mac = macHolder.get();
            mac.reset();
            mac.update(data, offset, len);
            return mac.doFinal();
        }
    }

    /**
     * HMAC-SHA256 native 实现（OpenSSL via {@code edap-native}）。
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
     * 一起交给 native 单次调用，无共享可变状态；与 Java 实现的 ThreadLocal<Mac> 等价。</p>
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