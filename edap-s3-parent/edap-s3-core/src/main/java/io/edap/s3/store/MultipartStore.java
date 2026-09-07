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

import io.edap.s3.model.CompletedObject;
import io.edap.s3.model.CompletedPart;
import io.edap.s3.model.MultipartPart;
import io.edap.s3.model.MultipartUpload;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * S3 Multipart Upload 的存储 SPI —— 跟 {@link ObjectStore} 平级,
 * 但生命周期不同:Initiate → UploadPart*N → Complete(或 Abort)。
 *
 * <p>实现要点:
 * <ul>
 *   <li>{@link #initiate} 生成 uploadId 并返回,handler 用它构造响应 XML</li>
 *   <li>{@link #uploadPart} 把 part bytes 存起来,返回 ETag(handler 把它放进
 *       {@code ETag} response header;客户端在 CompleteMultipartUpload 请求
 *       XML 里带回这个 ETag 用于校验)</li>
 *   <li>{@link #complete} 把 parts 按 partNumber 升序拼成完整对象,塞进底层
 *       {@link ObjectStore},返回复合 ETag({@code <md5>-<count>})</li>
 *   <li>{@link #abort} 立即丢弃整个 upload(包括所有 part bytes)</li>
 * </ul>
 *
 * <p>错误语义:
 * <ul>
 *   <li>uploadId 不存在 → {@link io.edap.s3.error.S3Exception}
 *       {@code NO_SUCH_UPLOAD}(404)</li>
 *   <li>桶不存在 → {@code NO_SUCH_BUCKET}(404)</li>
 *   <li>partNumber 非法(<=0 或 >10000)→ {@code INVALID_ARGUMENT}(400)</li>
 * </ul>
 *
 * <p>Phase 2 仅 {@link io.edap.s3.store.mem.InMemoryMultipartStore};
 * {@code DiskMultipartStore} 留 Phase 3。
 */
public interface MultipartStore extends AutoCloseable {

    /**
     * 创建一次 multipart upload。返回 uploadId(UUID 形式)。
     *
     * @param bucket       桶名(必须已存在)
     * @param key          对象 key(可包含多段路径)
     * @param contentType  客户端声明的 Content-Type(可空)
     * @param userMetadata 客户端传的 {@code x-amz-meta-*} headers,可空
     */
    String initiate(String bucket,
                    String key,
                    String contentType,
                    Map<String, String> userMetadata) throws IOException;

    /**
     * 上传一个 part。同 partNumber 多次上传 → last-write-wins(S3 标准)。
     *
     * @param uploadId       上传会话 id
     * @param partNumber     1..10000
     * @param body           part body 流(handler 负责 close)
     * @param contentLength  part 字节数(>=0);-1 表示 chunked(暂不支持)
     * @return 该 part 的 ETag(MD5 hex,无引号)
     */
    String uploadPart(String uploadId,
                      int partNumber,
                      InputStream body,
                      long contentLength) throws IOException;

    /**
     * 完成 multipart upload —— 按 {@code parts} 里的顺序组装最终对象,
     * 持久化到 {@link ObjectStore},返回最终元数据。
     *
     * <p>调用后该 uploadId 从 store 中移除,后续任何 uploadPart / abort / listParts
     * 都得到 {@code NO_SUCH_UPLOAD}。
     *
     * @param uploadId  上传会话 id
     * @param parts     客户端声明的 part 列表(按 partNumber 升序;
     *                  etag 必须跟 uploadPart 当时返回的一致)
     */
    CompletedObject complete(String uploadId, List<CompletedPart> parts) throws IOException;

    /**
     * 中止 multipart upload —— 立即释放所有 part bytes。
     * uploadId 不存在 → 抛 {@code NO_SUCH_UPLOAD}。
     */
    void abort(String uploadId) throws IOException;

    /**
     * 列出指定桶内进行中的 multipart uploads。
     * Phase 2 不支持 prefix/delimiter/maxKeys 分页。
     */
    List<MultipartUpload> listUploads(String bucket) throws IOException;

    /**
     * 列出指定 upload 已上传的所有 parts(按 partNumber 升序)。
     */
    List<MultipartPart> listParts(String uploadId) throws IOException;

    @Override
    void close() throws IOException;
}
