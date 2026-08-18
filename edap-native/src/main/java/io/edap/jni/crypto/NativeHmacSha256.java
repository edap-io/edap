package io.edap.jni.crypto;

import io.edap.jni.Native;

import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 native 实现（OpenSSL libcrypto）。
 *
 * <p>作为 {@link io.edap.auth.jwt.algorithm.HmacSha256} 的 native 替代：避开
 * Java/Mac.reset()/update()/doFinal() 多次 JNI 切换，单次 native 调用完成 HMAC。</p>
 *
 * <p>第一版用 OpenSSL 一次性 HMAC()（无 thread-local 状态）—— 简单可靠；若 profile 显示
 * key 初始化是热点，再升级为 thread-local HMAC_CTX 复用。</p>
 *
 * <p><b>线程安全</b>：{@code key} 不可变，{@link #sign} 内部把 key + data 一起交给 native
 * 调用，无共享可变状态。</p>
 *
 * <p><b>Fallback</b>：{@link Native#ENABLE_NATIVE} 为 false 时构造抛
 * {@link UnsupportedOperationException}；调用方应在构造前检查。</p>
 */
public class NativeHmacSha256 {

    private final byte[] key;

    static {
        Native.loadLibrary();
    }

    /**
     * @param key HMAC 密钥字节
     * @throws UnsupportedOperationException native 库未加载
     */
    public NativeHmacSha256(byte[] key) {
        if (!Native.ENABLE_NATIVE) {
            throw new UnsupportedOperationException("edap-native not loaded");
        }
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.key = key;
    }

    public NativeHmacSha256(String key) {
        this(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算 HMAC-SHA256(key, data[offset..offset+len])。
     * 返回 32 字节结果数组。
     */
    public byte[] sign(byte[] data, int offset, int len) {
        return sign0(key, key.length, data, offset, len);
    }

    // --- native binding ---
    private static native byte[] sign0(byte[] key, int keyLen, byte[] data, int offset, int len);
}
