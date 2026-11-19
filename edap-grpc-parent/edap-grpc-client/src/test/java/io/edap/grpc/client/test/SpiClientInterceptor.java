/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.grpc.client.test;

import io.edap.grpc.client.GrpcClientCall;
import io.edap.grpc.client.GrpcClientInterceptor;
import io.edap.rpc.RpcContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SPI 装载测试用的客户端拦截器（测试夹具）。
 *
 * <p>注册在 {@code src/test/resources/META-INF/services/io.edap.grpc.client.GrpcClientInterceptor}。
 * 它只做计数，不改请求头 ⇒ 不会影响同模块的其它测试。</p>
 */
public class SpiClientInterceptor implements GrpcClientInterceptor {

    /** beforeCall 被调用的次数 */
    public static final AtomicInteger BEFORE_CALLS = new AtomicInteger();

    /** afterCall 被调用的次数 */
    public static final AtomicInteger AFTER_CALLS = new AtomicInteger();

    @Override
    public void beforeCall(String path, Map<String, String> headers, RpcContext context) {
        BEFORE_CALLS.incrementAndGet();
    }

    @Override
    public void afterCall(GrpcClientCall call, Throwable error) {
        AFTER_CALLS.incrementAndGet();
    }
}
