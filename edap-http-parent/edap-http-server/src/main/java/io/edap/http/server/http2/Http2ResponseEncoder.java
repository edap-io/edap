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
import io.edap.http.HttpTime;
import io.edap.http.ResponseEncoder;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.header.Header;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.hpack.HpackHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP/2 响应编码器。
 *
 * <p>把 {@link HttpResponse} 里的状态码 / 响应头 / body 落成 HEADERS + DATA 帧。</p>
 *
 * <p><b>与 HTTP/1.1 的差异</b>：</p>
 * <ul>
 *   <li>状态码不是状态行，而是 {@code :status} 伪头（RFC 9113 §8.3）；</li>
 *   <li>响应头名必须小写；</li>
 *   <li>不输出 {@code Content-Length}（h2 用 END_STREAM 定界，RFC 9113 §8.1.2.6 允许省略）；</li>
 *   <li>body 按对端的 {@code MAX_FRAME_SIZE} 切成多个 DATA 帧；</li>
 *   <li>响应结束靠 DATA 帧上的 END_STREAM 标志。</li>
 * </ul>
 *
 * <p><b>流控</b>：每次发 DATA 都要先 {@link Http2Connection#reserveSend} 预留窗口。
 * 窗口耗尽时剩余数据挂在 {@link Http2Stream} 上，等对端发来 WINDOW_UPDATE 再续发；
 * 此时 END_STREAM 也必须延后，否则对端会认为响应提前结束。</p>
 *
 * <p><b>不引入 isHttp2 布尔</b>：本类在构造时绑定了「连接 + 流」，
 * 协议差异由对象身份承载。</p>
 */
public class Http2ResponseEncoder implements ResponseEncoder {

    private static final byte[] EMPTY = new byte[0];

    private final Http2Connection connection;
    private final Http2Stream stream;

    public Http2ResponseEncoder(Http2Connection connection, Http2Stream stream) {
        this.connection = connection;
        this.stream = stream;
    }

    @Override
    public void writeHead(HttpResponse response, int contentLength) {
        if (Http2WriteStats.ENABLED) {
            Http2WriteStats.responseHeads.increment();
        }
        List<HpackHeader> headers = new ArrayList<>(8);
        // ★ :status 必须是第一个伪头
        headers.add(new HpackHeader(":status", Integer.toString(response.getStatusCode())));

        List<Header> extraHeaders = response.getExtraHeaders();
        if (extraHeaders != null) {
            for (Header header : extraHeaders) {
                headers.add(new HpackHeader(lower(header.getName()), header.getValue()));
            }
        }

        ContentTypeHeader contentType = response.getContentType();
        if (contentType != null) {
            headers.add(new HpackHeader("content-type", contentType.getValue()));
        }

        Map<String, String> userHeaders = response.getHeaders();
        if (userHeaders != null && !userHeaders.isEmpty()) {
            for (Map.Entry<String, String> entry : userHeaders.entrySet()) {
                headers.add(new HpackHeader(lower(entry.getKey()), entry.getValue()));
            }
        }

        headers.add(new HpackHeader("date", currentDate()));
        headers.add(new HpackHeader("server", "ews"));

        connection.writeHeaders(stream, headers, Http2Flags.END_HEADERS);
    }

    @Override
    public void writeBody(HttpResponse response, byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        connection.writeBody(stream, data, offset, length);
    }

    @Override
    public void end(HttpResponse response) {
        Map<String, String> trailers = response.getTrailers();
        if (trailers != null && !trailers.isEmpty()) {
            // ★ 有 trailers —— 响应以 HEADERS(END_STREAM) 帧结束（RFC 9113 §8.1）。
            //   gRPC 的 grpc-status / grpc-message 就在这里。
            connection.writeTrailers(stream, trailers);
            return;
        }
        // 空 DATA 帧 + END_STREAM —— 这是 h2 里「响应结束」的唯一标记。
        // body 还没发完时由连接层延后 END_STREAM。
        connection.endStream(stream);
    }

    /**
     * 当前时间的 HTTP-date 字符串（不带尾部 CRLF）。
     */
    private static String currentDate() {
        byte[] gmt = HttpTime.instance().getGMTBytes();
        int len = gmt.length;
        // getGMTBytes() 尾部带 "\r\n"，h2 的 header 值不带 CRLF
        if (len >= 2 && gmt[len - 2] == '\r' && gmt[len - 1] == '\n') {
            len -= 2;
        }
        return new String(gmt, 0, len, StandardCharsets.ISO_8859_1);
    }

    private static String lower(String name) {
        if (name == null) {
            return null;
        }
        return name.toLowerCase(Locale.ENGLISH);
    }
}
