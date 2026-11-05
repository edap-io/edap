# edap-s3 Presigned URL 设计

> 本文档是 `edap-s3-parent/edap-s3-core` SigV4 query-string auth mode(预签 URL)的实现规范,覆盖 SDK 入口 `SigV4Presigner`、verifier 改动、调用方集成方式与踩过的坑。
>
> **目标读者**:在 `edap-s3-parent` 下做改动的开发者;以及需要在 app 后端集成"代签 URL → 客户端裸 PUT/GET"流程的应用开发者。
>
> **覆盖范围**:
>
> | 层 | 本文档章节 | 对应类 | 关系 |
> |----|-----------|--------|------|
> | SDK 入口(纯函数) | 第四章 | `io.edap.s3.auth.SigV4Presigner`(新增) | app 后端直接调用,生成 URL |
> | 验签器 query 模式 | 第五章 | `io.edap.s3.auth.SigV4Verifier#verifyQueryMode`(新增) | header 模式逻辑**逐字节不动**,仅在入口 dispatch |
> | HTTP 入口 | 第六章 | `io.edap.s3.http.S3HttpHandler`、`S3RequestParser` | **零改动** |
> | 端到端集成测试 | 第七章 | `S3HttpHandlerPresignIT`、`SigV4PresignerTest`、`SigV4VerifierTest` | |

---

## 一、目标与范围

### 1.1 解决什么问题

edap-s3 之前只支持 `Authorization` header 模式的 SigV4(`SigV4Verifier.java:55` 明确写 "Phase 1 简化:不支持 presigned URL")。这意味着手机端无法直接 PUT 文件到 S3——必须经 app 后端中转,中转过程要在堆上接完整 body(GB 级文件 OOM 风险),且多一跳流量。

**目标**:让 app 后端用 server-side 凭证生成带签名的 URL,手机端拿 URL 直接 `PUT <url> --data-binary @file`,服务端在 SigV4 query-string 模式下验签通过就落盘。手机端 **零 SDK、零 access key**。

### 1.2 不做什么

- **不开新 HTTP 端点**:Presign 是 SDK 入口,服务端 HTTP 协议保持不变。
- **不下发 access key**:Presigner 在 `edap-s3-core` 里纯函数调用,secret 永远留在服务端 JVM。
- **不做 STS / X-Amz-Security-Token**:本 Phase 只支持长期凭证。临时凭证走法在风险章节标注。
- **不做 path-style vs virtual-host 切换**:统一 path-style(`/bucket/key`)。

---

## 二、设计决策

| # | 决策 | 备选 | 理由 |
|---|------|------|------|
| 1 | **纯 SDK 入口**,不开新 HTTP 端点 | 新增 `POST /presign` server 端点 + app 调用 | 端点方案让 app 多一跳 RPC,且 secret 在 wire 上多走一段;SDK 纯函数直接返回 URL 给 app,zero 额外 I/O。 |
| 2 | 同一 `S3RequestParser`/`S3HttpHandler` 复用 | 新增 query 模式专属解析器 | `S3RequestParser` 已经能解任意 query 参数;`S3HttpHandler.handle` 不需要知道是哪种模式。 |
| 3 | `SigV4Verifier.verify` 入口 dispatch | 新增 `verifyQuery` 公开方法由调用方选择 | 单一入口减少 caller 心智负担;header 模式逻辑不变。 |
| 4 | Presigner 实例无状态、可复用 | 每次 new | SigV4 计算全部基于输入参数,无副作用,实例线程安全。 |
| 5 | 强制 path-style URL | 支持两种 | 与 edap-s3 服务端路由(`/bucket/key`)一致;不引入 virtual-host 解析。 |
| 6 | `host` 与 `port` 拆开传入 Presigner | 拼好完整 Host 字符串传入 | 让 caller 不容易拼错(拼错 = `SIGNATURE_DOES_NOT_MATCH`);Presigner 内部负责 canonical 形式。后续演进为 `Endpoint` 值对象(scheme + host + port),见第四章。 |

---

## 三、Canonical Request 模式对比

这是整套设计最关键的一节 —— **两种 auth mode 共享同一算法骨架,只在若干字段上分叉**,理解这张表后所有代码逻辑都顺。

| 维度 | header 模式(原) | query 模式(新) |
|---|---|---|
| 算法 | `AWS4-HMAC-SHA256` | 同 |
| SignedHeaders | `host;x-amz-content-sha256;x-amz-date` | **`host`(恒定)** |
| Payload hash | 真实 SHA256(body) | **字面量 `"UNSIGNED-PAYLOAD"`** |
| 鉴权位置 | `Authorization` header | `X-Amz-*` query params |
| 时间位置 | `X-Amz-Date` header | `X-Amz-Date` query param |
| 时钟 skew 校验 | ±15 分钟 | **不校验**(`X-Amz-Expires` 即绝对窗口) |
| `X-Amz-Signature` 在 canonical query | n/a | **不参与**(算 sig 前移除) |
| bucket ACL | `keyResolver.allowedBuckets()` | 同 |

### 3.1 一句话概括

**header 模式**用 `X-Amz-Content-Sha256` + `X-Amz-Date` + `Authorization` 三件套;**query 模式**用 `X-Amz-Credential` + `X-Amz-Date` + `X-Amz-Expires` + `X-Amz-SignedHeaders=host` + `X-Amz-Signature` 五件套全塞 URL 里,服务端验签时所有信息在 query + Host header 上凑齐。

### 3.2 为什么 SignedHeaders 只能 `host`

presign 时 client 还没决定发什么额外 header(Content-Type/Content-Length/Cache-Control/...),如果签名覆盖这些 header,签完的 URL 就被锁死了 —— client 不能加任何 header。AWS spec 直接规定 query 模式的 `SignedHeaders` 永远是 `host`,放宽语义。

服务端验证时:
1. 从 query 取 `X-Amz-SignedHeaders=host`
2. 从 wire 上取 `Host` header
3. `host` 必须与 presign 时算出的 `Endpoint#canonicalHost()` 逐字节相等(端口是否为「默认端口」取决于 scheme:http=80、https=443)

---

## 四、Presigner API

### 4.1 类签名

```java
public final class SigV4Presigner {
    public static final String  UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final Duration MIN_EXPIRES     = Duration.ofSeconds(1);
    public static final Duration MAX_EXPIRES     = Duration.ofSeconds(604_800);   // 7 天

    // 每个操作都有两个重载:Endpoint 版(推荐,支持 https)与 (host, port) 兼容版(= http)
    public PresignedUrl presignPutObject (...);                    // PUT  /bucket/key
    public PresignedUrl presignGetObject (...);                    // GET  /bucket/key
    public PresignedUrl presignHeadObject(...);                    // HEAD /bucket/key
    public PresignedUrl presignInitiateMultipart(...);             // POST /bucket/key?uploads
    public PresignedUrl presignUploadPart(..., String uploadId,    // PUT  /bucket/key?partNumber=N&uploadId=ID
                                          int partNumber, ...);
    public PresignedUrl presignCompleteMultipart(...,              // POST /bucket/key?uploadId=ID
                                                 String uploadId, ...);
    public PresignedUrl presignAbortMultipart(..., String uploadId, ...);  // DELETE /bucket/key?uploadId=ID

    @Deprecated
    public static String canonicalHost(String host, int port);     // http 语义;"host 与 port 拆开"时代的遗留

    /** 预签目标端点:scheme + host + port */
    public static final class Endpoint {
        public static Endpoint http(String host);                   // 端口 80
        public static Endpoint http(String host, int port);
        public static Endpoint https(String host);                  // 端口 443
        public static Endpoint https(String host, int port);

        public String scheme();                                     // "http" / "https"
        public String host();
        public int port();
        public boolean secure();
        public int defaultPort();                                   // 80 / 443
        public String canonicalHost();                              // "s3.example.com" 或 "s3.example.com:8443"
    }

    public static final class PresignedUrl {
        public String url();                                       // 完整 path-style URL
        public Instant expiresAt();                                // 失效绝对时刻
        public Map<String,String> queryParams();                   // 已签好的 query(快照,不可变)
    }
}
```

### 4.2 典型用法(app 后端)

```java
SigV4Presigner presigner = new SigV4Presigner();
PresignedUrl u = presigner.presignPutObject(
        accessKeyId, secretKey, "us-east-1",
        "my-bucket", "uploads/photo.jpg",
        SigV4Presigner.Endpoint.https("s3.example.com"),   // https + 默认端口 443
        Duration.ofMinutes(15));
return u.url();                                 // 给手机端
```

内网直连(明文 http)用 `Endpoint.http("s3.internal", 9000)`;旧的
`presignPutObject(ak, sk, region, bucket, key, host, port, expires)` 签名保留,
内部等价于 `Endpoint.http(host, port)`。

### 4.3 内部构建流程(7 个公开方法都走同一个 `build(...)`)

1. **校验 `expires`**:`MIN_EXPIRES ≤ expires ≤ MAX_EXPIRES`,否则 `IllegalArgumentException`。
2. **取单一 now 锚点**:`amzDate` 和 `expiresAt` 都基于同一 `Instant.now()`,避免时钟漂移导致 `expiresAt - nowBuild` 跨秒边界被截断成 0。
3. **构造 credential**:`AKID/YYYYMMDD/region/s3/aws4_request`(5 段)。
4. **构造 signed query(TreeMap)**:6 个固定字段 + 多段操作自己的 query(`uploads`/`uploadId`/`partNumber`)。
5. **canonical request**(复用 `SigV4Verifier.buildCanonicalRequest`):
   - `payloadHash = "UNSIGNED-PAYLOAD"`
   - `signedHeaders = ["host"]`
   - `headersMap = {host: endpoint.canonicalHost()}`
   - canonical query 用 **不含** `X-Amz-Signature` 的 query 算
6. **stringToSign** + **deriveSigningKey** + **HMAC** + **hex** 出 sig。
7. **把 sig append 进 query**,组装成 `{scheme}://{authority}/bucket/key?<querystring>`,
   其中 `authority` 与第 5 步的 canonical host **同一个字符串**(同源,不可能对不上)。

### 4.4 关键不变量

- **`expiresAt` 是绝对值**:不是相对当前时刻的字符串,而是 `Instant`,便于服务端对照 `Instant.now()` 做 `isAfter` 判定,没有时区/Parser 误差。
- **`X-Amz-Signature` 不参与 canonical query**:算 sig 时先把它从 query 里 remove,append 在最后 —— AWS 规范的硬要求,违反 → 服务端永远验不过。
- **端点用 `Endpoint` 描述**:`Endpoint#canonicalHost()` 内部负责:
  - `host.toLowerCase(Locale.ROOT)`(SigV4 强制;用 ROOT 避免土耳其语 locale 的 `I` → `ı` 坑)
  - `port == defaultPort(scheme)`(http=80、https=443)→ 省略端口
  - 否则拼 `:port`
- **URL authority == canonical host**:同一个变量,保证「客户端发出来的 Host header」与
  「签名时算出来的 host」必然一致。默认端口在 URL 里**不出现** —— RFC 7230 §5.4 要求
  `Host` 字段值与 target URI 的 authority 逐字节相同,所以 URL 里带了 `:443`、
  canonical 里不带(或反过来)都会对不上;把端口从 URL 里省掉就没有这个歧义。
- **canonical host 与 wire Host header 严格逐字节相等**:任何差异(`s3.Internal` vs `s3.internal`、端口漏写/多写)都会触发服务端 `SIGNATURE_DOES_NOT_MATCH`。
- **query key 大小写**:Presigner 输出时统一用 `X-Amz-*` 大写(与 AWS CLI 一致);服务端 verifier 在 canonical 时用 `TreeMap` 自然按字典序排序,大小写不敏感(因为只有一种合法 casing)。

---

## 五、Verifier 改动

### 5.1 唯一入口改动

`SigV4Verifier.verify(...)` 在 `headers` 转 lowercase 之前(line 88-91)插入 dispatch:

```java
// query-string 模式 dispatch:presign URL 带 X-Amz-Signature query param,
// header 模式不带 —— 优先按 query 模式验签。
if (queryParams != null && queryParams.containsKey("X-Amz-Signature")) {
    verifyQueryMode(httpMethod, path, queryParams, headers, parsed);
    return;
}
// 原 header-mode 逻辑一字不动 ↓
Map<String, String> lowerHeaders = new HashMap<>();
for (Map.Entry<String, String> e : headers.entrySet()) {
    lowerHeaders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
}
// ...
```

**9 个现有 IT 测试零回归** —— `S3HttpHandlerIT` 全部走 header 模式,queryParams 不含 `X-Amz-Signature`,dispatch 不会触发,header 逻辑一字不动。

### 5.2 `verifyQueryMode` 步骤

1. **必备字段**(6 个 query):`X-Amz-Algorithm` / `Credential` / `Date` / `Expires` / `SignedHeaders` / `Signature`;任一缺失 → `ACCESS_DENIED`。
2. **算法校验**:`AWS4-HMAC-SHA256`。
3. **Credential 解析**:5 段 `/` 分隔。
4. **查 access key**:`keyResolver.resolve(accessKeyId)` → 失败 → `INVALID_ACCESS_KEY_ID`。
5. **Scope 一致性**:`credDate == amzDate[0..7]`、`credRegion == resolved.region()`、`credService == resolved.service()`。
6. **expires 校验**(无 clock skew):
   - 解析 `X-Amz-Expires` 为 long,`1 ≤ x ≤ 604800`,否则 `ACCESS_DENIED`
   - `signedAt = parse(X-Amz-Date)`,`expiresAt = signedAt + expiresSeconds`
   - `Instant.now().isAfter(expiresAt)` → `ACCESS_DENIED "Request has expired"`
7. **桶级 ACL**:`keyResolver.allowedBuckets().contains(parsed.bucket())` —— 同 header 模式。
8. **canonical request 重建**:
   - `SignedHeaders` 必须字面 `host`,否则 `SIGNATURE_DOES_NOT_MATCH`(防 caller 滥用)
   - headers 只取 `host`
   - **canonical query 移除 `X-Amz-Signature`**
   - `payloadHash = "UNSIGNED-PAYLOAD"`
9. **重建 stringToSign + signingKey + HMAC + hex**,**constant-time 比对** `X-Amz-Signature`。

### 5.3 错误码统一

| 场景 | S3ErrorCode | HTTP status |
|------|------------|-------------|
| 缺 query 字段 | `ACCESS_DENIED` | 403 |
| 算法不是 `AWS4-HMAC-SHA256` | `ACCESS_DENIED` | 403 |
| Credential 格式错 | `SIGNATURE_DOES_NOT_MATCH` | 403 |
| AccessKey 不存在 | `INVALID_ACCESS_KEY_ID` | 403 |
| region/service 不匹配 | `ACCESS_DENIED` | 403 |
| expires 越界 | `ACCESS_DENIED` | 403 |
| URL 过期 | `ACCESS_DENIED "Request has expired"` | 403 |
| bucket ACL 拒绝 | `ACCESS_DENIED` | 403 |
| SignedHeaders 不是 `host` | `SIGNATURE_DOES_NOT_MATCH` | 403 |
| **host header 缺失** | `ACCESS_DENIED "Missing Host header (required for SigV4 query mode)"` | 403 |
| **signature 不匹配**(任何字符差异) | `SIGNATURE_DOES_NOT_MATCH` | 403 |

---

## 六、HTTP 入口零改动

- `S3RequestParser`:query 参数解析走现有 `ValueHttpRequest.getParameters()` 路径,`X-Amz-*` 全在第一分支,大小写保留 wire 形式。
- `S3HttpHandler.handle`:line 71-76 调用 `verifier.verify(method, rawHttpRequest, queryParams, headers, parsed)`,签名不动。verifier 内部按 queryParams 自动 dispatch。
- `S3ServerBuilder`:无新增配置。

---

## 七、测试覆盖

### 7.1 单元测试

**`SigV4PresignerTest`**(`edap-s3-core/src/test/java/.../auth/`,共 25 个用例):

| 用例 | 验证点 |
|------|--------|
| `presignPutObject_urlShape` | URL 含 6 个 `X-Amz-*` 参数 + `X-Amz-Signature`;`SignedHeaders=host`;路径 `/{bucket}/{key}` |
| `presignPutObject_signatureDeterministic` | 同输入两次 sig 一致;不同 `expiresAt` sig 不同 |
| `presignPutObject_expiresBoundary` | `expires=1` OK;`expires=604800` OK;`expires=0` IAE;`expires=604801` IAE;`expiresAt<now` IAE |
| `presignPutObject_hostWithPort` | port=8080 同时出现在 URL 和 canonical host |
| `presignPutObject_hostPortDefault` | 端口是否省略随 scheme 变(http=80、https=443);老 `canonicalHost(host, port)` 是 http 语义 |
| `presignPutObject_https_urlShape` | `Endpoint.https(host)` → `https://host/bucket/key?...`,URL 里无 `:443` |
| `presignPutObject_httpsNonDefaultPort_appearsInUrl` | `Endpoint.https(host, 8443)` → URL 与 canonical host 都带 `:8443` |
| `presignPutObject_httpDefaultPort_omittedFromUrl` | `Endpoint.http(host)` → URL 里无 `:80` |
| `endpoint_validation` | 空 host / 越界 port → IAE |
| `endpoint_equalsAndHashCode` | host 大小写不敏感的值对象语义 |
| `presignRoundtripWithVerifier_https_defaultPort` | https + 443 → Host header 不带端口,验签通过 |
| `presignRoundtripWithVerifier_https_nonDefaultPort` | https + 8443 → Host header 带 `:8443`,验签通过 |
| `presignRoundtripWithVerifier_httpDefaultPort` | http + 80 → Host header 不带端口,验签通过 |
| `presignUploadPart_queryIncludesPartNumberAndUploadId` | `uploadId` + `partNumber` 在 query 里且参与签名 |
| `presignInitiateMultipart_hasUploadsParam` | `uploads=` 在 query 里 |
| `presignRoundtripWithVerifier` | presign → 手动 parse 回 `Map<...> queryParams` + path + headers → 调 verifier → 通过 |
| `presignCompleteAbortMultipart_*` | `Complete`/`Abort` 的 `uploadId` 参与签名 |

**`SigV4VerifierTest`** 新增 query-mode 用例(11 个):
- `queryMode_roundtripAccepts` / `queryMode_expired_throwsAccessDenied`
- `queryMode_tamperedSignature_throwsSignatureMismatch` / `queryMode_tamperedQuery_*` / `queryMode_tamperedKey_*` / `queryMode_hostMismatch_*`
- `queryMode_missingSignature_throwsAccessDenied` / `queryMode_payloadIsUnsignedPayload`
- `queryMode_regionMismatch_*` / `queryMode_bucketAclDenied`
- `queryMode_signedHeadersNotHost_throwsSignatureMismatch`

### 7.2 集成测试

**`S3HttpHandlerPresignIT`**(`edap-s3-server/src/test/java/.../http/`,共 6 个用例,真实起 `HttpServer` + 裸 `HttpURLConnection`):

| 用例 | 验证点 |
|------|--------|
| `presignedPutObject_roundtrip` | presign PUT → 裸 PUT(无 Authorization header,只设 Host)→ 200;presign GET → 拿回相同字节 |
| `presignedGetObject_objectNotFound` | presign GET 不存在的 key → 404 |
| `presignedPutObject_largeBody` | 1 MB payload 走 presigned PUT,验内容 + ETag |
| `presignedMultipart_e2e` | presign Initiate → UploadPart × 3 → Complete → GET 拼回原 payload |
| `presignedMultipart_abort` | presign Initiate → UploadPart × 2 → Abort → 204;再 ListParts → 404 |
| `presignedPutObject_expiredUrlRejected` | backdate `X-Amz-Date` 使 URL 过期 → ACCESS_DENIED |

### 7.3 测试运行

```bash
mvn -pl edap-s3-parent/edap-s3-core test                  # 单元测试
mvn -pl edap-s3-parent/edap-s3-server test \
    -Dtest='S3HttpHandlerIT,S3HttpHandlerPresignIT'       # 集成测试
```

---

## 八、调用方集成示意

### 8.1 app 后端(REST 端点示例)

```java
@Path("/api/upload-token")
public class UploadTokenResource {
    @Inject SigV4Presigner presigner;            // 单例 bean
    @Inject @Config("s3.access-key") String ak;
    @Inject @Config("s3.secret-key") String sk;

    @POST
    @Path("/put")
    public TokenResp putToken(@Valid UploadReq req, @Context CallerUser user) {
        // 业务鉴权:验证 user 有上传到 req.bucket 的权限
        requirePermission(user, "s3:PutObject", req.bucket, req.key);

        PresignedUrl u = presigner.presignPutObject(
                ak, sk, "us-east-1",
                req.bucket, req.key,
                SigV4Presigner.Endpoint.https(s3Host),
                Duration.ofMinutes(15));

        return new TokenResp(u.url(), u.expiresAt());
    }
}
```

业务鉴权(session/role/ACL)由 app 现有 REST 框架处理,SDK 不参与。

### 8.2 手机端(iOS/Android/Web)

```bash
# 拿到 URL 后:
curl -X PUT --upload-file photo.jpg "<presigned-url>"

# 上传大文件直接走 presigned URL,不经过 app 后端
curl -X PUT -H "Content-Type: image/jpeg" \
     --data-binary @large-file.bin "<presigned-url>"
```

手机端 **零 SDK**:任何 HTTP 客户端都行(`curl`、`NSURLSession`、`OkHttp`、`fetch`)。

### 8.3 access key 安全

- **长期凭证**:Presigner 在 `edap-s3-core` JVM 内运行,secret key 不出进程;URL 上没有 secret(只有 signature)。
- **不要**:在客户端 JS/小程序里调 Presigner(需要把 secret 给客户端,违反安全模型)。
- **不要**:把签名 URL 存到数据库 / 日志 / 长期缓存(URL 7 天内可被任何持有者用)。

---

## 九、风险与未来工作

### 9.1 已知的硬约束

1. **`host` 端口不一致 → 必败**。`s3.internal`(wire Host) vs `s3.internal:9000`(canonical host)永远验不过。Javadoc 与 `canonicalHost(...)` 方法上必须显式写。
2. **`X-Amz-Signature` 必须不参与 canonical query**。这是 AWS 规范的硬要求。算 sig 时先 remove 再 append,不能颠倒。
3. **query key 大小写**:Presigner 统一输出 `X-Amz-*` 大写;verifier 端 `TreeMap` 排序保证大小写不歧义。**禁止**用 `x-amz-date`(小写 d)发请求,会被 verifier 视为 `X-Amz-Date` 缺失 → `ACCESS_DENIED`。这条与 AWS CLI 行为一致。
4. **没有 clock skew 容忍**:URL 签发后超过 `X-Amz-Expires` 秒就过期,与客户端时钟无关。客户端系统时间不准不会"提前过期",只会在它实际超时时被拒。
5. **`host` header 必须由 HTTP 客户端发送**:某些 HTTP 库(如某些 Node.js 客户端)默认不发 Host,需要显式设置。
6. **scheme 与端口必须成对**:`Endpoint` 把 scheme + host + port 绑在一起,就是为了避免
   「URL 用 https、canonical host 却按 http 的默认端口算」这类错配。历史遗留的
   `canonicalHost(host, port)` 静态方法按 **http 语义**(只有 80 省略端口),已 `@Deprecated`。
7. **https 需要 TLS 在 edap-s3 之外终结**:edap-s3 的 `HttpServer` 本身不终结 TLS,
   https 部署形态是「前置 nginx / LB 做 TLS + 转发到 edap-s3 的 http 端口」。
   验签只看 wire 上的 `Host` header,**服务端零改动**,但前置代理必须保留原始 `Host`
   (nginx 默认 `$host` 即不含端口;若客户端 URL 带非默认端口,要用 `$http_host`)。
8. **STS / `X-Amz-Security-Token`** 不支持。要支持时需要:
   - Presigner 把 `X-Amz-Security-Token` 加进 signed query + 头里
   - verifier 从 query 取 token 传给 `keyResolver.resolve(...)`,或额外校验

### 9.2 性能

- 每次 presign 调用 1 次 SHA256 + 4 次 HMAC + 1 次 hex,纯 CPU 计算。
- 单核 QPS ≈ 数万(取决于 secret 长度)。无 I/O、无锁、无共享状态,可水平扩展。
- 如果业务有高并发签 URL 场景,可在 app 后端做签名结果缓存(注意 expires 边界)。

### 9.3 未来扩展

- STS 临时凭证支持。
- `pathStyle=false`(virtual-host)参数化。
- 签名 URL 缓存(基于 `(ak, region, bucket, key, expiresAt)` 五元组)。
- `X-Amz-Content-Sha256` 参数化:支持 presign 时指定 payload hash(给已知大文件的场景优化,但失去了"任意 client 直接 PUT"的灵活性)。

### 9.4 nginx 反代部署(外网 https → 内网 http)

典型拓扑:

```
client ──https──> nginx (TLS 终结) ──http──> S3 (127.0.0.1:9000)
```

**S3 侧零改动** —— 验签只看 wire 上的 `Host` header,与 scheme 无关。但 nginx 必须把
**公网** Host 原样转发过去,否则签名一定对不上。

#### 关键:nginx 的 `proxy_set_header Host`

nginx **默认**把 `Host` 改写成上游地址(`Host → $proxy_host`,即 `127.0.0.1:9000`)。
这样 S3 端重建出的 canonical host 与签名里的公网 host 不一致 → **403 SIGNATURE_DOES_NOT_MATCH**。

```nginx
server {
    listen 443 ssl;
    server_name s3.example.com;

    location / {
        proxy_pass http://127.0.0.1:9000;

        # 必须:把公网 Host 原样转发(SigV4 签的就是它)
        proxy_set_header Host $host;          # 公网 host,不含端口
        # 公网 URL 带非默认端口(如 https://s3.example.com:8443)时改用 $http_host
        # proxy_set_header Host $http_host;   # 原始 Host header,保留端口

        # 大文件直传:别让 nginx 先把整个 body 落盘缓冲
        proxy_request_buffering off;
        proxy_buffering off;

        # 上游 keep-alive
        proxy_set_header Connection "";
    }
}
```

| nginx 转发 | S3 收到的 Host | 签名用的 canonical host | 结果 |
|---|---|---|---|
| 默认 `$proxy_host` | `127.0.0.1:9000` | `s3.example.com` | ✗ 403 `SIGNATURE_DOES_NOT_MATCH` |
| `$host` | `s3.example.com` | `s3.example.com` | ✓ |
| `$http_host`(公网带端口) | `s3.example.com:8443` | `s3.example.com:8443` | ✓ |

#### presigner 侧

签名用的是**公网** authority(客户端 URL 里那个),不是 S3 的内网地址:

```java
// 客户端访问 https://s3.example.com/... → 签 s3.example.com
presigner.presignPutObject(ak, sk, region, bucket, key,
        SigV4Presigner.Endpoint.https("s3.example.com"), Duration.ofMinutes(15));
```

内网直连(不经过 nginx)才用 `Endpoint.http("127.0.0.1", 9000)` —— 两种拓扑的签名**不能混用**。

#### 排错

预签 URL 报 `403 SIGNATURE_DOES_NOT_MATCH` 而 URL 看着没问题时,按顺序查:

1. nginx 的 `proxy_set_header Host` 配了没,是不是 `$host` / `$http_host`;
2. presigner 签的是不是公网 host(而不是内网地址);
3. 客户端有没有自己改写 `Host` 头。

---

## 十、参考

- AWS SigV4 规范:<https://docs.aws.amazon.com/general/latest/gr/signature-version-4.html>
- Query-string auth 规范:<https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html>
- AWS 官方 test suite:<https://github.com/aws/aws-sdk-go/tree/main/aws/signer/v4/testdata>
- 源码入口:
  - `edap-s3-parent/edap-s3-core/src/main/java/io/edap/s3/auth/SigV4Presigner.java`
  - `edap-s3-parent/edap-s3-core/src/main/java/io/edap/s3/auth/SigV4Verifier.java`(`verifyQueryMode` at line 208)
