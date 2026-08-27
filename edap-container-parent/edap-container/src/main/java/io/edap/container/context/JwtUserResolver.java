package io.edap.container.context;

import io.edap.auth.jwt.JwtPayload;
import io.edap.auth.jwt.JwtService;
import io.edap.auth.jwt.VerifyResult;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 框架默认 {@link UserResolver}:从 {@link HttpRequest} 的 {@code Authorization} 头
 * 取 JWT token,经 {@link JwtService} 验签后构造 {@link RequestContext}。
 *
 * <p><b>字段映射规则</b>:
 * <ul>
 *   <li>{@link RequestContext#userId()} ← {@link JwtPayload#getSubject()}(标准 JWT {@code sub})</li>
 *   <li>{@link RequestContext#userName()} ← {@code customerClaims["userName"]}</li>
 *   <li>{@link RequestContext#roles()} ← {@code customerClaims["roles"]}(必须是字符串集合)</li>
 *   <li>{@link RequestContext#traceId()} ← {@code X-Trace-Id} 头,缺失则随机 UUID</li>
 * </ul>
 *
 * <p><b>依赖注入契约</b>:构造器注入 {@link JwtService} <b>接口</b>(不是 {@code DefaultJwtService}
 * 具体类)。这样 {@code appCL} 解析 {@link JwtService} 接口时通过双亲委派拿到
 * {@code containerCL} 加载的同一份接口 Class,instance 注入时 {@code isAssignableFrom}
 * 永远 true,跨 ClassLoader 安全。</p>
 *
 * <p><b>注册位置</b>:在 {@code Container.initContainerBeans()} 注册,bean 名
 * {@code "jwtUserResolver"}(与 {@code @RequireAuth} 默认值匹配)。应用覆盖同名
 * bean 即可。</p>
 */
public class JwtUserResolver implements UserResolver {

    private final JwtService jwtService;

    public JwtUserResolver(JwtService jwtService) {
        if (jwtService == null) {
            throw new IllegalArgumentException("jwtService must not be null");
        }
        this.jwtService = jwtService;
    }

    @Override
    public RequestContext resolve(HttpRequest req) throws IOException {
        String token = headerValue(req, "Authorization");
        if (token == null || token.isEmpty()) {
            throw new IOException("missing Authorization header");
        }
        // 部分客户端传 "Bearer <token>" 格式,兼容剥离前缀
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
            if (token.isEmpty()) {
                throw new IOException("empty bearer token");
            }
        }

        VerifyResult vr = jwtService.verify(token);
        if (vr.getCode() != 0) {
            throw new IOException("invalid jwt: code=" + vr.getCode()
                    + " message=" + vr.getMessage());
        }
        JwtPayload payload = vr.getPayload();
        if (payload == null) {
            throw new IOException("verify success but payload is null");
        }

        Map<String, Object> custom = payload.getCustomerClaims();
        String userName = custom != null ? asString(custom.get("userName")) : null;
        Set<String> roles = custom != null ? toRoleSet(custom.get("roles")) : Collections.emptySet();
        String traceId = headerValue(req, "X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }

        return new RequestContext(payload.getSubject(), userName, roles, traceId);
    }

    private static String headerValue(HttpRequest req, String name) {
        HeaderValue hv = req.getHeaderValue(name);
        return (hv == null) ? null : hv.getValue();
    }

    private static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    /**
     * 把 customerClaims["roles"](JSON 反序列化后可能是 List/Set/字符串数组)归一成
     * {@code Set<String>}。非集合类型 → 空集(避免传错的角色数据污染权限判断)。
     */
    private static Set<String> toRoleSet(Object o) {
        if (!(o instanceof Collection)) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : (Collection<?>) o) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(out);
    }
}