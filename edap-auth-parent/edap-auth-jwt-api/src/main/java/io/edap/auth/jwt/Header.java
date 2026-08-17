package io.edap.auth.jwt;

/**
 * JWT header 抽象：lite 版的 {@link JwtHeader}，方便实现层定义匿名默认 header
 * （如 {@code algo=HS256, typ=JWT, kid=""} 的固定 DEFAULT_HEADER）而不必每次实例化。
 *
 * <p>典型用例：实现层在静态区声明一个匿名 {@code Header}，避免反复创建 {@link JwtHeader}。</p>
 */
public interface Header {
    String getAlgorithm();
    String getType();
    String getKeyId();
}
