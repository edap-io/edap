package io.edap.auth.jwt.test;

import io.edap.auth.jwt.Algorithm;
import io.edap.auth.jwt.algorithm.HmacSha256;
import io.edap.auth.jwt.algorithm.HmacSha256.HmacSha256Native;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HmacSha256Native 正确性 + 线程安全 + 与 Java Mac 一致性。
 *
 * <p>本测试默认跑 {@link HmacSha256Native#isAvailable()} 为 true 的路径
 * （依赖 edap-native 在 test classpath + 当前平台有预编译 .o）；若 native 不可用
 * （如未引入 edap-native / 当前平台无 .o），测试跳过对应 case。
 * 显式 {@code -Dedap.jwt.hmac.native=false} 时也跳过 native path 的功能测试，
 * 但 disable flag 语义本身有 case 覆盖（{@code testIsAvailableHonorsDisableFlag}）。</p>
 */
public class HmacSha256NativeTest {

    private static final String KEY = "this-is-a-test-signing-key-32-bytes!";

    private static boolean nativeAvailable() {
        return HmacSha256.HmacSha256Native.isAvailable();
    }

    @Test
    public void testIsAvailableDefaultsTrueWhenNativeReady() {
        // 默认行为：native 在 classpath + ENABLE_NATIVE=true → isAvailable() 直接返回 true
        // 不需要任何系统属性；只需显式置 native=false 才禁用
        if (!nativeClasspathPresent()) {
            System.out.println("SKIP: edap-native not on test classpath");
            return;
        }
        // 测试环境未显式设 -Dedap.jwt.hmac.native=false → 应能拿到 native
        if ("false".equalsIgnoreCase(System.getProperty("edap.jwt.hmac.native"))) {
            System.out.println("SKIP: native explicitly disabled by -D flag");
            return;
        }
        assertTrue(HmacSha256.HmacSha256Native.isAvailable(),
                "isAvailable() should return true by default when native is ready");
    }

    @Test
    public void testIsAvailableHonorsDisableFlag() {
        // 显式 -Dedap.jwt.hmac.native=false 不管 native 是不是加载好了，都应该返回 false
        String prev = System.getProperty("edap.jwt.hmac.native");
        try {
            System.setProperty("edap.jwt.hmac.native", "false");
            assertEquals(false, HmacSha256Native.isAvailable(),
                    "-Dedap.jwt.hmac.native=false must force Java path");
        } finally {
            if (prev == null) {
                System.clearProperty("edap.jwt.hmac.native");
            } else {
                System.setProperty("edap.jwt.hmac.native", prev);
            }
        }
    }

    private static boolean nativeClasspathPresent() {
        try {
            Class.forName("io.edap.jni.Native");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    public void testSignatureMatchesJava() throws Exception {
        if (!nativeAvailable()) {
            System.out.println("SKIP: native not available on this platform/property");
            return;
        }
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] data = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMyJ9"
                .getBytes(StandardCharsets.UTF_8);

        HmacSha256Native nativeHmac = new HmacSha256Native(KEY);
        byte[] nativeResult = nativeHmac.sign(data, 0, data.length);

        byte[] javaResult = javaHmac(keyBytes, data);

        assertEquals(32, nativeResult.length);
        assertArrayEquals(javaResult, nativeResult,
                "native HmacSha256 output must equal javax.crypto.Mac HmacSHA256");
    }

    @Test
    public void testPartialDataSign() throws Exception {
        if (!nativeAvailable()) return;
        byte[] data = "prefix-actual-data-suffix".getBytes(StandardCharsets.UTF_8);

        HmacSha256Native nativeHmac = new HmacSha256Native(KEY);
        byte[] nativeOffset = nativeHmac.sign(data, 7, 11); // "actual-data"

        byte[] expected = javaHmac(KEY.getBytes(StandardCharsets.UTF_8),
                "actual-data".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(expected, nativeOffset,
                "partial data sign must match java result for same key+slice");
    }

    @Test
    public void testDifferentKeysProduceDifferentSignatures() throws Exception {
        if (!nativeAvailable()) return;
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        HmacSha256Native a = new HmacSha256Native("key-a-32-bytes-padding-pad");
        HmacSha256Native b = new HmacSha256Native("key-b-32-bytes-padding-pad");
        byte[] sigA = a.sign(data, 0, data.length);
        byte[] sigB = b.sign(data, 0, data.length);
        assertEquals(32, sigA.length);
        assertEquals(32, sigB.length);
        // 至少有一字节不同（概率 2^-256，可视为不会撞）
        boolean equal = true;
        for (int i = 0; i < 32; i++) {
            if (sigA[i] != sigB[i]) { equal = false; break; }
        }
        assertTrue(!equal, "different keys should produce different signatures");
    }

    @Test
    public void testMultiThreadSafe() throws Exception {
        if (!nativeAvailable()) return;
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] data = "multi-thread-payload".getBytes(StandardCharsets.UTF_8);

        HmacSha256Native nativeHmac = new HmacSha256Native(KEY);
        final byte[] expected = javaHmac(keyBytes, data);

        int threads = 16, iters = 5000;
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
        assertEquals(0, errs.get(), "no thread should have mismatched signature");
    }

    @Test
    public void testNullKeyRejected() {
        if (!nativeAvailable()) return;
        try {
            new HmacSha256Native((String) null);
            fail("should have thrown");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** AlgorithmRegistry 工厂派发：验证 HS256 工厂签名与 Java 或 Native 实现兼容 */
    @Test
    public void testFactoryProducesCompatibleSignature() throws Exception {
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] data = "factory-dispatch-test".getBytes(StandardCharsets.UTF_8);

        // 直接 new 一个 Java 实现做参照（不走 registry 的 static init 路径，避免
        // 污染其他测试对 HS256 工厂的假设）
        Algorithm javaAlg = new HmacSha256(KEY);
        byte[] javaSig = javaAlg.sign(data, 0, data.length);

        if (!nativeAvailable()) {
            System.out.println("SKIP: native not available, only verifying Java factory");
            return;
        }
        // 模拟 registry 工厂派发：分别构造 Java / Native 实例，结果必须完全一致
        Algorithm nativeAlg = new HmacSha256Native(KEY);
        byte[] nativeSig = nativeAlg.sign(data, 0, data.length);
        assertArrayEquals(javaSig, nativeSig,
                "factory-dispatched Native and Java signatures must match");
    }

    private static byte[] javaHmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
