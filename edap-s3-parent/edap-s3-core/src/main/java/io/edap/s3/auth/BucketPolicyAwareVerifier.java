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

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.BucketCannedAcl;
import io.edap.s3.model.S3Request;
import io.edap.s3.store.BucketStore;

import java.io.IOException;
import java.util.Map;

/**
 * 桶级 ACL 感知的鉴权包装器 —— 包住 {@link SigV4Verifier} 实现
 * "桶 public-read / public-read-write 时允许匿名读,其他场景仍必须签名"。
 *
 * <p>行为:
 * <ul>
 *   <li>请求带 {@code Authorization} / presign query → 走底层 SigV4 严格验签</li>
 *   <li>请求未签名 → 看桶 ACL:
 *       <ul>
 *         <li>桶 ACL = {@link BucketCannedAcl#PUBLIC_READ} 且 op 允许匿名 → 放行</li>
 *         <li>桶 ACL = {@link BucketCannedAcl#PUBLIC_READ_WRITE} 且 op 允许匿名 → 放行</li>
 *         <li>其他 → 抛 {@link S3Exception} ACCESS_DENIED</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>返回 {@link #allowAnonymous()} = true;{@code S3HttpHandler} 据此不再
 * "未签 + 不放行" 提前 reject。
 *
 * <p>不属于桶级匿名允许的 op(桶级管理类操作,任何 ACL 下都要求签名):
 * <ul>
 *   <li>{@link io.edap.s3.op.S3Operation#LIST_BUCKETS} —— 跨桶列表</li>
 *   <li>{@link io.edap.s3.op.S3Operation#CREATE_BUCKET} / {@code DELETE_BUCKET} /
 *       {@code HEAD_BUCKET} —— 桶元信息</li>
 *   <li>{@link io.edap.s3.op.S3Operation#PUT_BUCKET_ACL} —— 改 ACL 本身</li>
 * </ul>
 */
public final class BucketPolicyAwareVerifier implements S3AuthVerifier {

    private final S3AuthVerifier delegate;
    private final BucketStore bucketStore;

    public BucketPolicyAwareVerifier(S3AuthVerifier delegate, BucketStore bucketStore) {
        this.delegate = delegate;
        this.bucketStore = bucketStore;
    }

    @Override
    public boolean allowAnonymous() {
        return true;
    }

    @Override
    public void verify(String httpMethod,
                       String path,
                       Map<String, String> queryParams,
                       Map<String, String> headers,
                       S3Request parsed) throws Exception {
        // 1. 带签名 → 严格验签(委托底层)
        boolean hasAuthHeader = headers != null && headers.get("authorization") != null;
        boolean querySigned = queryParams != null && queryParams.containsKey("X-Amz-Signature");
        if (hasAuthHeader || querySigned) {
            delegate.verify(httpMethod, path, queryParams, headers, parsed);
            return;
        }

        // 2. 未签名 —— 看桶 ACL 是否放行
        String bucket = parsed.bucket();
        if (bucket == null) {
            // LIST_BUCKETS 等无 bucket 的 op —— 永远要求签名
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Anonymous access not allowed for " + parsed.operation());
        }
        BucketCannedAcl acl;
        try {
            acl = bucketStore.getCannedAcl(bucket);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "Failed to read bucket ACL: " + e.getMessage());
        }
        if (!acl.allowsAnonymous(parsed.operation())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Bucket " + bucket + " ACL=" + acl
                            + " does not allow anonymous " + parsed.operation());
        }
    }
}