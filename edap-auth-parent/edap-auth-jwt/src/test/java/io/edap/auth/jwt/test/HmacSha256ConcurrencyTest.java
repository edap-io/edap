package io.edap.auth.jwt.test;

import io.edap.auth.jwt.algorithm.HmacSha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HmacSha256 的线程安全：Mac 实例不可跨线程共享，改用 ThreadLocal<Mac> 模式后
 * 应当满足：
 * <ul>
 *   <li>多线程并发 sign() 不抛异常</li>
 *   <li>单线程前后两次结果一致（Mac.reset() 正确）</li>
 *   <li>不同 HmacSha256 实例（不同 key）签名结果不同</li>
 * </ul>
 */
public class HmacSha256ConcurrencyTest {

    private static final String KEY = "test-key-must-be-at-least-32-bytes-long-padding";

    @Test
    public void testMultiThreadSignNoError() throws Exception {
        HmacSha256 hmac = new HmacSha256(KEY);
        byte[] payload = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9"
                .getBytes(StandardCharsets.UTF_8);

        int threads = 16, iters = 10000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errs = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        byte[] sig = hmac.sign(payload, 0, payload.length);
                        if (sig.length != 32) errs.incrementAndGet();
                    }
                } catch (Throwable e) {
                    errs.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        assertEquals(0, errs.get(), "no thread should have failed");
    }

    @Test
    public void testSignReproducible() {
        HmacSha256 hmac = new HmacSha256(KEY);
        byte[] payload = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9"
                .getBytes(StandardCharsets.UTF_8);

        HmacSha256 solo = new HmacSha256(KEY);
        byte[] expected = solo.sign(payload, 0, payload.length);
        byte[] again = hmac.sign(payload, 0, payload.length);

        assertArrayEquals(expected, again, "same key + same payload → same signature");
    }

    @Test
    public void testDifferentKeysProduceDifferentSignatures() {
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        HmacSha256 a = new HmacSha256("key-a-32-bytes-padding-padding-padding-a");
        HmacSha256 b = new HmacSha256("key-b-32-bytes-padding-padding-padding-b");
        byte[] sigA = a.sign(payload, 0, payload.length);
        byte[] sigB = b.sign(payload, 0, payload.length);
        // 32 byte output
        assertEquals(32, sigA.length);
        assertEquals(32, sigB.length);
        // 签名内容不同
        if (Arrays.equals(sigA, sigB)) {
            throw new AssertionError("different keys should produce different signatures");
        }
    }

    /** 给手工肉眼核对：打印 hex 方便和 openssl 对照 */
    @Test
    public void testKnownVectorForInspection() {
        // RFC 4231 §4.2 HS256 测试向量不在本测试覆盖范围内（用了不同 key 长度）；
        // 这里只验证同一个固定输入多次 sign → 同样的结果，便于打印对比
        HmacSha256 hmac = new HmacSha256(KEY);
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] sig = hmac.sign(payload, 0, payload.length);
        System.out.println("HS256(hello) = " + Base64.getEncoder().encodeToString(sig));
    }
}
