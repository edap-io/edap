package io.edap.jni.test;

import io.edap.jni.Native;
import io.edap.jni.crypto.NativeHmacSha256;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NativeHmacSha256 正确性 + 线程安全 + 性能对比 Java Mac。
 *
 * <p>正确性：跟 Java {@link javax.crypto.Mac} "HmacSHA256" 输出对每个测试向量逐字节比对。</p>
 *
 * <p>线程安全：16 线程 × 10000 次 sign()，结果跟 Java 一致。</p>
 */
public class NativeHmacSha256Test {

    private static final String KEY = "this-is-a-test-signing-key-32-bytes!";

    @Test
    public void testNativeLibraryLoaded() {
        // 如果不在支持的平台（4 个之一），ENABLE_NATIVE 为 false，其他测试跳过
        Native.loadLibrary();
        if (!Native.ENABLE_NATIVE) {
            System.out.println("SKIP: native not loaded on this platform");
            return;
        }
        assertTrue(Native.ENABLE_NATIVE);
    }

    @Test
    public void testSingleHmacMatchesJava() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] data = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9".getBytes(StandardCharsets.UTF_8);

        NativeHmacSha256 nativeHmac = new NativeHmacSha256(keyBytes);
        byte[] nativeResult = nativeHmac.sign(data, 0, data.length);

        byte[] javaResult = javaHmac(keyBytes, data);

        assertEquals(32, nativeResult.length);
        assertEquals(32, javaResult.length);
        for (int idx = 0; idx < 32; idx++) {
            final int i = idx;
            byte exp = javaResult[i];
            byte got = nativeResult[i];
            assertEquals(exp, got,
                    () -> "byte " + i + " differs: native=" + got + " java=" + exp);
        }
    }

    @Test
    public void testPartialDataSign() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] data = "prefix-actual-data-suffix".getBytes(StandardCharsets.UTF_8);

        NativeHmacSha256 nativeHmac = new NativeHmacSha256(KEY);
        byte[] nativeOffset = nativeHmac.sign(data, 7, 11);  // "actual-data"

        byte[] expected = javaHmac(KEY.getBytes(StandardCharsets.UTF_8), "actual-data".getBytes(StandardCharsets.UTF_8));

        for (int idx = 0; idx < 32; idx++) {
            final int i = idx;
            byte exp = expected[i];
            byte got = nativeOffset[i];
            assertEquals(exp, got, () -> "partial sign byte " + i + " differs");
        }
    }

    @Test
    public void testDifferentKeysProduceDifferentSignatures() {
        if (!Native.ENABLE_NATIVE) return;
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        NativeHmacSha256 a = new NativeHmacSha256("key-a-32-bytes-padding-pad");
        NativeHmacSha256 b = new NativeHmacSha256("key-b-32-bytes-padding-pad");
        byte[] sigA = a.sign(data, 0, data.length);
        byte[] sigB = b.sign(data, 0, data.length);
        assertEquals(32, sigA.length);
        assertEquals(32, sigB.length);
        boolean equal = true;
        for (int i = 0; i < 32; i++) {
            if (sigA[i] != sigB[i]) { equal = false; break; }
        }
        assertTrue(!equal, "different keys should produce different signatures");
    }

    @Test
    public void testMultiThreadSafe() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] data = "multi-thread-payload".getBytes(StandardCharsets.UTF_8);

        NativeHmacSha256 nativeHmac = new NativeHmacSha256(keyBytes);
        final byte[] expected = javaHmac(keyBytes, data);

        int threads = 16, iters = 10000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errs = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        byte[] sig = nativeHmac.sign(data, 0, data.length);
                        for (int j = 0; j < 32; j++) {
                            if (sig[j] != expected[j]) {
                                errs.incrementAndGet();
                                return;
                            }
                        }
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
        assertEquals(0, errs.get(), "no thread should have a mismatched signature");
    }

    @Test
    public void testNullKeyRejected() {
        if (!Native.ENABLE_NATIVE) return;
        try {
            new NativeHmacSha256((byte[]) null);
            throw new AssertionError("should have thrown");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /**
     * ★ 载荷跨小载荷阈值 —— native 侧 ≤ 1024 走 {@code GetByteArrayRegion} 拷栈缓冲，
     * &gt; 1024 走 critical section，两条路径必须给出完全一样的结果。
     *
     * <p>这条守的是 edap-auth-jwt 的 HS256 生产路径
     * （{@code HmacSha256.HmacSha256Native}）。</p>
     */
    @Test
    public void testPayloadsAcrossSmallPayloadThreshold() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        NativeHmacSha256 hmac = new NativeHmacSha256(keyBytes);
        java.util.Random random = new java.util.Random(20261117L);
        int[] lengths = {0, 1, 63, 64, 65, 1023, 1024, 1025, 2048, 65536};
        for (int len : lengths) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            assertArrayEquals(javaHmac(keyBytes, data), hmac.sign(data, 0, len), "len=" + len);
        }
    }

    /**
     * ★ key 跨小载荷阈值 —— key 走同一套分流。
     */
    @Test
    public void testKeysAcrossSmallPayloadThreshold() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] data = "key threshold".getBytes(StandardCharsets.UTF_8);
        java.util.Random random = new java.util.Random(20261118L);
        int[] keyLengths = {1, 16, 32, 63, 64, 65, 1023, 1024, 1025, 4096};
        for (int keyLen : keyLengths) {
            byte[] key = new byte[keyLen];
            random.nextBytes(key);
            NativeHmacSha256 hmac = new NativeHmacSha256(key);
            assertArrayEquals(javaHmac(key, data), hmac.sign(data, 0, data.length), "keyLen=" + keyLen);
        }
    }

    /**
     * ★ 带偏移的切片 —— 小载荷路径也必须按 {@code offset} 取。
     */
    @Test
    public void testSliceWithOffsetAcrossThreshold() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        NativeHmacSha256 hmac = new NativeHmacSha256(keyBytes);
        java.util.Random random = new java.util.Random(20261119L);
        int[] lengths = {10, 1023, 1024, 1025, 4096};
        for (int len : lengths) {
            byte[] buffer = new byte[len + 16];
            random.nextBytes(buffer);
            byte[] slice = new byte[len];
            System.arraycopy(buffer, 7, slice, 0, len);
            assertArrayEquals(javaHmac(keyBytes, slice), hmac.sign(buffer, 7, len), "len=" + len);
        }
    }

    /** 输出 hex 方便肉眼对比 */
    @Test
    public void testKnownVectorForInspection() throws Exception {
        if (!Native.ENABLE_NATIVE) return;
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        NativeHmacSha256 hmac = new NativeHmacSha256(KEY);
        byte[] sig = hmac.sign(data, 0, data.length);
        System.out.println("native HMAC-SHA256(hello) = " + Base64.getEncoder().encodeToString(sig));
        System.out.println("java   HMAC-SHA256(hello) = " + Base64.getEncoder().encodeToString(javaHmac(KEY.getBytes(StandardCharsets.UTF_8), data)));
    }

    private static byte[] javaHmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
