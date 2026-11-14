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
import io.edap.http.http2.hpack.HpackHeader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * <p>RFC 9113 §6.5.2 的默认值是 16384（帧头 9 字节 + 载荷 16384 = 16393 字节），
     * 而写缓冲是 256KB —— 一个缓冲装得下 15 个最大帧。</p>
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

    /**
     * 活跃的流。
     *
     * <p><b>★ 必须是 {@link ConcurrentHashMap}</b>：这张表会被<b>两个线程</b>改 ——
     * IO 线程（收 RST_STREAM、派发失败时摘流）和业务线程
     * （{@code endStream} / {@code writeTrailers} → {@code removeIfFullyClosed}）。
     * 普通 {@link HashMap} 在「一边遍历一边改」时抛
     * {@link ConcurrentModificationException}，而这个异常会从 decode 一路抛出去 ——
     * 连接状态就此损坏，对端永远等不到响应（表现为并发一高就全部超时）。</p>
     */
    private final Map<Integer, Http2Stream> streams = new ConcurrentHashMap<>();

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

    /**
     * 协议路由器（gRPC 之类）—— 决定一条流走默认 HTTP 处理还是协议处理器。
     *
     * <p>null = 全部走默认 HTTP 处理（既有行为，逐字节不变）。</p>
     */
    private Http2ProtocolRouter protocolRouter;

    private boolean goAwayReceived;

    public Http2Connection(HttpServerNioSession session, byte[] carry) {
        this.session = session;
        this.writer = new Http2FrameWriter(this);

        // 本端设置：关掉 push（服务端不启用）、限制并发流数与头列表长度
        localSettings.setEnablePush(0);
        localSettings.setMaxConcurrentStreams(128);
        localSettings.setMaxHeaderListSize(16384);
        // ★ 流级接收窗口给到 1MB（RFC 9113 §6.9.2 的默认值 65535 太小 ——
        //   一条 64KB 的请求塞不进一个窗口，在途请求数被压在 1 个）
        localSettings.setInitialWindowSize(Http2FlowController.STREAM_WINDOW);
        hpackDecoder.setMaxTableSize(localSettings.getHeaderTableSize());
        flowController.setLocalInitialWindowSize(localSettings.getInitialWindowSize());

        Server server = session.getServer();
        this.pathDecoder = (server instanceof HttpServer)
                ? ((HttpServer) server).getPathDecoder()
                : new PathDecoder();
        // 协议路由器与 pathDecoder 同源：都从 per-HttpServer 的配置里取
        this.protocolRouter = (server instanceof HttpServer)
                ? ((HttpServer) server).getProtocolRouter()
                : null;

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

    /**
     * 协议路由器（gRPC 之类）。null = 全部走默认 HTTP 处理。
     */
    public Http2ProtocolRouter getProtocolRouter() {
        return protocolRouter;
    }

    /**
     * 设置协议路由器。构造之后设置只对<b>之后新建的流</b>生效 ——
     * 已经收完 header 的流早就选好处理路径了。
     */
    public void setProtocolRouter(Http2ProtocolRouter protocolRouter) {
        this.protocolRouter = protocolRouter;
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

    /**
     * 写一个帧。
     *
     * <p><b>★ 出站整体加锁</b>：同一条流上的帧必须<b>严格保序</b>
     * （DATA 帧的顺序就是消息的顺序），而 gRPC 这类协议的业务线程
     * （服务端流式在别的线程推消息）与 IO 线程（补发被流控挂起的 body）
     * 会并发写。锁的粒度是「连接」—— 一条连接一个 writer，帧队列本来就是共享的。</p>
     */
    public synchronized void writeFrame(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        writer.writeFrame(type, flags, streamId, payload, offset, length);
    }

    public synchronized void writeFrame(int type, int flags, int streamId, byte[] payload) {
        writer.writeFrame(type, flags, streamId, payload, 0, payload.length);
    }

    /**
     * 写响应体（DATA 帧）—— 流控窗口不够时剩余数据挂到流上，等 WINDOW_UPDATE 再发。
     */
    public synchronized void writeBody(Http2Stream stream, byte[] data, int offset, int length) {
        int pos = offset;
        int remaining = length;
        while (remaining > 0) {
            int n = reserveSend(stream, remaining);
            if (n <= 0) {
                // 流控窗口耗尽 —— 剩余数据挂到流上，等 WINDOW_UPDATE 再发
                stream.appendPendingBody(data, pos, remaining);
                return;
            }
            writeFrame(Http2FrameType.DATA, 0, stream.getStreamId(), data, pos, n);
            pos += n;
            remaining -= n;
        }
    }

    /**
     * 写一个 HEADERS 帧（响应头 / trailers）。
     *
     * <p><b>★ 必须整体加锁</b>：HPACK 编码器是<b>连接级共享</b>的有状态对象
     * （动态表 + 索引），编码与写帧之间不能被打断，也不能与另一个线程的编码交错 ——
     * 交错会让动态表状态错乱，对端解出来的头是乱的。
     * 业务线程（比如 gRPC 的服务端流式在别的线程推消息）与 IO 线程并发写是常态。</p>
     *
     * @param stream 目标流
     * @param headers 待编码的头
     * @param flags   帧标志（{@code END_HEADERS} / {@code END_STREAM}）
     */
    public synchronized void writeHeaders(Http2Stream stream, List<HpackHeader> headers, int flags) {
        byte[] block = hpackEncoder.encode(headers);
        writeFrame(Http2FrameType.HEADERS, flags, stream.getStreamId(), block, 0, block.length);
    }

    /**
     * 写 trailers —— 以 HEADERS(END_STREAM) 帧结束这条流（RFC 9113 §8.1）。
     *
     * <p><b>gRPC 的响应就是以 trailers 结束的</b>：{@code grpc-status} /
     * {@code grpc-message} 都在里面。</p>
     *
     * <p>body 还没发完（流控挂起）时不能发 END_STREAM —— 否则对端会认为响应提前结束。
     * 这时 trailers 挂在流上，等 body 发完再发（见 {@link #flushPendingBody}）。</p>
     */
    public synchronized void writeTrailers(Http2Stream stream, Map<String, String> trailers) {
        if (trailers == null || trailers.isEmpty()) {
            endStream(stream);
            return;
        }
        if (stream.hasPendingBody()) {
            stream.setPendingTrailers(trailers);
            stream.setPendingEnd();
            return;
        }
        List<HpackHeader> headers = new ArrayList<>(trailers.size());
        for (Map.Entry<String, String> e : trailers.entrySet()) {
            headers.add(new HpackHeader(lower(e.getKey()), e.getValue()));
        }
        writeHeaders(stream, headers, Http2Flags.END_HEADERS | Http2Flags.END_STREAM);
        stream.markClosedLocally();
        removeIfFullyClosed(stream);
    }

    private static String lower(String name) {
        return name == null ? null : name.toLowerCase(Locale.ENGLISH);
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
     * <p>★ 没有这个回调，socket 写满后挂起的帧永远发不出去 ——
     * 内核的发送缓冲不保证一次收得下一整条响应（自动调优也不是无限的）。</p>
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
        if (Http2WriteStats.ENABLED) {
            Http2WriteStats.inboundAppends.increment();
            Http2WriteStats.inboundBytes.add(remain);
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
            if (Http2WriteStats.ENABLED) {
                Http2WriteStats.inboundGrowths.increment();
            }
        }
        // 直接落到累积缓冲的空闲区 —— 一次 copyMemory 到位，不分配临时数组。
        // （原来先 new byte[remain] 再 System.arraycopy，等于每读一次多一次分配 + 一次整块拷贝）
        buf.get(inBuf, inLen, remain);
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
            Http2Stream stream = removeStream(streamId);
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
                addStream(streamId, stream);
                sendRstStream(streamId, Http2ErrorCode.REFUSED_STREAM);
            } else {
                stream = new Http2Stream(streamId, this);
                addStream(streamId, stream);
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
            // ★ 总是试一次派发：协议处理器接管时「header 收齐即可派发」（不等 body）。
            //   普通 HTTP 流走 isReadyToDispatch() 的 endStream 条件，这里仍然是 no-op。
            dispatchIfReady(stream);
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
        try {
            stream.appendBody(inBuf, pos, end - pos - padLength);
        } catch (Throwable t) {
            // 协议处理器处理请求体时抛异常 —— 流级错误，不要干掉整个连接
            LOG.error("http2 stream body error: ", t);
            sendRstStream(streamId, Http2ErrorCode.INTERNAL_ERROR);
            removeStream(streamId);
            stream.markClosedLocally();
            return;
        }
        if (header.hasFlag(Http2Flags.END_STREAM)) {
            stream.markEndStream();
            // ★ 协议处理器接管时，响应可能已经在上面 appendBody 的过程中写完了
            //   （gRPC 的业务 handler 是同步回应的）。那时 closedRemotely 还是 false，
            //   writeTrailers 里的 removeIfFullyClosed 摘不掉这条流；等 END_STREAM
            //   在这里被标记时才真正「两侧都关闭」—— 必须补一次检查，
            //   否则流会永久留在 streams map 里，累积到 MAX_CONCURRENT_STREAMS
            //   之后新流全被 REFUSED_STREAM。
            removeIfFullyClosed(stream);
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
            flushPendingBodies();
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
        Http2Stream stream = removeStream(streamId);
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
            if (Http2WriteStats.ENABLED) {
                Http2WriteStats.connWuCount.increment();
                Http2WriteStats.connWuBytes.add(increment);
                if (connIdleSince != 0L) {
                    // 「连接级窗口见底 → 收到下一个 WU」之间的空等时长
                    Http2WriteStats.connIdleNanos.add(System.nanoTime() - connIdleSince);
                    connIdleSince = 0L;
                }
            }
            flowController.increaseConnSendWindow(increment);
            // 连接级窗口放开 ⇒ 所有挂起的流都试一次
            flushPendingBodies();
            return;
        }
        Http2Stream stream = streams.get(streamId);
        if (stream == null) {
            // 已关闭的流上的 WINDOW_UPDATE 直接忽略（RFC 9113 §6.9）
            return;
        }
        if (Http2WriteStats.ENABLED) {
            Http2WriteStats.streamWuCount.increment();
            Http2WriteStats.streamWuBytes.add(increment);
        }
        stream.increaseSendWindow(increment);
        // 走同一套「公平轮转」：这条流的额度刚放开，但连接级额度是共享的 ——
        // 让它单独吃光会把其它挂起的流饿死。
        flushPendingBodies();
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
        // ★ 单帧上限取 min(对端 MAX_FRAME_SIZE, 协议上限 16KB) ——
        //   对端可以通告到 16MB，但 RFC 9113 §4.2 的帧载荷上限是 16,384 字节。
        int n = Math.min(want, getPeerMaxFrameSize());
        n = Math.min(n, MAX_FRAME_PAYLOAD);
        int streamLimit = stream.getSendWindow();
        n = Math.min(n, streamLimit);
        int connLimit = flowController.connSendWindow();
        n = flowController.consumeConnSendWindow(n);
        stream.consumeSendWindow(n);
        if (Http2WriteStats.ENABLED && n <= 0 && want > 0) {
            // 窗口耗尽 —— 这次发送被流控挡住，响应要拆成多段
            Http2WriteStats.fcStalls.increment();
            if (connLimit <= 0) {
                Http2WriteStats.connStalls.increment();
                if (connIdleSince == 0L) {
                    connIdleSince = System.nanoTime();
                    Http2WriteStats.connWindowEmpty.increment();
                }
            } else if (streamLimit <= 0) {
                Http2WriteStats.streamStalls.increment();
            }
        }
        return n;
    }

    /**
     * 连接级窗口见底的那一刻（纳秒）—— 0 表示窗口还没见底过。
     *
     * <p>诊断用：量「服务端空等额度」等了多久。</p>
     */
    private volatile long connIdleSince;

    /**
     * 补发所有因流控挂起的 body。
     *
     * <p><b>★ 必须两遍</b>：补发 DATA 之后的收尾（{@code endStream} /
     * {@code writeTrailers} → {@code removeIfFullyClosed} → {@code streams.remove(...)}）
     * <b>本身就在改 streams 表</b>。一边遍历一边改，遍历器会在下一轮 {@code next()}
     * 时发现表被改过，抛 {@link ConcurrentModificationException}。
     * 这个异常从 decode 一路抛出去，连接状态就此损坏，对端永远等不到响应
     * （表现就是并发一上来全部超时）。</p>
     *
     * <p>第一遍只收集、不改表；第二遍才补发。绝大多数时候没有任何挂起 body，
     * 第一遍扫完就返回，一个 ArrayList 都不分配。</p>
     *
     * <p><b>★ 必须轮转，不能让一条流把额度吃光</b>：连接级发送窗口是<b>所有流共享</b>的。
     * 如果第一条流就把额度用光，剩下的流要等<b>下一次</b> {@code WINDOW_UPDATE} ——
     * 而下一次 {@code WINDOW_UPDATE} 要等客户端把数据消费掉才会来。
     * 于是 N 条并发的响应被<b>串行化</b>：最后一条要多等 N-1 个往返。
     * 实测（grpc-java 客户端，它只给 1MB 的连接级窗口；32 并发 / 64KB 负载）：
     * 串行化时 p50 3.9ms、p99 19ms；轮转后与 grpc-java 服务端持平。</p>
     */
    private synchronized void flushPendingBodies() {
        List<Http2Stream> pending = null;
        for (Http2Stream stream : streams.values()) {
            if (stream.hasPendingBody() || stream.isPendingEnd()) {
                if (pending == null) {
                    pending = new ArrayList<>();
                }
                pending.add(stream);
            }
        }
        if (pending == null) {
            return;
        }
        // 轮转：每轮每条流只发一帧，走完一圈再看还有没有额度 ——
        // 保证额度在流之间<b>公平</b>分配，而不是被第一条流吃光。
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Http2Stream stream : pending) {
                if (!stream.hasPendingBody()) {
                    continue;
                }
                int n = reserveSend(stream, stream.pendingBodyLength());
                if (n <= 0) {
                    // 这条流的额度用完了（流级窗口或连接级窗口耗尽）—— 下一轮再说
                    continue;
                }
                writeFrame(Http2FrameType.DATA, 0, stream.getStreamId(),
                        stream.pendingBodyArray(), stream.pendingBodyOffset(), n);
                stream.advancePendingBody(n);
                progress = true;
            }
        }
        // body 发完的流再收尾（trailers / END_STREAM）——
        // 这一步会改 streams 表，但遍历的是上面收集好的 ArrayList，安全。
        for (Http2Stream stream : pending) {
            if (stream.hasPendingBody() || !stream.isPendingEnd()) {
                continue;
            }
            if (Http2WriteStats.ENABLED) {
                long since = stream.takePendingSinceNanos();
                if (since > 0L) {
                    Http2WriteStats.fcStallNanos.add(System.nanoTime() - since);
                }
            }
            stream.clearPendingEnd();
            Map<String, String> trailers = stream.takePendingTrailers();
            if (trailers != null) {
                writeTrailers(stream, trailers);
            } else {
                endStream(stream);
            }
        }
    }

    /**
     * 响应结束 —— 发一个空的 DATA 帧并带上 END_STREAM。
     *
     * <p>body 还没发完（流控挂起）时不能发 END_STREAM，否则对端会认为响应提前结束。</p>
     */
    public synchronized void endStream(Http2Stream stream) {
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
        if (stream == null) {
            return;
        }
        if (stream.isReadyToDispatch()) {
            try {
                long t0 = Http2WriteStats.ENABLED ? System.nanoTime() : 0L;
                stream.dispatch();
                if (Http2WriteStats.ENABLED) {
                    // 业务 handler 的整段耗时（解码请求 → 组响应 → 编码 → 写帧）
                    Http2WriteStats.handlerNanos.add(System.nanoTime() - t0);
                    Http2WriteStats.handlerCalls.increment();
                }
            } catch (Throwable t) {
                LOG.error("http2 stream dispatch error: ", t);
                sendRstStream(stream.getStreamId(), Http2ErrorCode.INTERNAL_ERROR);
                removeStream(stream.getStreamId());
                stream.markClosedLocally();
                return;
            }
        }
        // 协议处理器接管时：请求侧的 END_STREAM 到达 → 通知它请求体结束。
        // 这一步必须独立于 dispatch —— 协议流在 header 收齐时就派发了，
        // 最后一个 DATA 帧带 END_STREAM 到达时流已经是 dispatched 状态。
        if (stream.isDispatched() && stream.hasProtocolHandler() && stream.isEndStream()) {
            try {
                stream.endProtocol();
            } catch (Throwable t) {
                LOG.error("http2 protocol end error: ", t);
                sendRstStream(stream.getStreamId(), Http2ErrorCode.INTERNAL_ERROR);
                removeStream(stream.getStreamId());
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
        // ★ 主动把连接级接收窗口撑到 CONN_WINDOW（RFC 9113 §6.9.2：连接级窗口不受
        //   SETTINGS_INITIAL_WINDOW_SIZE 影响，只能由接收方用 WINDOW_UPDATE 撑大）。
        //   不撑的话一条连接上只能有 64KB 在途 —— 并发再高也没有意义。
        int increment = flowController.growConnRecvWindow();
        if (increment > 0) {
            writeFrame(Http2FrameType.WINDOW_UPDATE, 0, 0, Http2FrameCodec.intPayload(increment));
        }
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
            removeStream(stream.getStreamId());
        }
    }

    /**
     * 把流登记进 {@code streams} 表。
     */
    private void addStream(int streamId, Http2Stream stream) {
        streams.put(streamId, stream);
        Http2WriteStats.liveStreams.incrementAndGet();
    }

    /**
     * 把流从 {@code streams} 表摘掉 —— <b>只有真的摘掉了才减计数</b>
     * （同一条流可能被摘两次）。
     */
    private Http2Stream removeStream(int streamId) {
        Http2Stream removed = streams.remove(streamId);
        if (removed != null) {
            Http2WriteStats.liveStreams.decrementAndGet();
        }
        return removed;
    }

    public boolean isGoAwayReceived() {
        return goAwayReceived;
    }

    public Map<Integer, Http2Stream> getStreams() {
        return streams;
    }
}
