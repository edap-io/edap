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
import io.edap.s3.auth.S3AuthVerifier;
import io.edap.s3.auth.SigV4Verifier;
import io.edap.s3.op.S3OperationHandler;
import io.edap.s3.store.BucketStore;
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
 */
public final class S3ServerBuilder {

    private final HttpServerBuilder httpBuilder = new HttpServerBuilder();
    private final S3Dispatcher dispatcher = new S3Dispatcher();

    private BucketStore bucketStore;
    private ObjectStore objectStore;
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
            authVerifier = new SigV4Verifier(accessKeyResolver);
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