/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.server;

import io.edap.erpc.frame.ErpcFrameCodec;
import io.edap.erpc.frame.ErpcFrameHeader;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * <b>裸 socket 的 eRPC 客户端</b> —— 测试专用。
 *
 * <p>框架自带的 {@code ErpcClient} 会自己填帧头、只会用「自己配置的那个」
 * 序列化器 ⇒ 测不出「同一连接上 codec 随帧变化」「未知 codec 回 ERROR」
 * 这类协议级行为。这类行为只能手工拼帧来测。</p>
 */
final class ErpcRawClient implements Closeable {

    private final Socket       socket;
    private final InputStream in;
    private final OutputStream out;

    /** 读侧分帧器。 */
    private final ErpcFrameCodec codec = new ErpcFrameCodec();

    ErpcRawClient(int port) throws IOException {
        this(port, 0);
    }

    /**
     * @param recvBufferSize 接收缓冲大小（字节）；0 ⇒ 用系统默认值。
     *                        <b>调小它是为了逼出服务端的「半写」</b> ——
     *                        接收窗口一小，服务端 socket 的发送缓冲很快写满。
     */
    ErpcRawClient(int port, int recvBufferSize) throws IOException {
        this.socket = new Socket();
        if (recvBufferSize > 0) {
            socket.setReceiveBufferSize(recvBufferSize);
        }
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
        this.socket.setTcpNoDelay(true);
        // 读超时：测试不能被服务端不响应卡死
        this.socket.setSoTimeout(5_000);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * 手工拼一帧发出去（flags = 0 —— 模拟老客户端）。
     */
    void send(int type, int codecId, int methodId, int serviceId, int requestId,
              long traceId, byte[] payload) throws IOException {
        send(type, codecId, methodId, serviceId, requestId, traceId, 0, payload);
    }

    /**
     * 手工拼一帧发出去（带 flags —— bit1~bit7 是超时代码）。
     */
    void send(int type, int codecId, int methodId, int serviceId, int requestId,
              long traceId, int flags, byte[] payload) throws IOException {
        int length = payload == null ? 0 : payload.length;
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH + length];
        new ErpcFrameHeader()
            .setType(type)
            .setFlags(flags)
            .setCodec(codecId)
            .setLength(length)
            .setMethodId(methodId)
            .setServiceId(serviceId)
            .setRequestId(requestId)
            .setTraceId(traceId)
            .writeTo(buf, 0);
        if (length > 0) {
            System.arraycopy(payload, 0, buf, ErpcFrameHeader.HEADER_LENGTH, length);
        }
        out.write(buf);
        out.flush();
    }

    /**
     * 直接写原始字节（用来测「不是 eRPC 流量」的服务端行为）。
     */
    void sendRaw(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    /**
     * 读一帧（阻塞直到收全）。
     */
    RawFrame readFrame() throws IOException {
        byte[] buf = new byte[4096];
        while (!codec.hasFrame()) {
            int n = in.read(buf);
            if (n < 0) {
                throw new EOFException("服务端关闭了连接");
            }
            codec.append(buf, 0, n);
        }
        ErpcFrameHeader header = codec.header();
        byte[] payload = codec.payload();
        codec.consume();
        return new RawFrame(header, payload);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignore) {
            // 忽略
        }
    }

    /**
     * 读到的一帧。
     */
    static final class RawFrame {

        final ErpcFrameHeader header;
        final byte[]          payload;

        RawFrame(ErpcFrameHeader header, byte[] payload) {
            this.header = header;
            this.payload = payload;
        }
    }
}
