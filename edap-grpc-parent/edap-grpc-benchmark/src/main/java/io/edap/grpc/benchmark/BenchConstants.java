/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.grpc.benchmark;

/**
 * 基准测试的常量 —— edap 侧与 google 侧共用。
 *
 * <p>这里的每一个值都会同时影响两个框架，保证对比口径一致。</p>
 */
public final class BenchConstants {

    /**
     * proto 里的服务全名。
     *
     * <p>edap 侧来自 {@code @ProtoService(serviceName=...)}，google 侧来自
     * {@code BenchServiceGrpc.SERVICE_NAME} —— 两边都是 {@code edap.bench.BenchService}，
     * 所以拼出来的 {@code :path} 完全一致，两边的 server / client 可以互相调用。</p>
     */
    public static final String SERVICE_NAME = "edap.bench.BenchService";

    /**
     * gRPC 的 {@code :path}。
     *
     * <p>edap 侧来自 {@code @ProtoGrpc(path=...)}，google 侧由
     * {@code SERVICE_NAME + '/' + "Unary"} 生成 —— 两边逐字符相同。</p>
     */
    public static final String PATH = "/edap.bench.BenchService/Unary";

    /** 客户端流式的 {@code :path} —— 多个请求消息，一个响应消息。 */
    public static final String PATH_CLIENT_STREAM = "/edap.bench.BenchService/ClientStream";

    /** 服务端流式的 {@code :path} —— 一个请求消息，多个响应消息。 */
    public static final String PATH_SERVER_STREAM = "/edap.bench.BenchService/ServerStream";

    /** 双向流式的 {@code :path} —— 多个请求消息，多个响应消息。 */
    public static final String PATH_BIDI = "/edap.bench.BenchService/Bidi";

    /** 服务端流式返回几条消息 —— 两边服务端必须一致，否则互操作断言没有意义。 */
    public static final int SERVER_STREAM_COUNT = 3;

    /** 双向流式的响应倍数 —— 响应值 = 请求值 × 该倍数（便于识别「回错了哪一条」）。 */
    public static final int BIDI_MULTIPLIER = 3;

    /**
     * 兼容性测试用的自定义元数据 key —— 客户端带上它，服务端原样回显到响应头。
     *
     * <p>gRPC 的元数据 key 必须是小写 ASCII。</p>
     */
    public static final String ECHO_HEADER = "x-bench-echo";

    /** 服务端给响应 name 加的前缀 —— 两边必须一致，否则互通断言没有意义。 */
    public static final String NAME_PREFIX = "bench:";

    /** 请求里的 name。 */
    public static final String REQUEST_NAME = "edap-grpc-benchmark";

    /** 请求里的 value（服务端会 ×2 后回显）。 */
    public static final int REQUEST_VALUE = 7;

    /** 负载矩阵：blob 的字节数（0 测纯协议开销，64K 逼近默认 64KB 流控窗口）。 */
    public static final int[] BLOB_SIZES = {0, 1024, 16 * 1024, 64 * 1024};

    /** 并发矩阵：共享一条连接的 worker 线程数。 */
    public static final int[] CONCURRENCIES = {1, 8, 32};

    private BenchConstants() {
    }
}
