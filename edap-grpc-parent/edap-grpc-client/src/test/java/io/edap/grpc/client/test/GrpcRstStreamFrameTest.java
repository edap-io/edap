/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc.client.test;

import io.edap.grpc.GrpcException;
import io.edap.grpc.client.GrpcChannel;
import io.edap.grpc.client.GrpcClientCall;
import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2FrameType;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端发出的 RST_STREAM 帧的线格式测试。
 *
 * <p><b>回归背景</b>：客户端取消调用时曾经把 RST_STREAM 的载荷写成 8 字节，
 * 而 RFC 9113 §6.4 规定载荷**必须正好 4 字节**（错误码）。对端按
 * FRAME_SIZE_ERROR 处理，而这是<b>连接级</b>错误 —— 整条连接被干掉，
 * 其它在途调用全部陪葬。</p>
 */
public class GrpcRstStreamFrameTest {

    /** HTTP/2 连接前言的长度（"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"） */
    private static final int PREFACE_LENGTH = 24;

    @Test
    void rstStreamPayloadIsExactlyFourBytesWithCancelCode() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(16);

            Thread acceptor = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    in.skipNBytes(PREFACE_LENGTH);
                    while (true) {
                        Frame f = readFrame(in);
                        if (f == null) {
                            return;
                        }
                        if (f.type == Http2FrameType.RST_STREAM && frames.offer(f)) {
                            return;
                        }
                    }
                } catch (Exception ignore) {
                    // 测试结束时会关掉 socket —— 忽略
                }
            }, "rst-frame-capture");
            acceptor.setDaemon(true);
            acceptor.start();

            try (GrpcChannel channel = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                GrpcClientCall call = channel.newCall("/demo.Svc/Unary",
                        GrpcClientEndToEndTest.Req.class, GrpcClientEndToEndTest.Resp.class, null);
                call.cancel(new GrpcException("测试用的取消"));

                Frame rst = frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(rst, "客户端取消调用后必须发出 RST_STREAM");
                assertEquals(Http2FrameType.RST_STREAM, rst.type);
                assertEquals(4, rst.payload.length,
                        "RST_STREAM 的载荷必须正好 4 字节（RFC 9113 §6.4），"
                                + "多写字节对端会判 FRAME_SIZE_ERROR 并断掉整条连接");
                int code = ((rst.payload[0] & 0xFF) << 24) | ((rst.payload[1] & 0xFF) << 16)
                        | ((rst.payload[2] & 0xFF) << 8) | (rst.payload[3] & 0xFF);
                assertEquals(Http2ErrorCode.CANCEL, code, "取消调用要用 CANCEL(8)");
            }
        }
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        int b2 = in.read();
        int type = in.read();
        int flags = in.read();
        int s0 = in.read();
        int s1 = in.read();
        int s2 = in.read();
        int s3 = in.read();
        int length = (b0 << 16) | (b1 << 8) | b2;
        int streamId = ((s0 & 0x7F) << 24) | ((s1 & 0xFF) << 16) | ((s2 & 0xFF) << 8) | (s3 & 0xFF);
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new Frame(type, flags, streamId, payload);
    }

    private static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }
    }
}
