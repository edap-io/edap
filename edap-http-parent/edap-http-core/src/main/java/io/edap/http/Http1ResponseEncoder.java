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

package io.edap.http;

import io.edap.buffer.FastBuf;
import io.edap.http.header.ContentLength;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.header.DateHeader;
import io.edap.http.header.Header;
import io.edap.http.header.ServerHeader;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.ByteData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * HTTP/1.1 响应编码器 —— 把 {@link HttpResponse} 落到 HTTP/1.1 的字节上。
 *
 * <p><b>字节级契约</b>：本类产出的字节必须与改造前 {@code HttpResponse} 内联的写法
 * 逐字节一致。响应头的输出顺序固定为：</p>
 *
 * <pre>
 *   状态行        HTTP/1.1 200 OK\r\n
 *   extraHeaders  setSimpleResponse 的 varargs（WebSocket 握手用）
 *   Content-Type  有 contentType 时输出
 *   Content-Length 有 contentLength 时输出（&gt;= 0）
 *   用户 headers   header(String,String) / setSimpleResponse 的 map
 *   Date
 *   Server
 *   空行          \r\n
 * </pre>
 *
 * <p>该顺序对两条既有路径都与改造前一致：{@code write(byte[])} 路径没有 extraHeaders
 * 也没有用户 headers；{@code setSimpleResponse} 路径没有 contentType / contentLength。</p>
 *
 * <p><b>partial write 的处理</b>：{@link FastBuf#write(byte[], int, int)} 是<b>静默截断</b>的
 * （容量不足时只写一部分并返回实际写入数）。socket 满时 {@code writeToChannel} 返回 false，
 * 此时数据必须留在 buffer 里等 {@code OP_WRITE} 续写 —— 这里沿用改造前的处理方式：
 * 写满就 flush，flush 失败则记录告警并放弃剩余字节（真正的修法是 task #20/#21/#22）。</p>
 */
public class Http1ResponseEncoder implements ResponseEncoder {

    private static final Logger LOG = LoggerManager.getLogger(Http1ResponseEncoder.class);

    private static final ServerHeader HEADER_SERVER = (ServerHeader) HttpConsts.BUILDIN_HEADERS.get("Server");
    private static final DateHeader   HEADER_DATE   = (DateHeader)   HttpConsts.BUILDIN_HEADERS.get("Date");

    private static final byte[] LINE        = new byte[]{'\r', '\n'};
    private static final byte[] COLON_SPACE = new byte[]{':', ' '};

    @Override
    public void writeHead(HttpResponse response, int contentLength) throws IOException {
        FastBuf buf = response.getBuf();
        try {
            byte[] version = response.getVersion().bytes();
            write0(response, buf, version, 0, version.length);

            byte[] status = ResponseStatusCode.get(response.getStatusCode());
            write0(response, buf, status, 0, status.length);

            List<Header> extraHeaders = response.getExtraHeaders();
            if (extraHeaders != null) {
                for (Header header : extraHeaders) {
                    byte[] bytes = header.getBytes();
                    write0(response, buf, bytes, 0, bytes.length);
                }
            }

            ContentTypeHeader contentType = response.getContentType();
            if (contentType != null) {
                byte[] bytes = contentType.getBytes();
                write0(response, buf, bytes, 0, bytes.length);
            }

            if (contentLength >= 0) {
                write0(response, buf, ContentLength.getByteData(contentLength));
            }

            Map<String, String> headers = response.getHeaders();
            if (!headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                    write0(response, buf, name, 0, name.length);
                    write0(response, buf, COLON_SPACE, 0, COLON_SPACE.length);
                    byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                    write0(response, buf, value, 0, value.length);
                    write0(response, buf, LINE, 0, LINE.length);
                }
            }

            byte[] date = HEADER_DATE.getBytes();
            write0(response, buf, date, 0, date.length);
            byte[] server = HEADER_SERVER.getBytes();
            write0(response, buf, server, 0, server.length);
            write0(response, buf, LINE, 0, LINE.length);
        } catch (IOException ioe) {
            LOG.error("write error: ", ioe);
            throw ioe;
        }
    }

    @Override
    public void writeBody(HttpResponse response, byte[] data, int offset, int length) throws IOException {
        FastBuf buf = response.getBuf();
        try {
            write0(response, buf, data, offset, length);
        } catch (IOException ioe) {
            LOG.error("write error: ", ioe);
            throw ioe;
        }
    }

    @Override
    public void end(HttpResponse response) {
        // HTTP/1.1 没有"响应结束"的显式标记，body 长度由 Content-Length 定界。
    }

    private boolean write0(HttpResponse response, FastBuf buf, byte[] data, int offset, int length) throws IOException {
        int wlen = buf.write(data, offset, length);
        if (wlen >= length) {
            return true;
        }
        // buf 剩余容量不足 —— 先把已有的数据写出去再继续。
        // 必须看 writeToChannel 的返回值 —— 否则 socket 满时会死循环:
        // writeToChannel false → rpos 不动 → 下次 buf.write 在 rpos=address 处覆盖同样字节
        // → writeToChannel 还是 false → CPU 死转。
        while (wlen < length) {
            if (!response.getNioSession().writeToChannel(buf)) {
                LOG.warn("Http1ResponseEncoder.write0: writeToChannel 返回 false (socket 满 / EAGAIN),data truncated at wlen=" + wlen + "/" + length);
                return false;
            }
            buf.wpos(buf.address());
            wlen += buf.write(data, offset + wlen, length - wlen);
        }
        return true;
    }

    private boolean write0(HttpResponse response, FastBuf buf, ByteData byteData) throws IOException {
        byte[] bytes = byteData.getBytes();
        return write0(response, buf, bytes, byteData.getOffset(), byteData.getLength());
    }
}
