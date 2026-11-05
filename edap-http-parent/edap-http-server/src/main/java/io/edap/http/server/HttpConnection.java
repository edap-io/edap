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

package io.edap.http.server;

import io.edap.buffer.FastBuf;
import io.edap.pool.Pool;

import java.io.IOException;

/**
 * 协议连接策略对象。
 *
 * <p>edap 采用「<b>单物理 session + 协议策略对象</b>」模型：
 * {@link HttpServerNioSession} 仍然是唯一的物理连接（持有 socketChannel / writeBufs /
 * selectionKey），协议差异全部收敛到本接口的两个实现里：</p>
 *
 * <ul>
 *   <li>{@link Http1Connection} —— HTTP/1.1（复用既有的 RangeHttpRequestDecoder）</li>
 *   <li>{@code io.edap.http.server.http2.Http2Connection} —— HTTP/2（RFC 9113）</li>
 * </ul>
 *
 * <p>这样 {@code flush} / {@code writeBufs} / {@code OP_WRITE} / 直写 socket 这些机制
 * <b>零改动复用</b>，协议差异 100% 隔离，WebSocket 与 HTTP/2 天然正交。</p>
 */
public interface HttpConnection {

    /**
     * 处理一次 socket read 收到的数据。
     *
     * @param buf 本次 read 的缓冲（读位置起是尚未处理的字节）
     * @return true 表示本次读产出了可响应的消息（响应已写入待写缓冲，需要 flush）
     */
    boolean decode(FastBuf buf) throws IOException;

    /**
     * 把待写缓冲刷到 socket。
     *
     * @param bbPool 写缓冲池（可为 null）
     */
    void flush(Pool<FastBuf> bbPool) throws IOException;

    /**
     * NIO 层把「上次没写完的字节」写完之后回调。
     *
     * <p>HTTP/2 场景下 socket 缓冲（默认 16KB）远小于一个窗口（64KB），大响应必然写不完，
     * 残余字节挂在 session 的写队列上，等 {@code OP_WRITE} 事件继续写。本回调就是那个
     * 「继续写」的触发点 —— 没有它，挂起的帧永远发不出去。</p>
     */
    default void onWriteBufDrained() {
    }
}
