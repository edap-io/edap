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
import io.edap.image.ImageFormat;
import io.edap.image.ThumbSpec;
import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.store.DerivedObjectStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 缩略图 HTTP 入口 —— 处理 {@code /thumb/*} 路径。
 *
 * <p><b>URL 形态</b>：
 * <pre>
 *   GET /thumb/{bucket}/{key}?w=200[&amp;h=150][&amp;f=webp][&amp;q=80]
 * </pre>
 * 路径部分跟 S3 的 path-style 一致：第一段是桶，其余是对象 key（key 可以含 "/"）。
 *
 * <p><b>响应码</b>：
 * <ul>
 *   <li><b>200</b> —— 缓存命中，body 是图片字节；</li>
 *   <li><b>202</b> —— 未命中，已提交异步派生，客户端按 {@code Retry-After} 重试；</li>
 *   <li><b>400</b> —— query 参数非法；</li>
 *   <li><b>404</b> —— 源对象不存在；</li>
 *   <li><b>503</b> —— 引擎不可用或派生队列已满。</li>
 * </ul>
 *
 * <p><b>为什么未命中是 202 而不是同步算</b>：HTTP handler 跑在 NIO selector 线程上
 * （{@code DisruptorReadDispatcher → Http1Connection}），缩略图是百毫秒级的像素运算 +
 * 子进程，做在请求线程上会把整个连接层卡住。所以未命中只提交任务立刻返回，
 * 客户端轮询到 200 为止。</p>
 *
 * <p><b>路径冲突</b>：{@code /thumb/*} 比 S3 的 {@code /*} 长，路由时优先匹配
 * ⇒ 名为 {@code thumb} 的桶的<b>对象</b>路径（{@code /thumb/xxx}）会被本处理器接管。
 * 桶级路径（{@code /thumb}、{@code /thumb/}）不受影响。</p>
 */
public final class ThumbHttpHandler implements HttpHandler {

    /** 缩略图路径前缀（含结尾的 "/"）。 */
    public static final String PATH_PREFIX = "/thumb/";

    /** 目标宽度上限 —— 防止离谱的 query 参数把引擎拉爆。 */
    public static final int MAX_WIDTH = 4096;

    /** 未命中时告诉客户端隔多久重试（秒）。 */
    public static final int RETRY_AFTER_SECONDS = 1;

    private static final byte[] PENDING_BODY =
            "缩略图正在生成，请稍后重试".getBytes(StandardCharsets.UTF_8);

    private final DerivedObjectStore store;

    public ThumbHttpHandler(DerivedObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store 不能为 null");
        }
        this.store = store;
    }

    @Override
    public void handle(HttpRequest req, HttpResponse resp) throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(req.getMethod());
        try {
            ThumbSpec spec = parseSpec(req);
            String[] target = parseTarget(req.getPath());

            byte[] content = store.getDerived(target[0], target[1], spec);
            if (content == null) {
                writePending(req, resp, head);
                return;
            }
            writeImage(req, resp, spec.format(), content, head);
        } catch (S3Exception e) {
            writeError(req, resp, head, e.httpStatus(), e.getMessage());
        } catch (Exception e) {
            writeError(req, resp, head, 500,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ================= 响应 =================

    private void writeImage(HttpRequest req, HttpResponse resp, ImageFormat format,
                            byte[] content, boolean head) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType(format));
        // 缩略图 URL 里带了规格，源图换了之后同一个 URL 的内容会变 ——
        // 给一个短 TTL，客户端最多用 5 分钟前的旧缩略图。
        headers.put("Cache-Control", "public, max-age=300");
        headers.put("Content-Length", String.valueOf(content.length));
        HttpResponseWriter.write(req, resp, 200, headers, head ? new byte[0] : content);
    }

    private void writePending(HttpRequest req, HttpResponse resp, boolean head) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        headers.put("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        headers.put("Content-Length", String.valueOf(PENDING_BODY.length));
        HttpResponseWriter.write(req, resp, 202, headers, head ? new byte[0] : PENDING_BODY);
    }

    private void writeError(HttpRequest req, HttpResponse resp, boolean head, int status,
                            String message) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        byte[] body = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        HttpResponseWriter.write(req, resp, status, headers, head ? new byte[0] : body);
    }

    private static String contentType(ImageFormat format) {
        return format == ImageFormat.WEBP ? "image/webp" : "image/jpeg";
    }

    // ================= 解析 =================

    /**
     * 拆路径 —— {@code /thumb/<bucket>/<key...>}。
     *
     * <p>路径已被 HTTP 层 URL decode 过，这里不再二次解码（否则 key 里的字面
     * {@code %} 会被吃掉）。</p>
     */
    static String[] parseTarget(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY, "不是缩略图路径: " + path);
        }
        String rest = path.substring(PATH_PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "路径缺少桶名或对象 key: " + path);
        }
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        if (key.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "路径缺少对象 key: " + path);
        }
        return new String[]{bucket, key};
    }

    /**
     * 解析 query 成 {@link ThumbSpec}。
     *
     * <ul>
     *   <li>{@code w} —— 目标宽度，必填；</li>
     *   <li>{@code h} —— 目标高度，可空；</li>
     *   <li>{@code f} —— 输出格式，{@code jpeg}/{@code jpg}/{@code webp}，默认 jpeg；</li>
     *   <li>{@code q} —— 编码质量 1~100，默认 {@link ThumbSpec#DEFAULT_QUALITY}。</li>
     * </ul>
     */
    static ThumbSpec parseSpec(HttpRequest req) {
        String w = req.getParameter("w");
        if (w == null || w.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "缺少 w 参数（目标宽度）");
        }
        int width = parseInt(w, "w");
        if (width <= 0 || width > MAX_WIDTH) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "w 必须在 1~" + MAX_WIDTH + " 之间: " + width);
        }

        String h = req.getParameter("h");
        Integer height = null;
        if (h != null && !h.isEmpty()) {
            height = parseInt(h, "h");
            if (height <= 0) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "h 必须大于 0: " + height);
            }
        }

        ImageFormat format = ImageFormat.JPEG;
        String f = req.getParameter("f");
        if (f != null && !f.isEmpty()) {
            switch (f.toLowerCase(Locale.ROOT)) {
                case "jpeg":
                case "jpg":
                    format = ImageFormat.JPEG;
                    break;
                case "webp":
                    format = ImageFormat.WEBP;
                    break;
                default:
                    throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                            "不支持的输出格式: " + f);
            }
        }

        int quality = ThumbSpec.DEFAULT_QUALITY;
        String q = req.getParameter("q");
        if (q != null && !q.isEmpty()) {
            quality = parseInt(q, "q");
            if (quality < 1 || quality > 100) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "q 必须在 1~100 之间: " + quality);
            }
        }

        return ThumbSpec.of(width, height, format, quality);
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    name + " 不是合法整数: " + raw);
        }
    }
}
