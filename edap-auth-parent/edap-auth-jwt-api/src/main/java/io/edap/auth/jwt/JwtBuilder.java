package io.edap.auth.jwt;

/**
 * JWT 链式构造器接口（API 层）。
 *
 * <p>实现位于 {@code edap-auth-jwt} 模块（默认 {@link io.edap.auth.jwt.internal.DefaultJwtBuilder}）。
 * 业务方通过 {@link JwtService#builder()} 拿到预绑定 signKey 的实例，无需关心实现细节。</p>
 *
 * <p><b>线程不安全</b>：一个实例应当只在单线程内完成 {@code create() → setXxx() → build()} 全过程；
 * 不同线程请各自 {@code new JwtBuilder()}。</p>
 */
public interface JwtBuilder {

    /** sub claim */
    JwtBuilder subject(String subject);

    /** iss claim */
    JwtBuilder issuer(String issuer);

    /** aud claim */
    JwtBuilder audience(String audience);

    /** exp claim（绝对时间，秒） */
    JwtBuilder expiresAt(long expiresAt);

    /** nbf claim（绝对时间，秒） */
    JwtBuilder notBefore(long notBefore);

    /** iat claim（绝对时间，秒） */
    JwtBuilder issuedAt(long issuedAt);

    /** jti claim */
    JwtBuilder jwtId(String jwtId);

    /** 自定义 claim */
    JwtBuilder claim(String name, Object value);

    /**
     * 指定签名密钥。{@link JwtService#builder()} 拿到的实例通常已预绑定，调用方无需调用。
     * 如果调用了，会覆盖默认 signKey。
     */
    JwtBuilder signWith(String signKey);

    /** 序列化为 {@code header.payload.signature} 字符串 */
    String build();
}
