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

package io.edap.http.server.http2;

import io.edap.Server;
import io.edap.buffer.FastBuf;
import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameCodec;
import io.edap.http.http2.Http2FrameHeader;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.http2.Http2Settings;
import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackEncoder;
import io.edap.http.server.HttpConnection;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerNioSession;
import io.edap.http.server.rangedecoder.PathDecoder;
import io.edap.http.server.rangedecoder.QueryStringDecoder;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.pool.Pool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP/2 连接（RFC 9113）。
 *
 * <p><b>★ 入站必须自建累积缓冲</b>：{@code DisruptorReadDispatcher} 每次 read 前都会
 * {@code buf.reset()}，跨 read 的字节<b>不保留</b>。HTTP/1.1 靠解析状态机续解，
 * HTTP/2 的帧是定长的，必须自己把字节攒够一帧再解析。</p>
 *
 * <p><b>★ 每条流独立的 request / response / body 缓冲</b>：HTTP/2 是多路复用的，
 * 不能沿用 HTTP/1.1 的线程级单例。</p>
 *
 * <p><b>★ 流控</b>：连接级窗口在 {@link Http2FlowController}，流级窗口在
 * {@link Http2Stream}。发 DATA 前必须扣窗口，窗口耗尽时剩余数据挂在流上等
 * WINDOW_UPDATE。</p>
 */
public class Http2Connection implements HttpConnection {

    static final Logger LOG = LoggerManager.getLogger(Http2Connection.class);

    /**
     * 单个 DATA 帧的最大载荷。
     *
     * <p>RFC 9113 §6.5.2 的默认值是 16384，够用且能装进 32KB 的写缓冲
     * （帧头 9 字节 + 载荷 16384 = 16393 &lt; 32768）。</p>
     */
    static final int MAX_FRAME_PAYLOAD = 16384;

    /** HTTP/2 连接前言（RFC 9113 §3.4）：24 字节固定串 */
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] EMPTY = new byte[0];

    private final HttpServerNioSession session;

    private final Http2FrameWriter writer;
    private final HpackDecoder hpackDecoder = new HpackDecoder();
    private final HpackEncoder hpackEncoder = new HpackEncoder();
    private final QueryStringDecoder queryDecoder = new QueryStringDecoder();

    /** 连接级流控窗口 */
    private final Http2FlowController flowController = new Http2FlowController();

    /** 本端通告给对端的设置 */
    private final Http2Settings localSettings = new Http2Settings();
    /** 对端通告给本端的设置 */
    private final Http2Settings remoteSettings = new Http2Settings();

    private final Map<Integer, Http2Stream> streams = new HashMap<>();

    /** per-HttpServer 的路径解码器（与 HTTP/1.1 共用，保证路由语义一致） */
    private final PathDecoder pathDecoder;

    /** 入站累积缓冲 */
    private byte[] inBuf = new byte[8192];
    private int inLen;
    private int inPos;

    /** 已比对过的连接前言字节数 */
    private int prefacePos;
    private boolean prefaceDone;

    /** 正在接收 header block 的流（CONTINUATION 必须跟随它） */
    private Http2Stream headerBlockStream;

    /** 对端发起的最大流标识符 */
    private int lastStreamId;

    /**
     * h2c 升级：承载「触发升级的那个 HTTP/1.1 请求」的流（RFC 7540 §3.2）。
     *
     * <p><b>刻意不放进 {@link #streams}</b>：这条流的请求早就「完成」了
     *（对端方向半关闭），响应发完就该消失。但它必须留着 ——
     * 客户端如果违规再对 stream 1 发 HEADERS，要回流级错误（RST_STREAM），
     * 而不是让 {@code streamId 必须递增} 的连接级错误把整个连接干掉。</p>
     */
    private Http2Stream upgradeStream;

    /** 服务器连接前言（SETTINGS 帧）是否已经发出 */
    private boolean localSettingsSent;

    private boolean goAwayReceived;

    public Http2Connection(HttpServerNioSession session, byte[] carry) {
        this.session = session;
        this.writer = new Http2FrameWriter(this);

        // 本端设置：关掉 push（服务端不启用）、限制并发流数与头列表长度
        localSettings.setEnablePush(0);
        localSettings.setMaxConcurrentStreams(128);
        localSettings.setMaxHeaderListSize(16384);
        hpackDecoder.setMaxTableSize(localSettings.getHeaderTableSize());
        flowController.setLocalInitialWindowSize(localSettings.getInitialWindowSize());

        Server server = session.getServer();
        this.pathDecoder = (server instanceof HttpServer)
                ? ((HttpServer) server).getPathDecoder()
                : new PathDecoder();

        // 嗅探阶段已经比对过 carry.length 个字节（前言的开头）
        if (carry != null && carry.length > 0) {
            if (carry.length > PREFACE.length) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "连接前言过长");
            }
            this.prefacePos = carry.length;
        }
    }

    public HttpServerNioSession getSession() {
        return session;
    }

    public HpackDecoder getHpackDecoder() {
        return hpackDecoder;
    }

    public HpackEncoder getHpackEncoder() {
        return hpackEncoder;
    }

    public PathDecoder getPathDecoder() {
        return pathDecoder;
    }

    public QueryStringDecoder getQueryDecoder() {
        return queryDecoder;
    }

    public Http2Settings getLocalSettings() {
        return localSettings;
    }

    public Http2Settings getRemoteSettings() {
        return remoteSettings;
    }

    public Http2FlowController getFlowController() {
        return flowController;
    }

    /**
     * 对端允许的单帧最大载荷（发 DATA 帧时按它切分）。
     */
    public int getPeerMaxFrameSize() {
        return remoteSettings.getMaxFrameSize();
    }

    /**
     * 应用对端通过 {@code HTTP2-Settings} 头通告的设置（RFC 9113 §3.2.1）。
     *
     * <p><b>为什么必须提前应用</b>：h2c 升级时客户端在 HTTP/1.1 请求里就通告了自己的
     * SETTINGS（尤其是 {@code INITIAL_WINDOW_SIZE}）。如果服务端等到客户端的 SETTINGS 帧
     * 到达才应用，中间那段时间里服务端按默认的 65535 窗口发数据，而客户端通告的窗口
     * 可能更小 ⇒ 客户端判定为 FLOW_CONTROL_ERROR 并关闭连接。</p>
     *
     * <p>客户端随后还会发自己的 SETTINGS 帧，那时会再应用一次（幂等：
     * 值没变则窗口差值为 0）。</p>
     *
     * <p>载荷非法时只告警不抛异常 —— 客户端真正的 SETTINGS 帧马上就会到，
     * 那时按正常路径处理（非法值 ⇒ 协议错误）。</p>
     */
    public void applyPeerSettings(byte[] payload) {
        try {
            remoteSettings.apply(payload, 0, payload.length);
        } catch (Http2Exception e) {
            LOG.warn("HTTP2-Settings 非法，忽略: ", l -> l.threw(e));
            return;
        }
        int delta = flowController.applyPeerInitialWindowSize(remoteSettings.getInitialWindowSize());
        if (delta != 0) {
            for (Http2Stream stream : streams.values()) {
                stream.adjustSendWindow(delta);
            }
        }
    }

    public void writeFrame(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        writer.writeFrame(type, flags, streamId, payload, offset, length);
    }

    public void writeFrame(int type, int flags, int streamId, byte[] payload) {
        writer.writeFrame(type, flags, streamId, payload, 0, payload.length);
    }

    /**
     * ★ h2c 升级：创建 stream 1（RFC 7540 §3.2）。
     *
     * <p>RFC 7540 §3.2：触发升级的 HTTP/1.1 请求被分配 stream 1，
     * 且「Stream 1 is implicitly "half-closed" from the client toward the server」。
     * 服务端必须用 stream 1 给出这个请求的响应。</p>
     */
    public Http2Stream createUpgradeStream() {
        Http2Stream stream = new Http2Stream(1, this);
        stream.markUpgradeStream();
        this.upgradeStream = stream;
        // 客户端发起的流标识符必须递增 —— 1 已经被升级请求占用
        lastStreamId = 1;
        return stream;
    }

    /**
     * ★ h2c 升级：启动 HTTP/2 连接，立刻发出服务器连接前言（SETTINGS 帧）。
     *
     * <p>RFC 7540 §3.2：「The first HTTP/2 frame sent by the server MUST be a server
     * connection preface consisting of a SETTINGS frame.」升级请求的响应帧必须排在
     * SETTINGS 之后，所以这里要先把 SETTINGS 发出去 —— 不能等客户端的连接前言到达
     * （那要等下一个 read 循环，而响应必须马上发）。</p>
     */
    public void startAfterUpgrade() {
        sendLocalSettings();
    }

    @Override
    public boolean decode(FastBuf buf) throws IOException {
        try {
            writer.resetFrameCount();
            appendInbound(buf);
            boolean produced = false;
            if (!prefaceDone) {
                if (!matchPreface()) {
                    // 前言还没收全 —— 等下一次 read
                    compactInbound();
                    return false;
                }
                prefaceDone = true;
                // 服务端连接前言的第一帧就是 SETTINGS（RFC 9113 §3.4）
                sendLocalSettings();
                produced = true;
            }
            if (processFrames()) {
                produced = true;
            }
            compactInbound();
            return produced || writer.getFrameCount() > 0;
        } catch (Http2Exception e) {
            goAway(e.getErrorCode(), e.getMessage());
            try {
                // GOAWAY 必须真的写到 socket 上，否则对端只看到连接被关闭，
                // 无法区分「服务端主动优雅关闭」和「协议错误」。
                writer.flushToSocket();
            } catch (Throwable t) {
                LOG.warn("send GOAWAY failed: ", l -> l.threw(t));
            }
            throw new IOException("HTTP/2 协议错误: " + e.getMessage(), e);
        }
    }

    @Override
    public void flush(Pool<FastBuf> bbPool) throws IOException {
        writer.setPool(bbPool);
        writer.flushToSocket();
    }

    /**
     * NIO 层把写队列头写完之后回调 —— 继续发剩下的帧。
     *
     * <p>★ 没有这个回调，socket 写满后挂起的帧永远发不出去：
     * socket 发送缓冲只有 16KB，而一个发送窗口是 64KB。</p>
     */
    @Override
    public void onWriteBufDrained() {
        writer.onWriteBufDrained();
    }

    // ------------------------------------------------------------------
    // 入站
    // ------------------------------------------------------------------

    private void appendInbound(FastBuf buf) {
        int remain = buf.remain();
        if (remain <= 0) {
            return;
        }
        if (inLen + remain > inBuf.length) {
            compactInbound();
            int cap = inBuf.length;
            while (cap < inLen + remain) {
                cap <<= 1;
            }
            byte[] nb = new byte[cap];
            System.arraycopy(inBuf, 0, nb, 0, inLen);
            inBuf = nb;
        }
        byte[] tmp = new byte[remain];
        buf.get(tmp);
        System.arraycopy(tmp, 0, inBuf, inLen, remain);
        inLen += remain;
    }

    private void compactInbound() {
        if (inPos == 0) {
            return;
        }
        int left = inLen - inPos;
        if (left > 0) {
            System.arraycopy(inBuf, inPos, inBuf, 0, left);
        }
        inLen = left;
        inPos = 0;
    }

    /**
     * 比对连接前言。数据不足返回 false（等下一次 read）。
     */
    private boolean matchPreface() {
        while (prefacePos < PREFACE.length) {
            if (inPos >= inLen) {
                return false;
            }
            byte b = inBuf[inPos++];
            if (b != PREFACE[prefacePos]) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                        "HTTP/2 连接前言不匹配，位置 " + prefacePos);
            }
            prefacePos++;
        }
        return true;
    }

    private boolean processFrames() {
        boolean produced = false;
        while (true) {
            int avail = inLen - inPos;
            if (avail < Http2FrameCodec.FRAME_HEADER_LENGTH) {
                break;
            }
            Http2FrameHeader header = Http2FrameCodec.readHeader(inBuf, inPos);
            int length = header.getLength();
            if (length > localSettings.getMaxFrameSize()) {
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR,
                        "帧长度超过 MAX_FRAME_SIZE: " + length);
            }
            if (avail - Http2FrameCodec.FRAME_HEADER_LENGTH < length) {
                // 载荷没收全 —— 等下一次 read
                break;
            }
            int payloadStart = inPos + Http2FrameCodec.FRAME_HEADER_LENGTH;
            handleFrame(header, payloadStart, length);
            inPos = payloadStart + length;
            produced = true;
        }
        return produced;
    }

    private void handleFrame(Http2FrameHeader header, int offset, int length) {
        try {
            dispatchFrame(header, offset, length);
        } catch (Http2Exception e) {
            if (!e.isStreamError()) {
                throw e;
            }
            // 流级错误：只关这一条流，连接继续可用（RFC 9113 §5.4.2）
            int streamId = header.getStreamId();
            sendRstStream(streamId, e.getErrorCode());
            Http2Stream stream = streams.remove(streamId);
            if (stream != null) {
                stream.markClosedLocally();
            }
            if (headerBlockStream == stream) {
                headerBlockStream = null;
            }
        }
    }

    private void dispatchFrame(Http2FrameHeader header, int offset, int length) {
        switch (header.getType()) {
            case Http2FrameType.DATA:
                onData(header, offset, length);
                break;
            case Http2FrameType.HEADERS:
                onHeaders(header, offset, length);
                break;
            case Http2FrameType.PRIORITY:
                // RFC 9113 §6.3：收即忽略
                break;
            case Http2FrameType.RST_STREAM:
                onRstStream(header, offset, length);
                break;
            case Http2FrameType.SETTINGS:
                onSettings(header, offset, length);
                break;
            case Http2FrameType.PUSH_PROMISE:
                // 服务端不启用 push —— 收到就是连接级错误
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "不接受 PUSH_PROMISE");
            case Http2FrameType.PING:
                onPing(header, offset, length);
                break;
            case Http2FrameType.GOAWAY:
                onGoAway(header, offset, length);
                break;
            case Http2FrameType.WINDOW_UPDATE:
                onWindowUpdate(header, offset, length);
                break;
            case Http2FrameType.CONTINUATION:
                onContinuation(header, offset, length);
                break;
            default:
                // RFC 9113 §4.1：未知帧类型必须被忽略
                break;
        }
    }

    private void onHeaders(Http2FrameHeader header, int offset, int length) {
        int streamId = header.getStreamId();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "HEADERS 的 streamId 不能为 0");
        }
        Http2Stream stream = streams.get(streamId);
        if (upgradeStream != null && streamId == upgradeStream.getStreamId()) {
            // RFC 7540 §3.2：stream 1 已经是「触发升级的那个 HTTP/1.1 请求」，
            // 客户端不该再对它发 HEADERS（curl / JDK 都不会重发）
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                    "stream " + streamId + " 已经是升级请求，不能再发 HEADERS", true);
        }
        if (stream == null) {
            if ((streamId & 1) == 0) {
                // RFC 9113 §5.1.1：客户端发起的流标识符必须是奇数
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                        "客户端流的 streamId 必须是奇数: " + streamId);
            }
            if (streamId <= lastStreamId) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                        "客户端流的 streamId 必须递增: " + streamId);
            }
            lastStreamId = streamId;
            if (streams.size() >= localSettings.getMaxConcurrentStreams()) {
                // RFC 9113 §5.1.2：超过并发上限 → RST_STREAM(REFUSED_STREAM)
                stream = new Http2Stream(streamId, this);
                stream.markRefused();
                streams.put(streamId, stream);
                sendRstStream(streamId, Http2ErrorCode.REFUSED_STREAM);
            } else {
                stream = new Http2Stream(streamId, this);
                streams.put(streamId, stream);
            }
        }

        int pos = offset;
        int end = offset + length;
        int padLength = 0;
        if (header.hasFlag(Http2Flags.PADDED)) {
            if (pos >= end) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "HEADERS 载荷被截断");
            }
            padLength = inBuf[pos++] & 0xff;
        }
        if (header.hasFlag(Http2Flags.PRIORITY)) {
            if (pos + 5 > end) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "HEADERS 优先级字段被截断");
            }
            pos += 5;
        }
        if (end - pos < padLength) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "HEADERS 的 padding 长度非法");
        }
        stream.appendHeaderBlock(inBuf, pos, end - pos - padLength);
        headerBlockStream = stream;
        if (header.hasFlag(Http2Flags.END_HEADERS)) {
            headerBlockStream = null;
            stream.completeHeaders();
        }
        if (header.hasFlag(Http2Flags.END_STREAM)) {
            stream.markEndStream();
        }
        dispatchIfReady(stream);
    }

    private void onContinuation(Http2FrameHeader header, int offset, int length) {
        Http2Stream stream = headerBlockStream;
        if (stream == null) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                    "收到没有前置 HEADERS 的 CONTINUATION");
        }
        if (header.getStreamId() != stream.getStreamId()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                    "CONTINUATION 的 streamId 与 HEADERS 不一致");
        }
        stream.appendHeaderBlock(inBuf, offset, length);
        if (header.hasFlag(Http2Flags.END_HEADERS)) {
            headerBlockStream = null;
            stream.completeHeaders();
            if (stream.isEndStream()) {
                dispatchIfReady(stream);
            }
        }
    }

    private void onData(Http2FrameHeader header, int offset, int length) {
        int streamId = header.getStreamId();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "DATA 的 streamId 不能为 0");
        }
        Http2Stream stream = streams.get(streamId);
        if (stream == null) {
            if (streamId > lastStreamId) {
                // RFC 9113 §5.1：idle 流上的帧是连接级错误
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                        "收到 idle 流 " + streamId + " 上的 DATA");
            }
            // 已关闭的流上的 DATA → 流级错误（RFC 9113 §6.9）
            throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED,
                    "收到已关闭流 " + streamId + " 上的 DATA", true);
        }
        int pos = offset;
        int end = offset + length;
        int padLength = 0;
        if (header.hasFlag(Http2Flags.PADDED)) {
            if (pos >= end) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "DATA 载荷被截断");
            }
            padLength = inBuf[pos++] & 0xff;
        }
        if (end - pos < padLength) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "DATA 的 padding 长度非法");
        }
        // ★ 流控：整个 DATA 载荷（含 padding）都受流控约束（RFC 9113 §6.9）
        flowController.consumeConnRecvWindow(length);
        stream.consumeRecvWindow(length);
        stream.appendBody(inBuf, pos, end - pos - padLength);
        if (header.hasFlag(Http2Flags.END_STREAM)) {
            stream.markEndStream();
        }
        grantRecvCredit(stream);
        dispatchIfReady(stream);
    }

    /**
     * 把收数据的窗口额度还给对端（RFC 9113 §6.9）。
     *
     * <p>必须「边收边还」：body 是全部收齐才派发的，如果等收完再还，
     * 超过一个窗口（64KB）的请求体会直接卡死。</p>
     */
    private void grantRecvCredit(Http2Stream stream) {
        int streamCredit = stream.takeRecvCredit();
        if (streamCredit > 0) {
            writeFrame(Http2FrameType.WINDOW_UPDATE, 0, stream.getStreamId(),
                    Http2FrameCodec.intPayload(streamCredit));
        }
        int connCredit = flowController.takeConnRecvCredit();
        if (connCredit > 0) {
            writeFrame(Http2FrameType.WINDOW_UPDATE, 0, 0,
                    Http2FrameCodec.intPayload(connCredit));
        }
    }

    private void onSettings(Http2FrameHeader header, int offset, int length) {
        if (header.hasFlag(Http2Flags.ACK)) {
            if (length != 0) {
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "SETTINGS ACK 不能带载荷");
            }
            return;
        }
        remoteSettings.apply(inBuf, offset, length);
        int delta = flowController.applyPeerInitialWindowSize(remoteSettings.getInitialWindowSize());
        if (delta != 0) {
            // RFC 9113 §6.9.2：INITIAL_WINDOW_SIZE 变更要传播给所有已存在的流
            for (Http2Stream stream : streams.values()) {
                stream.adjustSendWindow(delta);
            }
            // 窗口变大 ⇒ 之前挂起的 body 可以继续发
            for (Http2Stream stream : streams.values()) {
                flushPendingBody(stream);
            }
        }
        writeFrame(Http2FrameType.SETTINGS, Http2Flags.ACK, 0, EMPTY, 0, 0);
    }

    private void onPing(Http2FrameHeader header, int offset, int length) {
        if (length != 8) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "PING 载荷必须是 8 字节");
        }
        if (header.hasFlag(Http2Flags.ACK)) {
            return;
        }
        byte[] payload = new byte[8];
        System.arraycopy(inBuf, offset, payload, 0, 8);
        writeFrame(Http2FrameType.PING, Http2Flags.ACK, 0, payload, 0, 8);
    }

    private void onRstStream(Http2FrameHeader header, int offset, int length) {
        if (length != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "RST_STREAM 载荷必须是 4 字节");
        }
        int streamId = header.getStreamId();
        Http2Stream stream = streams.remove(streamId);
        if (stream != null) {
            stream.markClosedLocally();
        }
        if (headerBlockStream == stream) {
            headerBlockStream = null;
        }
    }

    private void onGoAway(Http2FrameHeader header, int offset, int length) {
        if (length < 8) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "GOAWAY 载荷至少 8 字节");
        }
        goAwayReceived = true;
    }

    /**
     * WINDOW_UPDATE（RFC 9113 §6.9）。
     */
    private void onWindowUpdate(Http2FrameHeader header, int offset, int length) {
        if (length != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "WINDOW_UPDATE 载荷必须是 4 字节");
        }
        int increment = ((inBuf[offset] & 0xff) << 24)
                | ((inBuf[offset + 1] & 0xff) << 16)
                | ((inBuf[offset + 2] & 0xff) << 8)
                | (inBuf[offset + 3] & 0xff);
        int streamId = header.getStreamId();
        if (increment == 0) {
            if (streamId == 0) {
                // RFC 9113 §6.9：连接级 WINDOW_UPDATE 增量不能为 0
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE 增量不能为 0");
            }
            // 流级增量为 0 是流级错误
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                    "WINDOW_UPDATE 增量不能为 0", true);
        }
        if (streamId == 0) {
            flowController.increaseConnSendWindow(increment);
            // 连接级窗口放开 ⇒ 所有挂起的流都试一次
            for (Http2Stream stream : streams.values()) {
                flushPendingBody(stream);
            }
            return;
        }
        Http2Stream stream = streams.get(streamId);
        if (stream == null) {
            // 已关闭的流上的 WINDOW_UPDATE 直接忽略（RFC 9113 §6.9）
            return;
        }
        stream.increaseSendWindow(increment);
        flushPendingBody(stream);
    }

    // ------------------------------------------------------------------
    // 出站与流控
    // ------------------------------------------------------------------

    /**
     * 为一次 DATA 发送预留窗口。
     *
     * @param stream 目标流
     * @param want   想发的字节数
     * @return 本次实际能发的字节数（0 表示窗口耗尽）
     */
    int reserveSend(Http2Stream stream, int want) {
        // ★ 单帧上限取 min(对端 MAX_FRAME_SIZE, 本端写缓冲能装下的最大值) ——
        //   对端可以通告到 16MB，但本端的写缓冲只有 32KB，装不下超大的帧。
        int n = Math.min(want, getPeerMaxFrameSize());
        n = Math.min(n, MAX_FRAME_PAYLOAD);
        n = Math.min(n, stream.getSendWindow());
        n = flowController.consumeConnSendWindow(n);
        stream.consumeSendWindow(n);
        return n;
    }

    /**
     * 把因流控挂起的 body 继续发出去。
     */
    private void flushPendingBody(Http2Stream stream) {
        while (stream.hasPendingBody()) {
            int n = reserveSend(stream, stream.pendingBodyLength());
            if (n <= 0) {
                return;
            }
            writeFrame(Http2FrameType.DATA, 0, stream.getStreamId(),
                    stream.pendingBodyArray(), stream.pendingBodyOffset(), n);
            stream.advancePendingBody(n);
        }
        if (stream.isPendingEnd()) {
            stream.clearPendingEnd();
            endStream(stream);
        }
    }

    /**
     * 响应结束 —— 发一个空的 DATA 帧并带上 END_STREAM。
     *
     * <p>body 还没发完（流控挂起）时不能发 END_STREAM，否则对端会认为响应提前结束。</p>
     */
    public void endStream(Http2Stream stream) {
        if (stream.hasPendingBody()) {
            stream.setPendingEnd();
            return;
        }
        writeFrame(Http2FrameType.DATA, Http2Flags.END_STREAM, stream.getStreamId(), EMPTY, 0, 0);
        stream.markClosedLocally();
        removeIfFullyClosed(stream);
    }

    /**
     * 发 RST_STREAM（RFC 9113 §5.4.2）。
     */
    private void sendRstStream(int streamId, int errorCode) {
        if (streamId == 0) {
            return;
        }
        writeFrame(Http2FrameType.RST_STREAM, 0, streamId, Http2FrameCodec.intPayload(errorCode));
    }

    private void dispatchIfReady(Http2Stream stream) {
        if (stream != null && stream.isReadyToDispatch()) {
            try {
                stream.dispatch();
            } catch (Throwable t) {
                LOG.error("http2 stream dispatch error: ", t);
                sendRstStream(stream.getStreamId(), Http2ErrorCode.INTERNAL_ERROR);
                streams.remove(stream.getStreamId());
                stream.markClosedLocally();
            }
        }
    }

    private void sendLocalSettings() {
        if (localSettingsSent) {
            // 已经发过了（h2c 升级路径：先发 SETTINGS，等客户端连接前言到达时不再重发）
            return;
        }
        localSettingsSent = true;
        byte[] payload = localSettings.encode();
        writeFrame(Http2FrameType.SETTINGS, 0, 0, payload, 0, payload.length);
    }

    /**
     * 发 GOAWAY 并终止连接（RFC 9113 §6.8）。
     */
    private void goAway(int errorCode, String debug) {
        byte[] debugBytes = debug == null ? EMPTY : debug.getBytes(StandardCharsets.ISO_8859_1);
        byte[] payload = new byte[8 + debugBytes.length];
        payload[0] = (byte) (lastStreamId >>> 24);
        payload[1] = (byte) (lastStreamId >>> 16);
        payload[2] = (byte) (lastStreamId >>> 8);
        payload[3] = (byte) lastStreamId;
        payload[4] = (byte) (errorCode >>> 24);
        payload[5] = (byte) (errorCode >>> 16);
        payload[6] = (byte) (errorCode >>> 8);
        payload[7] = (byte) errorCode;
        System.arraycopy(debugBytes, 0, payload, 8, debugBytes.length);
        try {
            writeFrame(Http2FrameType.GOAWAY, 0, 0, payload, 0, payload.length);
        } catch (Throwable t) {
            LOG.warn("send GOAWAY failed: ", l -> l.threw(t));
        }
    }

    /**
     * 两侧都关闭了的流从连接里摘掉（否则 streams map 会无限增长）。
     */
    private void removeIfFullyClosed(Http2Stream stream) {
        if (stream.isFullyClosed()) {
            streams.remove(stream.getStreamId());
        }
    }

    public boolean isGoAwayReceived() {
        return goAwayReceived;
    }

    public Map<Integer, Http2Stream> getStreams() {
        return streams;
    }
}
