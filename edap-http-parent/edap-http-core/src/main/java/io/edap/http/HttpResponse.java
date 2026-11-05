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
import io.edap.http.header.*;
import io.edap.io.BufOut;
import io.edap.io.BufWriter;
import io.edap.json.Eson;
import io.edap.json.JsonWriter;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.util.ByteData;
import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.edap.http.HttpConsts.DEFAULT_CHARSET;
import static io.edap.protobuf.ProtoBuf.THREAD_WRITER;
import static io.edap.protobuf.ProtoBufCodecRegister.INSTANCE;

/**
 * HTTP 响应。
 *
 * <p><b>响应侧策略化</b>：本类只持有「状态码 / 响应头 / body」，具体怎么落到字节由
 * {@link ResponseEncoder} 决定。默认是 {@link Http1ResponseEncoder}（HTTP/1.1），
 * HTTP/2 由 {@code io.edap.http.server.http2.Http2ResponseEncoder} 接管。</p>
 *
 * <p><b>没有 {@code isHttp2} 布尔</b>：HTTP/2 的编码器在构造时就绑定了连接与流标识符，
 * 协议差异由对象身份承载。</p>
 *
 * <p><b>复用与重置</b>：HTTP/1.1 路径下本对象是<b>线程级单例</b>
 * （{@code RangeHttpRequestDecoder} 的 ThreadLocal），所以每个新请求开始时
 * 必须 {@link #reset()}，否则上一个请求设置的头会漏进下一个响应。</p>
 */
public class HttpResponse {

    static final Logger LOG = LoggerManager.getLogger(HttpResponse.class);

    /**
     * 用户自定义响应头（{@link #header(String, String)} 与 {@link #setSimpleResponse} 的 map）。
     * 用 LinkedHashMap 保证输出顺序与插入顺序一致。
     */
    private final Map<String, String> headers = new LinkedHashMap<>();

    /**
     * {@link #setSimpleResponse} 的 varargs 头 —— 输出在 {@link #headers} 之前。
     */
    private List<Header> extraHeaders;

    private HttpVersion version;
    private FastBuf buf;
    private ContentTypeHeader contentType;

    /**
     * 响应状态码。改造前状态码是写死在各个 write 方法里的，现在由响应对象持有，
     * 编码器按协议决定怎么表达（HTTP/1.1 是状态行，HTTP/2 是 {@code :status} 伪头）。
     */
    private int statusCode = 200;

    /**
     * 响应编码策略。null 表示用默认的 {@link Http1ResponseEncoder}。
     */
    private ResponseEncoder encoder;

    private HttpNioSession nioSession;

    private static final ResponseEncoder DEFAULT_ENCODER = new Http1ResponseEncoder();

    public HttpResponse() {
    }

    /**
     * 只写状态行 + 响应头，不写 body。
     *
     * <p>改造后统一走 {@link ResponseEncoder}：HTTP/1.1 下产出的字节与改造前完全一致
     * （状态行 → varargs 头 → map 头 → Date → Server → 空行）。</p>
     *
     * <p><b>★ 唯一的行为变更（P5-5）</b>：改造前本方法<b>不写</b> Content-Length，
     * 导致 HTTP/1.1 客户端只能 read-until-close，而 edap 默认 keep-alive 不关连接
     * ⇒ 客户端死等（连 request timeout 都不触发）。
     * 现在按 {@link #simpleContentLength()} 的规则补 Content-Length。</p>
     */
    public void setSimpleResponse(int code, Map<String, String> headers, Header... header) throws IOException {
        this.statusCode = code;
        if (header != null && header.length > 0) {
            if (extraHeaders == null) {
                extraHeaders = new ArrayList<>(header.length);
            }
            for (Header h : header) {
                extraHeaders.add(h);
            }
        }
        if (!CollectionUtils.isEmpty(headers)) {
            this.headers.putAll(headers);
        }
        ResponseEncoder encoder = getEncoder();
        encoder.writeHead(this, simpleContentLength());
        encoder.end(this);
    }

    /**
     * {@code setSimpleResponse} 的 Content-Length 决策（返回 {@code -1} 表示不输出）。
     *
     * <p>规则：</p>
     * <ol>
     *   <li><b>1xx / 204 / 304 不输出</b>。RFC 9110 §8.6：1xx 与 204 禁止发送
     *       Content-Length；304 的 Content-Length 必须等于「同样请求用 GET 时 body 的长度」，
     *       无从得知 ⇒ 一律不发。
     *       101（WebSocket 握手、h2c 升级）属于 1xx ⇒ 天然不输出 —— 升级响应里
     *       带 Content-Length 会让对端按 0 字节读后续的协议数据。</li>
     *   <li><b>调用方已提供 Content-Length 则不额外输出</b>（否则会出现两个
     *       Content-Length）。edap-s3 的 {@code S3HttpHandler} 就是这种用法 ——
     *       它自己按 body 长度填 Content-Length ⇒ 它的响应字节<b>完全不变</b>。</li>
     *   <li>其余情况输出 {@code Content-Length: 0}。</li>
     * </ol>
     */
    private int simpleContentLength() {
        int code = statusCode;
        if ((code >= 100 && code < 200) || code == 204 || code == 304) {
            return -1;
        }
        if (hasContentLength()) {
            return -1;
        }
        return 0;
    }

    /**
     * 调用方是否已经给了 Content-Length（map 头或 varargs 头，大小写不敏感）。
     */
    private boolean hasContentLength() {
        for (String name : headers.keySet()) {
            if (ContentLength.NAME.equalsIgnoreCase(name)) {
                return true;
            }
        }
        if (extraHeaders != null) {
            for (Header h : extraHeaders) {
                if (h != null && ContentLength.NAME.equalsIgnoreCase(h.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public HttpResponse setRequest(HttpRequest request) {
        nioSession = request.getHttpNioSession();
        version = request.getVersion();
        return this;
    }

    public HttpResponse setNioSession(HttpNioSession nioSession) {
        this.nioSession = nioSession;
        return this;
    }

    public HttpResponse contentType(ContentTypeHeader contentType) {
        this.contentType = contentType;
        return this;
    }

    public HttpResponse write(String body) throws IOException {
        return write(200, body.getBytes(DEFAULT_CHARSET));
    }

    public HttpResponse write(byte[] data) throws IOException {
        return write(200, data);
    }

    public HttpResponse write(int code, byte[] data) throws IOException {
        this.statusCode = code;
        ResponseEncoder encoder = getEncoder();
        encoder.writeHead(this, data.length);
        encoder.writeBody(this, data, 0, data.length);
        encoder.end(this);
        return this;
    }

    public HttpResponse write(BufWriter writer) throws IOException {
        return write(200, writer.toByteArray());
    }

    public HttpResponse write(Object obj) throws IOException {
        if (contentType == null) {
            contentType = ContentTypeHeader.from("application/json; charset=UTF-8");
        }
        switch (contentType.getContentType()) {
            case "application/json": {
                FastBuf _buf = buf;
                _buf.wpos(_buf.address());
                JsonWriter writer = Eson.THREAD_WRITER.get();
                try {
                    writer.reset();
                    Eson.serialize(obj, writer);
                    write(200, writer.toByteArray());
                } catch (IOException ioe) {
                    LOG.error("write error: ", ioe);
                    throw ioe;
                }
                break;
            }
            case "application/x-protobuf": {
                FastBuf _buf = buf;
                _buf.wpos(_buf.address());
                ProtoBufEncoder codec = INSTANCE.getEncoder(obj.getClass());
                ProtoBufWriter protoWriter = THREAD_WRITER.get();
                protoWriter.reset();
                BufOut out = protoWriter.getBufOut();
                out.reset();
                try {
                    codec.encode(protoWriter, obj);
                    write(200, protoWriter.toByteArray());
                } catch (EncodeException e) {
                    LOG.error("write EncodeException error: ", e);
                    throw new IOException("EncodeException", e);
                } catch (IOException ioe) {
                    LOG.error("write error: ", ioe);
                    throw ioe;
                } finally {
                    THREAD_WRITER.set(protoWriter);
                }
                break;
            }
            default:

        }
        return this;
    }

    /**
     * 写 body 字节（不写响应头）。
     */
    public HttpResponse write(ByteData data) throws IOException {
        byte[] bytes = new byte[data.getLength()];
        System.arraycopy(data.getBytes(), data.getOffset(), bytes, 0, data.getLength());
        return write(200, bytes);
    }

    /**
     * 设置一个自定义响应头。
     *
     * <p><b>改造前这里是空实现</b>（头被塞进 map 但永远不写到 wire），现在会真正输出。</p>
     */
    public HttpResponse header(String name, String value) {
        if (!HttpConsts.BUILDIN_HEADERS.containsKey(name)) {
            headers.put(name, value);
        }
        return this;
    }

    /**
     * 设置一个自定义响应头。
     *
     * <p><b>改造前这里是空实现</b>，现在会真正输出。</p>
     */
    public HttpResponse header(Header header) {
        if (header == null) {
            return this;
        }
        if (extraHeaders == null) {
            extraHeaders = new ArrayList<>(2);
        }
        extraHeaders.add(header);
        return this;
    }

    public void setBuf(FastBuf buf) {
        this.buf = buf;
    }

    public FastBuf getBuf() {
        return buf;
    }

    public HttpNioSession getNioSession() {
        return nioSession;
    }

    public HttpVersion getVersion() {
        return version;
    }

    public HttpResponse setVersion(HttpVersion version) {
        this.version = version;
        return this;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public HttpResponse setStatusCode(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public ContentTypeHeader getContentType() {
        return contentType;
    }

    /**
     * 用户自定义响应头（只读视图）。
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * varargs 响应头（只读视图）。
     */
    public List<Header> getExtraHeaders() {
        return extraHeaders;
    }

    public ResponseEncoder getEncoder() {
        return encoder == null ? DEFAULT_ENCODER : encoder;
    }

    public HttpResponse setEncoder(ResponseEncoder encoder) {
        this.encoder = encoder;
        return this;
    }

    /**
     * 清空上一个请求残留的响应状态。
     *
     * <p>HTTP/1.1 路径下 {@code HttpResponse} 是线程级单例，必须在每个新请求开始时调用。</p>
     */
    public void reset() {
        statusCode = 200;
        contentType = null;
        headers.clear();
        extraHeaders = null;
        encoder = null;
    }
}
