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

package io.edap.http;

import java.io.IOException;

/**
 * 响应编码策略。
 *
 * <p>HTTP/1.1 与 HTTP/2 的响应字节完全不同（状态行 vs {@code :status} 伪头、
 * 裸 body vs DATA 帧），但业务 handler 的写法必须一样，所以响应侧也走
 * 「策略对象」模型：{@link HttpResponse} 只负责状态码 / 响应头 / body，
 * 具体怎么落到字节由本接口的实现决定。</p>
 *
 * <p>实现：</p>
 * <ul>
 *   <li>{@link Http1ResponseEncoder} —— HTTP/1.1，产出与改造前<b>字节级完全一致</b>的输出</li>
 *   <li>{@code io.edap.http.server.http2.Http2ResponseEncoder} —— HTTP/2（RFC 9113 §8）</li>
 * </ul>
 *
 * <p><b>不引入 {@code isHttp2} 布尔</b>：HTTP/2 的编码器在构造时就绑定了连接与流标识符，
 * 协议差异由对象身份承载，而不是靠标志位。</p>
 */
public interface ResponseEncoder {

    /**
     * 写响应头。
     *
     * @param response      响应对象（提供状态码、响应头、目标缓冲）
     * @param contentLength 响应体长度；<b>负数表示未知</b>
     *                     （HTTP/1.1 下不写 Content-Length，HTTP/2 下本来就不需要）
     */
    void writeHead(HttpResponse response, int contentLength) throws IOException;

    /**
     * 写响应体。
     *
     * @param data   响应体字节
     * @param offset 起始偏移
     * @param length 长度
     */
    void writeBody(HttpResponse response, byte[] data, int offset, int length) throws IOException;

    /**
     * 响应结束。
     *
     * <p>HTTP/1.1 是空操作；HTTP/2 用来发 END_STREAM。</p>
     */
    void end(HttpResponse response) throws IOException;
}
