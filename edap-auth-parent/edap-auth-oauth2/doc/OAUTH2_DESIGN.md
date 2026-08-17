# Edap / Auth / OAuth 2.0 详细设计

> 本文档定义 edap 框架内 OAuth 2.0 授权服务器模块 `edap-auth-oauth2` 的完整端点、组件协作、Grant Types、OIDC 扩展与存储 SPI 设计。
>
> **目标读者**：在 `edap-auth-parent` / `edap-container-parent` / `edap-http-parent` 模块下做 OAuth 2.0 授权服务器、OIDC 身份认证、应用客户端集成的开发者。
>
> **前置文档**：
> - [`../edap-auth-jwt/doc/JWT_DESIGN.md`](../edap-auth-jwt/doc/JWT_DESIGN.md) JWT 工具模块设计（access_token / id_token 签名复用）
> - [`../../../doc/WS_HANDLER_DESIGN.md`](../../../doc/WS_HANDLER_DESIGN.md) §1.3 三层组件边界 / HTTP 端点注入机制
> - [`../../../doc/CONTAINER_APPCONTEXT_DESIGN.md`](../../../doc/CONTAINER_APPCONTEXT_DESIGN.md) Container/AppContext 双层 Bean 容器

---

## 一、目标与范围

### 1.1 设计目标

1. **完整 RFC 6749 + RFC 6750 + OIDC Core**：第一期实现 OAuth 2.0 Authorization Framework + Bearer Token Usage + OpenID Connect Core 1.0（id_token + userinfo + discovery + jwks + dynamic client registration）
2. **Grant types SPI 化**：5 个 grant types（authorization_code / implicit / password / client_credentials / refresh_token）的派发器接口完整设计；v1 实现 3 个（authorization_code + client_credentials + refresh_token），password / implicit 留接口不实现
3. **存储可插拔**：ClientStore / UserStore / TokenStore / AuthorizationCodeStore 全部抽象为 SPI；v1 内置 in-memory 实现，DB-backed 实现后续模块提供
4. **复用 edap-auth-jwt**：access_token 可选 JWT 格式；id_token 强制 JWT；签名/验签复用 JWT 模块
5. **容器能力**：模块本身作为 edap 容器 bean（`AuthorizationServer`）注册到 `Container.containerBeans`，应用可通过 byType 替换存储实现

### 1.2 不在本文档范围内

- OAuth 2.1 新约束（PKCE 强制、redirect_uri 严格匹配等）—— 第二期
- 设备授权流程（Device Authorization Grant，RFC 8628）—— 第二期
- 令牌绑定（Token Binding，RFC 8471）—— 第二期
- FAPI（Financial-grade API）合规 —— 业务层
- 用户登录 UI / 第三方 IdP 集成（GitHub/Google OAuth）—— 应用层，框架只提供协议端点
- 多租户隔离 / 客户端分组 —— 第二期

### 1.3 与 OAuth 2.0 标准映射

| RFC | 名称 | v1 状态 |
|---|---|---|
| RFC 6749 | The OAuth 2.0 Authorization Framework | ✅ 端点 + 3 个 grant |
| RFC 6750 | Bearer Token Usage | ✅ `Authorization: Bearer` 解析 |
| RFC 7009 | Token Revocation | ✅ `/revoke` 端点 |
| RFC 7662 | Token Introspection | ✅ `/introspect` 端点 |
| RFC 7591 | Dynamic Client Registration | ✅ `/register` 端点 |
| RFC 7636 | PKCE | ❌ 第二期 |
| RFC 8414 | Authorization Server Metadata | ✅ `/.well-known/oauth-authorization-server` |
| OIDC Core | OpenID Connect Core 1.0 | ✅ id_token + userinfo + discovery |
| OIDC RP-Initiated Logout 1.0 | RP 发起登出 | ✅ `/oauth2/logout`（见 §十五） |
| OIDC Back-Channel Logout 1.0 | OP→RP 服务端通知 | ✅ `backchannel_logout_uri`（见 §十五） |
| OIDC Front-Channel Logout 1.0 | 浏览器 iframe 通知 | ❌ 第二期 |
| RFC 7517 | JSON Web Key | ✅ `/jwks` 端点 |
| RFC 8628 | Device Authorization Grant | ❌ 第二期 |

---

## 二、模块结构

### 2.1 新模块拓扑

```
edap-auth-parent
├── pom.xml
├── edap-auth-jwt                              (叶子：JWT 算法工具)
│   └── src/main/java/io/edap/auth/jwt/...
└── edap-auth-oauth2                           (新增：OAuth 2.0 授权服务器)
    ├── pom.xml
    ├── src/main/java/io/edap/auth/oauth2/
    │   ├── AuthorizationServer.java            (顶层 facade)
    │   ├── endpoint/
    │   │   ├── AuthorizeEndpoint.java
    │   │   ├── TokenEndpoint.java
    │   │   ├── IntrospectEndpoint.java
    │   │   ├── RevokeEndpoint.java
    │   │   ├── JwksEndpoint.java
    │   │   ├── UserinfoEndpoint.java
    │   │   ├── RegisterEndpoint.java
    │   │   └── DiscoveryEndpoint.java
    │   ├── grant/
    │   │   ├── GrantHandler.java               (SPI 接口)
    │   │   ├── AuthorizationCodeHandler.java   (v1 实现)
    │   │   ├── ClientCredentialsHandler.java   (v1 实现)
    │   │   ├── RefreshTokenHandler.java        (v1 实现)
    │   │   ├── PasswordHandler.java            (v2 留位)
    │   │   └── ImplicitHandler.java            (v2 留位，已废弃)
    │   ├── store/
    │   │   ├── ClientStore.java                (SPI)
    │   │   ├── UserStore.java                  (SPI)
    │   │   ├── TokenStore.java                 (SPI)
    │   │   ├── AuthorizationCodeStore.java     (SPI)
    │   │   └── memory/                          (v1 默认实现)
    │   │       ├── InMemoryClientStore.java
    │   │       ├── InMemoryUserStore.java
    │   │       ├── InMemoryTokenStore.java
    │   │       └── InMemoryAuthorizationCodeStore.java
    │   ├── model/
    │   │   ├── Client.java
    │   │   ├── User.java
    │   │   ├── AccessToken.java
    │   │   ├── RefreshToken.java
    │   │   ├── AuthorizationCode.java
    │   │   └── TokenRequest.java
    │   └── grant/
    │       └── GrantType.java                  (enum)
    └── doc/OAUTH2_DESIGN.md                     (本文档)
```

### 2.2 模块依赖

```xml
<!-- edap-auth-oauth2/pom.xml -->
<dependencies>
    <dependency>
        <groupId>io.edap</groupId>
        <artifactId>edap-auth-jwt</artifactId>
    </dependency>
    <dependency>
        <groupId>io.edap</groupId>
        <artifactId>edap-http-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.edap</groupId>
        <artifactId>edap-container</artifactId>
    </dependency>
    <dependency>
        <groupId>io.edap</groupId>
        <artifactId>edap-json</artifactId>
    </dependency>
    <dependency>
        <groupId>io.edap</groupId>
        <artifactId>edap-common</artifactId>
    </dependency>
</dependencies>
```

### 2.3 依赖方向原则

1. **edap-auth-jwt 不依赖 edap-auth-oauth2**：JWT 是叶子算法库，OAuth 2.0 复用它，不反向依赖
2. **edap-auth-oauth2 依赖 edap-auth-jwt**：签 id_token / 可选 access_token
3. **edap-auth-oauth2 依赖 edap-container**：作为容器能力注册 `AuthorizationServer` bean；端点作为 HTTP handler 注入
4. **存储 SPI 不绑 edap-data-jdbc-dao**：in-memory 默认；DB 实现由 `edap-auth-oauth2-jdbc`（后续模块）提供，避免当前模块臃肿

---

## 三、端点定义

### 3.1 端点清单

| 端点 | 路径 | 方法 | OAuth 2.0 角色 | OIDC 角色 | v1 实现 |
|---|---|---|---|---|---|
| `/oauth2/authorize` | authorization endpoint | GET, POST | ✅ | ✅ | ✅ |
| `/oauth2/token` | token endpoint | POST | ✅ | — | ✅ |
| `/oauth2/introspect` | RFC 7662 | POST | ✅ | — | ✅ |
| `/oauth2/revoke` | RFC 7009 | POST | ✅ | — | ✅ |
| `/oauth2/jwks` | RFC 7517 | GET | — | ✅ | ✅ |
| `/oauth2/userinfo` | OIDC | GET, POST | — | ✅ | ✅ |
| `/oauth2/register` | RFC 7591 | POST | — | ✅ | ✅ |
| `/oauth2/logout` | OIDC RP-Initiated Logout | GET, POST | — | ✅ | ✅ |
| `/.well-known/openid-configuration` | OIDC Discovery | GET | — | ✅ | ✅ |
| `/.well-known/oauth-authorization-server` | RFC 8414 | GET | ✅ | — | ✅ |

> **路径前缀**：所有端点统一 `/oauth2/*`，OIDC discovery 用 `.well-known` 标准路径。应用可在 `Container.env` 配置自定义前缀（`oauth2.path-prefix`）

### 3.2 端点注册机制

```java
// AuthorizationServer 在 init 时向 edap-container 注册 HTTP handler
public class AuthorizationServer {
    public void registerEndpoints(BeanContainer beans, RouterHub routerHub) {
        // 1. 注册 HTTP handler bean
        beans.register(new BeanDef("oauth2.AuthorizeEndpoint", AuthorizeEndpoint.class, ...));
        // ... 其他端点

        // 2. 路由注入：/oauth2/* → AuthorizeEndpoint 等
        Map<String, HttpHandler> handlers = new HashMap<>();
        handlers.put("/oauth2/authorize", authorizeEndpoint);
        handlers.put("/oauth2/token", tokenEndpoint);
        // ...
        routerHub.setHandlers(handlers);
    }
}
```

> **注册时机**：参考 `WS_HANDLER_DESIGN.md` §2，路径固定 + handler 替换走 `RouterHub.setHandlers` 同套机制

### 3.3 请求/响应统一封装

```java
// TokenRequest: 统一 token 端点入参
public class TokenRequest {
    private String grantType;
    private String code;             // authorization_code
    private String redirectUri;      // authorization_code
    private String clientId;
    private String clientSecret;
    private String username;         // password (v2)
    private String password;         // password (v2)
    private String refreshToken;     // refresh_token
    private String scope;            // space-delimited
    private String codeVerifier;     // PKCE (v2)
    // getters/setters
}

// TokenResponse: 统一 token 端点出参（RFC 6749 §5.1）
public class TokenResponse {
    private String accessToken;
    private String tokenType;        // "Bearer"
    private long expiresIn;          // seconds
    private String refreshToken;
    private String scope;
    private String idToken;          // OIDC
    // getters/setters
}

// ErrorResponse: RFC 6749 §5.2
public class ErrorResponse {
    private String error;            // invalid_request / invalid_client / ...
    private String errorDescription;
    private String errorUri;
    // getters/setters
}
```

---

## 四、Grant Types

### 4.1 GrantType 枚举

```java
public enum GrantType {
    AUTHORIZATION_CODE("authorization_code"),
    IMPLICIT("implicit"),
    PASSWORD("password"),
    CLIENT_CREDENTIALS("client_credentials"),
    REFRESH_TOKEN("refresh_token");

    private final String value;
    // ...
}
```

### 4.2 GrantHandler SPI

```java
public interface GrantHandler {
    /** 该 handler 支持的 grant type */
    GrantType grantType();

    /**
     * 处理 token 请求。
     *
     * @param req   解析后的 token 请求（含 grant_type + 各 grant 特定参数）
     * @param ctx   授权服务器上下文（含 ClientStore / UserStore / TokenStore 等）
     * @return 成功返回 TokenResponse；失败返回 ErrorResponse（HTTP 400/401）
     */
    GrantResult handle(TokenRequest req, AuthorizationContext ctx);
}

public final class GrantResult {
    private final TokenResponse success;
    private final ErrorResponse error;

    public static GrantResult success(TokenResponse resp);
    public static GrantResult error(String code, String description);
}
```

### 4.3 GrantDispatcher

```java
public class GrantDispatcher {
    private final Map<GrantType, GrantHandler> handlers = new HashMap<>();

    public void register(GrantHandler handler) {
        handlers.put(handler.grantType(), handler);
    }

    public GrantResult dispatch(TokenRequest req, AuthorizationContext ctx) {
        GrantType gt = GrantType.fromValue(req.getGrantType());
        GrantHandler h = handlers.get(gt);
        if (h == null) {
            return GrantResult.error("unsupported_grant_type",
                    "grant_type " + req.getGrantType() + " not supported");
        }
        return h.handle(req, ctx);
    }
}
```

### 4.4 v1 实现的三个 Grant

#### 4.4.1 authorization_code

**流程**：
1. 客户端重定向用户到 `/oauth2/authorize?response_type=code&client_id=X&redirect_uri=Y&scope=Z&state=S`
2. 用户在 edap 登录 + 同意授权（应用提供登录页面，框架提供 consent 端点预留）
3. edap 重定向回 `redirect_uri?code=C&state=S`
4. 客户端用 code 调 `/oauth2/token`（POST）→ 拿到 access_token + refresh_token + id_token

**handler 要点**：
- 校验 `client_id` 在 ClientStore 存在 + `redirect_uri` 严格匹配
- 校验 `code` 在 AuthorizationCodeStore 存在 + 未过期 + 未使用过
- 校验 `scope` 是 client 注册 scope 的子集
- 生成 access_token（可选 JWT）+ refresh_token（opaque）+ id_token（JWT 含 sub/iss/aud/exp/iat）
- code 标记已使用（一次性）

#### 4.4.2 client_credentials

**流程**：
1. 客户端直接 POST `/oauth2/token` 带 `grant_type=client_credentials&client_id=X&client_secret=Y&scope=Z`
2. edap 校验 client 凭据 → 返回 access_token（无 refresh_token，无 id_token）

**handler 要点**：
- 用 HTTP Basic Auth 或 body 传 client_id + client_secret
- 校验 client_secret（bcrypt / scrypt 哈希比对）
- 不涉及 user，scope 直接绑 client

#### 4.4.3 refresh_token

**流程**：
1. 客户端用 refresh_token POST `/oauth2/token` 带 `grant_type=refresh_token&refresh_token=R&scope=Z`
2. edap 校验 refresh_token → 颁发新 access_token（+ 可选新 refresh_token rotation）

**handler 要点**：
- 校验 refresh_token 在 TokenStore 存在 + 未撤销 + 未过期
- 可选 refresh token rotation（v1 暂不实现，留开关）
- 校验 scope 是原 scope 的子集

### 4.5 v2 留位的两个 Grant（不实现）

| Grant | RFC 6749 § | 状态 | 备注 |
|---|---|---|---|
| password | §4.3 | 接口预留 | 应用通过 SPI 注册；edap 不内置（不安全） |
| implicit | §4.2 | 接口预留 | OAuth 2.1 已废弃，标 `@Deprecated`；应用不应使用 |

```java
public class PasswordHandler implements GrantHandler {
    @Override public GrantType grantType() { return GrantType.PASSWORD; }
    @Override public GrantResult handle(...) {
        throw new UnsupportedOperationException(
            "password grant is not implemented by default; register a custom GrantHandler");
    }
}
```

> **为什么不内置**：password grant 要求应用直接拿用户密码给 edap，违反最小权限原则；应用若确需（如第一方内部系统），自行实现

---

## 五、Token 模型

### 5.1 access_token

| 字段 | 类型 | 说明 |
|---|---|---|
| token | String | 格式：`{opaque-id}` 或 `{jwt}` |
| type | enum | Bearer / MAC（v1 只实现 Bearer） |
| clientId | String | 颁发给的 client |
| userId | String? | 关联用户（client_credentials 时为 null） |
| scope | Set<String> | 授权 scope |
| expiresAt | long | Unix epoch ms |
| createdAt | long | Unix epoch ms |

**JWT 还是 opaque**：
- 默认 opaque（`UUID.randomUUID().toString()`）—— 服务端可即时撤销
- 应用可配置 `oauth2.access-token.format=jwt` —— JWT 形式（自包含、可离线校验）
- id_token 强制 JWT（OIDC 规定）

### 5.2 refresh_token

| 字段 | 类型 | 说明 |
|---|---|---|
| token | String | opaque（高熵随机串） |
| clientId | String | |
| userId | String? | |
| scope | Set<String> | |
| expiresAt | long | 默认 30 天 |
| accessTokenId | String? | rotation 时指向新 access_token |

### 5.3 id_token（OIDC）

JWT 格式（强制），复用 edap-auth-jwt 签名：

```json
{
  "iss": "https://edap.example.com",
  "sub": "user-123",
  "aud": "client-id",
  "exp": 1234567890,
  "iat": 1234567000,
  "auth_time": 1234567000,
  "nonce": "n-0S6_WzA2Mj",
  "name": "Alice",
  "email": "alice@example.com",
  "email_verified": true
}
```

**claim 来源**：
- `iss` / `exp` / `iat` —— AuthorizationServer 配置 + 当前时间
- `sub` —— UserStore 查询
- `aud` —— 请求的 client_id
- `auth_time` —— 用户授权时间（authorize 端点记录）
- `nonce` —— authorize 请求透传（防重放）
- `name` / `email` / 自定义 —— UserStore 返回的 profile

---

## 六、存储 SPI

### 6.1 ClientStore

```java
public interface ClientStore {
    /** 查询 client；不存在返回 null */
    Client findById(String clientId);

    /** 校验 client_secret；正确返回 true */
    boolean verifySecret(String clientId, String clientSecret);

    /** dynamic registration (RFC 7591) 时注册新 client */
    Client register(ClientRegistrationRequest req);

    /** 更新 client（如修改 redirect_uri） */
    Client update(String clientId, ClientUpdateRequest req);

    /** 列出所有 client（管理接口用） */
    List<Client> list();
}
```

### 6.2 UserStore

```java
public interface UserStore {
    /** 按用户名查询 */
    User findByUsername(String username);

    /** 校验密码（应用决定 password hashing 策略） */
    boolean verifyPassword(String username, String password);

    /** OIDC userinfo 端点用 */
    Map<String, Object> getProfile(String userId);
}
```

### 6.3 TokenStore

```java
public interface TokenStore {
    void saveAccessToken(AccessToken token);
    AccessToken findAccessToken(String token);
    void revokeAccessToken(String token);

    void saveRefreshToken(RefreshToken token);
    RefreshToken findRefreshToken(String token);
    void revokeRefreshToken(String token);

    /** RFC 7009 revocation：撤销该 refresh_token 及其派生的 access_token */
    void revokeAllForClient(String clientId, String userId);
}
```

### 6.4 AuthorizationCodeStore

```java
public interface AuthorizationCodeStore {
    void save(AuthorizationCode code);
    AuthorizationCode findAndRemove(String code);  // 一次性：找到即删
    void purgeExpired();                          // 定期清理
}
```

### 6.5 in-memory 默认实现（v1）

```java
// store/memory/InMemoryClientStore.java
public class InMemoryClientStore implements ClientStore {
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    public InMemoryClientStore(List<Client> initialClients) {
        for (Client c : initialClients) clients.put(c.getClientId(), c);
    }
    // ... 其他方法
}
```

**配置加载**：
```properties
# container.properties
oauth2.clients[0].client-id=my-app
oauth2.clients[0].client-secret=secret-hash-bcrypt-...
oauth2.clients[0].redirect-uris[0]=https://app.example.com/callback
oauth2.clients[0].scopes[0]=read
oauth2.clients[0].scopes[1]=write
oauth2.clients[0].grant-types[0]=authorization_code
oauth2.clients[0].grant-types[1]=refresh_token
```

> **secret 存储**：v1 in-memory 明文（仅 demo）；生产必须 hash（bcrypt/scrypt）；DB 实现强制 hash

---

## 七、Client 与 User

### 7.1 Client 模型

```java
public class Client {
    private String clientId;
    private String clientSecret;        // hashed in DB impl
    private String clientName;
    private List<String> redirectUris;  // 严格匹配（RFC 6749 §3.1.2）
    private List<String> grantTypes;    // 允许的 grant types
    private List<String> scopes;        // 允许申请的 scope
    private long createdAt;
    private String clientType;          // "confidential" or "public"
    // ...
}
```

### 7.2 User 模型

```java
public class User {
    private String userId;              // sub
    private String username;
    private String passwordHash;
    private Map<String, Object> profile;  // OIDC userinfo 返回字段
    // ...
}
```

### 7.3 Scope 设计

```java
public class Scope {
    private String name;                // "read" / "write" / "openid" / "profile" / "email"
    private String description;
    // OIDC 标准 scope：openid / profile / email / address / phone
    // 自定义 scope：应用按业务定义
}
```

**OIDC 标准 scope**（必须支持）：
- `openid` —— 必填，触发 id_token 颁发
- `profile` —— userinfo 返回 name / given_name / family_name / picture 等
- `email` —— userinfo 返回 email / email_verified
- `address` —— userinfo 返回 address
- `phone` —— userinfo 返回 phone_number / phone_number_verified

---

## 八、集成 edap-auth-jwt

### 8.1 access_token 可选 JWT

```java
public class AccessTokenFactory {
    private final boolean jwtFormat;       // 配置：oauth2.access-token.format
    private final String signKey;          // HS256 密钥

    public AccessToken create(Client client, User user, Set<String> scopes) {
        if (jwtFormat) {
            // 走 edap-auth-jwt：JWT.create().subject(...).claim(...).signWith(...).build()
            String jwt = JWT.create()
                .subject(user != null ? user.getUserId() : client.getClientId())
                .claim("client_id", client.getClientId())
                .claim("scope", String.join(" ", scopes))
                .expiresAt(System.currentTimeMillis() + expiresIn * 1000)
                .signWith(signKey)
                .build();
            return new AccessToken(jwt, BEARER, client.getClientId(),
                                   user != null ? user.getUserId() : null,
                                   scopes, expiresAt);
        } else {
            // opaque：UUID.randomUUID().toString()
            ...
        }
    }
}
```

### 8.2 id_token 强制 JWT

```java
public class IdTokenFactory {
    public String create(Client client, User user, String nonce, long authTime) {
        return JWT.create()
            .issuer(authorizationServerIssuer)        // 配置：oauth2.issuer
            .subject(user.getUserId())
            .audience(client.getClientId())
            .issuedAt(System.currentTimeMillis())
            .expiresAt(System.currentTimeMillis() + idTokenTtl * 1000)
            .claim("auth_time", authTime)
            .claim("nonce", nonce)
            .claim("name", user.getProfile().get("name"))
            .claim("email", user.getProfile().get("email"))
            .signWith(signKey)
            .build();
    }
}
```

### 8.3 算法选择

- v1 仅 HS256（复用 edap-auth-jwt 现有 `HmacSha256`）
- 第二期 RS256：access_token JWT / id_token JWT 用 RSA 私钥签名；`/jwks` 端点发布公钥

---

## 九、OIDC 完整支持

### 9.1 id_token 签名算法

```java
public enum SigningAlgorithm {
    HS256,    // v1 默认
    RS256,    // 第二期
    ES256     // 第三期
}
```

### 9.2 userinfo 端点（OIDC §5.3）

```
GET /oauth2/userinfo
Authorization: Bearer <access_token>

200 OK
{
  "sub": "user-123",
  "name": "Alice",
  "email": "alice@example.com",
  "email_verified": true
}
```

**实现要点**：
- 校验 access_token 在 TokenStore 存在 + 未过期
- 从 access_token 取 userId
- 根据 scope 返回字段：`profile` scope → name/picture；`email` scope → email/email_verified
- access_token 不含 `openid` scope → 403 insufficient_scope

### 9.3 discovery 端点（OIDC Discovery）

```
GET /.well-known/openid-configuration

200 OK
{
  "issuer": "https://edap.example.com",
  "authorization_endpoint": "https://edap.example.com/oauth2/authorize",
  "token_endpoint": "https://edap.example.com/oauth2/token",
  "userinfo_endpoint": "https://edap.example.com/oauth2/userinfo",
  "jwks_uri": "https://edap.example.com/oauth2/jwks",
  "registration_endpoint": "https://edap.example.com/oauth2/register",
  "revocation_endpoint": "https://edap.example.com/oauth2/revoke",
  "introspection_endpoint": "https://edap.example.com/oauth2/introspect",
  "response_types_supported": ["code", "id_token", "token"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["HS256"],
  "scopes_supported": ["openid", "profile", "email"],
  "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"],
  "claims_supported": ["sub", "iss", "aud", "exp", "iat", "name", "email"]
}
```

### 9.4 jwks 端点（RFC 7517）

```
GET /oauth2/jwks

200 OK
{
  "keys": [
    {
      "kty": "oct",      // HS256 对称密钥，v1 是对称
      "kid": "key-2024-01",
      "use": "sig",
      "alg": "HS256"
    }
    // 第二期加 RSA：
    // {
    //   "kty": "RSA",
    //   "kid": "key-2024-02",
    //   "use": "sig",
    //   "alg": "RS256",
    //   "n": "...",
    //   "e": "AQAB"
    // }
  ]
}
```

> **HS256 jwks 安全性问题**：对称密钥发布到 JWKS 端点意味着任何拿到 jwks 的人都能伪造签名。v1 仅 demo；第二期切 RS256（私钥签名 + 公钥发布），edap-auth-jwt 加 `RsaSha256` 算法实现

### 9.5 dynamic client registration（RFC 7591）

```
POST /oauth2/register
Content-Type: application/json

{
  "client_name": "My App",
  "redirect_uris": ["https://app.example.com/callback"],
  "grant_types": ["authorization_code", "refresh_token"],
  "scopes": ["openid", "profile", "email"]
}

201 Created
{
  "client_id": "auto-generated-uuid",
  "client_secret": "auto-generated-secret",
  "client_id_issued_at": 1234567890,
  "client_secret_expires_at": 0
}
```

**安全**：默认禁用；通过 `oauth2.registration.enabled=true` 开启；生产建议加 admin token 鉴权

---

## 十、容器接入

### 10.1 AuthorizationServer bean

```java
// edap-container/.../Container.initContainerBeans() 中
private void initContainerBeans() {
    // ... 已有 EventPublisher / ShardRegistry 初始化 ...

    // 注册 AuthorizationServer
    try {
        // 1. 加载配置 → 创建默认 store
        Props oauth2Props = env.child("oauth2");
        List<Client> initialClients = parseClients(oauth2Props);
        InMemoryClientStore clientStore = new InMemoryClientStore(initialClients);
        InMemoryUserStore userStore = new InMemoryUserStore(parseUsers(oauth2Props));
        InMemoryTokenStore tokenStore = new InMemoryTokenStore();
        InMemoryAuthorizationCodeStore codeStore = new InMemoryAuthorizationCodeStore();

        // 2. 创建 AuthorizationServer 实例（注入所有 store）
        AuthorizationServer authz = new AuthorizationServer.Builder()
            .issuer(oauth2Props.getString("issuer"))
            .signKey(oauth2Props.getString("jwt.sign-key"))
            .clientStore(clientStore)
            .userStore(userStore)
            .tokenStore(tokenStore)
            .authorizationCodeStore(codeStore)
            .build();

        // 3. 注册 v1 grants
        authz.registerGrant(new AuthorizationCodeHandler(...));
        authz.registerGrant(new ClientCredentialsHandler(...));
        authz.registerGrant(new RefreshTokenHandler(...));

        // 4. 注册为 bean
        BeanDef def = new BeanDef(
            "container." + AuthorizationServer.class.getSimpleName(),
            AuthorizationServer.class, Scope.SINGLETON,
            null, null, null, new Object[]{authz}, 0);
        containerBeans.register(def);

        // 5. 注册 HTTP 端点（路由注入）
        authz.registerEndpoints(containerBeans, routerHub);
    } catch (Exception e) {
        log.warn("注册 AuthorizationServer bean 失败", ...);
    }

    // ... JwtWSAuthenticator 注册 ...
}
```

### 10.2 应用覆盖

应用可通过 byType 替换 store 实现：

```java
@Bean
public ClientStore myDbBackedClientStore() {
    return new JdbcClientStore(dataSource);
}

@Bean
public UserStore myLdapUserStore() {
    return new LdapUserStore(ldapContext);
}
```

`AuthorizationServer.Builder` 在初始化时优先按 byType 查找应用 store bean，找不到才用默认 in-memory 实现

### 10.3 路由冲突

参考 `WS_HANDLER_DESIGN.md` §4.2 路由策略：`/oauth2/*` 路径独占，OAuth 端点优先于业务 handler；应用不能注册 `/oauth2/*` 前缀的路径

---

## 十一、性能

### 11.1 验证路径开销

| 端点 | 操作 | 开销 |
|---|---|---|
| `/authorize` GET | 渲染登录页（应用） + 写 code | ~1ms |
| `/token` authorization_code | 校验 code + 查 client + 查 user + 签 JWT + 写 token store | ~5ms |
| `/token` client_credentials | 校验 client + 签 JWT + 写 store | ~2ms |
| `/token` refresh_token | 查 refresh_token + 查 client + 签 JWT | ~1ms |
| `/introspect` | 查 access_token | ~100μs |
| `/revoke` | 删 access_token + cascade refresh_token | ~200μs |
| `/jwks` GET | 返回静态 JSON | ~50μs |
| `/userinfo` | 校验 token + 查 user profile | ~1ms |
| `/.well-known/...` | 返回静态 JSON | ~50μs |
| `/logout` GET | 校验 id_token + 撤销 refresh + 异步通知 + 302 | ~3ms（不含 Back-Channel 异步） |
| Back-Channel 通知 | 异步 POST（不等响应） | < 1ms 触发开销 |

### 11.2 缓存策略

| 缓存 | 内容 | 失效策略 |
|---|---|---|
| ClientCache | client_id → Client | client 更新时失效（TTL 5min） |
| UserCache | user_id → User profile | user 更新时失效（TTL 5min） |
| TokenCache | token → AccessToken/RefreshToken | token 撤销时失效（不 TTL） |
| JwksCache | 静态 JWKS JSON | 进程启动期加载，永不失效（除非 key 轮换） |

> **缓存不持久**：重启 in-memory 丢失；DB 实现不缓存（DB 自身有缓存）

---

## 十二、安全

### 12.1 client_secret 存储

- **in-memory 默认实现**：明文存储（demo only；生产禁用）
- **DB 实现**：bcrypt（cost ≥ 10）哈希；verify 时 `BCrypt.checkpw(input, stored)`
- **配置加载层**：检测到明文 secret + in-memory store → WARN 日志提示生产风险

### 12.2 state 参数校验

- authorization_code grant 强制要求 `state` 参数（防 CSRF）
- `/authorize` 端点生成 state → 存入 session；redirect 时透传
- 应用 callback 必须校验 state 一致

### 12.3 redirect_uri 严格匹配

- v1 实现：完全字符串匹配（RFC 6749 §3.1.2.3 简单比较）
- v1 不支持：通配符 / prefix 匹配（安全风险）
- v1 不支持：动态端口 / `http://localhost` 例外

### 12.4 PKCE（v2）

- v1 不实现 PKCE
- v2 在 authorization_code 上加 PKCE：客户端生成 `code_verifier` + `code_challenge`；authorize 端点存 challenge；token 端点校验 `code_verifier` 哈希

### 12.5 scope 隔离

- access_token scope 是申请 scope ∩ client 注册 scope
- client_credentials grant：scope 是 client 自己的 scope（无 user）
- 拒绝越权申请：`requested_scope - registered_scope` 非空 → invalid_scope

### 12.6 日志脱敏

- access_token / refresh_token / client_secret 不写入日志
- 失败日志仅打印：错误码 + 请求 client_id（不含 secret）

---

## 十三、API 演进

### 13.1 第一期落地清单

| 改动 | 文件 | 说明 |
|---|---|---|
| 新模块 pom | `edap-auth-oauth2/pom.xml` | 依赖 edap-auth-jwt + edap-http-core + edap-container + edap-json + edap-common |
| GrantType 枚举 | `grant/GrantType.java` | 5 个值 |
| GrantHandler SPI | `grant/GrantHandler.java` | 派发接口 |
| AuthorizationCodeHandler | `grant/AuthorizationCodeHandler.java` | v1 实现 |
| ClientCredentialsHandler | `grant/ClientCredentialsHandler.java` | v1 实现 |
| RefreshTokenHandler | `grant/RefreshTokenHandler.java` | v1 实现 |
| PasswordHandler | `grant/PasswordHandler.java` | v2 留位（throw UnsupportedOperationException） |
| ImplicitHandler | `grant/ImplicitHandler.java` | v2 留位 + @Deprecated |
| ClientStore SPI + in-memory | `store/ClientStore.java` + `memory/InMemoryClientStore.java` | |
| UserStore SPI + in-memory | `store/UserStore.java` + `memory/InMemoryUserStore.java` | |
| TokenStore SPI + in-memory | `store/TokenStore.java` + `memory/InMemoryTokenStore.java` | |
| AuthorizationCodeStore SPI + in-memory | `store/AuthorizationCodeStore.java` + `memory/InMemoryAuthorizationCodeStore.java` | |
| SessionStore SPI + in-memory（§十五新增） | `store/SessionStore.java` + `memory/InMemorySessionStore.java` | Session 模型 + sid 生成 |
| Client / User / Token / Session 模型 | `model/*.java` | Client 新增 backchannelLogoutUri / postLogoutRedirectUris |
| 8 个端点 HTTP handler | `endpoint/*.java` | 新增 LogoutEndpoint（GET + POST） |
| BackChannelLogoutNotifier | `logout/BackChannelLogoutNotifier.java` | OP 侧异步通知（线程池 + 指数退避重试） |
| FailedLogoutStore（v1 in-memory） | `logout/FailedLogoutStore.java` | v1 仅内存；v2 持久化重试 |
| AuthorizationServer facade | `AuthorizationServer.java` | Builder 模式 + registerEndpoints + registerGrant |
| Container 集成 | `edap-container/.../Container.java` | 注册 AuthorizationServer bean + 路由注入 |
| 配置文件示例 | `edap-auth-oauth2/src/main/resources/oauth2-default.properties` | 默认配置模板 |
| 单元测试 | `edap-auth-oauth2/src/test/...` | 3 个 grant type 端到端测试 + logout 端到端测试 |

### 13.2 第二期路线

- RS256 算法 + JWKS 公钥发布（解决 HS256 jwks 安全性问题）
- PKCE（authorization_code 强制）
- JdbcClientStore / JdbcUserStore / JdbcTokenStore（DB-backed 实现）
- 设备授权流程（RFC 8628）
- dynamic client registration 默认禁用 + admin token 鉴权

### 13.3 第三期路线

- ES256 / EdDSA
- 令牌绑定（Token Binding）
- 多租户隔离（client 按 tenant 分组）

### 13.4 向后兼容

- 5 个 GrantType 枚举值稳定
- SPI 接口方法签名变更需要 minor version bump
- in-memory 默认实现仅 demo，生产前必须替换

---

## 十五、用户登出与全 RP 通知

### 15.1 设计目标

OAuth 2.0 核心（RFC 6749）**不**定义用户登出语义；access_token 是自包含的，资源服务器只校验签名/有效期，不查中心化失效表。本节基于 **OIDC RP-Initiated Logout 1.0** + **OIDC Back-Channel Logout 1.0** 实现完整的登出机制：

1. **RP-Initiated Logout**：用户在 RP 点"登出" → RP 重定向到 OP `/oauth2/logout` → OP 销毁服务端 session + 撤销该 user 的所有 refresh_token + 异步通知所有 RP + 重定向回 RP
2. **Back-Channel Logout**（P1）：OP 服务端主动向各 RP 注册的 `backchannel_logout_uri` 发 POST，附带 `logout_token`（短 TTL JWT），RP 验签后销毁本地 session
3. **Front-Channel Logout**（v2）：浏览器 iframe 方式，受第三方 cookie 限制，第二期
4. **与 RFC 7009 `/revoke` 的关系**：单 token 撤销 vs 全 user session 撤销，分工清晰

### 15.2 三种"撤销"语义对比

| 操作 | 范围 | 触发方 | 标准 | v1 |
|---|---|---|---|---|
| `POST /oauth2/revoke` | 单个 access_token / refresh_token | 客户端（client） | RFC 7009 | ✅ |
| `GET/POST /oauth2/logout` | 该 user 的所有 sessions + refresh_tokens | 用户（经 RP） | OIDC RP-Initiated Logout | ✅ |
| `POST {rp.backchannel_logout_uri}` | 通知所有 RP 该 user 已登出 | OP 服务端（异步） | OIDC Back-Channel Logout | ✅ |
| Front-Channel iframe 登出 | 浏览器内通知所有 RP | OP 渲染页面 | OIDC Front-Channel | ❌ v2 |

> **关键区分**：`/revoke` 是"客户端**自己**撤销**自己**的 token"（客户端知道 token 内容）；`/logout` 是"用户**自己**登出**所有** session"（OP 知道所有 session）。两者**不**等价，不能互相替代。

### 15.3 RP-Initiated Logout 流程

```
用户在 RP1 上点"登出"
   │
   ▼
RP1 服务端发起重定向到 OP:
GET /oauth2/logout?
    id_token_hint=eyJ...                       ← OIDC 必填（让 OP 知道是谁）
   &post_logout_redirect_uri=https://rp1.example/logged-out
   &state=xyz                                   ← 推荐，防 CSRF
   │
   ▼
OP 端 LogoutEndpoint 处理：
   1. 校验 id_token_hint
      ├─ 签名：用 OP 的 JWKS 公钥验签（HS256 则用 signKey）
      ├─ iss：必须等于本 OP 的 issuer
      ├─ aud：必须等于当前请求的 client_id（防 cross-RP 触发登出）
      └─ exp：过期也接受（用户要登出，id_token 过期不代表"不要登出"）
   2. 从 id_token 提取 sub / sid
   3. 校验 post_logout_redirect_uri：
      ├─ 必须在 client 注册的 postLogoutRedirectUris 白名单内
      ├─ 完全字符串匹配（不允许通配符）
      └─ 不在白名单 → 拒绝（不回 redirect，防 open redirect）
   4. 销毁该 sub 的 OP 端 session（SessionStore.deleteBySubject）
   5. 撤销该 sub 的所有 refresh_token（TokenStore.revokeAllBySubject）
   6. 触发 Back-Channel Logout（§15.4，**异步**，不等结果）
   7. 重定向到 post_logout_redirect_uri + state（可选）

RP1 收到 302：
   ├─ state 校验
   └─ 清 RP1 本地 session / cookie
```

**没有 id_token_hint 的回退**：RP 不知道 id_token 时，可只传 `post_logout_redirect_uri` + `state`，OP 提示用户选择要登出的账号（仅在 OP 支持多账号切换时）；v1 要求 id_token_hint 必填，未带 → `invalid_request`。

### 15.4 Back-Channel Logout 机制

**触发时机**：`/oauth2/logout` 成功后异步触发；不阻塞 logout 响应。

**logout_token 结构**（JWT，由 OP 签发）：

```json
{
  "iss": "https://op.edap.io",
  "sub": "user-123",
  "aud": "rp-client-id-1",
  "iat": 1700000000,
  "exp": 1700000060,
  "jti": "logout-event-uuid",
  "events": {
    "http://schemas.openid.net/event/backchannel-logout": {}
  },
  "sid": "session-id-from-op"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `iss` | ✅ | OP 的 issuer URL |
| `sub` | ✅ | 登出用户 ID |
| `aud` | ✅ | **目标 RP 的 client_id**（每个 RP 收到的 logout_token aud 不同） |
| `iat` | ✅ | 签发时间 |
| `exp` | ✅ | **强制 ≤ 60s**（重放窗口短） |
| `jti` | ✅ | UUID，RP 用来防重放 |
| `events` | ✅ | 固定值 `{ "http://schemas.openid.net/event/backchannel-logout": {} }` |
| `sid` | ⚠️ | 可选；`backchannelLogoutSessionRequired=true` 时必填 |

### 15.5 OP 侧 LogoutNotifier

```java
package io.edap.auth.oauth2.logout;

public final class BackChannelLogoutNotifier {
    private final TokenStore tokenStore;       // 查所有给该 sub 发过 token 的 client
    private final SessionStore sessionStore;   // 查 sid
    private final HttpClient httpClient;       // POST logout_token 到各 RP
    private final String opIssuer;             // iss claim
    private final String signKey;              // HS256 signKey（复用 edap-auth-jwt）
    private final long tokenTtlSeconds;        // 默认 60s
    private final int retryMax;                // 默认 3
    private final ExecutorService executor;    // 异步线程池

    public void notify(String sub) {
        // 1. 查所有给该 sub 发过 token 的 client_id
        List<String> clientIds = tokenStore.findClientsBySubject(sub);
        if (clientIds.isEmpty()) return;

        // 2. 查 sid（用于 logout_token）
        String sid = sessionStore.findBySubject(sub)
                .map(Session::getSid).orElse(null);

        // 3. 异步向各 client 发 logout_token
        for (String clientId : clientIds) {
            Client client = clientStore.findById(clientId)
                    .orElseThrow(() -> new IllegalStateException(
                            "client " + clientId + " not found"));
            if (client.getBackchannelLogoutUri() == null) continue;

            String logoutToken = buildLogoutToken(client, sub, sid);
            executor.submit(() -> postWithRetry(client, logoutToken));
        }
    }

    private String buildLogoutToken(Client client, String sub, String sid) {
        JwtBuilder builder = JWT.create()
            .issuer(opIssuer)
            .subject(sub)
            .audience(client.getClientId())
            .expiresAt(System.currentTimeMillis() / 1000 + tokenTtlSeconds)
            .jwtId(UUID.randomUUID().toString())
            .claim("events", Map.of(
                "http://schemas.openid.net/event/backchannel-logout", Map.of()));
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder.signWith(signKey).build();
    }

    private void postWithRetry(Client client, String logoutToken) {
        String uri = client.getBackchannelLogoutUri();
        for (int attempt = 1; attempt <= retryMax; attempt++) {
            try {
                int status = httpClient.post(uri, logoutToken);
                if (status >= 200 && status < 300) return;  // 成功（含 204）
                log.warn("BackChannel logout to {} returned {}", uri, status);
            } catch (Exception e) {
                log.warn("BackChannel logout to {} attempt {} failed", uri, attempt, e);
            }
            sleep(backoffMillis(attempt));  // 1s / 5s / 30s
        }
        // 最终失败 → 记录到 FailedLogoutStore（v2 持久化重试）
        failedLogoutStore.record(client.getClientId(), logoutToken);
    }
}
```

### 15.6 RP 侧验证流程

```
RP 端收到 POST { logout_token } 后的处理：

   1. 校验 logout_token
      ├─ 用 OP JWKS 公钥验签（v2 引入 RS256 后；v1 HS256 共享 key）
      ├─ iss == OP issuer
      ├─ aud == RP 自己的 client_id（**关键**：防 OP 误发）
      ├─ exp > now（短 TTL，过期直接拒绝）
      ├─ jti 未见过（防重放，jti 缓存 5min）
      └─ events 含 backchannel-logout
   2. 销毁本地 session
      ├─ 若 sid 存在 → 删 sid 对应的 session
      └─ 否则 → 删 sub 对应的 session
   3. 返回 200 OK
      └─ 即使 RP 内部 session 不存在也返 200，避免无限重试
```

### 15.7 Client 模型扩展

```java
public class Client {
    // ... 现有字段（client_id / client_secret / redirect_uris / grant_types / scopes）...

    /** Back-Channel Logout 通知 URI；null = 不通知 */
    private String backchannelLogoutUri;

    /** 是否要求 logout_token 含 sid；默认 true */
    private boolean backchannelLogoutSessionRequired = true;

    /** RP-Initiated Logout 允许的重定向 URI 白名单 */
    private Set<String> postLogoutRedirectUris = Collections.emptySet();
}
```

**注册校验**（`InMemoryClientStore.register` / DB 实现同样）：

- `backchannelLogoutUri` 非空时：
  - 必须 HTTPS（除 `http://localhost` / `http://127.0.0.1`）
  - 主机名不在 RFC 1918 / link-local / loopback 网段（防 SSRF）
  - 端口 ≤ 65535
- `postLogoutRedirectUris` 至少 1 个 URI（v1 强制；否则 RP-Initiated Logout 没法工作）

### 15.8 Session 与 sid 模型

**OP 侧 session 生命周期**：

```
用户登录成功
   │
   ▼
AuthorizationCodeHandler（或 PasswordHandler v2）：
   ├─ 生成 sid = UUID.randomUUID().toString()
   ├─ SessionStore.save(Session(sid, sub, now, expiresAt, clientId))
   └─ id_token payload 增加 "sid" claim
```

```java
public class Session {
    private String sid;             // session id (UUID)
    private String sub;             // user id
    private long createdAt;         // epoch seconds
    private long expiresAt;         // epoch seconds（默认 8h，可配）
    private String clientId;        // 触发登录的 client（用于审计，非业务关键）
}
```

**为什么需要 sid**：用户可能在多个 RP 同时登录（不同 client_id），但共享同一 OP session。logout_token 带 sid 让 RP 只销毁自己 session 中对应的部分，避免误删其他 RP 的 session。

### 15.9 TokenStore / SessionStore SPI 扩展

```java
public interface TokenStore {
    // ... 现有方法（saveAccessToken / findAccessToken / revokeAccessToken 等）...

    /** 撤销某 user 的所有 refresh_token（logout 用） */
    void revokeAllBySubject(String sub);

    /** 查所有给该 sub 发过 token 的 client_id（Back-Channel Logout 用） */
    List<String> findClientsBySubject(String sub);
}

public interface SessionStore {
    /** 新 SPI（§十五新增） */
    void save(Session session);
    Optional<Session> findBySid(String sid);
    Optional<Session> findBySubject(String sub);
    void deleteBySid(String sid);
    void deleteBySubject(String sub);
}
```

`InMemoryTokenStore` 实现要点：

```java
private final Map<String, Set<String>> subjectToClients = new ConcurrentHashMap<>();

@Override
public void saveAccessToken(AccessToken token) {
    // ... 现有逻辑 ...
    subjectToClients
        .computeIfAbsent(token.getSubject(), k -> ConcurrentHashMap.newKeySet())
        .add(token.getClientId());
}

@Override
public void revokeAllBySubject(String sub) {
    refreshTokens.entrySet().removeIf(e -> sub.equals(e.getValue().getSubject()));
    // access_token 不立即删（短期自然过期），仅 refresh_token 撤销后无法再换新
}

@Override
public List<String> findClientsBySubject(String sub) {
    Set<String> ids = subjectToClients.get(sub);
    return ids == null ? List.of() : List.copyOf(ids);
}
```

### 15.10 安全考虑

#### 15.10.1 post_logout_redirect_uri 严格白名单

- **强制白名单**：RP 注册时声明 `postLogoutRedirectUris`；OP 仅允许重定向到白名单内 URI
- **完全字符串匹配**：不允许通配符 / prefix（RFC 6749 §3.1.2.3 同等严格度）
- **缺白名单兜底**：RP 注册时 `postLogoutRedirectUris` 为空 → 禁用 RP-Initiated Logout（返 `unsupported_operation`）

#### 15.10.2 id_token_hint 校验

| 检查 | 失败处理 |
|---|---|
| 签名错误 | `invalid_token` |
| iss 不匹配 | `invalid_request` |
| aud 不等于当前 client_id | `invalid_request`（防 cross-RP 登出触发） |
| exp 过期 | **接受**（用户要登出，exp 过期无关） |
| 完全缺失 | `invalid_request` |

#### 15.10.3 logout_token 防重放

- **jti 唯一**：每次登出生成新 UUID
- **exp ≤ 60s**：重放窗口极短
- **RP jti 缓存**：RP 收到过的 jti 缓存 5 分钟 → 第二次同 jti 拒绝（`400 Bad Request`）

#### 15.10.4 Back-Channel 防 SSRF

- **注册时校验**：`backchannelLogoutUri` 必须 HTTPS（除 localhost/127.0.0.1），主机名不在私有网段
- **发送时超时**：connect 3s / read 5s（防慢速 RP 拖死 OP 线程池）
- **限流**：每 RP 通知上限 100 QPS（防失控 RP 拖死 OP）

#### 15.10.5 access_token 自然过期

- 用户登出 **不**主动失效 access_token（短期，5~15 分钟自然过期即可）
- 资源服务器应做 introspection 二次校验，或用 deny-list 拦截最后几分钟的 token
- v1 提供 `/oauth2/introspect` 端点；resource server 高安全场景调它

#### 15.10.6 日志脱敏

- logout_token 不写入日志
- 失败日志仅打印：目标 client_id + HTTP status + retry 次数

### 15.11 端点清单（更新）

| 端点 | 方法 | 说明 | v1 |
|---|---|---|---|
| `/oauth2/authorize` | GET / POST | 授权入口 | ✅ |
| `/oauth2/token` | POST | token 签发 | ✅ |
| `/oauth2/introspect` | POST | RFC 7662 introspection | ✅ |
| `/oauth2/revoke` | POST | RFC 7009 单 token 撤销 | ✅ |
| `/oauth2/jwks` | GET | 公钥发布 | ✅ |
| `/oauth2/userinfo` | GET / POST | OIDC userinfo | ✅ |
| `/oauth2/register` | POST | RFC 7591 动态注册 | ✅ |
| `/oauth2/logout` | **GET / POST** | **RP-Initiated Logout**（**本节新增**） | ✅ |
| `/.well-known/openid-configuration` | GET | OIDC discovery | ✅ |
| `/.well-known/oauth-authorization-server` | GET | RFC 8414 metadata | ✅ |
| RP `backchannel_logout_uri` | POST | OP 主动调用（**本节新增**） | ✅ |

### 15.12 容器配置

```properties
# application.properties
container.oauth2.enabled=true
container.oauth2.issuer=https://auth.edap.io
container.oauth2.signKey=...                       # 复用 ws.jwt.signKey（HS256）

# Logout 配置
container.oauth2.logout.backchannel.timeoutMs=5000
container.oauth2.logout.backchannel.retryMax=3
container.oauth2.logout.tokenTtlSeconds=60
container.oauth2.logout.sessionTtlSeconds=28800    # 8h
container.oauth2.logout.threadPoolSize=8           # 异步通知线程池

# Client 注册时声明：
client.backchannelLogoutUri=https://rp.example/backchannel-logout
client.backchannelLogoutSessionRequired=true
client.postLogoutRedirectUris=https://rp.example/logged-out,https://rp.example/home
```

### 15.13 API 演进

| 期 | 改动 |
|---|---|
| **v1** | RP-Initiated Logout 端点 + Back-Channel Logout + SessionStore SPI + LogoutNotifier + Client 模型扩展 |
| v2 | Front-Channel Logout（受第三方 cookie 限制，实用性弱） |
| v2 | LogoutNotifier 异步队列化（独立线程池 + 限流） |
| v2 | FailedLogoutStore 持久化重试（Redis Stream / DB 表） |
| v2 | RS256 切换后 logout_token 用 JWKS 公钥验签（v1 HS256 共享 key） |
| v2 | 批量登出（admin 强制登出某 user） |

---

## 十六、参考

- RFC 6749 — The OAuth 2.0 Authorization Framework
- RFC 6750 — OAuth 2.0 Bearer Token Usage
- RFC 7009 — OAuth 2.0 Token Revocation
- RFC 7662 — OAuth 2.0 Token Introspection
- RFC 7517 — JSON Web Key (JWK)
- RFC 7591 — OAuth 2.0 Dynamic Client Registration
- RFC 7636 — Proof Key for Code Exchange (PKCE)
- RFC 8414 — OAuth 2.0 Authorization Server Metadata
- OpenID Connect Core 1.0
- OpenID Connect RP-Initiated Logout 1.0
- OpenID Connect Back-Channel Logout 1.0
- OpenID Connect Front-Channel Logout 1.0
- OpenID Connect Session Management 1.0
- [`../edap-auth-jwt/doc/JWT_DESIGN.md`](../edap-auth-jwt/doc/JWT_DESIGN.md) JWT 工具模块（access_token / id_token / logout_token 签名复用）
- [`../../../doc/WS_HANDLER_DESIGN.md`](../../../doc/WS_HANDLER_DESIGN.md) §1.3 三层组件边界 / §4 路由策略
- [`../../../doc/CONTAINER_APPCONTEXT_DESIGN.md`](../../../doc/CONTAINER_APPCONTEXT_DESIGN.md) Container/AppContext Bean 容器