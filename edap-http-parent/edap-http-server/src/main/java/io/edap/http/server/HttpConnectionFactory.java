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
import io.edap.http.server.http2.Http2Connection;

/**
 * 协议连接工厂 —— 嗅探 + 分派（RFC 9113 §3.5）。
 *
 * <p>每个 session 一个实例（嗅探器要跨 read 保存暂存字节）。</p>
 */
public class HttpConnectionFactory {

    private final HttpServerNioSession session;
    private final HttpProtocolSniffer sniffer = new HttpProtocolSniffer();

    public HttpConnectionFactory(HttpServerNioSession session) {
        this.session = session;
    }

    /**
     * 嗅探协议并创建对应的连接策略对象。
     *
     * @param buf 本次 read 的缓冲
     * @return 连接对象；{@code null} 表示数据不足（还判断不出协议），需要等下一次 read
     */
    public HttpConnection detect(FastBuf buf) {
        HttpProtocolSniffer.Protocol protocol = sniffer.detect(buf);
        if (protocol == null) {
            return null;
        }
        // 嗅探阶段暂存的字节必须回放：
        //   HTTP/1.1 → 交给解码器（否则首字节被吞掉，请求直接废掉）
        //   HTTP/2   → 是连接前言的开头，交给 Http2Connection 继续比对
        byte[] carry = sniffer.getCarry();
        if (protocol == HttpProtocolSniffer.Protocol.HTTP_2) {
            return new Http2Connection(session, carry);
        }
        return new Http1Connection(session, carry);
    }
}
