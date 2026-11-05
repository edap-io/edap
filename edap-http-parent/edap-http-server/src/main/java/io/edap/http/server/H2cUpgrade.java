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

package io.edap.http.server;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.MethodInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.header.HeaderConnection;
import io.edap.http.header.UpgradeHeader;
import io.edap.http.server.http2.Http2Connection;
import io.edap.http.server.http2.Http2Stream;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.ByteData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * h2c 明文升级（RFC 9113 §3.2）。
 *
 * <p>客户端先发一个普通的 HTTP/1.1 请求：</p>
 *
 * <pre>
 *   GET / HTTP/1.1
 *   Host: server.example.com
 *   Connection: Upgrade, HTTP2-Settings
 *   Upgrade: h2c
 *   HTTP2-Settings: &lt;base64url(SETTINGS 载荷)&gt;
 * </pre>
 *
 * <p>服务端回 101：</p>
 *
 * <pre>
 *   HTTP/1.1 101 Switching Protocols
 *   Connection: Upgrade
 *   Upgrade: h2c
 * </pre>
 *
 * <p>之后客户端发 HTTP/2 连接前言 + SETTINGS，连接进入 HTTP/2 帧处理。</p>
 *
 * <p><b>★ 升级请求本身就是 stream 1 的请求</b>（RFC 7540 §3.2）：</p>
 *
 * <blockquote>
 *   The HTTP/1.1 request that is sent prior to upgrade is assigned a stream
 *   identifier of 1 ... Stream 1 is implicitly "half-closed" from the client
 *   toward the server ... After commencing the HTTP/2 connection, stream 1 is
 *   used for the response.
 * </blockquote>
 *
 * <p>也就是说<b>客户端不会把升级请求再发一遍</b>（curl 与 JDK HttpClient 都不会），
 * 服务端必须自己用 stream 1 给出这个请求的响应。Go 的 {@code x/net/http2/h2c}
 * 与 nghttp2 的 {@code nghttp2_session_upgrade()} 也是同样的语义。</p>
 *
 * <p><b>★ 101 必须先于任何 HTTP/2 帧到达客户端</b>：HTTP/2 的帧写出器是直接写 socket 的
 * （不经过线程写缓冲），所以 101 必须在切换连接对象之前同步刷出去。</p>
 *
 * <p><b>★ HTTP2-Settings 头要提前应用</b>：客户端在 Upgrade 请求里就通告了自己的
 * SETTINGS（尤其是 {@code INITIAL_WINDOW_SIZE}）。如果服务端等到收到客户端的 SETTINGS 帧
 * 才开始发数据，而客户端通告的初始窗口比默认的 65535 小，服务端就会超出客户端的流控窗口 ——
 * 客户端会判定为 FLOW_CONTROL_ERROR。所以这里先应用 HTTP2-Settings 头里的参数，
 * 客户端自己的 SETTINGS 帧到达后再应用一次（幂等）。</p>
 */
public final class H2cUpgrade {

    static final Logger LOG = LoggerManager.getLogger(H2cUpgrade.class);

    /** Upgrade 头 */
    public static final String UPGRADE_HEADER = "Upgrade";
    /** Connection 头 */
    public static final String CONNECTION_HEADER = "Connection";
    /** HTTP2-Settings 头（RFC 9113 §3.2.1） */
    public static final String HTTP2_SETTINGS_HEADER = "HTTP2-Settings";

    /** Upgrade 头里的 h2c 标记 */
    public static final String H2C_TOKEN = "h2c";
    /** Connection 头里的 HTTP2-Settings 标记 */
    public static final String HTTP2_SETTINGS_TOKEN = "HTTP2-Settings";

    private static final byte[] EMPTY = new byte[0];

    private H2cUpgrade() {
    }

    /**
     * 判断一个 HTTP/1.1 请求是不是 h2c 升级请求。
     *
     * <p>RFC 9113 §3.2 规定的两个必要条件：</p>
     * <ul>
     *   <li>{@code Upgrade} 头里包含 {@code h2c}；</li>
     *   <li>{@code Connection} 头里包含 {@code HTTP2-Settings}。</li>
     * </ul>
     *
     * <p>另外要求方法为 GET 且没有 body —— 带 body 的请求升级后语义无法保证。</p>
     */
    public static boolean isUpgradeRequest(HttpRequest request) {
        if (request == null) {
            return false;
        }
        // ★ 用 getMethodInfo() 而不是 getMethod()：后者在 methodInfo 为 null 时会 NPE，
        //   而本方法对每个 HTTP/1.1 请求都会被调用 —— 一次 NPE 就会打断整个连接。
        MethodInfo methodInfo = request.getMethodInfo();
        if (methodInfo == null || !"GET".equalsIgnoreCase(methodInfo.getMethod())) {
            return false;
        }
        if (request.getContentLength() > 0) {
            return false;
        }
        String upgrade = headerValue(request, UPGRADE_HEADER);
        if (upgrade == null || !hasToken(upgrade, H2C_TOKEN)) {
            return false;
        }
        String connection = headerValue(request, CONNECTION_HEADER);
        return connection != null && hasToken(connection, HTTP2_SETTINGS_TOKEN);
    }

    /**
     * 执行升级：回 101 → 把 101 刷到 socket → 把连接对象换成 HTTP/2 →
     * 用 stream 1 给出升级请求的响应 → 把残留字节交给 HTTP/2 连接。
     *
     * @param session  当前会话
     * @param request  触发升级的请求
     * @param leftover 请求之后还没消费的字节（客户端提前把前言 pipelining 上来才会有）
     * @return true 表示升级成功
     */
    public static boolean tryUpgrade(HttpServerNioSession session, HttpRequest request, FastBuf leftover)
            throws IOException {
        if (!isUpgradeRequest(request)) {
            return false;
        }
        if (!(request instanceof ValueHttpRequest)) {
            // 只有 ValueHttpRequest 能把 method/path/query/header 完整搬到 HTTP/2 流上
            return false;
        }
        ValueHttpRequest valueRequest = (ValueHttpRequest) request;

        // 1. 回 101
        HttpResponse resp = request.getResponse();
        resp.reset();
        resp.setNioSession(session);
        resp.setRequest(request);
        resp.setSimpleResponse(101, null, HeaderConnection.UPGRADE, UpgradeHeader.UPGRADE_H2C);

        // 2. ★ 101 必须现在就发出去 —— 它必须先于任何 HTTP/2 帧到达客户端
        session.flushThreadWriteBuf();

        // 3. 换成 HTTP/2 连接对象
        Http2Connection h2 = new Http2Connection(session, EMPTY);
        // 4. 应用客户端在 HTTP2-Settings 头里通告的参数（尤其是 INITIAL_WINDOW_SIZE）
        byte[] settings = decodeSettings(request);
        if (settings != null) {
            h2.applyPeerSettings(settings);
        }
        session.setConnection(h2);

        // 5. 服务器连接前言（SETTINGS 帧）必须先于任何其它帧发出
        h2.startAfterUpgrade();

        // 6. ★ 升级请求本身就是 stream 1 的请求（RFC 7540 §3.2）——
        //    客户端不会再重发它，服务端必须自己给出这个请求的响应。
        Http2Stream stream = h2.createUpgradeStream();
        stream.dispatchUpgradeRequest(valueRequest);

        // 7. 客户端提前 pipelining 上来的前言交给 h2 连接处理
        if (leftover != null && leftover.remain() > 0) {
            h2.decode(leftover);
        }
        LOG.info("h2c upgrade ok");
        return true;
    }

    /**
     * 取出并解码 {@code HTTP2-Settings} 头。
     *
     * @return SETTINGS 载荷；没有这个头（或解码失败）时返回 null
     */
    public static byte[] decodeSettings(HttpRequest request) {
        String value = headerValue(request, HTTP2_SETTINGS_HEADER);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            // base64url（RFC 9113 §3.2.1：base64url 编码，不带填充）
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            LOG.warn("HTTP2-Settings 解码失败: ", l -> l.arg(value).threw(e));
            return null;
        }
    }

    /**
     * 取请求头的值。
     *
     * <p>优先用解析好的 header map；如果解析器是「懒解析」模式（{@code HttpHandleOption}），
     * header map 是空的，只能从原始 header 数据区里扫。</p>
     */
    private static String headerValue(HttpRequest request, String name) {
        HeaderValue hv = request.getHeaderValue(name);
        if (hv != null) {
            String v = hv.getValue();
            if (v != null && !v.isEmpty()) {
                return v.trim();
            }
            return null;
        }
        return rawHeaderValue(request.getHeaderData(), name);
    }

    /**
     * 从原始 header 数据区里取某个头的值（懒解析模式的兜底）。
     *
     * <p>数据区形如 {@code "Host: x\r\nUpgrade: h2c\r\n"}（不含末尾的空行）。</p>
     */
    static String rawHeaderValue(ByteData headerData, String name) {
        if (headerData == null) {
            return null;
        }
        byte[] bytes = headerData.getBytes();
        int len = headerData.getLength();
        if (bytes == null || len <= 0) {
            return null;
        }
        byte[] target = name.getBytes(StandardCharsets.ISO_8859_1);
        int p = 0;
        while (p < len) {
            int eol = indexOfCrlf(bytes, p, len);
            int lineEnd = eol < 0 ? len : eol;
            int colon = -1;
            for (int i = p; i < lineEnd; i++) {
                if (bytes[i] == ':') {
                    colon = i;
                    break;
                }
            }
            if (colon > p && colon - p == target.length
                    && regionEqualsIgnoreCase(bytes, p, target, target.length)) {
                int vs = colon + 1;
                while (vs < lineEnd && (bytes[vs] == ' ' || bytes[vs] == '\t')) {
                    vs++;
                }
                int ve = lineEnd;
                while (ve > vs && (bytes[ve - 1] == ' ' || bytes[ve - 1] == '\t')) {
                    ve--;
                }
                return new String(bytes, vs, ve - vs, StandardCharsets.ISO_8859_1);
            }
            p = eol < 0 ? len : eol + 2;
        }
        return null;
    }

    private static int indexOfCrlf(byte[] bytes, int from, int len) {
        for (int i = from; i < len - 1; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static boolean regionEqualsIgnoreCase(byte[] bytes, int from, byte[] target, int targetLen) {
        for (int i = 0; i < targetLen; i++) {
            byte a = bytes[from + i];
            byte b = target[i];
            if (a != b && Character.toLowerCase((char) (a & 0xff)) != Character.toLowerCase((char) (b & 0xff))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 逗号分隔的 token 列表里是否包含某个 token（大小写不敏感）。
     *
     * <p>每个 token 前后可能有空白（{@code "Connection: Upgrade, HTTP2-Settings"}），
     * 所以要逐段 trim 再比。</p>
     */
    static boolean hasToken(String value, String token) {
        int start = 0;
        int len = value.length();
        while (start <= len) {
            int comma = value.indexOf(',', start);
            int end = comma < 0 ? len : comma;
            int s = start;
            int e = end;
            while (s < e && isSpace(value.charAt(s))) {
                s++;
            }
            while (e > s && isSpace(value.charAt(e - 1))) {
                e--;
            }
            if (e - s == token.length()
                    && value.regionMatches(true, s, token, 0, token.length())) {
                return true;
            }
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return false;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}
