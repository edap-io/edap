package io.edap.container.context;

import io.edap.auth.jwt.DefaultJwtService;
import io.edap.auth.jwt.JwtService;
import io.edap.http.HeaderValue;
import io.edap.http.HttpBody;
import io.edap.http.HttpRequest;
import io.edap.mw.context.JwtUserResolver;
import io.edap.mw.context.UserResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUserResolver 单测:用 DefaultJwtService 实例化 + 各种 HttpRequest 模拟。
 */
class JwtUserResolverTest {

    private static final String SIGN_KEY = "test-sign-key-32bytes-xxxxxxxx";

    private static JwtService newService() {
        return new DefaultJwtService(SIGN_KEY);
    }

    private static HttpRequest stubRequest(String authHeader, String traceId) {
        return new StubRequest(authHeader, traceId);
    }

    @Test
    void missingAuthorizationHeaderThrowsIOException() {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        IOException ex = assertThrows(IOException.class,
                () -> resolver.resolve(stubRequest(null, null)));
        assertTrue(ex.getMessage().contains("missing Authorization"));
    }

    @Test
    void emptyAuthorizationHeaderThrowsIOException() {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        IOException ex = assertThrows(IOException.class,
                () -> resolver.resolve(stubRequest("", null)));
        assertTrue(ex.getMessage().contains("missing Authorization"));
    }

    @Test
    void invalidTokenThrowsIOException() {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        IOException ex = assertThrows(IOException.class,
                () -> resolver.resolve(stubRequest("not-a-jwt", null)));
        assertTrue(ex.getMessage().contains("invalid jwt"));
    }

    @Test
    void validTokenWithClaimsProducesCorrectContext() throws IOException {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        String token = svc.builder()
                .subject("u-1")
                .claim("userName", "alice")
                .claim("roles", Arrays.asList("admin", "user"))
                .build();

        UserResolver.ResolverResult result = resolver.resolve(stubRequest(token, "trace-xyz"));

        assertEquals("u-1", result.getRequestContext().userId());
        assertEquals("alice", result.getRequestContext().userName());
        assertTrue(result.getRequestContext().roles().contains("admin"));
        assertTrue(result.getRequestContext().roles().contains("user"));
        assertEquals("trace-xyz", result.getRequestContext().traceId());
    }

    @Test
    void validTokenWithoutCustomClaimsStillProducesContext() throws IOException {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        String token = svc.builder().subject("u-2").build();
        UserResolver.ResolverResult result = resolver.resolve(stubRequest(token, "trace-z"));

        assertEquals("u-2", result.getRequestContext().userId());
        assertNull(result.getRequestContext().userName());
        assertTrue(result.getRequestContext().roles().isEmpty());
        assertEquals("trace-z", result.getRequestContext().traceId());
    }

    @Test
    void missingTraceIdHeaderGeneratesUuid() throws IOException {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        String token = svc.builder().subject("u-3").build();
        UserResolver.ResolverResult result = resolver.resolve(stubRequest(token, null));

        assertNotNull(result.getRequestContext().traceId());
        // 简单判断:不是 trace- 前缀的固定值,看起来是 UUID 格式
        assertTrue(result.getRequestContext().traceId().length() >= 32, "traceId 应是 UUID 格式,got=" +
                result.getRequestContext().traceId());
    }

    @Test
    void ctorRejectsNullJwtService() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUserResolver(null));
    }

    @Test
    void bearerPrefixIsStripped() throws IOException {
        JwtService svc = newService();
        JwtUserResolver resolver = new JwtUserResolver(svc);

        String token = svc.builder().subject("u-4").build();
        UserResolver.ResolverResult result = resolver.resolve(stubRequest("Bearer " + token, "trace-b"));
        assertEquals("u-4", result.getRequestContext().userId());
    }

    /**
     * 极简 HttpRequest stub ——只覆盖 JwtUserResolver 用到的 getHeaderValue。
     */
    private static class StubRequest implements HttpRequest {
        private final Map<String, String> headers;

        StubRequest(String authHeader, String traceId) {
            this.headers = new LinkedHashMap<>();
            if (authHeader != null) headers.put("Authorization", authHeader);
            if (traceId != null) headers.put("X-Trace-Id", traceId);
        }

        @Override
        public HeaderValue getHeaderValue(String name) {
            String v = headers.get(name);
            return v == null ? null : new HeaderValue(v);
        }

        // 下面这些方法 JwtUserResolver 不会调,全部抛异常防误用
        @Override public String getPath()                            { throw new UnsupportedOperationException(); }
        @Override public io.edap.http.PathInfo getPathInfo()        { throw new UnsupportedOperationException(); }
        @Override public HttpBody getBody()            { throw new UnsupportedOperationException(); }
        @Override public io.edap.util.ByteData getHeaderData()       { throw new UnsupportedOperationException(); }
        @Override public String getMethod()                         { throw new UnsupportedOperationException(); }
        @Override public io.edap.http.MethodInfo getMethodInfo()    { throw new UnsupportedOperationException(); }
        @Override public int getContentLength()                      { throw new UnsupportedOperationException(); }
        @Override public void setBody(HttpBody data)    { throw new UnsupportedOperationException(); }
        @Override public void reset()                                { throw new UnsupportedOperationException(); }
        @Override public io.edap.http.HttpVersion getVersion()      { throw new UnsupportedOperationException(); }
        @Override public io.edap.http.HttpNioSession getHttpNioSession() { throw new UnsupportedOperationException(); }
        @Override public io.edap.http.HttpResponse getResponse()     { throw new UnsupportedOperationException(); }
        @Override public void setPath(String string)                 { throw new UnsupportedOperationException(); }
        @Override public String getClientAddr()                      { throw new UnsupportedOperationException(); }
        @Override public String getParameter(String name)            { throw new UnsupportedOperationException(); }
    }
}