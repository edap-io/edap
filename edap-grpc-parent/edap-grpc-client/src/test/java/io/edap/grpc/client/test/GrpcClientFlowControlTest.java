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

import io.edap.grpc.client.GrpcChannel;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端发送窗口的流控测试。
 *
 * <p><b>回归背景</b>：客户端曾经把<b>流级</b>发送窗口的初值硬编码成 65535，
 * 而不是对端在 SETTINGS 里通告的 {@code INITIAL_WINDOW_SIZE}。grpc-java 默认通告 1MB，
 * 于是在「edap 客户端 → grpc-java 服务端」这条链路上：</p>
 * <ul>
 *   <li>客户端发满 65535 字节就把流级窗口用光；</li>
 *   <li>服务端那边窗口还剩 900KB，<b>根本不会</b>发流级 WINDOW_UPDATE；</li>
 *   <li>客户端于是一直等流级额度 → 「gRPC 发送流控窗口等待超时」。</li>
 * </ul>
 *
 * <p>本测试用一个假服务端精确复刻这个场景：通告 {@code INITIAL_WINDOW_SIZE=1048576}，
 * 只补<b>连接级</b>窗口，<b>永远不发</b>流级 WINDOW_UPDATE。客户端必须能一次发完
 * 超过 64KB 的请求。</p>
 */
public class GrpcClientFlowControlTest {

    /** HTTP/2 连接前言的长度（"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"） */
    private static final int PREFACE_LENGTH = 24;

    /** 假服务端通告的 INITIAL_WINDOW_SIZE（grpc-java 的默认值） */
    private static final int PEER_INITIAL_WINDOW_SIZE = 1024 * 1024;

    /** 请求消息里 bytes 字段的长度 —— 必须让编码后的请求超过 65535 字节 */
    private static final int BLOB_SIZE = 96 * 1024;

    public static class BigReq {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 3, type = Field.Type.BYTES)
        public byte[] blob;
    }

    public static class EmptyResp {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;
    }

    @Test
    void requestLargerThanDefaultWindowIsSentWhenPeerAdvertisesBiggerWindow() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            AtomicLong received = new AtomicLong();
            CountDownLatch done = new CountDownLatch(1);

            Thread peer = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    in.skipNBytes(PREFACE_LENGTH);
                    readFrame(in);                               // 客户端的 SETTINGS

                    // ① SETTINGS：通告 INITIAL_WINDOW_SIZE = 1MB
                    byte[] settings = new byte[6];
                    put16(settings, 0, 0x4);
                    put32(settings, 2, PEER_INITIAL_WINDOW_SIZE);
                    out.write(frame(0x4, 0, 0, settings));
                    // ② 只补连接级窗口（连接窗口恒为 65535，不受 SETTINGS 影响）
                    out.write(frame(0x8, 0, 0, intPayload(PEER_INITIAL_WINDOW_SIZE - 65535)));
                    out.flush();

                    // ③ 读请求，直到 END_STREAM
                    boolean endStream = false;
                    while (!endStream) {
                        Frame f = readFrame(in);
                        if (f == null) {
                            return;
                        }
                        if (f.type == 0x0) {
                            received.addAndGet(f.payload.length);
                            endStream = (f.flags & 0x1) != 0;
                        }
                    }
                    // ④ 回响应：HEADERS(:status 200) + DATA + trailers(grpc-status: 0)
                    out.write(frame(0x1, 0x4, 1, new byte[]{(byte) 0x88}));
                    out.write(frame(0x0, 0, 1, new byte[]{0, 0, 0, 0, 0}));
                    out.write(frame(0x1, 0x4, 1, new byte[]{
                            0x40, 0x0b, 'g', 'r', 'p', 'c', '-', 's', 't', 'a', 't', 'u', 's', 0x01, '0'}));
                    out.flush();
                    done.countDown();
                    Thread.sleep(500);
                } catch (Exception ignore) {
                    // 测试结束时会关掉 socket —— 忽略
                }
            }, "flow-control-peer");
            peer.setDaemon(true);
            peer.start();

            BigReq req = new BigReq();
            req.name = "big";
            req.blob = new byte[BLOB_SIZE];

            try (GrpcChannel channel = new GrpcChannel("127.0.0.1", port, 10_000, 5_000)) {
                EmptyResp resp = channel.unary("/demo.Svc/Unary", BigReq.class, EmptyResp.class, req);
                assertTrue(resp != null);
            }

            assertTrue(done.await(5, TimeUnit.SECONDS), "服务端必须收到完整的请求");
            assertTrue(received.get() > 65535,
                    "服务端应收到了超过 65535 字节的请求体，实际 " + received.get()
                            + " —— 客户端卡在 64KB 说明流级发送窗口初值用错了"
                            + "（必须用对端 SETTINGS 的 INITIAL_WINDOW_SIZE，不是硬编码 65535）");
        }
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static byte[] intPayload(int v) {
        byte[] b = new byte[4];
        put32(b, 0, v);
        return b;
    }

    private static byte[] frame(int type, int flags, int streamId, byte[] payload) {
        byte[] f = new byte[9 + payload.length];
        f[0] = (byte) ((payload.length >>> 16) & 0xFF);
        f[1] = (byte) ((payload.length >>> 8) & 0xFF);
        f[2] = (byte) (payload.length & 0xFF);
        f[3] = (byte) type;
        f[4] = (byte) flags;
        f[5] = (byte) ((streamId >>> 24) & 0x7F);
        f[6] = (byte) ((streamId >>> 16) & 0xFF);
        f[7] = (byte) ((streamId >>> 8) & 0xFF);
        f[8] = (byte) (streamId & 0xFF);
        System.arraycopy(payload, 0, f, 9, payload.length);
        return f;
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
