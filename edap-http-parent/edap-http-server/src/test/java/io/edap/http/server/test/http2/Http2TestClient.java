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

package io.edap.http.server.test.http2;

import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackEncoder;
import io.edap.http.http2.hpack.HpackHeader;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 裸 socket 的 HTTP/2 客户端（prior knowledge，RFC 9113 §3.4）。
 *
 * <p>刻意不用 JDK {@code HttpClient}：它默认先发 {@code Upgrade: h2c}，
 * h2c Upgrade 还没实现（Phase 3），会一直死等。用裸 socket 才能精确控制字节。</p>
 */
public class Http2TestClient implements Closeable {

    /** HTTP/2 连接前言（RFC 9113 §3.4） */
    public static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private final HpackEncoder encoder = new HpackEncoder();
    private final HpackDecoder decoder = new HpackDecoder();

    public Http2TestClient(int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setSoTimeout(5000);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /**
     * 发连接前言 + 空 SETTINGS 帧。
     *
     * <p>RFC 9113 §3.4：客户端连接前言之后的第一帧必须是 SETTINGS。</p>
     */
    public void start() throws IOException {
        out.write(PREFACE);
        writeFrame(Http2FrameType.SETTINGS, 0, 0, new byte[0]);
    }

    /**
     * 发一个完整的 GET 请求（HEADERS + END_STREAM）。
     */
    public void request(int streamId, String method, String path, String authority) throws IOException {
        List<HpackHeader> headers = new ArrayList<>(4);
        headers.add(new HpackHeader(":method", method));
        headers.add(new HpackHeader(":path", path));
        headers.add(new HpackHeader(":scheme", "http"));
        headers.add(new HpackHeader(":authority", authority));
        byte[] block = encoder.encode(headers);
        writeFrame(Http2FrameType.HEADERS,
                Http2Flags.END_HEADERS | Http2Flags.END_STREAM, streamId, block);
    }

    // ------------------------------------------------------------------
    // h2c 升级（RFC 9113 §3.2）
    // ------------------------------------------------------------------

    /**
     * 发 h2c 升级请求。
     *
     * @param path            请求路径
     * @param authority       Host 头的值
     * @param settingsBase64  HTTP2-Settings 头的值（base64url 编码的 SETTINGS 载荷），
     *                        null 表示不发这个头
     */
    public void sendH2cUpgrade(String path, String authority, String settingsBase64) throws IOException {
        StringBuilder sb = new StringBuilder(160);
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(authority).append("\r\n");
        sb.append("Connection: Upgrade, HTTP2-Settings\r\n");
        sb.append("Upgrade: h2c\r\n");
        if (settingsBase64 != null) {
            sb.append("HTTP2-Settings: ").append(settingsBase64).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * 读一个 HTTP/1.1 响应头（读到空行为止），返回原始文本。
     */
    public String readHttpResponseHead() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
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

    /**
     * 把 SETTINGS 载荷编码成 base64url（不带填充）—— HTTP2-Settings 头用。
     */
    public static String encodeSettingsBase64(byte[] settingsPayload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(settingsPayload);
    }

    /**
     * 发一个普通的 HTTP/1.1 请求（不升级）。
     */
    public void sendHttp11Request(String path, String authority) throws IOException {
        writeRaw("GET " + path + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "\r\n");
    }

    /**
     * 原样写一段字节。
     */
    public void writeRaw(String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * 发一个带 body 的 POST 请求（HEADERS + DATA + END_STREAM）。
     */
    public void requestWithBody(int streamId, String method, String path, String authority, byte[] body)
            throws IOException {
        List<HpackHeader> headers = new ArrayList<>(4);
        headers.add(new HpackHeader(":method", method));
        headers.add(new HpackHeader(":path", path));
        headers.add(new HpackHeader(":scheme", "http"));
        headers.add(new HpackHeader(":authority", authority));
        headers.add(new HpackHeader("content-length", Integer.toString(body.length)));
        byte[] block = encoder.encode(headers);
        writeFrame(Http2FrameType.HEADERS, Http2Flags.END_HEADERS, streamId, block);
        writeFrame(Http2FrameType.DATA, Http2Flags.END_STREAM, streamId, body);
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

    // ------------------------------------------------------------------
    // 流控 / 控制帧
    // ------------------------------------------------------------------

    /**
     * 发 WINDOW_UPDATE（RFC 9113 §6.9）。
     *
     * @param streamId 0 = 连接级窗口
     * @param increment 窗口增量（不能为 0）
     */
    public void sendWindowUpdate(int streamId, int increment) throws IOException {
        writeFrame(Http2FrameType.WINDOW_UPDATE, 0, streamId, intPayload(increment));
    }

    /**
     * 发 SETTINGS（RFC 9113 §6.5）。
     *
     * @param initialWindowSize 新流与已存在流的流级窗口初值
     */
    public void sendSettings(int initialWindowSize) throws IOException {
        byte[] payload = new byte[6];
        payload[0] = (byte) (0x4 >>> 8);
        payload[1] = (byte) 0x4;
        payload[2] = (byte) (initialWindowSize >>> 24);
        payload[3] = (byte) (initialWindowSize >>> 16);
        payload[4] = (byte) (initialWindowSize >>> 8);
        payload[5] = (byte) initialWindowSize;
        writeFrame(Http2FrameType.SETTINGS, 0, 0, payload);
    }

    /**
     * 发 PING（8 字节载荷）。
     */
    public void sendPing(byte[] payload) throws IOException {
        writeFrame(Http2FrameType.PING, 0, 0, payload);
    }

    /**
     * 发 RST_STREAM。
     */
    public void sendRstStream(int streamId, int errorCode) throws IOException {
        writeFrame(Http2FrameType.RST_STREAM, 0, streamId, intPayload(errorCode));
    }

    /**
     * 只发 HEADERS（不带 END_STREAM）—— 用来构造「请求头收齐了但 body 还没发完」的场景。
     *
     * <p>注意必须带 {@code END_HEADERS}：不带的话对端认为 header block 没收完，
     * 会一直等 CONTINUATION，请求永远不会被派发。</p>
     */
    public void sendHeadersOnly(int streamId, String method, String path, String authority) throws IOException {
        writeFrame(Http2FrameType.HEADERS, Http2Flags.END_HEADERS, streamId, encodePseudoHeaders(method, path, authority));
    }

    /**
     * 发 CONTINUATION 帧。
     */
    public void sendContinuation(int streamId, byte[] block, boolean endHeaders) throws IOException {
        writeFrame(Http2FrameType.CONTINUATION, endHeaders ? Http2Flags.END_HEADERS : 0, streamId, block);
    }

    /**
     * 发 DATA 帧。
     */
    public void sendData(int streamId, byte[] payload, boolean endStream) throws IOException {
        writeFrame(Http2FrameType.DATA, endStream ? Http2Flags.END_STREAM : 0, streamId, payload);
    }

    /**
     * 编码伪头（公开出来供 CONTINUATION 场景拼接 header block）。
     */
    public byte[] encodePseudoHeaders(String method, String path, String authority) {
        List<HpackHeader> headers = new ArrayList<>(4);
        headers.add(new HpackHeader(":method", method));
        headers.add(new HpackHeader(":path", path));
        headers.add(new HpackHeader(":scheme", "http"));
        headers.add(new HpackHeader(":authority", authority));
        return encoder.encode(headers);
    }

    /**
     * 编码任意 header 列表（用于构造非法 header 等场景）。
     */
    public byte[] encodeHeaders(List<HpackHeader> headers) {
        return encoder.encode(headers);
    }

    /**
     * 发一个自定义 header block 的 HEADERS 帧。
     */
    public void sendRawHeaders(int streamId, byte[] block, int flags) throws IOException {
        writeFrame(Http2FrameType.HEADERS, flags, streamId, block);
    }

    /**
     * 4 字节整数载荷。
     */
    public static byte[] intPayload(int value) {
        byte[] p = new byte[4];
        p[0] = (byte) (value >>> 24);
        p[1] = (byte) (value >>> 16);
        p[2] = (byte) (value >>> 8);
        p[3] = (byte) value;
        return p;
    }

    /**
     * 读一帧。
     */
    public Frame readFrame() throws IOException {
        byte[] head = readFully(9);
        int length = ((head[0] & 0xff) << 16) | ((head[1] & 0xff) << 8) | (head[2] & 0xff);
        int type     = head[3] & 0xff;
        int flags    = head[4] & 0xff;
        int streamId = ((head[5] & 0x7f) << 24) | ((head[6] & 0xff) << 16)
                | ((head[7] & 0xff) << 8) | (head[8] & 0xff);
        byte[] payload = length == 0 ? new byte[0] : readFully(length);
        return new Frame(type, flags, streamId, payload);
    }

    /**
     * 解 HPACK header block（用本客户端自己的解码器）。
     */
    public List<HpackHeader> decodeHeaders(byte[] block) {
        return decoder.decode(block, 0, block.length);
    }

    /**
     * 读一帧；超时则返回 null（用于断言「服务端此刻不会再发数据」）。
     */
    public Frame readFrameOrNull(long timeoutMillis) throws IOException {
        socket.setSoTimeout((int) timeoutMillis);
        try {
            return readFrame();
        } catch (java.net.SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(5000);
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

        public String typeName() {
            return Http2FrameType.name(type);
        }

        @Override
        public String toString() {
            return typeName() + "{streamId=" + streamId + ", flags=" + flags
                    + ", len=" + payload.length + "}";
        }
    }
}
