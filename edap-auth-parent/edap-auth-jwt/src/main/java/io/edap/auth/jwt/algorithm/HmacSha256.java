package io.edap.auth.jwt.algorithm;

import io.edap.auth.jwt.Algorithm;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 签名实现。
 *
 * <p>线程安全：每个线程持有独立的 {@link Mac} 实例（{@link ThreadLocal}），
 * 不共享可变状态。{@link #sign(byte[], int, int)} 路径无锁。</p>
 *
 * <p>实例本身不可变（keyBytes + macHolder 均为 final），可跨线程安全发布。</p>
 */
public class HmacSha256 implements Algorithm {

    private final byte[] keyBytes;

    private final ThreadLocal<Mac> macHolder;

    public HmacSha256(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
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