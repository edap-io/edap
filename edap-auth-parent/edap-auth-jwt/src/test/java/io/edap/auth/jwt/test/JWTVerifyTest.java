package io.edap.auth.jwt.test;

import io.edap.auth.jwt.AlgorithmRegistry;
import io.edap.auth.jwt.JWT;
import io.edap.auth.jwt.JwtBuilder;
import io.edap.auth.jwt.Header;
import io.edap.auth.jwt.JwtHeader;
import io.edap.auth.jwt.JwtPayload;
import io.edap.auth.jwt.VerifyResult;
import io.edap.auth.jwt.Algorithm;
import io.edap.auth.jwt.DefaultJwtBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 JWT.verify() 的派发逻辑：
 * <ul>
 *   <li>合法 HS256 token（默认 header / 含 kid 的非默认 header）</li>
 *   <li>安全：alg=none / 未注册算法必须被拒绝</li>
 *   <li>格式：header base64url 解码失败 / JSON 解析失败 / 签名错误</li>
 *   <li>AlgorithmRegistry 注册/查找</li>
 * </ul>
 */
public class JWTVerifyTest {

    private static final String KEY = "this-is-a-test-signing-key-32-bytes!";

    /** 1. 合法 HS256 token（默认 header） */
    @Test
    public void testValidHs256() {
        String token = JWT.create().subject("u-1").signWith(KEY).build();
        VerifyResult r = JWT.verify(token, KEY);
        assertEquals(0, r.getCode(), () -> "verify code: " + r.getMessage());
        assertEquals("u-1", r.getPayload().getSubject());
        assertEquals("HS256", r.getHeader().getAlgorithm());
    }

    /** 2. 带 kid 的非默认 header 必须正确解析 */
    @Test
    public void testParseHeaderNonDefault() {
        String token = new DefaultJwtBuilder("HS256", "JWT", "my-key-id-2026")
                .subject("u-2")
                .signWith(KEY)
                .build();
        VerifyResult r = JWT.verify(token, KEY);
        assertEquals(0, r.getCode(), () -> "verify code: " + r.getMessage());
        Header h = r.getHeader();
        assertNotNull(h);
        assertEquals("u-2", r.getPayload().getSubject());
        assertEquals("my-key-id-2026", h.getKeyId());
        assertEquals("HS256", h.getAlgorithm());
        assertEquals("JWT", h.getType());
    }

    /** 3. alg=none 必须被拒绝（防 JWT alg=none 绕过签名） */
    @Test
    public void testRejectNoneAlg() {
        String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"attacker\"}";
        String token = b64(header) + "." + b64(payload) + ".";
        VerifyResult r = JWT.verify(token, "any-key");
        assertEquals(2, r.getCode());
        assertTrue(r.getMessage().contains("none"),
                () -> "message should contain 'none', got: " + r.getMessage());
    }

    /** 4. 未注册算法（如 RS256）必须被拒绝 */
    @Test
    public void testRejectUnknownAlg() {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"u\"}";
        String token = b64(header) + "." + b64(payload) + "." + "AAAA";
        VerifyResult r = JWT.verify(token, "any-key");
        assertEquals(2, r.getCode());
        assertTrue(r.getMessage().contains("RS256"),
                () -> "message should contain 'RS256', got: " + r.getMessage());
    }

    /** 5. header 不是合法 base64url 字符 */
    @Test
    public void testMalformedHeaderB64() {
        // "!!!" 不是合法 base64url
        String token = "!!!.eyJzdWIiOiJ0In0.AAAA";
        VerifyResult r = JWT.verify(token, "key");
        assertEquals(1, r.getCode());
        assertTrue(r.getMessage().contains("base64url"),
                () -> "message should contain 'base64url', got: " + r.getMessage());
    }

    /** 6. header base64url 解码成功但 JSON 解析失败 */
    @Test
    public void testMalformedHeaderJson() {
        // "{not-json" 是合法 base64url（短字符串），但不是合法 JSON
        String token = b64("{not-json") + ".eyJzdWIiOiJ0In0.AAAA";
        VerifyResult r = JWT.verify(token, "key");
        assertEquals(1, r.getCode());
        assertTrue(r.getMessage().contains("JSON"),
                () -> "message should contain 'JSON', got: " + r.getMessage());
    }

    /** 7. 错误签名（key 对但内容被改） */
    @Test
    public void testWrongSignature() {
        String token = JWT.create().subject("u").signWith("key-a").build();
        VerifyResult r = JWT.verify(token, "key-b");
        assertEquals(2, r.getCode());
        assertTrue(r.getMessage().contains("签名错误"),
                () -> "message should contain '签名错误', got: " + r.getMessage());
    }

    /** 8. AlgorithmRegistry 注册/查找 + none 强拒 */
    @Test
    public void testAlgorithmRegistry() {
        assertTrue(AlgorithmRegistry.names().contains("HS256"));

        Function<String, Algorithm> f = AlgorithmRegistry.getFactory("HS256");
        assertNotNull(f);

        // none / NONE 无论大小写必须抛 SecurityException
        assertThrows(SecurityException.class, () -> AlgorithmRegistry.getFactory("none"));
        assertThrows(SecurityException.class, () -> AlgorithmRegistry.getFactory("NONE"));

        // 未注册的算法返回 null
        assertNull(AlgorithmRegistry.getFactory("XX256"));
    }

    /** 9. verify 失败时不应抛异常，返回 VerifyResult（payload 可能为 null） */
    @Test
    public void testVerifyFailureReturnsResult() {
        // 错误 token — verify 必须 catch 所有异常，返回 result
        VerifyResult r = JWT.verify("not.a.jwt", "key");
        assertEquals(1, r.getCode());
    }

    /** 10. payload 解析覆盖（iss / sub / aud / exp / claim） */
    @Test
    public void testPayloadParseCoverage() {
        long now = System.currentTimeMillis() / 1000;
        String token = JWT.create()
                .subject("user-42")
                .issuer("edap.io")
                .audience("order-service")
                .expiresAt(now + 3600)
                .claim("role", "admin")
                .signWith(KEY)
                .build();
        VerifyResult r = JWT.verify(token, KEY);
        assertEquals(0, r.getCode());
        JwtPayload pl = r.getPayload();
        assertEquals("user-42", pl.getSubject());
        assertEquals("edap.io", pl.getIssuer());
        assertEquals("order-service", pl.getAudience());
        assertEquals(now + 3600, pl.getExpiresAt());
        assertEquals("admin", pl.getCustomerClaims().get("role"));
    }

    // --- helpers ---
    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
