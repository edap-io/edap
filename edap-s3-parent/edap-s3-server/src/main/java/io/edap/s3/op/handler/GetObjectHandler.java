/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.op.handler;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.GetStream;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * GET /{bucket}/{key...} —— 下载对象。支持 Range 头(单段),如 "bytes=0-1023"。
 * body 由 {@link GetStream#content()} 提供,handler 直接包到 S3Response.stream。
 *
 * <p>edap 无 chunked encoding,Phase 1 整段缓冲到 byte[] 由 S3HttpHandler 写出。
 */
public final class GetObjectHandler extends AbstractS3Handler {

    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                    .withLocale(Locale.US)
                    .withZone(ZoneOffset.UTC);

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;

    public GetObjectHandler(BucketStore bucketStore, ObjectStore objectStore) {
        super(S3Operation.GET_OBJECT);
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String bucket = request.bucket();
        String key = request.key();
        try {
            if (!bucketStore.exists(bucket)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + bucket, "/" + bucket);
            }
            long[] range = parseRange(request.header("range"),
                    objectStore.head(bucket, key).size());
            GetStream stream = objectStore.get(bucket, key, range[0], range[1]);
            ObjectMeta meta = stream.meta();
            String respContentType = meta.contentType() == null
                    ? "application/octet-stream" : meta.contentType();
            long length = stream.isPartial()
                    ? (stream.rangeEnd() - stream.rangeStart() + 1)
                    : meta.size();
            S3Response resp = (stream.isPartial()
                    ? S3Response.streamPartial(respContentType, stream.content(), length)
                    : S3Response.stream(respContentType, stream.content(), length));
            return resp
                    .withHeader("ETag", "\"" + meta.etag() + "\"")
                    .withHeader("Last-Modified", RFC1123.format(meta.lastModified()))
                    .withHeader("Accept-Ranges", "bytes")
                    .withHeader("Content-Length", String.valueOf(length));
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "GetObject failed: " + e.getMessage());
        }
    }

    /**
     * 解析 Range 头(支持单段,如 {@code bytes=0-1023} / {@code bytes=1024-} /
     * {@code bytes=-500} 即最后 500 字节)。返回 [start, end](-1 = 未指定 end)。
     */
    static long[] parseRange(String header, long totalSize) {
        if (header == null || header.isEmpty() || totalSize <= 0) {
            return new long[]{0, -1};
        }
        if (!header.startsWith("bytes=")) {
            return new long[]{0, -1};
        }
        String spec = header.substring("bytes=".length());
        int dash = spec.indexOf('-');
        if (dash < 0) return new long[]{0, -1};
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startStr.isEmpty()) {
                // suffix range: bytes=-N → 最后 N 字节
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) return new long[]{0, -1};
                start = Math.max(0, totalSize - suffix);
                end = totalSize - 1;
            } else {
                start = Long.parseLong(startStr);
                end = endStr.isEmpty() ? totalSize - 1 : Long.parseLong(endStr);
                if (end >= totalSize) end = totalSize - 1;
            }
            if (start < 0 || start > end || start >= totalSize) {
                return new long[]{0, -1};
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return new long[]{0, -1};
        }
    }
}