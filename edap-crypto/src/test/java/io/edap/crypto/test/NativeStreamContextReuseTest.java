/*
 * Copyright 2023 The edap Project
 *
 * The NetBeans Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.crypto.test;

import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import io.edap.crypto.mac.NativeMacProvider;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * native 流式上下文的**复用**正确性。
 *
 * <p><b>背景（2026-11-17）</b>：native 侧把「每次操作 malloc/free 一个摘要上下文」
 * 改成了「按线程复用一个空闲上下文」（见
 * {@code edap-native/src/main/c/io_edap_jni_crypto_NativeStream.c}）。
 * 复用带来两类新的风险，必须有测试守着：</p>
 *
 * <ol>
 *   <li><b>残留状态</b>：归还的上下文里还留着上一次算法的中间状态 ——
 *       下一个会话直接拿来用就会算出错误结果。
 *       （守法：OpenSSL 的 {@code SHA256_Init / SHA1_Init / MD5_Init} 会把上下文
 *       <b>全量复位</b>，所以只要每次使用前都重新 Init 就没有残留。）</li>
 *   <li><b>跨线程串扰</b>：池是线程私有的，但「在 A 线程创建、在 B 线程归还」
 *       这件事是允许的 ⇒ 必须保证归还后不会有人再拿它去改写。</li>
 * </ol>
 *
 * <p>另外还守着<b>小载荷路径</b>的边界：≤ 1 KB 走 {@code GetByteArrayRegion}，
 * &gt; 1 KB 走 critical section —— 两条路径必须给出完全一样的结果。</p>
 *
 * <p><b>edap-native 在 edap-crypto 是 test scope</b>，所以 native 不可用时
 * （edap-native 不在 classpath / 当前平台没有预编译产物）整类跳过，不能失败。</p>
 */
public class NativeStreamContextReuseTest {

    /**
     * 跨越小载荷阈值的载荷长度（native 侧阈值 = 1024）。
     */
    private static final int[] PAYLOAD_LENGTHS = {
            0, 1, 31, 32, 63, 64, 65, 127, 128, 255, 256, 511, 512,
            1023, 1024, 1025, 2047, 2048, 4096, 65535, 65536
    };

    private static final String[] DIGEST_ALGS = {"SHA-256", "SHA-1", "MD5"};

    private static final String[] MAC_ALGS = {"HmacSHA256", "HmacSHA1"};

    /**
     * ★ 所有摘要算法 × 所有载荷长度 ⇒ native 必须与 JCE 逐字节一致。
     *
     * <p>这条覆盖「小载荷 region 路径」与「大载荷 critical 路径」的<b>边界</b>。</p>
     */
    @Test
    public void testAllAlgorithmsAcrossPayloadThreshold() {
        NativeDigestProvider sha256 = NativeDigestProvider.tryCreate("SHA-256");
        NativeDigestProvider sha1 = NativeDigestProvider.tryCreate("SHA-1");
        NativeDigestProvider md5 = NativeDigestProvider.tryCreate("MD5");
        if (sha256 == null || sha1 == null || md5 == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        Random random = new Random(20261117L);
        for (String alg : DIGEST_ALGS) {
            DigestProvider nativeProvider = providerFor(alg, sha256, sha1, md5);
            DigestProvider jceProvider = new JceDigestProvider(alg);
            for (int len : PAYLOAD_LENGTHS) {
                byte[] data = new byte[len];
                random.nextBytes(data);
                assertArrayEquals(digest(jceProvider, data), digest(nativeProvider, data),
                        alg + " len=" + len);
            }
        }
    }

    /**
     * ★ 同一个线程里**交替使用不同算法** —— 归还的上下文会被下一个算法取走。
     *
     * <p>这条是「残留状态」风险的正面测试：如果 native 侧忘了重新 Init，
     * 或者 pad 没写全，第二个算法的结果就会带上前一个算法的残留。</p>
     */
    @Test
    public void testAlternatingAlgorithmsInSameThread() {
        NativeDigestProvider sha256 = NativeDigestProvider.tryCreate("SHA-256");
        NativeDigestProvider sha1 = NativeDigestProvider.tryCreate("SHA-1");
        NativeDigestProvider md5 = NativeDigestProvider.tryCreate("MD5");
        NativeMacProvider hmac256 = NativeMacProvider.tryCreate("HmacSHA256");
        NativeMacProvider hmac1 = NativeMacProvider.tryCreate("HmacSHA1");
        if (sha256 == null || sha1 == null || md5 == null || hmac256 == null || hmac1 == null) {
            System.out.println("SKIP: native unavailable");
            return;
        }
        byte[] key = "context-reuse-key".getBytes(StandardCharsets.UTF_8);
        byte[] data = "the quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

        // 交替 20 轮：每一轮都用「与上一轮不同的算法」⇒ 上下文必然被跨算法复用
        for (int round = 0; round < 20; round++) {
            switch (round % 5) {
                case 0:
                    assertArrayEquals(digest(new JceDigestProvider("SHA-256"), data),
                            digest(sha256, data), "round=" + round);
                    break;
                case 1:
                    assertArrayEquals(digest(new JceDigestProvider("SHA-1"), data),
                            digest(sha1, data), "round=" + round);
                    break;
                case 2:
                    assertArrayEquals(digest(new JceDigestProvider("MD5"), data),
                            digest(md5, data), "round=" + round);
                    break;
                case 3:
                    assertArrayEquals(mac(new JceMacProvider("HmacSHA256"), key, data),
                            mac(hmac256, key, data), "round=" + round);
                    break;
                default:
                    assertArrayEquals(mac(new JceMacProvider("HmacSHA1"), key, data),
                            mac(hmac1, key, data), "round=" + round);
                    break;
            }
        }
    }

    /**
     * ★ 多线程并发 ⇒ 每个线程的结果必须与 JCE 一致。
     *
     * <p>池是线程私有的，但「创建与归还发生在不同线程」是允许的
     * （Java 侧的 session 不跨线程，但 native 的入口可以被任何线程调用）。
     * 这条保证归还后的上下文不会被另一个线程同时改写。</p>
     */
    @Test
    public void testConcurrentThreadsMatchJce() throws Exception {
        NativeDigestProvider sha256 = NativeDigestProvider.tryCreate("SHA-256");
        NativeMacProvider hmac256 = NativeMacProvider.tryCreate("HmacSHA256");
        if (sha256 == null || hmac256 == null) {
            System.out.println("SKIP: native unavailable");
            return;
        }
        JceDigestProvider jceDigest = new JceDigestProvider("SHA-256");
        JceMacProvider jceMac = new JceMacProvider("HmacSHA256");
        byte[] key = "concurrent-key".getBytes(StandardCharsets.UTF_8);

        final int threads = 8;
        final int iterations = 500;
        AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    Random random = new Random(1000L + seed);
                    for (int i = 0; i < iterations; i++) {
                        byte[] data = new byte[1 + random.nextInt(2048)];
                        random.nextBytes(data);
                        if (!java.util.Arrays.equals(digest(jceDigest, data), digest(sha256, data))) {
                            failures.incrementAndGet();
                            return;
                        }
                        if (!java.util.Arrays.equals(mac(jceMac, key, data), mac(hmac256, key, data))) {
                            failures.incrementAndGet();
                            return;
                        }
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                }
            }, "native-reuse-" + t);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(0, failures.get(), "并发下 native 结果与 JCE 不一致");
    }

    /**
     * ★ 分片喂入（多次 update）与一次性喂入必须同结果。
     *
     * <p>覆盖「同一个上下文被多次 update」的路径：第一次 update 走小载荷路径，
     * 第二次走大载荷路径（或反过来）时，两次的偏移/长度都不能算错。</p>
     */
    @Test
    public void testChunkedUpdateAcrossThreshold() {
        NativeDigestProvider sha256 = NativeDigestProvider.tryCreate("SHA-256");
        NativeMacProvider hmac256 = NativeMacProvider.tryCreate("HmacSHA256");
        if (sha256 == null || hmac256 == null) {
            System.out.println("SKIP: native unavailable");
            return;
        }
        JceDigestProvider jceDigest = new JceDigestProvider("SHA-256");
        JceMacProvider jceMac = new JceMacProvider("HmacSHA256");
        byte[] key = "chunked-key".getBytes(StandardCharsets.UTF_8);
        Random random = new Random(777L);

        int[][] chunkPlans = {
                {1, 1, 1},
                {100, 100, 100},
                {1024, 1, 1024},
                {1, 1024, 1},
                {512, 512, 512, 512, 512, 512, 512, 512},
                {4096, 4096},
                {1023, 1025}
        };
        for (int[] plan : chunkPlans) {
            int total = 0;
            for (int c : plan) {
                total += c;
            }
            byte[] data = new byte[total];
            random.nextBytes(data);

            DigestSession nativeSession = sha256.newSession();
            DigestSession jceSession = jceDigest.newSession();
            MacSession nativeMac = hmac256.newSession(key);
            MacSession jceMacSession = jceMac.newSession(key);
            int off = 0;
            for (int c : plan) {
                nativeSession.update(data, off, c);
                jceSession.update(data, off, c);
                nativeMac.update(data, off, c);
                jceMacSession.update(data, off, c);
                off += c;
            }
            assertArrayEquals(jceSession.digest(), nativeSession.digest(),
                    "digest plan=" + java.util.Arrays.toString(plan));
            assertArrayEquals(jceMacSession.doFinal(), nativeMac.doFinal(),
                    "mac plan=" + java.util.Arrays.toString(plan));
        }
    }

    /**
     * ★ 各种长度的 HMAC key —— key 也走「小载荷 region / 大载荷 critical」两条路。
     */
    @Test
    public void testMacKeyLengths() throws Exception {
        NativeMacProvider hmac256 = NativeMacProvider.tryCreate("HmacSHA256");
        NativeMacProvider hmac1 = NativeMacProvider.tryCreate("HmacSHA1");
        if (hmac256 == null || hmac1 == null) {
            System.out.println("SKIP: native unavailable");
            return;
        }
        byte[] data = "key length matrix".getBytes(StandardCharsets.UTF_8);
        int[] keyLengths = {1, 16, 32, 63, 64, 65, 127, 128, 1023, 1024, 1025, 4096};
        for (int keyLen : keyLengths) {
            byte[] key = new byte[keyLen];
            new Random(keyLen).nextBytes(key);
            assertArrayEquals(mac(new JceMacProvider("HmacSHA256"), key, data),
                    mac(hmac256, key, data), "HmacSHA256 keyLen=" + keyLen);
            assertArrayEquals(mac(new JceMacProvider("HmacSHA1"), key, data),
                    mac(hmac1, key, data), "HmacSHA1 keyLen=" + keyLen);
        }
    }

    /**
     * ★ 收尾 / reset 之后必须能继续用 —— 守「归还的上下文被下一个会话取走」这条路径。
     */
    @Test
    public void testSessionReuseAfterFinishAndReset() {
        NativeDigestProvider sha256 = NativeDigestProvider.tryCreate("SHA-256");
        if (sha256 == null) {
            System.out.println("SKIP: native digest unavailable");
            return;
        }
        JceDigestProvider jceDigest = new JceDigestProvider("SHA-256");
        byte[] first = "first payload".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second payload".getBytes(StandardCharsets.UTF_8);

        DigestSession session = sha256.newSession();
        session.update(first, 0, first.length);
        assertArrayEquals(digest(jceDigest, first), session.digest(), "第一次收尾");

        // digest 之后继续喂 —— 必须算的是新数据（上下文已归还并可能被复用）
        session.update(second, 0, second.length);
        assertArrayEquals(digest(jceDigest, second), session.digest(), "收尾后继续喂");

        // reset 之后继续喂
        session.update(first, 0, first.length);
        session.reset();
        session.update(second, 0, second.length);
        assertArrayEquals(digest(jceDigest, second), session.digest(), "reset 之后继续喂");
    }

    // ---------------- helpers ----------------

    private static DigestProvider providerFor(String alg, NativeDigestProvider sha256,
            NativeDigestProvider sha1, NativeDigestProvider md5) {
        switch (alg) {
            case "SHA-256": return sha256;
            case "SHA-1": return sha1;
            default: return md5;
        }
    }

    private static byte[] digest(DigestProvider provider, byte[] data) {
        DigestSession session = provider.newSession();
        session.update(data, 0, data.length);
        byte[] result = session.digest();
        assertNotNull(result);
        return result;
    }

    private static byte[] mac(MacProvider provider, byte[] key, byte[] data) {
        MacSession session = provider.newSession(key);
        session.update(data, 0, data.length);
        byte[] result = session.doFinal();
        assertNotNull(result);
        return result;
    }
}
