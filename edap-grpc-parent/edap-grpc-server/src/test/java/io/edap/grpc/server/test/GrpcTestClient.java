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

package io.edap.grpc.server.test;

import io.edap.grpc.GrpcFrame;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackEncoder;
import io.edap.http.http2.hpack.HpackHeader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 裸 socket 的 gRPC 客户端（HTTP/2 prior knowledge + gRPC 分帧）。
 *
 * <p>刻意不用 JDK {@code HttpClient}：它不是 gRPC 客户端，没有 5 字节分帧、
 * 没有 trailers 语义。用裸 socket 才能精确验证 {@code :path}、{@code content-type}、
 * 分帧、trailers 与 {@code grpc-status}。</p>
 */
public class GrpcTestClient implements Closeable {

    /** HTTP/2 连接前言（RFC 9113 §3.4） */
    public static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private final HpackEncoder encoder = new HpackEncoder();
    private final HpackDecoder decoder = new HpackDecoder();

    public GrpcTestClient(int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setSoTimeout(5000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /**
     * 发连接前言 + 空 SETTINGS 帧。
     */
    public void start() throws IOException {
        out.write(PREFACE);
        writeFrame(Http2FrameType.SETTINGS, 0, 0, new byte[0]);
    }

    /**
     * 发 gRPC 请求头（{@code :method POST} + {@code content-type: application/grpc}）。
     */
    public void sendHeaders(int streamId, String path, String authority) throws IOException {
        List<HpackHeader> headers = new ArrayList<>(8);
        headers.add(new HpackHeader(":method", "POST"));
        headers.add(new HpackHeader(":path", path));
        headers.add(new HpackHeader(":scheme", "http"));
        headers.add(new HpackHeader(":authority", authority));
        headers.add(new HpackHeader("content-type", "application/grpc"));
        headers.add(new HpackHeader("te", "trailers"));
        headers.add(new HpackHeader("grpc-encoding", "identity"));
        headers.add(new HpackHeader("user-agent", "edap-grpc-test"));
        byte[] block = encoder.encode(headers);
        writeFrame(Http2FrameType.HEADERS, Http2Flags.END_HEADERS, streamId, block);
    }

    /**
     * 发一条 gRPC 消息（5 字节帧头 + 消息体）。
     *
     * @param endStream true 表示这条 DATA 帧带 END_STREAM（请求侧结束）
     */
    public void sendMessage(int streamId, byte[] payload, boolean endStream) throws IOException {
        writeFrame(Http2FrameType.DATA, endStream ? Http2Flags.END_STREAM : 0, streamId, GrpcFrame.encode(payload));
    }

    /**
     * 只发一个空的 DATA(END_STREAM) —— 请求侧结束但一条消息都没发。
     */
    public void sendEndStream(int streamId) throws IOException {
        writeFrame(Http2FrameType.DATA, Http2Flags.END_STREAM, streamId, new byte[0]);
    }

    /**
     * 读完整响应：响应头 → 若干消息 → trailers。
     */
    public Response readResponse(int streamId) throws IOException {
        Response response = new Response();
        byte[] body = new byte[0];
        boolean sawHeaders = false;
        while (true) {
            Frame frame = readFrame();
            if (frame.streamId != streamId) {
                continue;
            }
            if (frame.type == Http2FrameType.HEADERS) {
                List<HpackHeader> headers = decodeHeaders(frame.payload);
                if (!sawHeaders) {
                    sawHeaders = true;
                    response.headers = headers;
                } else {
                    response.trailers = headers;
                }
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    response.endStream = true;
                    break;
                }
            } else if (frame.type == Http2FrameType.DATA) {
                body = concat(body, frame.payload);
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    response.endStream = true;
                    break;
                }
            }
        }
        response.messages = splitMessages(body);
        return response;
    }

    /**
     * 把 gRPC 的字节流拆成一条条消息。
     */
    public static List<byte[]> splitMessages(byte[] body) {
        List<byte[]> messages = new ArrayList<>();
        int pos = 0;
        while (body.length - pos >= GrpcFrame.HEADER_LENGTH) {
            int length = GrpcFrame.decodeLength(body, pos);
            if (body.length - pos < GrpcFrame.HEADER_LENGTH + length) {
                throw new IllegalStateException("gRPC 消息不完整: 长度=" + length
                        + " 剩余=" + (body.length - pos));
            }
            byte[] payload = new byte[length];
            System.arraycopy(body, pos + GrpcFrame.HEADER_LENGTH, payload, 0, length);
            messages.add(payload);
            pos += GrpcFrame.HEADER_LENGTH + length;
        }
        return messages;
    }

    public void writeFrame(int type, int flags, int streamId, byte[] payload) throws IOException {
        byte[] frame = new byte[9 + payload.length];
        frame[0] = (byte) ((payload.length >>> 16) & 0xff);
        frame[1] = (byte) ((payload.length >>> 8) & 0xff);
        frame[2] = (byte) (payload.length & 0xff);
        frame[3] = (byte) (type & 0xff);
        frame[4] = (byte) (flags & 0xff);
        frame[5] = (byte) ((streamId >>> 24) & 0x7f);
        frame[6] = (byte) ((streamId >>> 16) & 0xff);
        frame[7] = (byte) ((streamId >>> 8) & 0xff);
        frame[8] = (byte) (streamId & 0xff);
        System.arraycopy(payload, 0, frame, 9, payload.length);
        out.write(frame);
        out.flush();
    }

    public Frame readFrame() throws IOException {
        byte[] head = readFully(9);
        int length = ((head[0] & 0xff) << 16) | ((head[1] & 0xff) << 8) | (head[2] & 0xff);
        int type = head[3] & 0xff;
        int flags = head[4] & 0xff;
        int streamId = ((head[5] & 0x7f) << 24) | ((head[6] & 0xff) << 16)
                | ((head[7] & 0xff) << 8) | (head[8] & 0xff);
        byte[] payload = length == 0 ? new byte[0] : readFully(length);
        return new Frame(type, flags, streamId, payload);
    }

    public List<HpackHeader> decodeHeaders(byte[] block) {
        return decoder.decode(block, 0, block.length);
    }

    /**
     * 读一帧属于指定流的帧（跳过连接级帧），超时返回 null。
     *
     * <p>用来断言「服务端此刻已经回响应了」——不能等整个响应结束，
     * 因为流式的响应要等请求侧结束才会发 END_STREAM。</p>
     */
    public Frame readFrameFor(int streamId, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            socket.setSoTimeout(200);
            Frame frame;
            try {
                frame = readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } finally {
                socket.setSoTimeout(5000);
            }
            if (frame.streamId == streamId) {
                return frame;
            }
        }
        return null;
    }

    /**
     * 原样写一段文本（构造 HTTP/1.1 请求之类）。
     */
    public void writeRaw(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * 读一个 HTTP/1.1 响应头（读到空行为止），返回原始文本。
     */
    public String readHttpResponseHead() throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("连接在读完响应头前关闭");
            }
            bos.write(b);
            byte[] arr = bos.toByteArray();
            int n = arr.length;
            if (n >= 4 && arr[n - 4] == '\r' && arr[n - 3] == '\n'
                    && arr[n - 2] == '\r' && arr[n - 1] == '\n') {
                return new String(arr, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private byte[] readFully(int n) throws IOException {
        byte[] data = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(data, read, n - read);
            if (r < 0) {
                throw new IOException("连接在读完 " + read + "/" + n + " 字节前关闭");
            }
            read += r;
        }
        return data;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /**
     * 一帧。
     */
    public static class Frame {
        public final int type;
        public final int flags;
        public final int streamId;
        public final byte[] payload;

        public Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }

        public boolean hasFlag(int flag) {
            return (flags & flag) != 0;
        }
    }

    /**
     * 一次 gRPC 响应。
     */
    public static class Response {
        /** 响应头（第一个 HEADERS 帧） */
        public List<HpackHeader> headers;
        /** trailers（第二个 HEADERS 帧） */
        public List<HpackHeader> trailers;
        /** 响应消息 */
        public List<byte[]> messages = new ArrayList<>();
        /** 响应是否以 END_STREAM 结束 */
        public boolean endStream;

        public String status() {
            return headerValue(":status");
        }

        public String headerValue(String name) {
            return value(headers, name);
        }

        public String trailerValue(String name) {
            return value(trailers, name);
        }

        private static String value(List<HpackHeader> headers, String name) {
            if (headers == null) {
                return null;
            }
            for (HpackHeader h : headers) {
                if (name.equals(h.getName())) {
                    return h.getValue();
                }
            }
            return null;
        }
    }
}
