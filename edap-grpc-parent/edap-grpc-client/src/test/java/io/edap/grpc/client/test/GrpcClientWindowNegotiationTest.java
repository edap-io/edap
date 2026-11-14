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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端建连时的<b>流控窗口协商</b>测试。
 *
 * <p><b>回归背景</b>：edap 客户端曾经「什么都不协商」—— 发一个空的 SETTINGS，
 * 也不发连接级的 {@code WINDOW_UPDATE}。于是一条连接上能同时在途的字节被压在
 * 64KB（RFC 9113 §6.9.2 的连接级窗口初值）：</p>
 * <ul>
 *   <li>64KB 的负载 → 一条连接最多 1 个请求在途 → 并发 1 和并发 32 吞吐一样；</li>
 *   <li>grpc-java 两端都会把连接级窗口撑到 1MB，并且用 SETTINGS 通告 1MB 的流级窗口。</li>
 * </ul>
 *
 * <p>本测试断言两件事：</p>
 * <ol>
 *   <li>客户端建连后必须发出 {@code SETTINGS(INITIAL_WINDOW_SIZE=1MB)}
 *       与 {@code WINDOW_UPDATE(streamId=0)}；</li>
 *   <li>客户端必须尊重对端通告的 {@code MAX_FRAME_SIZE} —— grpc-java 通告的是 8192，
 *       客户端硬编码 16384 会把对端按 FRAME_SIZE_ERROR 判错，整条连接被干掉。</li>
 * </ol>
 */
public class GrpcClientWindowNegotiationTest {

    /** HTTP/2 连接前言的长度（"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"） */
    private static final int PREFACE_LENGTH = 24;

    /** 客户端应该通告的流级接收窗口 */
    private static final int EXPECTED_STREAM_WINDOW = 1024 * 1024;

    /** 客户端应该把连接级接收窗口撑到的值（与生产常量同源，避免两处各写一份） */
    private static final int EXPECTED_CONN_WINDOW = GrpcChannel.CONN_WINDOW;

    /** 对端（grpc-java）通告的 MAX_FRAME_SIZE */
    private static final int PEER_MAX_FRAME_SIZE = 8192;

    /** 请求体大小 —— 必须大于一个对端允许的帧 */
    private static final int REQUEST_SIZE = 40 * 1024;

    public static class WinNegBigReq {
        @ProtoField(tag = 3, type = Field.Type.BYTES)
        public byte[] blob;
    }

    public static class WinNegEmptyResp {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;
    }

    /**
     * 客户端建连后必须通告 1MB 的流级窗口，并把连接级窗口撑到 {@link GrpcChannel#CONN_WINDOW}。
     */
    @Test
    void clientAdvertisesBigWindowsOnConnect() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Frame> settingsFrame = new AtomicReference<>();
            AtomicReference<Frame> windowUpdateFrame = new AtomicReference<>();

            Thread peer = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    in.skipNBytes(PREFACE_LENGTH);
                    // 客户端建连后应该先发 SETTINGS，再发连接级的 WINDOW_UPDATE
                    Frame first = readFrame(in);
                    Frame second = readFrame(in);
                    settingsFrame.set(first);
                    windowUpdateFrame.set(second);
                    done.countDown();
                    Thread.sleep(1000);
                } catch (Exception ignore) {
                    // 测试结束时会关掉 socket —— 忽略
                }
            }, "window-peer");
            peer.setDaemon(true);
            peer.start();

            try (GrpcChannel channel = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                assertTrue(done.await(5, TimeUnit.SECONDS), "客户端必须发出建连帧");
            }

            Frame settings = settingsFrame.get();
            assertNotNull(settings, "客户端必须发 SETTINGS");
            assertEquals(0x4, settings.type, "建连后第一帧应该是 SETTINGS");
            assertEquals(EXPECTED_STREAM_WINDOW, readSetting(settings.payload, 0x4),
                    "客户端必须通告 INITIAL_WINDOW_SIZE=1MB —— 不通告的话对端的流级窗口"
                            + "只有 64KB，一条 64KB 的响应就塞不进去");

            Frame windowUpdate = windowUpdateFrame.get();
            assertNotNull(windowUpdate, "客户端必须发连接级的 WINDOW_UPDATE");
            assertEquals(0x8, windowUpdate.type, "第二帧应该是 WINDOW_UPDATE");
            assertEquals(0, windowUpdate.streamId, "连接级 WINDOW_UPDATE 的 streamId 必须是 0");
            assertEquals(EXPECTED_CONN_WINDOW - 65535, readInt(windowUpdate.payload, 0),
                    "客户端必须把连接级接收窗口撑到 " + EXPECTED_CONN_WINDOW
                            + " —— 不撑的话一条连接上只能有 64KB 在途");
        }
    }

    /**
     * 客户端必须尊重对端通告的 {@code MAX_FRAME_SIZE}。
     *
     * <p>grpc-java 通告 8192；客户端硬编码 16384 的话会发出去一个超限的帧，
     * 对端按 {@code FRAME_SIZE_ERROR} 判错 —— 那是<b>连接级</b>错误，整条连接被干掉。</p>
     */
    @Test
    void clientRespectsPeerMaxFrameSize() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            AtomicLong maxDataFrame = new AtomicLong();
            CountDownLatch done = new CountDownLatch(1);
            CountDownLatch peerSettingsApplied = new CountDownLatch(1);

            Thread peer = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    socket.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    in.skipNBytes(PREFACE_LENGTH);

                    // ① 通告 MAX_FRAME_SIZE=8192 与 INITIAL_WINDOW_SIZE=1MB
                    byte[] settings = new byte[12];
                    put16(settings, 0, 0x4);
                    put32(settings, 2, EXPECTED_STREAM_WINDOW);
                    put16(settings, 6, 0x6);
                    put32(settings, 8, PEER_MAX_FRAME_SIZE);
                    out.write(frame(0x4, 0, 0, settings));
                    out.write(frame(0x8, 0, 0, intPayload(EXPECTED_STREAM_WINDOW - 65535)));
                    // ② 发一个 PING —— 客户端回 PING ACK 时，说明它已经处理完上面的 SETTINGS 了
                    //    （读线程是顺序 dispatch 的：SETTINGS 先于 PING）
                    out.write(frame(0x6, 0, 0, new byte[8]));
                    out.flush();

                    boolean endStream = false;
                    while (!endStream) {
                        Frame f = readFrame(in);
                        if (f == null) {
                            return;
                        }
                        if (f.type == 0x6 && (f.flags & 0x1) != 0) {
                            peerSettingsApplied.countDown();
                        } else if (f.type == 0x0) {
                            maxDataFrame.accumulateAndGet(f.payload.length, Math::max);
                            endStream = (f.flags & 0x1) != 0;
                        }
                    }
                    // 回响应
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
            }, "max-frame-peer");
            peer.setDaemon(true);
            peer.start();

            WinNegBigReq req = new WinNegBigReq();
            req.blob = new byte[REQUEST_SIZE];

            try (GrpcChannel channel = new GrpcChannel("127.0.0.1", port, 10_000, 5_000)) {
                // 等客户端处理完对端的 SETTINGS（收到 PING ACK 即说明已处理）
                assertTrue(peerSettingsApplied.await(5, TimeUnit.SECONDS),
                        "客户端必须回 PING ACK");
                channel.unary("/demo.Svc/Unary", WinNegBigReq.class, WinNegEmptyResp.class, req);
            }

            assertTrue(done.await(5, TimeUnit.SECONDS), "服务端必须收到完整的请求");
            assertTrue(maxDataFrame.get() > 0, "服务端必须收到 DATA 帧");
            assertTrue(maxDataFrame.get() <= PEER_MAX_FRAME_SIZE,
                    "客户端发出了一个 " + maxDataFrame.get() + " 字节的 DATA 帧，"
                            + "超过对端通告的 MAX_FRAME_SIZE=" + PEER_MAX_FRAME_SIZE
                            + " —— 对端会按 FRAME_SIZE_ERROR 判错，整条连接被干掉");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static int readSetting(byte[] payload, int id) {
        for (int i = 0; i + 6 <= payload.length; i += 6) {
            if (((payload[i] & 0xFF) << 8 | (payload[i + 1] & 0xFF)) == id) {
                return readInt(payload, i + 2);
            }
        }
        return -1;
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

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
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
