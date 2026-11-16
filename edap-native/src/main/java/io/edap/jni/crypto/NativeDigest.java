package io.edap.jni.crypto;

import io.edap.jni.Native;

/**
 * 摘要 native 实现（SHA-256 / SHA-1 / MD5，OpenSSL libcrypto）。
 *
 * <p>作为 {@code io.edap.crypto.digest.NativeDigestProvider} 的底层：edap-crypto
 * 不硬依赖 edap-native（test scope），通过 {@code NativeBridge} 反射绑定这里的
 * {@link #digestSha2560} / {@link #digestSha10} / {@link #digestMd50}。</p>
 *
 * <p><b>★ 为什么按算法拆成三个 native 入口</b>：早先是一个
 * {@code digest0(String algorithm, ...)}，每次调用都要 {@code GetStringUTFChars}
 * + {@code strcmp} + {@code ReleaseStringUTFChars}。在 Solaris/SPARC 上实测
 * 这段固定开销约 <b>9.5µs/次</b> —— native digest 的固定成本比 native HMAC
 * （无字符串入口）多出来的部分几乎全来自这里。拆开后算法在 Java 侧一次性绑定，
 * native 侧零字符串（与 {@link NativeMac} 同一形状）。</p>
 *
 * <p><b>算法名用 OpenSSL 名</b>（{@code SHA256} / {@code SHA1} / {@code MD5}），
 * 不是 JCE 名（{@code SHA-256}）—— 名字转换在 edap-crypto 侧做；
 * 本类的 {@link #digest(String, byte[], int, int)} 保留这层兼容入口。</p>
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
     * 计算摘要（按算法名分发的兼容入口）。
     *
     * @param algorithm OpenSSL 算法名（{@code SHA256} / {@code SHA1} / {@code MD5}）
     * @param data      输入数据
     * @param offset    起始偏移
     * @param len       长度
     * @return 摘要字节（SHA-256 32 / SHA-1 20 / MD5 16）；
     *         <b>算法名不认识时返回 {@code null}</b>（调用方按「不可用」处理）
     */
    public static byte[] digest(String algorithm, byte[] data, int offset, int len) {
        if (!Native.ENABLE_NATIVE) {
            throw new UnsupportedOperationException("edap-native not loaded");
        }
        if (algorithm == null || data == null) {
            throw new IllegalArgumentException("algorithm and data required");
        }
        // 算法在 Java 侧分发 —— native 入口不收字符串（每次 GetStringUTFChars
        // 的固定开销在 Solaris/SPARC 上约 9.5µs/次）
        if ("SHA256".equals(algorithm)) {
            return digestSha2560(data, offset, len);
        }
        if ("SHA1".equals(algorithm)) {
            return digestSha10(data, offset, len);
        }
        if ("MD5".equals(algorithm)) {
            return digestMd50(data, offset, len);
        }
        return null;
    }

    /**
     * native 绑定入口 —— SHA-256。
     *
     * <p><b>必须是 public</b>：{@code io.edap.crypto.internal.NativeBridge} 用
     * {@code MethodHandles.lookup().findStatic(...)} 绑定它，跨包只能访问 public 成员。</p>
     *
     * @return 32 字节摘要
     */
    public static native byte[] digestSha2560(byte[] data, int offset, int len);

    /**
     * native 绑定入口 —— SHA-1。见 {@link #digestSha2560}。
     *
     * @return 20 字节摘要
     */
    public static native byte[] digestSha10(byte[] data, int offset, int len);

    /**
     * native 绑定入口 —— MD5。见 {@link #digestSha2560}。
     *
     * @return 16 字节摘要
     */
    public static native byte[] digestMd50(byte[] data, int offset, int len);
}
