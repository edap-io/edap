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
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import io.edap.s3.auth.AccessKeyResolver;
import io.edap.s3.auth.BucketPolicyAwareVerifier;
import io.edap.s3.auth.S3AuthVerifier;
import io.edap.s3.auth.SigV4Verifier;
import io.edap.s3.op.S3OperationHandler;
import io.edap.s3.op.handler.AbortMultipartHandler;
import io.edap.s3.op.handler.CompleteMultipartHandler;
import io.edap.s3.op.handler.InitiateMultipartHandler;
import io.edap.s3.op.handler.ListMultipartUploadsHandler;
import io.edap.s3.op.handler.ListPartsHandler;
import io.edap.s3.op.handler.PutBucketAclHandler;
import io.edap.s3.op.handler.UploadPartHandler;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;

/**
 * S3 服务端启动 builder —— 把 edap 的 {@link HttpServerBuilder} 包一层:
 * <ol>
 *   <li>注册 {@link S3Dispatcher} + 全部 {@link S3OperationHandler}</li>
 *   <li>构造 {@link S3HttpHandler} 作为入口</li>
 *   <li>用 {@code serve("/*", handler)} postfix wildcard 覆盖所有 S3 路径</li>
 * </ol>
 *
 * <p>用法:
 * <pre>{@code
 *   HttpServer server = new S3ServerBuilder()
 *       .bucketStore(new InMemoryBucketStore())
 *       .objectStore(new InMemoryObjectStore())
 *       .accessKeyResolver(new ConfigAccessKeyResolver.fromProperties(...))
 *       .register(new ListBucketsHandler(...))
 *       .register(new CreateBucketHandler(...))
 *       ...
 *       .listen(8080)
 *       .build();
 *   server.start();
 * }</pre>
 *
 * <p>桶级 ACL:只要 {@link #bucketStore} 已设置且未显式提供 {@link #authVerifier},
 * build() 会自动用 {@link BucketPolicyAwareVerifier} 包装底层 SigV4 verifier,
 * 使桶 ACL = public-read / public-read-write 时允许匿名读。如果调用方自己
 * 注入了 {@code authVerifier},就以调用方为准(可禁用 ACL 自动放行)。
 */
public final class S3ServerBuilder {

    private final HttpServerBuilder httpBuilder = new HttpServerBuilder();
    private final S3Dispatcher dispatcher = new S3Dispatcher();

    private BucketStore bucketStore;
    private ObjectStore objectStore;
    private MultipartStore multipartStore;
    private AccessKeyResolver accessKeyResolver;
    private S3AuthVerifier authVerifier;

    // ===================== 注入 =====================

    public S3ServerBuilder bucketStore(BucketStore bucketStore) {
        this.bucketStore = bucketStore;
        return this;
    }

    public S3ServerBuilder objectStore(ObjectStore objectStore) {
        this.objectStore = objectStore;
        return this;
    }

    public S3ServerBuilder multipartStore(MultipartStore multipartStore) {
        this.multipartStore = multipartStore;
        return this;
    }

    public S3ServerBuilder accessKeyResolver(AccessKeyResolver resolver) {
        this.accessKeyResolver = resolver;
        return this;
    }

    public S3ServerBuilder authVerifier(S3AuthVerifier verifier) {
        this.authVerifier = verifier;
        return this;
    }

    public S3ServerBuilder register(S3OperationHandler<?> handler) {
        dispatcher.register(handler);
        return this;
    }

    /**
     * 一次挂 6 个 multipart handler —— 调用方就不用一个一个 .register(...)。
     * 必须先 {@link #multipartStore(MultipartStore)} 设好 store。
     */
    public S3ServerBuilder registerMultipartHandlers() {
        if (multipartStore == null) {
            throw new IllegalStateException(
                    "multipartStore must be set before registerMultipartHandlers()");
        }
        register(new InitiateMultipartHandler(multipartStore));
        register(new UploadPartHandler(multipartStore));
        register(new CompleteMultipartHandler(multipartStore));
        register(new AbortMultipartHandler(multipartStore));
        register(new ListMultipartUploadsHandler(multipartStore));
        register(new ListPartsHandler(multipartStore));
        return this;
    }

    /**
     * 注册 {@link PutBucketAclHandler} —— 必须先 {@link #bucketStore(BucketStore)}
     * 设好 store。
     */
    public S3ServerBuilder registerBucketAclHandler() {
        if (bucketStore == null) {
            throw new IllegalStateException(
                    "bucketStore must be set before registerBucketAclHandler()");
        }
        register(new PutBucketAclHandler(bucketStore));
        return this;
    }

    // ===================== HttpServerBuilder 直通 =====================

    public S3ServerBuilder listen(int... ports) {
        httpBuilder.listen(ports);
        return this;
    }

    public S3ServerBuilder listen(String address, int port) {
        httpBuilder.listen(address, port);
        return this;
    }

    public HttpServer build() {
        if (authVerifier == null) {
            if (accessKeyResolver == null) {
                throw new IllegalStateException(
                        "Either authVerifier or accessKeyResolver must be set");
            }
            S3AuthVerifier sigV4 = new SigV4Verifier(accessKeyResolver);
            // 如果调用方提供了 bucketStore,默认用 BucketPolicyAwareVerifier 包装,
            // 让桶级 canned ACL 生效(public-read / public-read-write 时允许匿名读)。
            authVerifier = bucketStore != null
                    ? new BucketPolicyAwareVerifier(sigV4, bucketStore)
                    : sigV4;
        }
        S3HttpHandler handler = new S3HttpHandler(dispatcher, authVerifier);
        // postfix wildcard 一条规则覆盖所有 S3 路径
        // 每个 HTTP method 单独注册一遍(addPathHandler 按 method 过滤)
        httpBuilder.get("/*", handler);
        httpBuilder.put("/*", handler);
        httpBuilder.post("/*", handler);
        httpBuilder.delete("/*", handler);
        httpBuilder.head("/*", handler);
        return httpBuilder.build();
    }
}