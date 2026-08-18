package io.edap.auth.jwt.algorithm;

import io.edap.auth.jwt.Algorithm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

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
public class HmacSha256Native implements Algorithm {

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