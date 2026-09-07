/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store;

import io.edap.s3.model.GetStream;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;

import java.io.IOException;
import java.util.List;

/**
 * S3 对象存储 SPI —— 桶级和对象级的元数据 / 内容操作分离:
 * <ul>
 *   <li>桶存在性 / 创建 / 删除 → {@link BucketStore}</li>
 *   <li>对象读写 / 列表 → {@code ObjectStore}(本接口)</li>
 * </ul>
 *
 * <p>所有方法在桶不存在时,put/delete/list 应抛
 * {@link io.edap.s3.error.S3Exception} NO_SUCH_BUCKET;get/head 应抛
 * NO_SUCH_KEY(桶或 key 不存在)。
 *
 * <p>Range: {@link #get} 的 {@code rangeStart} / {@code rangeEnd} 用 -1 表示
 * "未指定",应读全量返回;若指定则按 S3 规范的"含两端"语义切字节段。
 */
public interface ObjectStore extends AutoCloseable {

    /**
     * 写入对象。{@link PutStream#contentLength()} 必须非负,backend 据此决定
     * 是预分配还是流式写。写入完成后 backend 应计算 ETag(MD5 hex)并返回
     * 写入后的元数据(用于响应 ETag header)。
     */
    ObjectMeta put(String bucket, String key, PutStream body) throws IOException;

    /**
     * 读取对象。{@code rangeStart == 0 && rangeEnd == -1} 表示全量;
     * 否则返回对应字节段。返回的 {@link GetStream#content()} 必须 close,
     * 由调用方(GetObjectHandler)负责。
     */
    GetStream get(String bucket, String key, long rangeStart, long rangeEnd) throws IOException;

    /**
     * 取对象元数据(不读 body)。
     */
    ObjectMeta head(String bucket, String key) throws IOException;

    /**
     * 删除对象(对象不存在时不报错 —— S3 DELETE 是 idempotent)。
     */
    void delete(String bucket, String key) throws IOException;

    /**
     * 列出桶内对象。{@code prefix} 可选,null/"" 表示全部;{@code delimiter}
     * 常见为 "/",用于 CommonPrefixes 聚合;{@code maxKeys} 客户端建议值,
     * backend 可自行截断;{@code continuationToken} 用于分页续传。
     *
     * <p>Phase 1 简化:不实现 CommonPrefixes 分组,按 prefix 全量返回。
     */
    List<ObjectMeta> list(String bucket,
                          String prefix,
                          String delimiter,
                          int maxKeys,
                          String continuationToken) throws IOException;

    /**
     * 桶是否为空。DeleteBucketHandler 用此判断 BucketNotEmpty 错误。
     */
    boolean isEmpty(String bucket) throws IOException;

    @Override
    void close() throws IOException;
}
