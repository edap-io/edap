/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 预签 URL 生成器 —— 与 {@link SigV4Verifier} 配对的客户端侧工具,
 * 把签名过程从 inbound HTTP 请求下沉到本地函数调用,产出可直接 PUT/GET/HEAD 的 URL,
 * 客户端拿到 URL 后**不再需要 access key / secret**。
 *
 * <p>典型用法(app 后端):
 * <pre>{@code
 *   SigV4Presigner presigner = new SigV4Presigner();
 *   PresignedUrl u = presigner.presignPutObject(
 *           accessKeyId, secretKey, "us-east-1",
 *           "my-bucket", "uploads/photo.jpg",
 *           SigV4Presigner.Endpoint.https("s3.example.com"),
 *           Duration.ofMinutes(15));
 *   return u.url();   // 把这个 URL 给手机端
 * }</pre>
 *
 * <p>手机端拿到 URL 后直接:
 * <pre>{@code
 *   curl -X PUT --upload-file photo.jpg "<presigned-url>"
 * }</pre>
 *
 * <h2>http / https</h2>
 * 目标端点用 {@link Endpoint} 描述 —— scheme 决定 URL 前缀({@code http://} / {@code https://}),
 * 也决定<b>哪个端口算默认端口</b>(http=80、https=443)。默认端口在 URL authority 与
 * canonical host 里都不出现,非默认端口两处都带 {@code :port}。
 *
 * <p>服务端验签只认 wire 上的 {@code Host} header,与 scheme 无关 —— 所以切 https
 * <b>不需要改服务端</b>。前提是 TLS 在 edap-s3 之外终结(前置 nginx / LB),
 * 且转发时保留原始 {@code Host}:
 * <pre>{@code
 *   client ──https──> nginx ──http──> S3 (127.0.0.1:9000)
 * }</pre>
 * nginx 默认把 {@code Host} 改写成上游地址({@code $proxy_host}),必须显式
 * {@code proxy_set_header Host $host;}(公网 URL 带非默认端口时用 {@code $http_host}),
 * 否则 S3 端重建的 canonical host 与签名对不上 → {@code SIGNATURE_DOES_NOT_MATCH}。
 * 签名要签<b>公网</b> authority,不是 S3 的内网地址。
 *
 * <h2>与 header 模式 SigV4 的差异(写在这里强调)</h2>
 * <ul>
 *   <li><b>payload hash</b>:字面量 {@value #UNSIGNED_PAYLOAD} —— 签 URL 时还没 body,
 *       服务端验签时也只比对这个字面量,不重算 body hash。</li>
 *   <li><b>SignedHeaders</b>:永远 {@code host} 一个。客户端后续发的额外 header(如
 *       {@code Content-Type})不参与签名,也不影响验签 —— 这是 presign 的有意放宽。</li>
 *   <li><b>{@code X-Amz-Signature}</b>:不参与 canonical query 计算。先按不含 sig 的
 *       query 算 sig,再 append 到 query。这是 AWS 规范的硬要求。</li>
 *   <li><b>{@code X-Amz-Expires}</b>:相对 signedAt 的秒数,服务端验签时是<b>绝对窗口</b>,
 *       <b>不</b>叠加 15 分钟 clock skew。</li>
 *   <li><b>host</b>:canonical 形式必须与 wire 上的 {@code Host} header 逐字节相等,
 *       包括端口。<b>端口是否省略取决于 scheme</b>(http 默认 80、https 默认 443)——
 *       用 {@link Endpoint} 描述端点就是让这两个地方不可能对不上。</li>
 * </ul>
 *
 * <h2>STS / Security-Token</h2>
 * 本 Phase 不支持 {@code X-Amz-Security-Token}(临时凭证)。access key 是长期凭证时可直接使用。
 *
 * <h2>线程安全</h2>
 * 全部方法都是无状态的纯函数计算,实例可复用、可共享。
 */
public final class SigV4Presigner {

    /** Query-string auth mode 固定 payload hash。 */
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    /** SigV4 spec 允许的最小有效期。 */
    public static final Duration MIN_EXPIRES = Duration.ofSeconds(1);

    /** SigV4 spec 允许的最大有效期(7 天)。 */
    public static final Duration MAX_EXPIRES = Duration.ofSeconds(604_800);

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZDATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * 预签 PUT object URL。手机端可直接 {@code PUT <url> --data-binary @file}。
     *
     * @param endpoint 预签目标端点(scheme + host + port)
     */
    public PresignedUrl presignPutObject(String accessKeyId, String secretKey, String region,
                                         String bucket, String key, Endpoint endpoint,
                                         Duration expires) throws Exception {
        return build("PUT", bucket, key, endpoint, accessKeyId, secretKey, region,
                new LinkedHashMap<>(), expires);
    }

    /**
     * 预签 PUT object URL(http)。等价于
     * {@code presignPutObject(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignPutObject(String accessKeyId, String secretKey, String region,
                                         String bucket, String key, String host, int port,
                                         Duration expires) throws Exception {
        return presignPutObject(accessKeyId, secretKey, region, bucket, key,
                Endpoint.http(host, port), expires);
    }

    /**
     * 预签 GET object URL。手机端可直接 {@code GET <url>} 下载/预览。
     */
    public PresignedUrl presignGetObject(String accessKeyId, String secretKey, String region,
                                         String bucket, String key, Endpoint endpoint,
                                         Duration expires) throws Exception {
        return build("GET", bucket, key, endpoint, accessKeyId, secretKey, region,
                new LinkedHashMap<>(), expires);
    }

    /**
     * 预签 GET object URL(http)。等价于
     * {@code presignGetObject(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignGetObject(String accessKeyId, String secretKey, String region,
                                         String bucket, String key, String host, int port,
                                         Duration expires) throws Exception {
        return presignGetObject(accessKeyId, secretKey, region, bucket, key,
                Endpoint.http(host, port), expires);
    }

    /**
     * 预签 HEAD object URL。手机端可直接 {@code HEAD <url>} 检查元数据。
     */
    public PresignedUrl presignHeadObject(String accessKeyId, String secretKey, String region,
                                          String bucket, String key, Endpoint endpoint,
                                          Duration expires) throws Exception {
        return build("HEAD", bucket, key, endpoint, accessKeyId, secretKey, region,
                new LinkedHashMap<>(), expires);
    }

    /**
     * 预签 HEAD object URL(http)。等价于
     * {@code presignHeadObject(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignHeadObject(String accessKeyId, String secretKey, String region,
                                          String bucket, String key, String host, int port,
                                          Duration expires) throws Exception {
        return presignHeadObject(accessKeyId, secretKey, region, bucket, key,
                Endpoint.http(host, port), expires);
    }

    /**
     * 预签 Initiate Multipart Upload —— {@code POST /bucket/key?uploads}。
     */
    public PresignedUrl presignInitiateMultipart(String accessKeyId, String secretKey, String region,
                                                  String bucket, String key, Endpoint endpoint,
                                                  Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uploads", "");
        return build("POST", bucket, key, endpoint, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Initiate Multipart Upload(http)。等价于
     * {@code presignInitiateMultipart(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignInitiateMultipart(String accessKeyId, String secretKey, String region,
                                                  String bucket, String key, String host, int port,
                                                  Duration expires) throws Exception {
        return presignInitiateMultipart(accessKeyId, secretKey, region, bucket, key,
                Endpoint.http(host, port), expires);
    }

    /**
     * 预签 Upload Part —— {@code PUT /bucket/key?partNumber=N&uploadId=ID}。
     */
    public PresignedUrl presignUploadPart(String accessKeyId, String secretKey, String region,
                                           String bucket, String key, String uploadId,
                                           int partNumber, Endpoint endpoint,
                                           Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("partNumber", Integer.toString(partNumber));
        extra.put("uploadId", uploadId);
        return build("PUT", bucket, key, endpoint, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Upload Part(http)。等价于
     * {@code presignUploadPart(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignUploadPart(String accessKeyId, String secretKey, String region,
                                           String bucket, String key, String uploadId,
                                           int partNumber, String host, int port,
                                           Duration expires) throws Exception {
        return presignUploadPart(accessKeyId, secretKey, region, bucket, key, uploadId,
                partNumber, Endpoint.http(host, port), expires);
    }

    /**
     * 预签 Complete Multipart Upload —— {@code POST /bucket/key?uploadId=ID}(body 是 XML)。
     */
    public PresignedUrl presignCompleteMultipart(String accessKeyId, String secretKey, String region,
                                                  String bucket, String key, String uploadId,
                                                  Endpoint endpoint,
                                                  Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uploadId", uploadId);
        return build("POST", bucket, key, endpoint, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Complete Multipart Upload(http)。等价于
     * {@code presignCompleteMultipart(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignCompleteMultipart(String accessKeyId, String secretKey, String region,
                                                  String bucket, String key, String uploadId,
                                                  String host, int port,
                                                  Duration expires) throws Exception {
        return presignCompleteMultipart(accessKeyId, secretKey, region, bucket, key, uploadId,
                Endpoint.http(host, port), expires);
    }

    /**
     * 预签 Abort Multipart Upload —— {@code DELETE /bucket/key?uploadId=ID}。
     */
    public PresignedUrl presignAbortMultipart(String accessKeyId, String secretKey, String region,
                                               String bucket, String key, String uploadId,
                                               Endpoint endpoint,
                                               Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uploadId", uploadId);
        return build("DELETE", bucket, key, endpoint, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Abort Multipart Upload(http)。等价于
     * {@code presignAbortMultipart(..., Endpoint.http(host, port), ...)}。
     */
    public PresignedUrl presignAbortMultipart(String accessKeyId, String secretKey, String region,
                                               String bucket, String key, String uploadId,
                                               String host, int port,
                                               Duration expires) throws Exception {
        return presignAbortMultipart(accessKeyId, secretKey, region, bucket, key, uploadId,
                Endpoint.http(host, port), expires);
    }

    /**
     * canonical host 形式(http 语义):端口为 http 默认端口(80)时省略,否则拼 {@code :port},lowercase。
     * 与 wire 上 {@code Host} header 严格一致 —— 服务端验签时拿到 {@code headers["host"]}
     * 直接喂 canonical,任何字符差异都会触发 SIGNATURE_DOES_NOT_MATCH。
     *
     * @deprecated 端口是否省略取决于 scheme(http=80 / https=443),请改用
     *             {@link Endpoint#canonicalHost()}。
     */
    @Deprecated
    public static String canonicalHost(String host, int port) {
        return Endpoint.http(host, port).canonicalHost();
    }

    // ===================== 端点 =====================

    /**
     * 预签目标端点 —— scheme + host + port 三元组。
     *
     * <p>scheme 不只是 URL 前缀,它还决定<b>哪个端口算默认端口</b>:http 是 80、https 是 443。
     * 默认端口在 URL authority 与 canonical host 里都不出现,非默认端口两处都带 {@code :port}
     * —— 两处必须严格一致,否则 wire 上的 {@code Host} header 与签名算出来的 canonical host
     * 对不上,服务端直接 {@code SIGNATURE_DOES_NOT_MATCH}。
     *
     * <p>用法:
     * <pre>{@code
     *   Endpoint.https("s3.example.com")        // https://s3.example.com/...(443 省略)
     *   Endpoint.https("s3.example.com", 8443)  // https://s3.example.com:8443/...
     *   Endpoint.http("s3.internal", 9000)      // http://s3.internal:9000/...
     * }</pre>
     */
    public static final class Endpoint {
        /** http scheme。 */
        public static final String HTTP = "http";
        /** https scheme。 */
        public static final String HTTPS = "https";

        private final String scheme;
        private final String host;
        private final int port;

        private Endpoint(String scheme, String host, int port) {
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("host must not be empty");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range [1, 65535]: " + port);
            }
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        /** http 端点,显式端口。 */
        public static Endpoint http(String host, int port) {
            return new Endpoint(HTTP, host, port);
        }

        /** http 端点,默认端口 80。 */
        public static Endpoint http(String host) {
            return new Endpoint(HTTP, host, 80);
        }

        /** https 端点,显式端口。 */
        public static Endpoint https(String host, int port) {
            return new Endpoint(HTTPS, host, port);
        }

        /** https 端点,默认端口 443。 */
        public static Endpoint https(String host) {
            return new Endpoint(HTTPS, host, 443);
        }

        /** scheme —— {@code http} 或 {@code https}。 */
        public String scheme() {
            return scheme;
        }

        /** host(原样,未 lowercase)。 */
        public String host() {
            return host;
        }

        /** 端口。 */
        public int port() {
            return port;
        }

        /** 是否 https。 */
        public boolean secure() {
            return HTTPS.equals(scheme);
        }

        /** 该 scheme 的默认端口(http=80、https=443)。 */
        public int defaultPort() {
            return secure() ? 443 : 80;
        }

        /**
         * canonical host 形式 —— 与 wire 上 {@code Host} header 逐字节相等的形式:
         * lowercase + 端口为 scheme 默认端口时省略,否则拼 {@code :port}。
         */
        public String canonicalHost() {
            String lower = host.toLowerCase(Locale.ROOT);
            return port == defaultPort() ? lower : lower + ":" + port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Endpoint)) return false;
            Endpoint other = (Endpoint) o;
            return port == other.port
                    && scheme.equals(other.scheme)
                    && host.equalsIgnoreCase(other.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scheme, host.toLowerCase(Locale.ROOT), port);
        }

        @Override
        public String toString() {
            return scheme + "://" + canonicalHost();
        }
    }

    // ===================== 核心构建 =====================

    private PresignedUrl build(String method, String bucket, String key,
                                Endpoint endpoint,
                                String accessKeyId, String secretKey, String region,
                                Map<String, String> extraSignedQuery,
                                Duration expires) throws Exception {
        if (expires == null) {
            throw new IllegalArgumentException("expires must not be null");
        }
        if (expires.compareTo(MIN_EXPIRES) < 0 || expires.compareTo(MAX_EXPIRES) > 0) {
            throw new IllegalArgumentException(
                    "expires seconds out of range [1, 604800]: " + expires.getSeconds());
        }

        // 单一 now 锚点 —— amzDate 和 expiresAt 都基于这一刻,避免时钟漂移导致
        // expiresAt - nowBuild 跨秒边界被截断成 0。
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expires);
        String amzDate = AMZDATE_FMT.format(now);
        String dateStamp = amzDate.substring(0, 8);
        String credentialRaw = accessKeyId + "/" + dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        String expiresSeconds = Long.toString(expires.getSeconds());

        // canonical query: 全部用 X-Amz-* canonical casing, TreeMap 自动按字典序排序,
        // 服务端 verify 端走相同路径保证一致。
        Map<String, String> signedQuery = new TreeMap<>();
        signedQuery.put("X-Amz-Algorithm", ALGORITHM);
        signedQuery.put("X-Amz-Credential", credentialRaw);
        signedQuery.put("X-Amz-Date", amzDate);
        signedQuery.put("X-Amz-Expires", expiresSeconds);
        signedQuery.put("X-Amz-SignedHeaders", "host");
        // extraSignedQuery(uploads / uploadId / partNumber)也是签名的一部分 —— 服务端
        // 在 canonical 时会把它们一并纳入 canonicalQueryString,任何篡改都会拒绝。
        for (Map.Entry<String, String> e : extraSignedQuery.entrySet()) {
            signedQuery.put(e.getKey(), e.getValue());
        }

        // canonical request —— payload hash 用 UNSIGNED-PAYLOAD, signed headers 只有 host
        // canonical host 同时也是 URL 的 authority —— 两处同源,不可能对不上
        String canonicalHost = endpoint.canonicalHost();
        Map<String, String> signedHeadersMap = new LinkedHashMap<>();
        signedHeadersMap.put("host", canonicalHost);

        String canonicalRequest = SigV4Verifier.buildCanonicalRequest(
                method,
                SigV4Verifier.canonicalUri("/" + bucket + "/" + key),
                SigV4Verifier.canonicalQueryString(signedQuery),
                signedHeadersMap,
                new String[] {"host"},
                UNSIGNED_PAYLOAD);

        // string to sign + signing key + signature
        String scope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + scope + "\n"
                + SigV4Verifier.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = SigV4Verifier.deriveSigningKey(secretKey, dateStamp, region, SERVICE);
        String signature = SigV4Verifier.hexEncode(
                SigV4Verifier.hmac(signingKey, stringToSign));

        // final query(含 X-Amz-Signature)
        Map<String, String> finalQuery = new TreeMap<>(signedQuery);
        finalQuery.put("X-Amz-Signature", signature);

        // path-style URL —— authority 与 canonical host 同形(scheme 默认端口省略)
        StringBuilder url = new StringBuilder(128);
        url.append(endpoint.scheme()).append("://").append(canonicalHost);
        url.append('/').append(bucket).append('/').append(key);
        url.append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : finalQuery.entrySet()) {
            if (!first) url.append('&');
            url.append(SigV4Verifier.uriEncode(e.getKey()));
            url.append('=').append(SigV4Verifier.uriEncode(e.getValue()));
            first = false;
        }

        return new PresignedUrl(url.toString(), expiresAt, finalQuery);
    }

    // ===================== 数据 =====================

    /**
     * 预签 URL 的返回值。{@link #url()} 是可直接交给客户端的完整 path-style URL。
     * {@link #queryParams()} 暴露签好的 query,便于调试或在客户端 SDK 里反序列化重发。
     */
    public static final class PresignedUrl {
        private final String url;
        private final Instant expiresAt;
        private final Map<String, String> queryParams;

        PresignedUrl(String url, Instant expiresAt, Map<String, String> queryParams) {
            this.url = url;
            this.expiresAt = expiresAt;
            this.queryParams = queryParams;
        }

        /** 完整 path-style URL,例如 {@code https://s3.example.com/photo/1.jpg?X-Amz-...&X-Amz-Signature=...} */
        public String url() {
            return url;
        }

        /** URL 失效的绝对时刻(=签发时 {@code expiresAt})。 */
        public Instant expiresAt() {
            return expiresAt;
        }

        /** 签好的 query map(快照,不可变)。key 是 {@code X-Amz-*}/{@code uploads}/{@code uploadId}/{@code partNumber} 等。 */
        public Map<String, String> queryParams() {
            return queryParams;
        }

        @Override
        public String toString() {
            return "PresignedUrl{url=" + url + ", expiresAt=" + expiresAt + "}";
        }
    }
}
