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
import java.util.Map;
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
 *           "s3.internal", 9000,
 *           Instant.now().plus(Duration.ofMinutes(15)));
 *   return u.url();   // 把这个 URL 给手机端
 * }</pre>
 *
 * <p>手机端拿到 URL 后直接:
 * <pre>{@code
 *   curl -X PUT --upload-file photo.jpg "<presigned-url>"
 * }</pre>
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
 *       包括端口(80/443 除外)。presigner 接收 host + port 拆开的形式就是为了避免调用方
 *       把端口拼错。</li>
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
     */
    public PresignedUrl presignPutObject(String accessKeyId, String secretKey, String region,
                                         String bucket, String key, String host, int port,
                                         Duration expires) throws Exception {
        return build("PUT", bucket, key, host, port, accessKeyId, secretKey, region,
                new LinkedHashMap<>(), expires);
    }

    /**
     * 预签 GET object URL。手机端可直接 {@code GET <url>} 下载/预览。
     */
    public PresignedUrl presignGetObject(String accessKeyId, String secretKey, String region,
                                         String bucket, String key, String host, int port,
                                         Duration expires) throws Exception {
        return build("GET", bucket, key, host, port, accessKeyId, secretKey, region,
                new LinkedHashMap<>(), expires);
    }

    /**
     * 预签 HEAD object URL。手机端可直接 {@code HEAD <url>} 检查元数据。
     */
    public PresignedUrl presignHeadObject(String accessKeyId, String secretKey, String region,
                                          String bucket, String key, String host, int port,
                                          Duration expires) throws Exception {
        return build("HEAD", bucket, key, host, port, accessKeyId, secretKey, region,
                new LinkedHashMap<>(), expires);
    }

    /**
     * 预签 Initiate Multipart Upload —— {@code POST /bucket/key?uploads}。
     */
    public PresignedUrl presignInitiateMultipart(String accessKeyId, String secretKey, String region,
                                                  String bucket, String key, String host, int port,
                                                  Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uploads", "");
        return build("POST", bucket, key, host, port, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Upload Part —— {@code PUT /bucket/key?partNumber=N&uploadId=ID}。
     */
    public PresignedUrl presignUploadPart(String accessKeyId, String secretKey, String region,
                                           String bucket, String key, String uploadId,
                                           int partNumber, String host, int port,
                                           Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("partNumber", Integer.toString(partNumber));
        extra.put("uploadId", uploadId);
        return build("PUT", bucket, key, host, port, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Complete Multipart Upload —— {@code POST /bucket/key?uploadId=ID}(body 是 XML)。
     */
    public PresignedUrl presignCompleteMultipart(String accessKeyId, String secretKey, String region,
                                                  String bucket, String key, String uploadId,
                                                  String host, int port,
                                                  Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uploadId", uploadId);
        return build("POST", bucket, key, host, port, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * 预签 Abort Multipart Upload —— {@code DELETE /bucket/key?uploadId=ID}。
     */
    public PresignedUrl presignAbortMultipart(String accessKeyId, String secretKey, String region,
                                               String bucket, String key, String uploadId,
                                               String host, int port,
                                               Duration expires) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uploadId", uploadId);
        return build("DELETE", bucket, key, host, port, accessKeyId, secretKey, region,
                extra, expires);
    }

    /**
     * canonical host 形式:80/443 端口省略,其余拼 {@code :port},lowercase。
     * 与 wire 上 {@code Host} header 严格一致 —— 服务端验签时拿到 {@code headers["host"]}
     * 直接喂 canonical,任何字符差异都会触发 SIGNATURE_DOES_NOT_MATCH。
     */
    public static String canonicalHost(String host, int port) {
        String lower = host == null ? "" : host.toLowerCase();
        if (port == 80 || port == 443) {
            return lower;
        }
        return lower + ":" + port;
    }

    // ===================== 核心构建 =====================

    private PresignedUrl build(String method, String bucket, String key,
                                String host, int port,
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
        String canonicalHost = canonicalHost(host, port);
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

        // path-style URL
        StringBuilder url = new StringBuilder(128);
        url.append("http://").append(host.toLowerCase()).append(":").append(port);
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

        /** 完整 path-style URL,例如 {@code http://s3.internal:9000/photo/1.jpg?X-Amz-...&X-Amz-Signature=...} */
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
