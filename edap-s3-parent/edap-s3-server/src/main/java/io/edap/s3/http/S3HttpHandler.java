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

import io.edap.buffer.FastBuf;
import io.edap.http.HttpHandler;
import io.edap.http.HttpNioSession;
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
            if (auth != null || querySigned) {
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

        if (!customHeaders.containsKey("Content-Length")) {
            customHeaders.put("Content-Length", String.valueOf(body.length));
        }

        resp.setSimpleResponse(s3Resp.status(), customHeaders);
        flushWithBody(req, resp, body);
    }

    private void writeError(HttpRequest req, HttpResponse resp, S3Exception ex) throws IOException {
        Map<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Content-Type", "application/xml");
        hdrs.put("x-amz-error-code", ex.code().s3Code);
        hdrs.put("x-amz-error-message", ex.getMessage() == null ? "" : ex.getMessage());
        byte[] body = XmlResponseBuilder.errorBody(ex);
        hdrs.put("Content-Length", String.valueOf(body.length));
        resp.setSimpleResponse(ex.httpStatus(), hdrs);
        flushWithBody(req, resp, body);
    }

    /**
     * setSimpleResponse 已经把 status + headers + 结束 CRLF 写进 buf;
     * 现在把 body 直接 append 到 buf,然后调 nioSession.writeToChannel(buf)
     * 强制 flush 到 socket。
     *
     * <p>修复:原循环在 nio.writeToChannel 部分写返回 false 时,
     * {@code buf.wpos(buf.address())} 会把 wpos 重置到 address,但此时 rpos
     * 还在中间位置 —— 紧接着的 {@code buf.write(...)} 用新 body 数据覆盖掉
     * [address, rpos) 的已发字节和 [rpos, wpos) 的待发字节,包括头部和小 body
     * 首段。客户端在 parseHTTPHeader 处永久阻塞。修复:循环内不再盲目重置 wpos,
     * 而是把待发字节紧凑到 buf 头部,保证下次 writeToChannel 把完整内容送出。
     */
    private void flushWithBody(HttpRequest req, HttpResponse resp, byte[] body) throws IOException {
        FastBuf buf = resp.getBuf();
        if (buf == null) return;
        HttpNioSession nio = req.getHttpNioSession();
        int len = body == null ? 0 : body.length;
        int wlen = 0;
        if (len > 0) {
            int first = Math.min(len, buf.writeRemain());
            wlen = buf.write(body, 0, first);
        }
        while (wlen < len || buf.rpos() < buf.wpos()) {
            if (nio != null) {
                nio.writeToChannel(buf);
            }
            // 部分写?把 [rpos, wpos) 的待发内容紧凑到 buf 头部,
            // 避免下次 write 把待发字节覆盖掉。
            if (buf.rpos() > buf.address()) {
                long pending = buf.wpos() - buf.rpos();
                if (pending > 0) {
                    io.edap.util.UnsafeUtil.copyMemory(buf.rpos(), buf.address(), pending);
                }
                buf.wpos(buf.address() + pending);
                buf.rpos(buf.address());
            }
            // 填新 body 到 buf(只在 buf 空时才写)
            if (buf.rpos() == buf.wpos() && wlen < len) {
                buf.wpos(buf.address());
                buf.rpos(buf.address());
                int chunk = Math.min(len - wlen, buf.writeRemain());
                wlen += buf.write(body, wlen, chunk);
            }
        }
        // 收尾 flush(可能还有零碎字节)
        if (nio != null) nio.writeToChannel(buf);
    }
}
