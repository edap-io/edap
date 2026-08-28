package io.edap.mw.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 请求级线程上下文 —— {@link UserResolver} 从 {@link io.edap.http.HttpRequest} 解析后
 * 存到 {@link RequestContextHolder},业务实现类通过 {@code RequestContextHolder.current()}
 * 取当前用户的 {@link #userId} / {@link #userName} / {@link #roles}。
 *
 * <p>不可变:创建后字段不可改。{@link #roles} 内部 {@code LinkedHashSet} 包
 * {@link Collections#unmodifiableSet} 防业务侧意外篡改。</p>
 *
 * <p><b>为什么 immutable</b>:Handler try/finally 把同一 ctx 在请求线程上传递,任何业务方
 * 改 ctx 会污染后续读 ctx 的代码;线程复用场景(容器 NIO event loop)还会把脏 ctx 泄漏
 * 到下一个请求。</p>
 *
 * <p>{@link #anonymous(String)} 用于没有用户上下文的场景(公开路由 / 内部调用),占位 traceId
 * 仍可写日志/分布式追踪。</p>
 */
public final class RequestContext {

    private final String       userId;
    private final String       userName;
    private final Set<String>  roles;
    private final String       traceId;

    public RequestContext(String userId, String userName, Set<String> roles, String traceId) {
        this.userId   = userId;
        this.userName = userName;
        // 防御性拷贝 + 不可变包装 —— 调用方传 null/可变 Set 都能安全处理
        this.roles    = (roles == null || roles.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        this.traceId  = traceId;
    }

    /** 无用户上下文的占位 ctx:userId/userName=null,roles 空,traceId 仍可填。 */
    public static RequestContext anonymous(String traceId) {
        return new RequestContext(null, null, Collections.emptySet(), traceId);
    }

    public String      userId()   { return userId; }
    public String      userName() { return userName; }
    public Set<String> roles()    { return roles; }
    public String      traceId()  { return traceId; }
}