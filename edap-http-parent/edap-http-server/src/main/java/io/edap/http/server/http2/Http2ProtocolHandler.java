/*
 * Copyright 2023 The edap Project
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

package io.edap.http.server.http2;

import java.io.IOException;

/**
 * HTTP/2 之上的协议处理器 —— 接管一条流的请求体与响应。
 *
 * <p><b>生命周期</b>：</p>
 * <ol>
 *   <li>{@link #onHeaders(Http2Stream)} —— header 收齐后调用（<b>不等 body</b>）；
 *       响应可以在这里就开始写（gRPC 的服务端流式就是这么干的）。</li>
 *   <li>{@link #onBody(Http2Stream, byte[], int, int)} —— 每收到一块 DATA 调用一次
 *       （仅当 {@link #takesStreamingBody()} 为 true 时）；</li>
 *   <li>{@link #onEnd(Http2Stream)} —— 对端半关闭（END_STREAM）时调用一次。</li>
 * </ol>
 *
 * <p><b>响应</b>：处理器通过 {@link Http2Stream#getResponse()} 写响应 ——
 * {@code begin()} 写响应头、{@code chunk(...)} 写 body、{@code end()} 写 trailers
 * 并结束这条流。</p>
 */
public interface Http2ProtocolHandler {

    /**
     * 本处理器是否接管请求体。
     *
     * <p>返回 true 时请求体<b>不会</b>累积到 {@code Http2Stream} 的 body 缓冲里，
     * 而是逐块转发给 {@link #onBody} —— gRPC 的请求是「消息流」，
     * 必须增量处理。</p>
     */
    boolean takesStreamingBody();

    /**
     * 请求头收齐（body 还没到）。
     */
    void onHeaders(Http2Stream stream) throws IOException;

    /**
     * 收到一块请求体。
     *
     * @param data   数据缓冲
     * @param offset 起始偏移
     * @param length 长度
     */
    void onBody(Http2Stream stream, byte[] data, int offset, int length) throws IOException;

    /**
     * 对端半关闭 —— 请求体结束。
     */
    void onEnd(Http2Stream stream) throws IOException;
}
