# Edap / Auth / JWT 详细设计

> 本文档定义 edap 框架内 JWT (JSON Web Token) 工具模块 `edap-auth-jwt` 与容器层 `JwtWSAuthenticator` 接入的完整接口、组件协作、算法扩展路径与失败语义。
>
> **目标读者**：在 `edap-auth-parent` / `edap-container-parent` / `edap-http-parent` 模块下做 JWT 接入、握手鉴权、应用 bean 覆盖策略的开发者。
>
> **前置文档**：
> - [`WS_HANDLER_DESIGN.md`](../../../doc/WS_HANDLER_DESIGN.md) §1.3 三层组件边界中握手鉴权层（`WSAuthenticator` 接口）的定位
> - [`CONTAINER_APPCONTEXT_DESIGN.md`](../../../doc/CONTAINER_APPCONTEXT_DESIGN.md) Container/AppContext 双层 Bean 容器与 byType fallback 机制

---

## 一、目标与范围

### 1.1 设计目标

1. **JWT 工具模块自洽**：单文件 `JWT.create()` / `JWT.verify(token, key)` 即可完成签发与验签，不强制引入第三方库
2. **验签失败语义清晰**：区分"格式错 / 签名错 / claim 不满足"三类，调用方据此决定 HTTP 状态码（400 / 401 / 401）
3. **算法可扩展**：当前 HS256 内置；未来 RS256 / ES256 / EdDSA 通过 `Algorithm` SPI 注册，不破坏 API
4. **容器层默认 bean 真实可用**：`JwtWSAuthenticator`（容器默认 bean）执行实际 JWT 验签 + exp/nbf 校验；`HeaderTokenAuthenticator`（保留为本地开发兜底）标 `@Deprecated`
5. **应用零侵入**：应用通过 byType 注册自己的 `WSAuthenticator` bean 自动覆盖默认实现

### 1.2 不在本文档范围内

- OAuth 2.0 授权流程、refresh token 轮换 —— 由应用层负责
- JWKS 公钥自动拉取 / key rotation —— 第二期
- JWE（加密 JWT） —— 当前只支持 JWS 签名
- 撤销列表（revocation list） / 黑名单 —— 应用层 cache（如 Redis）
- 跨服务 token 透传 / audience 严格校验 —— 本文档定义 API，应用按需启用

### 1.3 组件边界

| 层级 | 类 / 接口 | 角色 | 模块 |
|---|---|---|---|
| 协议传输层 | `io.edap.http.ws.WSAuthenticator` | 鉴权接口（接受 `HttpRequest`，返回 `AuthResult`） | edap-http-core |
| 容器能力层 | `io.edap.container.ws.JwtWSAuthenticator` | 容器默认 bean：JWT 验签 + exp/nbf 校验 | edap-container |
| 容器兜底层 | `io.edap.http.ws.HeaderTokenAuthenticator` | 本地开发兜底（@Deprecated，无验签） | edap-http-core |
| JWT 工具层 | `io.edap.auth.jwt.JWT` | 静态 facade：`create()` / `verify(token, key)` | edap-auth-jwt |
| JWT 工具层 | `io.edap.auth.jwt.JwtBuilder` | 链式 builder | edap-auth-jwt |
| JWT 工具层 | `io.edap.auth.jwt.VerifyResult` | 验证结果 POJO | edap-auth-jwt |
| 算法 SPI | `io.edap.auth.jwt.Algorithm` | 算法接口（HMAC / RSA / ECDSA 统一） | edap-auth-jwt |

```
WS 客户端
   │  Upgrade: websocket + Authorization: Bearer <jwt>
   ▼
HttpServerNioSession.handshake
   │ 1. WSAuthenticator.verify(request)
   │    ├─ 提取 token: Header("Authorization: Bearer ...") → ?token=
   │    ├─ JWT.verify(token, signKey)
   │    │    ├─ format check → 401 + "format error"
   │    │    ├─ signature check → 401 + "signature error"
   │    │    └─ claim check (exp/nbf) → 401 + "expired" / "not yet valid"
   │    └─ success → Principal(userId, jti) 写 sessionContext
   ▼
101 Switching Protocols → onOpen
```

---

## 二、模块结构与依赖方向

### 2.1 当前模块拓扑

```
edap-auth-jwt-api                      (接口 + DTO，零依赖)
   └─ (无下行依赖)

edap-auth-jwt                          (默认实现)
   ├─→ edap-auth-jwt-api
   ├─→ edap-common
   └─→ edap-json

edap-native                             (跨模块 JNI 加速库，OpenSSL 后端)
   ├─→ edap-common
   └─→ edap-log-api
   (edap-auth-jwt 不硬依赖此模块；通过 MethodHandle 加载)

edap-container
   ├─→ edap-http-server ──→ edap-http-core
   ├─→ edap-erpc-api
   ├─→ edap-grpc-api
   ├─→ edap-log
   ├─→ edap-microservice-annotation
   ├─→ edap-nio-server
   └─→ edap-auth-jwt-api               (新增：仅引入 JwtService 接口，按需引入 edap-auth-jwt)

edap-http-core
   ├─→ edap-common
   ├─→ edap-nio-server
   ├─→ edap-json
   └─→ edap-protobuf
```

### 2.2 调整后依赖

```
edap-auth-jwt-api                      (新模块：纯接口 / DTO，无下行依赖)
edap-auth-jwt ──→ edap-auth-jwt-api    (实现依赖接口)
edap-container ──→ edap-auth-jwt-api   (按需：仅引接口；如需用默认实现再引 edap-auth-jwt)
edap-http-core                         (无变化：只暴露 WSAuthenticator 接口)
```

### 2.2.1 edap-auth-jwt-api 拆分动机

| 关注点 | 未拆分（只有 edap-auth-jwt） | 拆分后 |
|---|---|---|
| 业务方依赖 | 必引 edap-auth-jwt（含 HS256 / AlgorithmRegistry 等实现） | 只引 edap-auth-jwt-api（仅接口 / DTO） |
| 容器默认实现 | 业务方必须接受 HS256 默认实现 | 业务方可只引接口，写自己的实现 bean 覆盖 |
| 二方包大小 | 拖入 edap-json / edap-common 等传递依赖 | api 模块零依赖 |
| 演进 | 改 HS256 实现即破坏 API 兼容 | 接口稳定，实现模块可自由重构 |

### 2.3 设计原则

1. **edap-auth-jwt 是叶子模块**：不依赖 edap-container / edap-http-core，纯 JWT 算法工具。可被任何上层（HTTP / gRPC / 业务服务）单独使用
2. **edap-container 是"用法"而非"成员"**：参考 `feedback_edap_module_dependency`，JwtWSAuthenticator 作为容器能力放在这里，自然依赖 edap-auth-jwt
3. **WSAuthenticator 接口留在 edap-http-core**：HTTP/1.1 协议栈定义 handshake 钩子；具体鉴权实现由容器层注入
4. **HeaderTokenAuthenticator 不移动**：保留在 edap-http-core 作历史兼容，但 `@Deprecated` 引导到 JwtWSAuthenticator

---

## 三、Token 构建 API

### 3.1 现有 API（保留）

```java
String jwt = JWT.create()
    .subject("user-123")
    .issuer("auth.edap.io")
    .audience("order-service")
    .expiresAt(System.currentTimeMillis() + 3_600_000)
    .notBefore(System.currentTimeMillis())
    .issuedAt(System.currentTimeMillis())
    .jwtId(UUID.randomUUID().toString())
    .claim("role", "admin")
    .claim("tenant", "acme")
    .signWith("shared-secret-key")
    .build();
```

### 3.2 设计约束

1. **链式调用**：每个 setter 返回 `JwtBuilder`（不变性），允许 `JWT.create().subject(...).expiresAt(...).signWith(...).build()` 单语句串完
2. **claim 覆盖**：`claim(name, value)` 同名后写覆盖前写；最终 `serialize` 时按 key 序写出（避免 payload 顺序影响签名）
3. **signWith 强制**：未调用 `signWith` 调 `build()` 抛 `IllegalStateException`（无密钥不签）
4. **header 默认值**：`{"alg":"HS256","typ":"JWT"}` 编码为常量 `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9`（`JwtBuilder.DEFAULT_HEADER`），复用缓存避免重复编码

### 3.3 已知问题与修复

| 位置 | 问题 | 修复 |
|---|---|---|
| `JwtBuilder.java:127` | `Eson.serialize(payload, jsonWriter)` 未做 key 排序，相同内容 payload 序列化结果不一致 → 签名前编码不可重现 | 在 `serialize` 前 `TreeMap` 包装 payload |
| `JwtBuilder.java:142-144` | 末尾 `new String + System.arraycopy` 多余拷贝 | 直接返回 `byteBuilder.fastInstance()`（参考 `StringUtil.fastInstance`） |

---

## 四、Token 验证 API

### 4.1 现有 API

```java
VerifyResult result = JWT.verify(token, signKey);
if (result.getCode() != 0) {
    // result.getMessage() 区分 format / signature
}
JwtPayload payload = result.getPayload();
```

### 4.2 失败码语义（重新定义）

| code | 含义 | HTTP 映射 | 备注 |
|---|---|---|---|
| 0 | success | 200 | payload 已填充 |
| 1 | format error（token 非 `header.payload.signature` 三段、base64url 解码失败） | 400 | 客户端发的不是合法 JWT |
| 2 | signature error（签名不匹配 / 算法不支持 / key 错） | 401 | 鉴权失败 |
| 3 | expired（exp < now） | 401 | 仅在 `verify(..., withClaimCheck=true)` 时返回 |
| 4 | not yet valid（nbf > now） | 401 | 同上 |
| 5 | audience mismatch（aud 不在 expected list） | 401 | 仅在指定 expectedAudience 时返回 |

> **向后兼容**：现有 `code=0/1/2` 不变；新增 3/4/5 由调用方开关控制，不强制

### 4.3 失败码常量

```java
public final class VerifyCode {
    public static final int SUCCESS         = 0;
    public static final int FORMAT_ERROR    = 1;
    public static final int SIGNATURE_ERROR = 2;
    public static final int EXPIRED         = 3;
    public static final int NOT_YET_VALID   = 4;
    public static final int AUDIENCE_MISMATCH = 5;
}
```

### 4.4 验证分层

```java
// 第一期：仅签名验证（向后兼容）
public static VerifyResult verify(String token, String signKey);

// 第二期：签名 + 时间 claim 验证
public static VerifyResult verify(String token, String signKey, VerifyOptions opts);
```

```java
public final class VerifyOptions {
    /** 是否检查 exp；true 时返回 code=3 表示过期 */
    private boolean checkExpiresAt;
    /** 是否检查 nbf；true 时返回 code=4 表示未到生效时间 */
    private boolean checkNotBefore;
    /** 期望的 audience 列表；非空时 aud 不在列表中 → code=5 */
    private List<String> expectedAudiences;
    /** 时钟偏移容忍（秒）；默认 0；正值放宽 exp/nbf 比较 */
    private long clockSkewSeconds;
    // builder...
}
```

**为什么分层**：当前 `JWT.verify` 只验签名，把 exp/nbf 检查留给调用方（容易漏）；新 API 把可选 claim 检查封进 options，调用方显式开关

### 4.5 JwtService 服务门面（首选调用入口）

```java
public interface JwtService {
    VerifyResult verify(String token);
    JwtBuilder builder();   // 预绑定 signKey
}
```

**位置**：`io.edap.auth.jwt` 包，定义在 `edap-auth-jwt-api` 模块

**调用方代码**：

```java
@Inject JwtService jwt;

String token = jwt.builder().subject("u-1").issuer("edap.io").build();

VerifyResult r = jwt.verify(token);
if (r.getCode() == 0) { ... }
```

**设计原则**：
- 签发 / 验签 用同一个 bean（同一 signKey 上下文）
- `builder()` 返回的 `JwtBuilder` 已预绑定 signKey —— 调用方一般无需再调 `.signWith(...)`
- kid、多密钥、轮转等都在实现内部处理，**不暴露**给调用方
- 特殊需求（HSM / KMS / 自定义算法）由应用自行实现 `JwtService` 并以 bean 形式覆盖容器默认实现

**默认实现**：`io.edap.auth.jwt.DefaultJwtService`（在 `edap-auth-jwt` 模块），单 signKey 构造；由 edap-container 在 `Container.containerBeans` 注册为默认 bean，应用可注册自己的 `JwtService` 自动覆盖。

### 4.6 已知 bug 修复

| 位置 | bug | 修复 |
|---|---|---|
| `JWT.java:58` | `String headerStr = Base64URL.decode(header);` 解码结果未赋值，死代码；非默认 header 时 `result.getHeader()` 为 null | 解析后赋给 `result.setHeader(...)`；失败时 code=1 |
| `JWT.java:84-133` | 单 `if ("HS256".equals(algorithm))` 硬编码，无法扩展 RS256 | 改为 `AlgorithmRegistry.get(algorithm).verify(...)` 派发 |
| `JWT.java:91` | 验签后**未做** `Mac` 实例复用检查，每次新 key 都 `ALGORITHM_CACHE.put`（无界 HashMap，内存泄漏） | 引入 `KeyCache`（LRU + 最大容量），超过容量按 LRU 淘汰 |
| `HmacSha256.sign()` | `javax.crypto.Mac` 非线程安全；`ALGORITHM_CACHE` 同 key 跨线程共享同一 `Mac` 实例 → `reset/update/doFinal` 内部 buffer 竞态、概率性签名错、潜在 AIOOBE | 改为 `ThreadLocal<Mac>`：每个线程持有独立 `Mac` 实例；keyBytes 缓存到 `HmacSha256` 实例字段；详见 §5.4 |
| `JwtBuilder` 实现在 edap-auth-jwt，业务方被迫引整个实现 jar | 业务方实际只需要 `JwtBuilder` 接口 + 自己的实现 | 拆出 `edap-auth-jwt-api` 模块：仅含 `JwtService` / `JwtBuilder` / `VerifyResult` / `Header` / `JwtHeader` / `JwtPayload`，零依赖；实现在 `edap-auth-jwt` 模块 |

---

## 五、Algorithm 抽象

### 5.1 当前接口

```java
public interface Algorithm {
    byte[] sign(byte[] data, int offset, int len);
}
```

### 5.2 增强后接口

```java
public interface Algorithm {
    /** 算法名（如 "HS256" "RS256" "ES256"） */
    String name();

    /** 签名：返回 base64url-encoded signature bytes */
    byte[] sign(byte[] data, int offset, int len);
}
```

> **不暴露 verify 接口**：验签由 `Algorithm` 实现内部完成（持有密钥），对外仅暴露 `sign` 防止密钥被外部代码拿到后误用

### 5.3 算法注册

```java
public final class AlgorithmRegistry {
    private static final Map<String, Algorithm> ALGORITHMS = new HashMap<>();

    static {
        // 内置：仅 HS256；其他算法应用自行注册
        register("HS256", key -> HmacSha256.create(key));
    }

    /** 注册算法工厂（lambda 形式避免启动期实例化） */
    public static void register(String name, Function<String, Algorithm> factory);

    /** 获取算法实例；null 表示不支持 */
    public static Algorithm get(String name, String key);
}
```

### 5.4 缓存策略 + 线程安全

#### 5.4.1 KeyCache（跨 key 实例池）

```java
public final class KeyCache {
    /** LRU cache：key → Algorithm 实例 */
    private static final int MAX_KEYS = 64;       // 默认 64 个 key 上限
    private final LinkedHashMap<String, Algorithm> cache;

    public KeyCache(int maxSize) {
        this.cache = new LinkedHashMap<String, Algorithm>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Algorithm> eldest) {
                return size() > maxSize;
            }
        };
    }

    public Algorithm getOrCreate(String alg, String key, Function<String, Algorithm> factory);
}
```

> **为什么是 LRU**：JWT verify 是热路径，每个 HS256 实例持有 `Mac` + `SecretKeySpec`（约 200B）。64 个 key 上限 → 最多 ~12KB，可控；超过 LRU 淘汰最久未用

#### 5.4.2 单实例线程安全（ThreadLocal<Mac>）

`javax.crypto.Mac` 非线程安全（`reset/update/doFinal` 改内部 buffer）。`KeyCache` 同 key 的 `Algorithm` 实例跨线程共享 → 单纯锁粒会成热路径瓶颈。

**采用 ThreadLocal 方案**：

```java
public class HmacSha256 implements Algorithm {

    private final byte[] keyBytes;   // 构造期定下，不可变 → 安全发布到 ThreadLocal lambda

    private final ThreadLocal<Mac> macHolder = ThreadLocal.withInitial(() -> {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });

    public HmacSha256(String key) {
        this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] sign(byte[] data, int offset, int len) {
        Mac mac = macHolder.get();     // 每个线程首次访问时 lazy init
        mac.reset();
        mac.update(data, offset, len);
        return mac.doFinal();
    }
}
```

**关键设计点**：

| 点 | 理由 |
|---|---|
| `keyBytes` 不可变 + final | ThreadLocal lambda 闭包安全捕获；HmacSha256 实例本身可跨线程安全发布 |
| ThreadLocal 而非 synchronized | 无锁，热路径 QPS 不受锁争用影响；不同线程 `Mac` 实例完全隔离 |
| lazy init in `withInitial` | 首次 `sign()` 才分配 `Mac`；构造期不付出实例化代价 |
| 不调用 `remove()` | Web 容器线程复用，Mac 实例跟着线程生命周期走；显式 remove 仅在大量短线程场景才有意义 |

**线程安全证明**：

- `HmacSha256` 字段全部 final → 跨线程发布安全（JMM final 语义）
- `Mac` 实例在 `ThreadLocal` 内 → 仅创建它的线程能访问 → 满足 JCE 线程安全约束
- `sign()` 读 `macHolder.get()`（线程本地，无竞争）→ 调 `reset/update/doFinal`（线程独占）→ 无共享可变状态

**额外开销**：

| 操作 | 单次成本 |
|---|---|
| `ThreadLocal.get()` | ~20ns（hash lookup） |
| 首线程首次 `Mac.getInstance` + `init` | ~5μs（一次，后续线程各付一次） |
| 同线程后续 verify | 与原版持平（reset + update + doFinal） |

#### 5.4.3 其他算法的线程安全要求

| 算法 | 内部状态 | 线程安全策略 |
|---|---|---|
| HS256 | `javax.crypto.Mac` | ThreadLocal ✅ |
| RS256 | `java.security.Signature`（非线程安全） | ThreadLocal 同上 |
| ES256 | `Signature`（非线程安全） | ThreadLocal 同上 |
| EdDSA | `Signature`（非线程安全） | ThreadLocal 同上 |

> **共同模式**：所有算法实现都应"缓存不可变密钥字节 + ThreadLocal 持有 JCE 计算对象"。定义抽象基类 `AbstractThreadLocalAlgorithm` 复用此模式（v2 提取）

### 5.5 未来算法路径

| 算法 | 密钥形态 | 状态 |
|---|---|---|
| HS256 | 共享对称密钥（String） | ✅ 第一期实现 |
| RS256 | RSA 私钥（PKCS#8 PEM） | 第二期；`Algorithm` 工厂接收 PEM string |
| ES256 | ECDSA 私钥（P-256） | 第二期 |
| EdDSA | Ed25519 私钥 | 第三期 |

### 5.6 JNI 加速路径（已落地：edap-native）

> **关联文档**：`edap-native/doc/NATIVE_DESIGN.md` —— edap-native 模块架构、加载机制、JNI 主路径优化 backlog（Tier 1/2/3）、perf 实施指引。

**动机**：JCE 在高并发 JWT verify 场景下仍有 ~5μs/verify 的开销（其中 ~1μs 来自 JNI 边界、~1μs 来自 `Mac.update` 拷贝、~2μs 来自 SHA-256 内部循环）。edap 框架内部有大量 JWT verify（WS 握手 + HTTP 鉴权 + OAuth2 token 验证），单节点 QPS 峰值可达 50w+。**JNI 直调 OpenSSL/boringssl** 在 1-4 线程典型场景下实测 2-4.5x 提升（16+ 线程反退，详见下方收益估算）。

**当前架构**（第一期已落地）：

```
edap-native                          (跨模块 JNI 加速库，OpenSSL libcrypto 后端)
└─ io.edap.jni
   ├─ Native.java                    (加载入口：解析 os.arch → System.load .o)
   └─ crypto
      └─ NativeHmacSha256.java       (一次性 HMAC，无 thread-local 状态)

edap-auth-jwt
└─ io.edap.auth.jwt.algorithm
   ├─ HmacSha256.java                (JCE 实现，默认)
   └─ HmacSha256Native.java          (MethodHandle 加载 NativeHmacSha256，无编译期依赖)
```

**触发方式**：

```bash
# 默认行为：edap-native 在 classpath + 平台 .o 就绪就直接用 native
java ...  # 无需任何系统属性

# 显式禁用（强制走 Java / JCE）：
java -Dedap.jwt.hmac.native=false ...
```

**检测逻辑**（`HmacSha256Native.isAvailable()`）：

```java
1. -Dedap.jwt.hmac.native=false|disable|off  → 显式禁用，false
2. Class.forName("io.edap.jni.crypto.NativeHmacSha256") 触发其 static init
   → 内部调用 Native.loadLibrary()（幂等，双检锁）→ 加载成功后 ENABLE_NATIVE 翻成 true
3. Native.ENABLE_NATIVE == true ?  (即当前平台有预编译 .o 且 .o 加载成功)
   → 1 通过 + 2/3 全 true 才注册 native 工厂；任一失败静默 fallback 到 HmacSha256

⚠️ 顺序关键：必须先 Class.forName(NATIVE_CLASS) 触发其 static init，
否则直接读 ENABLE_NATIVE 会拿到初始 false（未 loadLibrary），永远走 Java 路径。
（旧实现 `isAvailable()` 先读字段再触发类初始化，导致 native 永不生效 —— 上述代码已修正）
```

**AlgorithmRegistry 派发**：

```java
static {
    Function<String, Algorithm> hs256Factory =
            HmacSha256Native.isAvailable() ? HmacSha256Native::new : HmacSha256::new;
    register("HS256", hs256Factory);
}
```

**为什么默认开启**：

- edap-native 加载是幂等 + 静默 fallback（加载失败不抛）→ 不会因为 native 集成失败导致服务起不来
- 性能收益是 deterministic（实测 1-4 线程 2-4.5x；16t 反退，按业务场景评估），没有理由不默认用
- 用户想关闭只需 `-Dedap.jwt.hmac.native=false`，比"想启用还要配 flag"少一步
- 生产环境 100% 行为可预测：native 加载成功 → 走 native；失败 → 走 Java（启动日志会记录）

**实现要点**：

| 点 | 设计 |
|---|---|
| Native lib 加载 | `Native.loadLibrary()`：运行时按 `os.arch` 从 `src/main/resources/edap-native-{os}_{arch}.o` 解析，写到 temp 后 `System.load` |
| MethodHandle vs 编译依赖 | HmacSha256Native 用 MethodHandle（findConstructor + findVirtual + bindTo + invokeExact）调 NativeHmacSha256 —— edap-auth-jwt 不硬依赖 edap-native；用户引入 edap-native 即自动启用 native |
| 默认行为 | **默认启用** —— edap-native 在 classpath + ENABLE_NATIVE=true 时直接走 native；显式 `-Dedap.jwt.hmac.native=false` 强制 Java |
| Fallback | `isAvailable()` 任一检查不过 → 静默 fallback 到 JCE；启动期日志由 `Native` 记录 `native crypto enabled: true/false` |
| 线程安全 | NativeHmacSha256 key 不可变 + sign0 一次性传 key+data，per-call stateless；多线程并发安全（已 16 线程 × 5000 次回归） |
| 灰度 | `-Dedap.jwt.hmac.native=false`；按 host 灰度，无需改代码 |

**支持的平台**（第一期）：

| os | arch | .o 文件 |
|---|---|---|
| macOS | aarch64 | `edap-native-macos_aarch64.o` ✅ |
| macOS | x86_64 | 待编译 |
| Linux | x86_64 | 待编译 |
| Linux | aarch64 | 待编译 |

不在以上平台 → `Native.ENABLE_NATIVE=false` → 自动 fallback JCE。

**收益估算**（实测，macOS aarch64 + JDK 17 + JMH 1.37，`HmacSha256NativeBenchmark`，`avgt` us/op，**Tier 2 落地后**）：

| 场景 | payload | 线程 | javaMac（Mac+ThreadLocal） | nativeHmac（手动 HMAC-SHA256，无 provider dispatch） | 倍数 |
|---|---|---|---|---|---|
| 单线程 verify | 100B | 1 | 1.503 | 0.493 | **3.05x** |
| 单线程 verify | 500B | 1 | 3.216 | 0.653 | **4.92x** |
| 单线程 verify | 2000B | 1 | 10.074 | 1.289 | **7.81x** |
| 4 线程 verify | 100B | 4 | 1.678 | 0.511 | **3.28x** |
| 4 线程 verify | 500B | 4 | 3.451 | 0.669 | **5.16x** |
| 4 线程 verify | 2000B | 4 | 10.482 | 1.325 | **7.91x** |
| 16 线程 verify | 100B | 16 | 3.266 | 0.980 | **3.33x** |
| 16 线程 verify | 500B | 16 | 6.758 | 1.283 | **5.27x** |
| 16 线程 verify | 2000B | 16 | 20.681 | 2.511 | **8.24x** |

**结论**：

- **1-4 线程（典型 JWT 业务 request handler）**：native 胜，2-8x 加速。payload 越大，native 优势越明显（HMAC 计算占比拉高，MethodHandle/JNI 边界开销摊薄）。
- **16+ 线程（高并发 verify 池）**：native 8x 胜。Tier 2 落地后 16t 反退问题已修复（见下文"16t 反退修复"），native 不再卡在固定开销，随线程扩展接近线性（1t→4t 仅 1.03x 退化、4t→16t 仅 1.89x 退化）。
- **MethodHandle vs 反射**：实测两者在 ±3% 误差棒内打平。JDK 17 JIT 已把 `Method.invoke` 内联到接近 `invokeExact` 性能。保留 MethodHandle 不是为了 perf，是为了代码质量（无 checked exception 拆包、签名直接、未来可挂 `asType`/`filterArguments`）。

**生产建议**：

- 默认保留 native（全场景 3-8x 更快，包括高并发 16t）
- `-Dedap.jwt.hmac.native=false` 保留为 JCE fallback（AArch64 Linux 等无预编译 .o 的平台）

#### 16t 反退修复（Tier 2 落地说明）

**问题**：Tier 1（GetPrimitiveArrayCritical）+ Tier 1.5（cache `EVP_sha256()` + 每线程 `HMAC_CTX`）落地后，16t 仍 ~12.4 μs/op 反而比 Java 慢 0.6x。

**profile 实证**（macOS `sample` 抓 16t 测量阶段 30s）：

| 帧 | 占比/线程 |
|---|---|
| `Java_..._sign0`（JNI 入口） | 39.5% |
| `HMAC_Init_ex` | **17.5%** |
| ↳ `evp_md_init_internal`（provider dispatch） | 16.0% |
| ↳ ↳ `inner_evp_generic_fetch`（算法注册表 fetch） | 15.1% |
| ↳ ↳ ↳ `ossl_method_store_cache_get` | 12.1% |
| ↳ ↳ ↳ ↳ `pthread_rwlock_rdlock` | 4.1% |
| ↳ ↳ ↳ ↳ `pthread_rwlock_unlock` | 7.9% |
| GC 线程 | 0.01%（不是 GC 问题） |

**根因**：`HMAC_Init_ex` 内部调 `evp_md_init_internal(md)`，OpenSSL 3.x 默认 provider 的 SHA-256 实现 `md->prov != NULL && md->prov->dbs != NULL`（dispatch table 存在），所以即便传 cached md 也会走 provider dispatch —— Tier 1.5 的 `EVP_sha256()` cache 完全没用。16 线程并发 `pthread_rwlock_rdlock` + atomic ref-count（`evp_md_up_ref`）共享同一 cache line，进一步放大开销。

**修复**（已合并到 `edap-native/src/main/c/io_edap_jni_crypto_NativeHmacSha256.c`）：

- 删除所有 OpenSSL HMAC API 调用，改用 legacy `SHA256_Init/Update/Final`（OpenSSL 1.1 风格，deprecated in 3.0 但仍是直接 C 函数，无 provider dispatch、无锁、无 atomic、无 hash table）
- 手工实现 `HMAC(K, m) = H((K xor opad) || H((K xor ipad) || m))`
- 删除 Tier 1.5 的进程级 `EVP_sha256()` cache 和 `pthread_key_t` `HMAC_CTX` 缓存（不再需要）
- key 早期 release（已 memcpy 到栈 `k_pad[64]`），data critical 区间只覆盖 HMAC compute

**profile 验证**：修复后栈帧从 `HMAC_Init_ex → evp_md_init_internal → pthread_rwlock_*` 变成 `SHA256_Update → sha256_block_armv8`（ARM64 SHA-256 硬件指令）。provider dispatch 帧完全消失。

**JMH 实测收益**（2000B payload）：

| | 1t | 4t | 16t |
|---|---|---|---|
| Tier 1.5 | 2.19 μs | 3.56 μs | **12.74 μs** |
| **Tier 2** | **1.29 μs** | **1.33 μs** | **2.51 μs** |
| 相对 Tier 1.5 | 1.70x | 2.69x | **5.07x** |

16t 从反退 Java 0.6x 变成 8.24x 超越 Java，扩展性恢复近线性。

**正确性**：与 `javax.crypto.Mac` 字节级一致（含 key>64 / 空 key 边界 / partial offset/len）。回归测试见 `edap-auth-jwt/src/test/java/io/edap/auth/jwt/test/HmacSha256NativeTest.java`。

详细设计（含代码片段）：`edap-native/doc/NATIVE_DESIGN.md §6.6`。

---

**测试覆盖**（`HmacSha256NativeTest`）：

- 与 `javax.crypto.Mac` 字节级一致
- partial data sign（offset + len）
- 不同 key 签名不同
- 16 线程 × 5000 次并发无错误
- null key 抛 IllegalArgumentException
- AlgorithmRegistry 工厂派发签名与 Java 一致

**性能压测**（`HmacSha256NativeBenchmark` — JMH）：

```bash
mvn -pl edap-auth-jwt -am test-compile

# 注意：edap-native 在 edap-auth-jwt 里是 test scope dep，
# mvn dependency:build-classpath 默认只列 runtime，必须加 -DincludeScope=test
# 否则 ClassNotFoundException → nativeHmac_* 不会被 include（仅 javaMac_* 跑）

# 默认：native 启用（如果 edap-native 在 classpath + 平台 .o 就绪）→ 两组都跑
java -cp edap-auth-jwt/target/test-classes:edap-auth-jwt/target/classes:\
$(mvn -pl edap-auth-jwt dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout) \
     io.edap.auth.jwt.benchmark.HmacSha256NativeBenchmark

# 强制只跑 Java 对照组：
java -Dedap.jwt.hmac.native=false -cp ... \
     io.edap.auth.jwt.benchmark.HmacSha256NativeBenchmark
```

- 对比 `javaMac`（ThreadLocal<Mac> 生产同款模式）vs `nativeHmac`（OpenSSL JNI + MethodHandle）
- payload 100 / 500 / 2000 字节 × 线程 1 / 4 / 16，覆盖典型 JWT 体积与并发组合
- native 不可用时通过 `OptionsBuilder.include(".*javaMac.*")` 仅 include Java benchmark；native 可用时 include `javaMac.*` + `nativeHmac.*`
- 默认配置：3 warmup × 5 measure × 2 fork，2 秒/iter；如需正式结果用 `forks(3)` `measurementIterations(10)`

**实测基线**（2026-08，macOS aarch64 + JDK 17 + JMH 1.37）：

- 完整 18 行见上方「收益估算」表
- 启动期 `main()` 自动检测 `isAvailable()`：可用 → 打 `Native: ENABLED (default)`；不可用 → 打 `Native: DISABLED (will skip native bench)` 并提示 `-DincludeScope=test` 漏配 / 平台无 .o
- 关键 takeaway：1-4 线程业务场景 native 2-4x 加速；16t 反退（OpenSSL 内部锁 / JNI 跨界瓶颈）

**不替代 JCE 的理由**：

- 维护成本：JNI 跨平台编译 + 签名分发复杂度高
- 安全审计：每次 OpenSSL CVE 都需重新打包 native lib
- 仅对**热路径算法**（HS256/RS256）做 native 实现；冷门算法继续走 JCE

**与 ThreadLocal 关系**：

- **JCE ThreadLocal 是当前默认解**（零依赖，纯 JDK）
- **JNI 是性能可选解**（默认开启，需 -Dedap.jwt.hmac.native=false 关闭 + edap-native 在 classpath）
- 两者互斥：AlgorithmRegistry 二选一；用户按需切换

---

## 六、Header 解析

### 6.1 当前实现

```java
// JWT.java:54-59
String header = token.substring(0, index);
if (DEFAULT_HEADER_STR.equals(header)) {
    result.setHeader(DEFAULT_HEADER);
} else {
    String headerStr = Base64URL.decode(header);  // ← 死代码
}
```

### 6.2 修复方案

```java
String headerB64 = token.substring(0, index);
String headerJson = Base64URL.decode(headerB64);
if (headerJson == null) {
    result.setCode(FORMAT_ERROR);
    result.setMessage("header base64url decode failed");
    return result;
}
JsonObject headerObj = Eson.parseJsonObject(headerJson);
JwtHeader hdr = new JwtHeader();
hdr.setAlgorithm(headerObj.getString("alg"));
hdr.setType(headerObj.getString("typ"));
hdr.setKeyId(headerObj.getString("kid"));
result.setHeader(hdr);
```

### 6.3 kid 支持

第一期仅做 header 解析（不用于 key 查找）；第二期引入 JWKS 时，`kid` 字段用于查公钥

---

## 七、Claims 验证

### 7.1 时间字段

| 字段 | RFC 7519 含义 | 默认处理 |
|---|---|---|
| `exp` | 过期时间（Unix epoch 秒） | 不检查；`VerifyOptions.checkExpiresAt=true` 时启用 |
| `nbf` | 生效时间 | 不检查；同上 |
| `iat` | 签发时间 | 仅解析填入 payload，不检查（用于审计） |

### 7.2 受众字段

```java
VerifyOptions opts = VerifyOptions.builder()
    .expectedAudience("order-service")  // 单值
    .build();
// 或 .expectedAudiences(List.of("order-service", "payment-service")) 多值
```

- `aud` 可以是 String 或 String[]；VerifyOptions 按"任一匹配即通过"处理
- 不指定 expectedAudiences → 不检查 aud（向后兼容）

### 7.3 时钟偏移容忍

```java
VerifyOptions.builder().clockSkewSeconds(30)  // 容忍 30s 误差
```

- `exp + skew >= now` 才算过期
- `nbf - skew <= now` 才算生效
- 默认 0（严格）

---

## 八、JwtWSAuthenticator 容器层接入

### 8.1 类位置

`io.edap.container.ws.JwtWSAuthenticator`（和 `ServiceWSHandler` / `WSServiceMsgHandler` 同包）

### 8.2 实现

```java
package io.edap.container.ws;

public class JwtWSAuthenticator implements WSAuthenticator {

    private final String signKey;

    public JwtWSAuthenticator(String signKey) {
        if (signKey == null || signKey.isEmpty()) {
            throw new IllegalArgumentException("signKey required");
        }
        this.signKey = signKey;
    }

    @Override
    public AuthResult verify(HttpRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return AuthResult.fail(401, "missing token");
        }

        VerifyResult vr = JWT.verify(token, signKey, VerifyOptions.builder()
                .checkExpiresAt(true)
                .checkNotBefore(true)
                .build());

        return switch (vr.getCode()) {
            case VerifyCode.SUCCESS -> {
                JwtPayload pl = vr.getPayload();
                String userId = pl.getSubject() != null ? pl.getSubject() : pl.getJwtId();
                yield AuthResult.success(new Principal(userId, pl.getJwtId()));
            }
            case VerifyCode.FORMAT_ERROR    -> AuthResult.fail(401, "invalid token format");
            case VerifyCode.SIGNATURE_ERROR -> AuthResult.fail(401, "invalid signature");
            case VerifyCode.EXPIRED         -> AuthResult.fail(401, "token expired");
            case VerifyCode.NOT_YET_VALID   -> AuthResult.fail(401, "token not yet valid");
            default -> AuthResult.fail(401, "verify failed: " + vr.getMessage());
        };
    }

    private String extractToken(HttpRequest request) {
        // Authorization: Bearer <token>
        HeaderValue hv = request.getHeaderValue("Authorization");
        if (hv != null) {
            String v = hv.getValue();
            if (v != null && v.startsWith("Bearer ")) {
                return v.substring(7);
            }
        }
        // 浏览器 fallback（WebSocket API 限制无法发 header）
        return request.getParameter("token");
    }
}
```

### 8.3 容器默认 bean 注册

```java
// Container.initContainerBeans() 中替换原 HeaderTokenAuthenticator 注册
private void initContainerBeans() {
    // ... 已有 BeanContainer 初始化 ...

    // 配置开关：有 ws.jwt.signKey → JwtWSAuthenticator；否则 HeaderTokenAuthenticator
    String signKey = env.getString("ws.jwt.signKey");
    if (signKey != null && !signKey.isEmpty()) {
        try {
            BeanDef def = new BeanDef(
                    "container." + JwtWSAuthenticator.class.getSimpleName(),
                    JwtWSAuthenticator.class,
                    Scope.SINGLETON,
                    null, null, null,
                    new Object[]{signKey},   // 构造器参数
                    0);
            containerBeans.register(def);
        } catch (Exception e) {
            log.warn("注册框架默认 {} bean 失败", ...);
            return;
        }
    } else {
        // 兜底：无 signKey 配置时使用 HeaderTokenAuthenticator（本地开发）
        // ... 现有逻辑 ...
    }
    // ... commit + instantiate ...
}
```

### 8.4 signKey 注入路径

```
edap.getProps()
   └─ child("container")
       └─ child("ws")
           └─ getString("jwt.signKey")
                ↓
       Container.env.getString("ws.jwt.signKey")
                ↓
       BeanDef 构造器参数
                ↓
       BeanContainer.instantiate(def) → JwtWSAuthenticator 实例
                ↓
       AppContext.beans.beanWrapByType(WSAuthenticator.class)
                ↓
       PathInfo.setWsAuthenticator(...)
```

> 配置示例：
> ```properties
> # application.properties 或启动参数
> container.ws.jwt.signKey=my-shared-secret-key-min-32-bytes
> ```

### 8.5 应用覆盖

应用提供自己的 `WSAuthenticator` bean（byType）自动覆盖：

```java
// 应用代码
@Bean  // 或 edap 容器等价注解
public WSAuthenticator myJwtAuthenticator() {
    return new JwtWSAuthenticator(mySecureKeyVault.get("ws-jwt-key"));
}
```

`AppContext.beans` 的 byType 查找先命中应用 bean，框架默认 bean 不会被查到（参考 `WS_HANDLER_DESIGN.md` §1.3 握手鉴权层 + `CONTAINER_APPCONTEXT_DESIGN.md` AppContext > Container 的查找顺序）

### 8.6 HeaderTokenAuthenticator 处置

- 保留在 `edap-http-core/src/main/java/io/edap/http/ws/`
- 加 `@Deprecated(since = "0.1.4", forRemoval = true)` 
- docstring 加警告：生产请用 `JwtWSAuthenticator` 或自定义 `WSAuthenticator`

---

## 九、性能与缓存

### 9.1 验证热路径开销

| 操作 | 单次开销 | 备注 |
|---|---|---|
| Token 字符串 split（`indexOf(".")` x2） | ~200ns | 不缓存 |
| Base64URL decode header + payload | ~1μs | 每 verify 必做 |
| Eson.parseJsonObject(payload) | ~3μs | 每 verify 必做 |
| HmacSha256.sign(data, 0, len) | ~1μs | 命中 KeyCache 后无实例化开销 |
| KeyCache LRU 查找 | ~50ns | LinkedHashMap accessOrder=true |

总开销：~5μs / verify，QPS 20w+ 单线程

### 9.2 不缓存 token

`VerifyResult` 不缓存（token 无重放保护由应用层负责）：
- 同一 token 每次 verify 重新走一遍完整流程
- 防止应用误用"verify 一次缓存结果"导致 exp 失效不及时

### 9.3 算法缓存策略

- 第一期：LRU 64 个 key（参考 §5.4）
- 第二期：按 `alg:kid` 复合 key 缓存，支持 JWKS 公钥轮换

---

## 十、安全考虑

### 10.1 必须固定算法

```java
// AlgorithmRegistry.get(alg, key) 必须显式拒绝 "none"
public static Algorithm get(String name, String key) {
    if ("none".equalsIgnoreCase(name)) {
        throw new SecurityException("'none' algorithm is not allowed");
    }
    ...
}
```

> **历史教训**：JWT 历史上多次因客户端可控 `alg` 字段导致 `alg=none` 绕过签名验证。框架层强制拒绝

### 10.2 密钥强度

- HS256 密钥 < 32 字节 → `HmacSha256.create(key)` 抛 `IllegalArgumentException`（HS256 RFC 建议 ≥ HMAC output size = 32 bytes）
- 配置加载层 (`Container.env.getString`) 校验密钥长度

### 10.3 重放保护（应用层）

- 框架**不**做：token revoke / nonce / jti 黑名单
- 应用应：通过 `JwtPayload.jwtId` 在 Redis 记录已用 jti；同一 jti 第二次出现 → 拒绝

### 10.4 日志脱敏

- `JwtWSAuthenticator.verify` 不打印完整 token
- 仅打印 userId + exp 过期时间
- 异常时仅打印 `vr.getCode()` + `vr.getMessage()`，不打印 payload 内容

---

## 十一、API 演进

### 11.1 第一期（与本文档同步落地）

| 改动 | 文件 |
|---|---|
| 修复 `JWT.java:58` header 解析死代码 | `edap-auth-jwt/src/main/java/io/edap/auth/jwt/JWT.java` |
| 引入 `VerifyCode` + `VerifyOptions` | `edap-auth-jwt/src/main/java/io/edap/auth/jwt/VerifyOptions.java` (新) |
| `JWT.verify` 重载支持 VerifyOptions | `edap-auth-jwt/.../JWT.java` |
| `KeyCache` 替换 `ALGORITHM_CACHE` HashMap | `edap-auth-jwt/.../AlgorithmRegistry.java` (新) / `JWT.java` |
| **修复 `HmacSha256` 线程安全**：`Mac` 实例改为 `ThreadLocal`，`keyBytes` 缓存到实例字段 | `edap-auth-jwt/.../algorithm/HmacSha256.java` |
| 新增 `JwtWSAuthenticator` | `edap-container/src/main/java/io/edap/container/ws/JwtWSAuthenticator.java` (新) |
| `Container.initContainerBeans` 按 signKey 配置切换默认 bean | `edap-container/.../Container.java` |
| `HeaderTokenAuthenticator` 加 `@Deprecated` | `edap-http-core/.../HeaderTokenAuthenticator.java` |
| **JNI 加速路径已落地（详见 §5.6）**：新增 `edap-native` 模块 + `HmacSha256Native` MethodHandle 桥 + `AlgorithmRegistry` 派发 native / JCE 工厂 | `edap-native/...` + `edap-auth-jwt/.../algorithm/HmacSha256Native.java` |

### 11.2 第二期

- RS256 / ES256 支持（PEM 私钥 + JCA Signature + ThreadLocal 抽象基类 `AbstractThreadLocalAlgorithm`）
- JWKS 公钥拉取 + kid 路由
- `audience` 严格校验开关（VerifyOptions.expectedAudiences）
- 时钟偏移容忍（VerifyOptions.clockSkewSeconds）

### 11.3 第三期

- NativeRsaSha256（RS256 JNI 加速，与 HmacSha256Native 同样的 MethodHandle 桥模式）
- 补齐剩余平台 .o（macOS x86_64 / Linux x86_64 / Linux aarch64）
- EdDSA（Ed25519）
- JWE（加密 JWT）
- Token introspection endpoint（OAuth 2.0 RFC 7662）

### 11.4 向后兼容

- `JWT.verify(token, key)` 双参方法**签名不变**，code 0/1/2 语义不变
- 新增 `JWT.verify(token, key, VerifyOptions)` 三参重载为 opt-in
- `VerifyResult` 字段不变；新增字段为可选
- `HeaderTokenAuthenticator` `@Deprecated` 但**不删除**（forRemoval = true 标记，0.3.0 移除）
- `HmacSha256` 构造签名 `HmacSha256(String)` **不变**；ThreadLocal 是实现细节，调用方无感知
- `ALGORITHM_CACHE` 替换为 `KeyCache` 是内部重构；公开 API `JWT.verify` 不变

---

## 十二、参考

- RFC 7519 — JSON Web Token (JWT)
- RFC 7515 — JSON Web Signature (JWS)
- RFC 7518 — JSON Web Algorithms (JWA)
- [`WS_HANDLER_DESIGN.md`](../../../doc/WS_HANDLER_DESIGN.md) §1.3 三层组件边界 / §11 第一期不做列表
- [`CONTAINER_APPCONTEXT_DESIGN.md`](../../../doc/CONTAINER_APPCONTEXT_DESIGN.md) byType fallback 机制