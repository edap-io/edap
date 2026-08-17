package io.edap.http.ws;

/**
 * {@link WSAuthenticator#verify} 的返回结果。
 *
 * <p>成功 / 失败两种形态用同一个 POJO 承载，避免异常路径用于控制流（鉴权失败是预期分支，
 *     不是异常情况）。</p>
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@link #ok}：鉴权是否通过。true → principal 必填；false → status + reason 必填</li>
 *   <li>{@link #status}：HTTP 状态码（如 401 / 403）；失败时返回给客户端</li>
 *   <li>{@link #reason}：失败原因（写入 HTTP body）；可为 null</li>
 *   <li>{@link #principal}：成功后写到 {@code WSConnection.setSessionContext("principal", p)}；
 *       失败时为 null</li>
 * </ul>
 */
public final class AuthResult {

    private final boolean  ok;
    private final int      status;
    private final String   reason;
    private final Principal principal;

    private AuthResult(boolean ok, int status, String reason, Principal principal) {
        this.ok        = ok;
        this.status    = status;
        this.reason    = reason;
        this.principal = principal;
    }

    /** 鉴权成功。principal 写到 sessionContext。 */
    public static AuthResult success(Principal principal) {
        return new AuthResult(true, 0, null, principal);
    }

    /**
     * 鉴权失败。
     *
     * @param status HTTP 状态码（如 401 / 403）
     * @param reason 失败原因（写入 HTTP response body）；可为 null
     */
    public static AuthResult fail(int status, String reason) {
        return new AuthResult(false, status, reason, null);
    }

    public boolean   ok()        { return ok; }
    public int       status()    { return status; }
    public String    reason()    { return reason; }
    public Principal principal() { return principal; }
}
