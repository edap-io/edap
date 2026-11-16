package io.edap.jni.crypto;

import io.edap.jni.Native;

/**
 * 摘要 native 实现（SHA-256 / SHA-1 / MD5，OpenSSL libcrypto）。
 *
 * <p>作为 {@code io.edap.crypto.digest.NativeDigestProvider} 的底层：edap-crypto
 * 不硬依赖 edap-native（test scope），通过 {@code NativeBridge} 反射绑定这里的
 * {@link #digest0}。</p>
 *
 * <p><b>算法名用 OpenSSL 名</b>（{@code SHA256} / {@code SHA1} / {@code MD5}），
 * 不是 JCE 名（{@code SHA-256}）—— 名字转换在 edap-crypto 侧做。</p>
 *
 * <p><b>Fallback</b>：{@link Native#ENABLE_NATIVE} 为 false 时
 * {@link #digest} 抛 {@link UnsupportedOperationException}；调用方应在调用前检查
 * {@code Native.ENABLE_NATIVE}。</p>
 */
public class NativeDigest {

    static {
        Native.loadLibrary();
    }

    /**
     * 计算摘要。
     *
     * @param algorithm OpenSSL 算法名（{@code SHA256} / {@code SHA1} / {@code MD5}）
     * @param data      输入数据
     * @param offset    起始偏移
     * @param len       长度
     * @return 摘要字节（SHA-256 32 / SHA-1 20 / MD5 16）
     */
    public static byte[] digest(String algorithm, byte[] data, int offset, int len) {
        if (!Native.ENABLE_NATIVE) {
            throw new UnsupportedOperationException("edap-native not loaded");
        }
        if (algorithm == null || data == null) {
            throw new IllegalArgumentException("algorithm and data required");
        }
        return digest0(algorithm, data, offset, len);
    }

    /**
     * native 绑定入口。
     *
     * <p><b>必须是 public</b>：{@code io.edap.crypto.internal.NativeBridge} 用
     * {@code MethodHandles.lookup().findStatic(...)} 绑定它，跨包只能访问 public 成员。</p>
     *
     * <p>算法名不认识时 native 侧返回 {@code null}。</p>
     */
    public static native byte[] digest0(String algorithm, byte[] data, int offset, int len);
}
