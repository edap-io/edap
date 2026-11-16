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
import io.edap.http.HttpNioSession;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应写出 —— status + 自定义 header + body 字节。
 *
 * <p>为什么不用 {@link HttpResponse#write(int, byte[])}：那条路径在写 body 时
 * 遇到 socket 缓冲满（{@code writeToChannel} 返回 false）就直接放弃并截断
 * （{@code Http1ResponseEncoder.write0} 只打一条 warn）⇒ 客户端按 Content-Length
 * 等一个永远等不到的尾巴。而 S3 的对象体、缩略图都可能大于线程写缓冲（32 KB），
 * 所以这里走「写进 FastBuf → 推进 channel → 缓冲空了就补写」的循环，
 * 遇到 EAGAIN 就短暂 sleep 重试，保证 body 完整送达。</p>
 */
public final class HttpResponseWriter {

    private HttpResponseWriter() {
    }

    /**
     * 写一个带 body 的响应。
     *
     * @param req     原请求（拿 NIO session 用）
     * @param resp    响应
     * @param status  HTTP 状态码
     * @param headers 自定义 header（{@code Content-Length} 缺省按 body 长度补）
     * @param body    body 字节，可为 null（视作空 body）
     * @throws IOException 写 socket 失败
     */
    public static void write(HttpRequest req, HttpResponse resp, int status,
                             Map<String, String> headers, byte[] body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        Map<String, String> hdrs = new LinkedHashMap<>();
        if (headers != null) {
            hdrs.putAll(headers);
        }
        hdrs.putIfAbsent("Content-Length", String.valueOf(payload.length));
        resp.setSimpleResponse(status, hdrs);
        flushWithBody(req, resp, payload);
    }

    /**
     * {@code setSimpleResponse} 已经把 status + headers + 结束 CRLF 写进 buf;
     * 现在把 body 直接 append 到 buf,然后调 nioSession.writeToChannel(buf)
     * 强制 flush 到 socket。
     *
     * <p>修复:原循环在 nio.writeToChannel 部分写返回 false 时,
     * {@code buf.wpos(buf.address())} 会把 wpos 重置到 address,但此时 rpos
     * 还在中间位置 —— 紧接着的 {@code buf.write(...)} 用新 body 数据覆盖掉
     * [address, rpos) 的已发字节和 [rpos, wpos) 的待发字节,包括头部和小 body
     * 首段。客户端在 parseHTTPHeader 处永久阻塞。修复:循环内不再盲目重置 wpos,
     * 而是把待发字节紧凑到 buf 头部,保证下次 writeToChannel 把完整内容送出。</p>
     */
    private static void flushWithBody(HttpRequest req, HttpResponse resp, byte[] body)
            throws IOException {
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
                long oldRpos = buf.rpos();
                boolean complete = nio.writeToChannel(buf);
                if (!complete && buf.rpos() == oldRpos) {
                    // writeToChannel 返回 false 且 rpos 没前进 = EAGAIN(socket send buffer 满)。
                    // 这里不能 break —— handler 局部变量 body[wlen..] 的字节还没进过 buf,
                    // break 后就丢了,客户端收不到完整响应(Content-Length 写的是完整长度)。
                    // 短暂 sleep 给 socket drain 机会,然后重试。
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while flushing body", ie);
                    }
                    continue;
                }
                // complete 或 partial:继续
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
