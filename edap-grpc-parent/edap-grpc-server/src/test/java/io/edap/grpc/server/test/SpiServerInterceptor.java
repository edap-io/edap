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

package io.edap.grpc.server.test;

import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcServerInterceptor;

/**
 * SPI 装载测试用的服务端拦截器（测试夹具）。
 *
 * <p>注册在 {@code src/test/resources/META-INF/services/io.edap.grpc.GrpcServerInterceptor}。
 * 它往响应头里写一个标记头 ⇒ 测试能断言「SPI 装出来的拦截器真的跑过了」。</p>
 */
public class SpiServerInterceptor implements GrpcServerInterceptor {

    /** 标记头的名字 */
    public static final String MARKER_HEADER = "x-spi-interceptor";

    /** 标记头的值 */
    public static final String MARKER_VALUE = "on";

    @Override
    public void beforeCall(GrpcCall call) {
        call.responseMetadata().set(MARKER_HEADER, MARKER_VALUE);
    }
}
