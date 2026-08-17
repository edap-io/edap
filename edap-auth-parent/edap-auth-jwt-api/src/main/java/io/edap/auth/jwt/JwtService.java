package io.edap.auth.jwt;

/**
 * JWT 服务门面：把 signKey 封装在实现里，业务方只通过 {@code @Inject JwtService} 即可签发 / 验签，
 * 无需关心 signKey 的来源。
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li>签发 / 验签 用同一个 bean（同一 signKey 上下文）</li>
 *   <li>{@link #builder()} 返回的 {@link JwtBuilder} 已预绑定 signKey —— 调用方只需继续 fluent 填 claims，直接 {@code build()}</li>
 *   <li>kid、多密钥、轮转等都在实现内部处理，<b>不暴露</b>给调用方</li>
 *   <li>特殊需求（HSM / KMS / 自定义算法）由应用自行实现本接口并以 bean 形式覆盖容器的默认实现</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 *   @Inject JwtService jwt;
 *
 *   // 签发
 *   String token = jwt.builder().subject("u-1").issuer("edap.io").build();
 *
 *   // 验签
 *   VerifyResult r = jwt.verify(token);
 *   if (r.getCode() == 0) { ... }
 * }</pre>
 */
public interface JwtService {

    /**
     * 验证 JWT token 签名。signKey 已封装在实现内部。
     *
     * @param token 形如 {@code header.payload.signature} 的 JWT
     * @return 验签结果（{@link VerifyResult#getCode()} == 0 表示通过）
     */
    VerifyResult verify(String token);

    /**
     * 拿到一个预绑定 signKey 的 builder，调用方继续用 fluent API 填 claims。
     *
     * <p>调用方通常不需要再调用 {@link JwtBuilder#signWith(String)} —— 如果调用了，会覆盖默认 signKey。
     * 实现应保证默认配置下可直接 {@code build()}。</p>
     */
    JwtBuilder builder();
}
