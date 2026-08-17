package io.edap.http.ws;

/**
 * 已通过 {@link WSAuthenticator} 鉴权的客户端身份信息。
 *
 * <p>由 {@link WSAuthenticator#verify} 在鉴权成功后构造；edap 容器把 principal
 * 写到 {@code WSConnection.setSessionContext("principal", p)}，供 {@code ServiceWSHandler.onOpen}
 * 异步加载用户信息 / 业务方法内查询当前用户使用。</p>
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@link #userId}：稳定用户标识（业务语义唯一 key），用于后续用户信息加载</li>
 *   <li>{@link #rawToken}：原始 token 字符串（Authorization header / query parameter），
 *       用于业务方法内部续签 / refresh 场景——可选字段，{@link WSAuthenticator} 实现自行决定是否携带</li>
 * </ul>
 *
 * <p>POJO 形态而非 record：保持与 edap 现有代码风格一致（{@code BeanWrap} /
 * {@code HandlerKey} 都是 POJO），便于子类化扩展字段。</p>
 */
public final class Principal {

    private final String userId;
    private final String rawToken;

    public Principal(String userId) {
        this(userId, null);
    }

    public Principal(String userId, String rawToken) {
        this.userId   = userId;
        this.rawToken = rawToken;
    }

    public String userId()   { return userId;   }
    public String rawToken() { return rawToken; }

    @Override
    public String toString() {
        return "Principal{userId=" + userId + "}";
    }
}
