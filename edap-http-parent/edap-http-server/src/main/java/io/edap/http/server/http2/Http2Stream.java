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

import io.edap.http.HttpResponse;
import io.edap.http.HttpVersion;
import io.edap.http.HeaderValue;
import io.edap.http.ParameterValue;
import io.edap.http.PathInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.body.HeapHttpBody;
import io.edap.http.cache.MethodCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;
import io.edap.http.http2.hpack.HpackHeader;
import io.edap.http.server.rangedecoder.QueryStringDecoder;
import io.edap.util.ByteData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.edap.nio.NioSession.THREAD_WRITE_BUF;

/**
 * 一条 HTTP/2 流。
 *
 * <p><b>★ 每个流必须持有自己的 request / response / 缓冲</b>：HTTP/1.1 路径下
 * {@code HttpResponse} 是线程级单例（{@code RangeHttpRequestDecoder} 的 ThreadLocal），
 * HTTP/2 是多路复用的，多条流并发交错 —— 沿用单例会导致响应头跨流泄漏。
 * 请求侧同理：{@code ValueHttpRequest} 不能复用。</p>
 *
 * <p>body 也是流级的：{@code HttpNioSession.getOrCreateBodyAccum} 是 session 级的，
 * 多流并发时会把 A 流的 body 累积到 B 流的缓冲里。</p>
 *
 * <p><b>流控窗口也是流级的</b>（RFC 9113 §6.9）：发送窗口初值取对端通告的
 * SETTINGS_INITIAL_WINDOW_SIZE，接收窗口初值取本端通告的值。</p>
 */
public class Http2Stream {

    private final int streamId;
    private final Http2Connection connection;

    private final ValueHttpRequest request = new ValueHttpRequest();
    private final HttpResponse response = new HttpResponse();

    /** path 与 query 各一个 dataRange（PathDecoder / QueryStringDecoder 会互相 reset） */
    private final HttpFastBufDataRange pathRange = new HttpFastBufDataRange();
    private final HttpFastBufDataRange queryRange = new HttpFastBufDataRange();

    /** 累积中的 header block（HEADERS + 若干 CONTINUATION） */
    private byte[] headerBlock = new byte[256];
    private int headerBlockLen;

    private boolean headersComplete;
    private boolean endStream;
    private boolean dispatched;

    /** true = 该流被服务端拒绝（超过并发上限）→ 不派发，只吞掉数据 */
    private boolean refused;

    /**
     * true = 这条流承载的是「触发 h2c 升级的那个 HTTP/1.1 请求」（RFC 7540 §3.2）。
     *
     * <p>这种流的请求不是从 HPACK 解出来的，而是从 HTTP/1.1 请求搬过来的；
     * 对端也不会再对它发 HEADERS。</p>
     */
    private boolean upgradeStream;

    /** 流级 body 累积 */
    private byte[] body = new byte[0];
    private int bodyLen;

    /** 发送窗口（对端还能接收多少字节） */
    private int sendWindow;

    /** 接收窗口（我们还能接收多少字节） */
    private int recvWindow;

    /** 接收窗口初值 —— 用来算「归还 credit」的阈值 */
    private int recvWindowInit;

    /** 已收到但还没归还给对端的流级字节数 */
    private int pendingRecvCredit;

    /** 因流控暂停、等待 WINDOW_UPDATE 的待发 body */
    private byte[] pendingBody;
    private int pendingBodyOffset;
    private int pendingBodyLength;

    /** 本端已发 END_STREAM */
    private boolean closedLocally;

    /** 对端已发 END_STREAM */
    private boolean closedRemotely;

    /** 响应已结束，但 body 还挂在流上等 WINDOW_UPDATE —— END_STREAM 要延后发 */
    private boolean pendingEnd;

    public Http2Stream(int streamId, Http2Connection connection) {
        this.streamId = streamId;
        this.connection = connection;
        this.request.setResponse(response);
        this.sendWindow = connection.getFlowController().peerInitialWindowSize();
        this.recvWindow = connection.getFlowController().localInitialWindowSize();
        this.recvWindowInit = this.recvWindow;
    }

    public int getStreamId() {
        return streamId;
    }

    public ValueHttpRequest getRequest() {
        return request;
    }

    public HttpResponse getResponse() {
        return response;
    }

    /**
     * 追加 header block 片段（HEADERS 或 CONTINUATION）。
     */
    public void appendHeaderBlock(byte[] data, int offset, int length) {
        if (headerBlockLen + length > headerBlock.length) {
            int cap = headerBlock.length;
            while (cap < headerBlockLen + length) {
                cap <<= 1;
            }
            byte[] nb = new byte[cap];
            System.arraycopy(headerBlock, 0, nb, 0, headerBlockLen);
            headerBlock = nb;
        }
        System.arraycopy(data, offset, headerBlock, headerBlockLen, length);
        headerBlockLen += length;
    }

    /**
     * header block 收齐 —— 解 HPACK 并映射成 {@link ValueHttpRequest}。
     *
     * <p><b>请求本身不合法 ⇒ 流级错误</b>（RST_STREAM），不是连接级错误 ——
     * 别的流还能继续用（RFC 9113 §8.3）。</p>
     */
    public void completeHeaders() {
        if (headersComplete) {
            return;
        }
        headersComplete = true;
        if (refused) {
            // 已被拒绝的流：header block 不用解析
            return;
        }
        List<HpackHeader> headers = connection.getHpackDecoder().decode(headerBlock, 0, headerBlockLen);

        String method = null;
        String path = null;
        String authority = null;
        boolean seenRegular = false;
        for (HpackHeader h : headers) {
            String name = h.getName();
            if (name.length() > 0 && name.charAt(0) == ':') {
                if (seenRegular) {
                    // RFC 9113 §8.3：伪头必须排在普通头之前
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                            "伪头必须排在普通头之前: " + name, true);
                }
                String value = h.getValue();
                switch (name) {
                    case ":method":
                        method = value;
                        break;
                    case ":path":
                        path = value;
                        break;
                    case ":authority":
                        authority = value;
                        break;
                    case ":scheme":
                        // RFC 9113 §8.3.1：:scheme 只用于判定 https，本服务端不区分
                        break;
                    default:
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "未知伪头: " + name, true);
                }
            } else {
                seenRegular = true;
                if (hasUpperCase(name)) {
                    // RFC 9113 §8.2.1：header 名必须全小写
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                            "header 名必须小写: " + name, true);
                }
                request.putHeader(name, new HeaderValue(h.getValue()));
            }
        }
        if (method == null || path == null) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "缺少 :method 或 :path", true);
        }

        // ★ :method 必须走 MethodCache —— methodIndex 是 httpHandlers[] 的下标，
        //   自己造 MethodInfo 会导致索引错位 → handlers[methodIndex] 越界。
        request.methodInfo = MethodCache.instance().getMethodInfo(method);
        request.setVersion(HttpVersion.HTTP_2);
        request.setHttpNioSession(connection.getSession());

        decodePath(path);
        if (authority != null) {
            request.setHost(authority);
        }
    }

    /**
     * 把 {@code :path} 拆成 path 与 query，分别喂给既有的
     * {@code PathDecoder} / {@code QueryStringDecoder}。
     *
     * <p><b>哨兵技巧</b>：两个解码器都以 {@code ' '} 作为「到此为止」的终止符
     * （HTTP/1.1 里路径后面本来就有空格接版本号）。h2 的 {@code :path} 后面没有空格，
     * 所以补一个空格哨兵 —— 这样 URL 解码（{@code +} → 空格、{@code %XX}）、
     * FNV hash、精确 cache 命中、通配符回退全部与 HTTP/1.1 完全一致。</p>
     */
    private void decodePath(String path) {
        int qi = path.indexOf('?');
        String p = qi >= 0 ? path.substring(0, qi) : path;
        String q = qi >= 0 ? path.substring(qi + 1) : null;

        byte[] pathBytes = p.getBytes(StandardCharsets.ISO_8859_1);
        io.edap.buffer.FastBuf tmp = new io.edap.buffer.FastBuf(pathBytes.length + 1);
        tmp.write(pathBytes, 0, pathBytes.length);
        tmp.write((byte) ' ');
        tmp.rewind();
        PathInfo pathInfo = connection.getPathDecoder().decode(tmp, pathRange, request);
        request.pathInfo = pathInfo;

        if (q != null) {
            byte[] queryBytes = q.getBytes(StandardCharsets.ISO_8859_1);
            io.edap.buffer.FastBuf qtmp = new io.edap.buffer.FastBuf(queryBytes.length + 2);
            qtmp.write((byte) '?');
            qtmp.write(queryBytes, 0, queryBytes.length);
            qtmp.write((byte) ' ');
            qtmp.rewind();
            request.queryInfo = connection.getQueryDecoder().decode(qtmp, queryRange, request);
        } else {
            request.queryInfo = QueryStringDecoder.EMPTY_QUERY_INFO;
        }
    }

    /**
     * ★ h2c 升级：触发升级的 HTTP/1.1 请求<b>本身就是</b> stream 1 的请求（RFC 7540 §3.2）。
     *
     * <p>RFC 7540 §3.2 原文：</p>
     * <blockquote>
     *   The HTTP/1.1 request that is sent prior to upgrade is assigned a stream
     *   identifier of 1 ... Stream 1 is implicitly "half-closed" from the client
     *   toward the server ... After commencing the HTTP/2 connection, stream 1 is
     *   used for the response.
     * </blockquote>
     *
     * <p>所以服务端必须用 stream 1 给出这个请求的响应，<b>不能等客户端再发一遍
     * HEADERS</b> —— curl 与 JDK HttpClient 都不会重发（它们把升级请求本身当作 h2 请求）。
     * Go 的 {@code x/net/http2/h2c}（{@code serverConn.upgradeRequest}）与 nghttp2 的
     * {@code nghttp2_session_upgrade()} 也是同样的语义。</p>
     *
     * <p><b>请求必须整份拷贝过来，不能直接复用 HTTP/1.1 那个请求对象</b>：
     * 它是 {@code RangeHttpRequestDecoder} 的线程级单例，同线程的下一个请求会
     * {@code reset()} 它 —— 直接复用会把别的 session 的请求冲掉。</p>
     */
    public void dispatchUpgradeRequest(ValueHttpRequest http11) throws IOException {
        if (dispatched) {
            return;
        }
        dispatched = true;
        upgradeStream = true;
        // 对端方向已经半关闭：请求作为 HTTP/1.1 完整送达了
        markEndStream();

        request.methodInfo = http11.getMethodInfo();
        request.setPath(http11.getPath());
        request.pathInfo = http11.getPathInfo();
        request.queryInfo = http11.queryInfo;
        request.setVersion(HttpVersion.HTTP_2);
        request.setHttpNioSession(connection.getSession());

        // HTTP/1.1 的 Host 头 ≈ HTTP/2 的 :authority 伪头
        HeaderValue host = http11.getHeaderValue("Host");
        if (host != null && host.getValue() != null) {
            request.setHost(host.getValue());
        }

        copyRequestHeaders(http11);
        copyRequestParameters(http11);

        // 响应编码策略绑定到「连接 + 流」—— 不引入 isHttp2 布尔
        response.setEncoder(new Http2ResponseEncoder(connection, this));
        response.setBuf(THREAD_WRITE_BUF.get());
        connection.getSession().handle(request);
    }

    /**
     * 把 HTTP/1.1 请求的头搬到本流上。
     *
     * <p>两种模式都要照顾到：</p>
     * <ul>
     *   <li><b>普通模式</b>：头已经解析进 map，逐个复制（逐跳首部剔除）；</li>
     *   <li><b>懒解析模式</b>（{@code HttpHandleOption.isLazyParseHeader()}）：
     *       头还在原始数据区里，map 是空的 —— 把原始数据整份拷过来，
     *       业务代码自己解析（与 HTTP/1.1 下的行为一致）。</li>
     * </ul>
     */
    private void copyRequestHeaders(ValueHttpRequest http11) {
        Map<String, HeaderValue> headers = http11.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, HeaderValue> e : headers.entrySet()) {
                if (isHopByHop(e.getKey())) {
                    continue;
                }
                // h2 强制 header 名小写（RFC 9113 §8.2.1）
                request.putHeader(e.getKey().toLowerCase(Locale.ENGLISH), e.getValue());
            }
            return;
        }
        // 懒解析模式：原始 header 数据区整份拷过来（不能直接引用 ——
        // 那块 ByteData 可能被下一个请求复用）
        ByteData src = http11.getHeaderData();
        if (src == null || src.getLength() <= 0) {
            return;
        }
        ByteData dst = new ByteData(src.getLength());
        byte[] raw = src.getBytes();
        System.arraycopy(raw, src.getOffset(), dst.getBytes(), 0, src.getLength());
        dst.setLength(src.getLength());
        request.setHeaderData(dst);
    }

    /**
     * 把 query string 解析出来的参数搬过来（{@code request.getParameter(name)}）。
     */
    private void copyRequestParameters(ValueHttpRequest http11) {
        Map<String, List<ParameterValue>> parameters = http11.getParameters();
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        request.getParameters().putAll(parameters);
    }

    /**
     * 逐跳首部（connection-specific header fields）—— 不能带到 HTTP/2 上
     * （RFC 9113 §8.2.2 / RFC 9110 §7.6.1）。
     */
    private static boolean isHopByHop(String name) {
        switch (name.toLowerCase(Locale.ENGLISH)) {
            case "connection":
            case "keep-alive":
            case "proxy-connection":
            case "proxy-authenticate":
            case "proxy-authorization":
            case "te":
            case "trailer":
            case "transfer-encoding":
            case "upgrade":
            // h2c 升级专用头，RFC 7540 §3.2.1：「A server MUST NOT send this header field」
            case "http2-settings":
                return true;
            default:
                return false;
        }
    }

    /**
     * 追加 body 数据（DATA 帧载荷）。
     */
    public void appendBody(byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        if (bodyLen + length > body.length) {
            int cap = body.length == 0 ? 1024 : body.length;
            while (cap < bodyLen + length) {
                cap <<= 1;
            }
            byte[] nb = new byte[cap];
            System.arraycopy(body, 0, nb, 0, bodyLen);
            body = nb;
        }
        System.arraycopy(data, offset, body, bodyLen, length);
        bodyLen += length;
    }

    public void markEndStream() {
        this.endStream = true;
        this.closedRemotely = true;
    }

    public boolean isEndStream() {
        return endStream;
    }

    /**
     * header 收齐 + 对端半关闭 ⇒ 请求可以派发了。
     */
    public boolean isReadyToDispatch() {
        return headersComplete && endStream && !dispatched && !refused;
    }

    /**
     * 组装最终请求并交给业务 handler。
     */
    public void dispatch() throws IOException {
        if (dispatched) {
            return;
        }
        dispatched = true;
        if (bodyLen > 0) {
            request.setBody(new HeapHttpBody(body, 0, bodyLen));
        }
        // 响应编码策略绑定到「连接 + 流」—— 不引入 isHttp2 布尔
        response.setEncoder(new Http2ResponseEncoder(connection, this));
        response.setBuf(THREAD_WRITE_BUF.get());
        connection.getSession().handle(request);
    }

    // ------------------------------------------------------------------
    // 流控
    // ------------------------------------------------------------------

    /**
     * 发送窗口（对端还能接收多少字节）。
     */
    public int getSendWindow() {
        return sendWindow;
    }

    /**
     * 对端把 INITIAL_WINDOW_SIZE 改了 —— 发送窗口要跟着调整（RFC 9113 §6.9.2）。
     */
    public void adjustSendWindow(int delta) {
        long next = (long) sendWindow + delta;
        if (next > Http2FlowController.MAX_WINDOW) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "流级发送窗口溢出: " + sendWindow + " + " + delta);
        }
        if (next < 0) {
            next = 0;
        }
        sendWindow = (int) next;
    }

    /**
     * 对端发来流级 WINDOW_UPDATE —— 发送窗口增加。
     */
    public void increaseSendWindow(int increment) {
        long next = (long) sendWindow + increment;
        if (next > Http2FlowController.MAX_WINDOW) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "流级发送窗口溢出: " + sendWindow + " + " + increment);
        }
        sendWindow = (int) next;
    }

    /**
     * 扣减流级发送窗口。
     *
     * @return 实际扣掉（也就是能发）的字节数
     */
    public int consumeSendWindow(int want) {
        int n = Math.min(want, sendWindow);
        if (n < 0) {
            n = 0;
        }
        sendWindow -= n;
        return n;
    }

    /**
     * 扣减流级接收窗口。
     *
     * @param length 收到的字节数（DATA 帧的<b>整个</b>载荷，含 padding ——
     *                RFC 9113 §6.9：整个 DATA 载荷都受流控约束）
     * @throws Http2Exception 窗口变成负数 —— 流级 FLOW_CONTROL_ERROR
     */
    public void consumeRecvWindow(int length) {
        recvWindow -= length;
        if (recvWindow < 0) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "流级接收窗口溢出: streamId=" + streamId, true);
        }
        pendingRecvCredit += length;
    }

    /**
     * 取出待归还的流级 credit（攒够初始窗口的一半才返回）。
     */
    public int takeRecvCredit() {
        int threshold = Math.max(1, recvWindowInit / 2);
        if (pendingRecvCredit < threshold) {
            return 0;
        }
        int credit = pendingRecvCredit;
        pendingRecvCredit = 0;
        recvWindow += credit;
        return credit;
    }

    /**
     * 标记「响应已结束但 body 还没发完」—— END_STREAM 延后到 body 发完再发。
     */
    public void setPendingEnd() {
        this.pendingEnd = true;
    }

    public boolean isPendingEnd() {
        return pendingEnd;
    }

    public void clearPendingEnd() {
        this.pendingEnd = false;
    }

    /**
     * 因流控暂停而挂起的待发 body。
     */
    public void appendPendingBody(byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        if (pendingBody == null) {
            pendingBody = new byte[length];
            System.arraycopy(data, offset, pendingBody, 0, length);
            pendingBodyOffset = 0;
            pendingBodyLength = length;
            return;
        }
        byte[] nb = new byte[pendingBodyLength + length];
        System.arraycopy(pendingBody, pendingBodyOffset, nb, 0, pendingBodyLength);
        System.arraycopy(data, offset, nb, pendingBodyLength, length);
        pendingBody = nb;
        pendingBodyOffset = 0;
        pendingBodyLength = nb.length;
    }

    public boolean hasPendingBody() {
        return pendingBodyLength > 0;
    }

    public int pendingBodyLength() {
        return pendingBodyLength;
    }

    public byte[] pendingBodyArray() {
        return pendingBody;
    }

    public int pendingBodyOffset() {
        return pendingBodyOffset;
    }

    /**
     * 标记挂起的 body 已经发出 n 字节。
     */
    public void advancePendingBody(int n) {
        pendingBodyOffset += n;
        pendingBodyLength -= n;
        if (pendingBodyLength <= 0) {
            pendingBody = null;
            pendingBodyOffset = 0;
            pendingBodyLength = 0;
        }
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /**
     * 该流被服务端拒绝（超过 MAX_CONCURRENT_STREAMS）。
     */
    public void markRefused() {
        this.refused = true;
    }

    public boolean isRefused() {
        return refused;
    }

    /**
     * 标记这是「h2c 升级请求」流（RFC 7540 §3.2）。
     */
    public void markUpgradeStream() {
        this.upgradeStream = true;
    }

    /**
     * 是否是 h2c 升级请求流。
     */
    public boolean isUpgradeStream() {
        return upgradeStream;
    }

    /**
     * 本端已发 END_STREAM（响应结束）。
     */
    public void markClosedLocally() {
        this.closedLocally = true;
    }

    public boolean isClosedLocally() {
        return closedLocally;
    }

    public boolean isClosedRemotely() {
        return closedRemotely;
    }

    /**
     * 两边都关闭了 ⇒ 流可以从连接里摘掉。
     */
    public boolean isFullyClosed() {
        return closedLocally && closedRemotely;
    }

    private static boolean hasUpperCase(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                return true;
            }
        }
        return false;
    }
}
