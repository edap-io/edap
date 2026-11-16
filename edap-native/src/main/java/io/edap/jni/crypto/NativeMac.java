package io.edap.jni.crypto;

import io.edap.jni.Native;

/**
 * MAC native 实现（HMAC-SHA256 / HMAC-SHA1，OpenSSL libcrypto）。
 *
 * <p>作为 {@code io.edap.crypto.mac.NativeMacProvider} 的底层：edap-crypto 不硬依赖
 * edap-native（test scope），通过 {@code NativeBridge} 反射绑定这里的
 * {@link #hmacSha2560} / {@link #hmacSha10}。</p>
 *
 * <p><b>与 {@link NativeHmacSha256} 的区别</b>：那边是实例式（key 在构造时固定），
 * 这边是 static（key 每次调用传入）—— 对应 edap-crypto 的
 * {@code MacProvider.newSession(key)} 语义。</p>
 *
 * <p><b>Fallback</b>：{@link Native#ENABLE_NATIVE} 为 false 时抛
 * {@link UnsupportedOperationException}。</p>
 */
public class NativeMac {

    static {
        Native.loadLibrary();
    }

    /**
     * 计算 HMAC-SHA256。
     *
     * @return 32 字节结果
     */
    public static byte[] hmacSha256(byte[] key, byte[] data, int offset, int len) {
        check("hmac-sha256", key, data);
        return hmacSha2560(key, data, offset, len);
    }

    /**
     * 计算 HMAC-SHA1。
     *
     * @return 20 字节结果
     */
    public static byte[] hmacSha1(byte[] key, byte[] data, int offset, int len) {
        check("hmac-sha1", key, data);
        return hmacSha10(key, data, offset, len);
    }

    private static void check(String what, byte[] key, byte[] data) {
        if (!Native.ENABLE_NATIVE) {
            throw new UnsupportedOperationException("edap-native not loaded");
        }
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException(what + ": key required");
        }
        if (data == null) {
            throw new IllegalArgumentException(what + ": data required");
        }
    }

    /**
     * native 绑定入口。
     *
     * <p><b>必须是 public</b>：{@code io.edap.crypto.internal.NativeBridge} 用
     * {@code MethodHandles.lookup().findStatic(...)} 绑定它，跨包只能访问 public 成员。</p>
     */
    public static native byte[] hmacSha2560(byte[] key, byte[] data, int offset, int len);

    /**
     * native 绑定入口 —— 见 {@link #hmacSha2560}。
     */
    public static native byte[] hmacSha10(byte[] key, byte[] data, int offset, int len);
}
