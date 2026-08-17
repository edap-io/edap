package io.edap.auth.jwt.test;

import io.edap.auth.jwt.JWT;
import io.edap.auth.jwt.JwtBuilder;
import io.edap.auth.jwt.VerifyResult;
import io.edap.auth.jwt.DefaultJwtBuilder;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtBuilder 端到端行为回归：
 * <ul>
 *   <li>build() → verify() 完整 roundtrip</li>
 *   <li>多 claim 多次 build 必须产生相同 token（TreeMap 排序生效）</li>
 *   <li>多线程并发 build() 同 signKey 不抛异常</li>
 *   <li>HEADER_CACHE 命中不应再 NPE（3 参构造器复用路径）</li>
 * </ul>
 */
public class JWTBuilderRegressionTest {

    private static final String KEY = "test-key-must-be-at-least-32-bytes-long-padding";

    /** 1. JWT.build() → JWT.verify() roundtrip + 多 claim 字段还原 */
    @Test
    public void testBuildVerifyRoundtrip() {
        String signKey = "another-shared-secret-min-32-bytes-padding";
        String token = JWT.create()
                .subject("user-42")
                .issuer("edap.io")
                .audience("order-service")
                .expiresAt(System.currentTimeMillis() / 1000 + 3600)
                .claim("role", "admin")
                .signWith(signKey)
                .build();
        VerifyResult r = JWT.verify(token, signKey);
        assertEquals(0, r.getCode(), () -> "verify code: " + r.getMessage());
        assertEquals("user-42", r.getPayload().getSubject());
        assertEquals("edap.io", r.getPayload().getIssuer());
        assertEquals("order-service", r.getPayload().getAudience());
        assertEquals("admin", r.getPayload().getCustomerClaims().get("role"));
    }

    /** 2. 相同 claims 多次 build 必须产生相同 token（TreeMap 排序生效） */
    @Test
    public void testBuildReproducibility() {
        String t1 = buildWithMultipleClaims(KEY);
        String t2 = buildWithMultipleClaims(KEY);
        String t3 = buildWithMultipleClaims(KEY);
        assertEquals(t1, t2);
        assertEquals(t2, t3);
    }

    /** 3. 多线程并发 build() 同 signKey → ALGORITHM_CACHE 不能死 */
    @Test
    public void testBuildConcurrent() throws Exception {
        String signKey = "concurrent-key-32-bytes-padding-padding-padding";
        int threads = 16, iters = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errs = new AtomicInteger(0);
        Set<String> tokens = Collections.synchronizedSet(new HashSet<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        String tok = JWT.create()
                                .subject("u" + i)
                                .signWith(signKey)
                                .build();
                        tokens.add(tok);
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
        // 同 signKey + 不同 subject → tokens 数量应该 = iters
        assertEquals(iters, tokens.size(), "each (signKey, subject) should produce a unique token");
    }

    /** 4. HEADER_CACHE 命中不能再 NPE（3 参构造器 + computeIfAbsent 路径） */
    @Test
    public void testHeaderCacheNoNpe() {
        for (int i = 0; i < 5; i++) {
            String tok = new DefaultJwtBuilder("HS256", "JWT", "key-id-abc")
                    .subject("u-" + i)
                    .signWith(KEY)
                    .build();
            assertNotEquals("", tok);
        }
    }

    /** 5. 不同 signKey 产生不同 token */
    @Test
    public void testDifferentKeysDifferentTokens() {
        String t1 = JWT.create().subject("u").signWith("key-a-32-bytes-padding-pad").build();
        String t2 = JWT.create().subject("u").signWith("key-b-32-bytes-padding-pad").build();
        assertTrue(!t1.equals(t2), "different keys should produce different tokens");
    }

    private static String buildWithMultipleClaims(String signKey) {
        return JWT.create()
                .claim("z_last", "z")
                .claim("a_first", "a")
                .claim("m_middle", "m")
                .claim("b_second", "b")
                .signWith(signKey)
                .build();
    }
}
