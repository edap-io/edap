package io.edap.auth.jwt;

import io.edap.util.StringUtil;

/**
 * {@link JwtService} 默认实现：单一 signKey，构造函数注入。
 *
 * <p>应用在 edap-container 里以 bean 形式注入（{@code @Bean} + {@code @Config("jwt.signKey") String}）；
 * 默认实现注册在 {@code Container.containerBeans} 里，应用可注册自己的 {@link JwtService} 自动覆盖。</p>
 *
 * <p>线程安全：{@code signKey} 不可变，所有方法都是无状态的（无实例字段读写）。
 * 签名 / 验签 的算法实例由 {@code JWT.ALGORITHM_CACHE}（KeyCache）按 (alg, key) 缓存共享。</p>
 *
 * <p><b>淘汰计划</b>：当 keysByKid 多密钥场景落地后，本类会演化为按 kid 路由的内部实现，公开签名不变。</p>
 */
public class DefaultJwtService implements JwtService {

    private final String signKey;

    /**
     * @param signKey 用于 HS256 签名 / 验签的密钥字符串；null / 空 视为配置错误
     * @throws IllegalArgumentException signKey 为 null 或空
     */
    public DefaultJwtService(String signKey) {
        if (StringUtil.isEmpty(signKey)) {
            throw new IllegalArgumentException("signKey must not be null or empty");
        }
        this.signKey = signKey;
    }

    @Override
    public VerifyResult verify(String token) {
        return JWT.verify(token, signKey);
    }

    @Override
    public JwtBuilder builder() {
        return new DefaultJwtBuilder().signWith(signKey);
    }
}
