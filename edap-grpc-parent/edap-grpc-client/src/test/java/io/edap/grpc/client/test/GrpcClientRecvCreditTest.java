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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端归还<b>流级</b>接收额度的测试。
 *
 * <p><b>回归背景</b>：客户端曾经把「连接级的累计字节数」记到「触发阈值的那一条流」上，
 * 然后发一帧<b>流级</b> {@code WINDOW_UPDATE}。而流级 WINDOW_UPDATE 只对它指定的那条流有效
 * （RFC 9113 §6.9.1）—— 其余的流就永远收不满响应。多流并发时表现为
 * <b>随机地有调用卡住不动</b>（等对端的额度，等不到）。</p>
 *
 * <p>本测试用一个假服务端复刻这个场景：</p>
 * <ul>
 *   <li>假服务端<b>永远不发</b>流级 WINDOW_UPDATE —— 完全依赖客户端归还额度；</li>
 *   <li>两条流各回一个<b>大于一个窗口</b>（1MB）的响应 —— 必须靠客户端归还额度才能发完；</li>
 *   <li>两条流都必须收到客户端的流级 WINDOW_UPDATE，且都能把响应发完。</li>
 * </ul>
 */
public class GrpcClientRecvCreditTest {

    /** HTTP/2 连接前言的长度（"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"） */
    private static final int PREFACE_LENGTH = 24;

    /** 客户端通告的流级接收窗口（1MB） */
    private static final int CLIENT_STREAM_WINDOW = 1024 * 1024;

    /** 客户端通告的连接级接收窗口（1MB） */
    private static final int CLIENT_CONN_WINDOW = 1024 * 1024;

    /** 每条流的响应大小 —— 必须大于一个窗口（1MB），才能逼出额度的往返 */
    private static final int RESPONSE_SIZE = 1536 * 1024;

    /** 假服务端一次发送的 DATA 载荷 */
    private static final int CHUNK = 16384;

    public static class BigResp {
        @ProtoField(tag = 3, type = Field.Type.BYTES)
        public byte[] blob;
    }

    public static class TinyReq {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;
    }

    /**
     * 两条流并发收大响应：客户端必须<b>按流</b>归还额度，两条流都要能收完。
     */
    @Test
    void streamCreditIsReturnedToTheRightStream() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            FakePeer peer = new FakePeer(serverSocket);
            Thread peerThread = new Thread(peer, "recv-credit-peer");
            peerThread.setDaemon(true);
            peerThread.start();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(2);
            List<Object> results = new ArrayList<>();

            try (GrpcChannel channel = new GrpcChannel("127.0.0.1", port, 20_000, 5_000)) {
                for (int i = 0; i < 2; i++) {
                    Thread t = new Thread(() -> {
                        try {
                            TinyReq req = new TinyReq();
                            req.name = "req";
                            BigResp resp = channel.unary("/demo.Svc/Unary", TinyReq.class, BigResp.class, req);
                            synchronized (results) {
                                results.add(resp);
                            }
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                        } finally {
                            done.countDown();
                        }
                    }, "caller-" + i);
                    t.setDaemon(true);
                    t.start();
                }
                assertTrue(done.await(30, TimeUnit.SECONDS),
                        "两条流都必须收完响应 —— 卡住说明流级额度归还错了流");
            }

            if (failure.get() != null) {
                throw new AssertionError("调用失败: " + failure.get(), failure.get());
            }
            assertEquals(2, results.size());
            for (Object o : results) {
                BigResp resp = (BigResp) o;
                assertNotNull(resp.blob, "响应消息必须解出来");
                assertEquals(RESPONSE_SIZE - 16, resp.blob.length, "响应体长度不对");
            }

            // ★ 关键断言：两条流都必须收到<b>流级</b> WINDOW_UPDATE
            assertTrue(peer.streamCredits.containsKey(1),
                    "流 1 必须收到流级 WINDOW_UPDATE —— 实际收到的流级额度: " + peer.streamCredits);
            assertTrue(peer.streamCredits.containsKey(3),
                    "流 3 必须收到流级 WINDOW_UPDATE —— 实际收到的流级额度: " + peer.streamCredits);
        }
    }

    /**
     * 假服务端：通告 1MB 的流级窗口，<b>永远不发</b>流级 WINDOW_UPDATE，
     * 只靠客户端归还的额度把大响应发完。
     */
    private static final class FakePeer implements Runnable {

        final ServerSocket serverSocket;

        /** 流 id → 客户端归还的流级额度 */
        final Map<Integer, Integer> streamCredits = new HashMap<>();

        /** 连接级发送窗口 */
        int connWindow = 65535;

        /** 流 id → 该流还能发多少字节 */
        final Map<Integer, Integer> sendWindow = new HashMap<>();

        final Object lock = new Object();
        final Object writeLock = new Object();

        volatile OutputStream out;
        volatile boolean stopped;

        FakePeer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        @Override
        public void run() {
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();
                out = output;
                in.skipNBytes(PREFACE_LENGTH);

                // 通告自己的 SETTINGS（INITIAL_WINDOW_SIZE=1MB）+ 连接级窗口扩容
                byte[] settings = new byte[6];
                put16(settings, 0, 0x4);
                put32(settings, 2, CLIENT_STREAM_WINDOW);
                write(frame(0x4, 0, 0, settings));
                write(frame(0x8, 0, 0, intPayload(CLIENT_CONN_WINDOW - 65535)));

                while (!stopped) {
                    Frame f = readFrame(in);
                    if (f == null) {
                        return;
                    }
                    switch (f.type) {
                        case 0x1: {                                   // HEADERS —— 新流
                            int streamId = f.streamId;
                            synchronized (lock) {
                                sendWindow.put(streamId, CLIENT_STREAM_WINDOW);
                            }
                            Thread sender = new Thread(() -> sendResponse(streamId),
                                    "peer-sender-" + streamId);
                            sender.setDaemon(true);
                            sender.start();
                            break;
                        }
                        case 0x8: {                                   // WINDOW_UPDATE
                            int inc = readInt(f.payload, 0);
                            synchronized (lock) {
                                if (f.streamId == 0) {
                                    connWindow += inc;
                                } else {
                                    streamCredits.merge(f.streamId, inc, Integer::sum);
                                    sendWindow.merge(f.streamId, inc, Integer::sum);
                                }
                                lock.notifyAll();
                            }
                            break;
                        }
                        default:
                            break;
                    }
                }
            } catch (Exception ignore) {
                // 连接断了 —— 测试结束
            }
        }

        /**
         * 发一条流的大响应 —— 严格受发送窗口约束（客户端不归还额度就卡住）。
         */
        private void sendResponse(int streamId) {
            try {
                byte[] grpcFrame = buildGrpcFrame(RESPONSE_SIZE - 16);
                // 响应头：:status 200
                write(frame(0x1, 0x4, streamId, new byte[]{(byte) 0x88}));
                int sent = 0;
                while (sent < grpcFrame.length) {
                    int allowed;
                    synchronized (lock) {
                        while (true) {
                            int streamLeft = sendWindow.getOrDefault(streamId, 0);
                            int connLeft = connWindow;
                            allowed = Math.min(Math.min(CHUNK, grpcFrame.length - sent),
                                    Math.min(streamLeft, connLeft));
                            if (allowed > 0) {
                                sendWindow.put(streamId, sendWindow.get(streamId) - allowed);
                                connWindow -= allowed;
                                break;
                            }
                            lock.wait(200);
                            if (stopped) {
                                return;
                            }
                        }
                    }
                    byte[] chunk = new byte[allowed];
                    System.arraycopy(grpcFrame, sent, chunk, 0, allowed);
                    write(frame(0x0, 0, streamId, chunk));
                    sent += allowed;
                }
                // trailers：grpc-status: 0
                write(frame(0x1, 0x4, streamId, new byte[]{
                        0x40, 0x0b, 'g', 'r', 'p', 'c', '-', 's', 't', 'a', 't', 'u', 's', 0x01, '0'}));
            } catch (Exception ignore) {
                // 连接断了
            }
        }

        private void write(byte[] bytes) throws IOException {
            synchronized (writeLock) {
                out.write(bytes);
                out.flush();
            }
        }
    }

    /**
     * 构造一个 gRPC 消息帧：1 字节压缩标志 + 4 字节长度 + protobuf（tag 3 的 bytes 字段）。
     */
    private static byte[] buildGrpcFrame(int blobSize) {
        byte[] varint = varint(blobSize);
        int payloadLen = 1 + varint.length + blobSize;
        byte[] frame = new byte[5 + payloadLen];
        frame[0] = 0;
        frame[1] = (byte) ((payloadLen >>> 24) & 0xFF);
        frame[2] = (byte) ((payloadLen >>> 16) & 0xFF);
        frame[3] = (byte) ((payloadLen >>> 8) & 0xFF);
        frame[4] = (byte) (payloadLen & 0xFF);
        frame[5] = 0x1a;                                      // tag 3, wire type 2
        System.arraycopy(varint, 0, frame, 6, varint.length);
        return frame;
    }

    private static byte[] varint(int v) {
        byte[] buf = new byte[5];
        int i = 0;
        while (true) {
            if ((v & ~0x7F) == 0) {
                buf[i++] = (byte) v;
                break;
            }
            buf[i++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        byte[] result = new byte[i];
        System.arraycopy(buf, 0, result, 0, i);
        return result;
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
