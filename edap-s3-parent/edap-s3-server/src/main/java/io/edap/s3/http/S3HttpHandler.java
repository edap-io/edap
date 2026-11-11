/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.http;

import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.s3.auth.S3AuthVerifier;
import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S3 协议入口 —— 实现 edap 的 {@link HttpHandler},所有 S3 请求汇聚到这里:
 * <ol>
 *   <li>{@link S3RequestParser} 解析 HTTP → S3Request</li>
 *   <li>{@link S3AuthVerifier} 验签(失败 → 403 + XML error)</li>
 *   <li>{@link S3Dispatcher} 路由到具体 handler</li>
 *   <li>把 {@link S3Response} 写到 socket</li>
 * </ol>
 *
 * <p>edap HttpResponse 不暴露"任意 custom header"的写入口 —— {@link HttpResponse#header(String, String)}
 * 把 header 塞进一个 map 但永远不会写到 wire;{@code write(int, byte[])} 只能写
 * ContentLength / Date / Server 三个 builtin header。S3 协议又要求 ETag /
 * Last-Modified / x-amz-* 等 custom header 必须随响应发出。
 *
 * <p>绕开方式:用 {@link HttpResponse#setSimpleResponse(int, Map, io.edap.http.header.Header...)}
 * 写 status + 自定义 header + Date + Server + 结束 CRLF,然后直接写 body 字节到
 * {@link FastBuf},最后调 {@link HttpNioSession#writeToChannel(FastBuf)} flush。
 */
public final class S3HttpHandler implements HttpHandler {

    private final S3Dispatcher dispatcher;
    private final S3AuthVerifier authVerifier;
    private final S3RequestParser parser;

    public S3HttpHandler(S3Dispatcher dispatcher, S3AuthVerifier authVerifier) {
        this.dispatcher = dispatcher;
        this.authVerifier = authVerifier;
        this.parser = new S3RequestParser();
    }

    @Override
    public void handle(HttpRequest req, HttpResponse resp) throws IOException {
        try {
            S3Request s3Req = parser.parse(req);
            String auth = s3Req.header("authorization");
            boolean querySigned = s3Req.queryParams() != null
                    && s3Req.queryParams().containsKey("X-Amz-Signature");
            if (auth == null && !querySigned && !authVerifier.allowAnonymous()) {
                throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Missing Authorization header");
            }
            // allowAnonymous()=true 时也要走 verify()(包装型 verifier 内部会按
            // 桶 ACL 决定是否放行匿名请求);否则只对有签名的请求验签。
            if (auth != null || querySigned || authVerifier.allowAnonymous()) {
                authVerifier.verify(req.getMethod(),
                        s3Req.rawHttpRequest(),
                        s3Req.queryParams(),
                        s3Req.headers(),
                        s3Req);
            }
            S3Response s3Resp = dispatcher.dispatch(s3Req);
            writeSuccess(req, resp, s3Resp);
        } catch (S3Exception e) {
            writeError(req, resp, e);
        } catch (Exception e) {
            writeError(req, resp, new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void writeSuccess(HttpRequest req, HttpResponse resp, S3Response s3Resp) throws IOException {
        // 1. 组 custom headers(含 Content-Type / Content-Length 等)
        Map<String, String> customHeaders = new LinkedHashMap<>();
        if (s3Resp.contentType() != null) {
            customHeaders.put("Content-Type", s3Resp.contentType());
        }
        customHeaders.putAll(s3Resp.headers());

        byte[] body = null;
        if (s3Resp.bodyStream() != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream in = s3Resp.bodyStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
            body = baos.toByteArray();
        } else if (s3Resp.bodyBytes() != null) {
            body = s3Resp.bodyBytes();
        } else {
            body = new byte[0];
        }

        HttpResponseWriter.write(req, resp, s3Resp.status(), customHeaders, body);
    }

    private void writeError(HttpRequest req, HttpResponse resp, S3Exception ex) throws IOException {
        Map<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Content-Type", "application/xml");
        hdrs.put("x-amz-error-code", ex.code().s3Code);
        hdrs.put("x-amz-error-message", ex.getMessage() == null ? "" : ex.getMessage());
        byte[] body = XmlResponseBuilder.errorBody(ex);
        HttpResponseWriter.write(req, resp, ex.httpStatus(), hdrs, body);
    }
}
