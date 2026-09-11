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
import io.edap.s3.model.BucketCannedAcl;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.BucketStore;

import java.io.IOException;

/**
 * PUT /{bucket}?acl —— 设置桶的 canned ACL。客户端通过 {@code x-amz-acl} header
 * 传值(private / public-read / public-read-write)。
 *
 * <p>响应 200 + 空 body(AWS S3 PUT bucket?acl 也是 200)。
 *
 * <p>注意:此 handler <b>始终</b>要求签名 —— 即便桶 ACL 是 public-read-write,
 * 改 ACL 也必须授权(否则任何匿名用户都能改桶的 ACL 把锁开了)。
 * {@link io.edap.s3.auth.BucketPolicyAwareVerifier} 对这个 op 返回 false,
 * 不放行匿名。
 */
public final class PutBucketAclHandler extends AbstractS3Handler {

    private final BucketStore bucketStore;

    public PutBucketAclHandler(BucketStore bucketStore) {
        super(S3Operation.PUT_BUCKET_ACL);
        this.bucketStore = bucketStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String name = request.bucket();
        String aclHeader = request.header("x-amz-acl");
        try {
            if (!bucketStore.exists(name)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + name, "/" + name);
            }
            BucketCannedAcl acl = BucketCannedAcl.fromHeader(aclHeader);
            bucketStore.setCannedAcl(name, acl);
            return S3Response.empty(200);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "PutBucketAcl failed: " + e.getMessage());
        }
    }
}