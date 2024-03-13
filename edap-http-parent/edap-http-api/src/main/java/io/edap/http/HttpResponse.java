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


import io.edap.BufPool;
import io.edap.buffer.FastBuf;
import io.edap.http.header.*;
import io.edap.io.BufOut;
import io.edap.io.BufWriter;
import io.edap.json.Eson;
import io.edap.json.JsonWriter;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.util.ByteData;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static io.edap.http.HttpConsts.BUILDIN_HEADERS;
import static io.edap.http.HttpConsts.DEFAULT_CHARSET;
import static io.edap.protobuf.ProtoBuf.THREAD_WRITER;
import static io.edap.protobuf.ProtoBufCodecRegister.INSTANCE;

/**
 */
public class HttpResponse {

    private Map<String, String> headers;
    private HttpVersion version;
    private FastBuf buf;
    private BufPool bufPool;
    private ContentType contentType;
    private static HeaderServer HEADER_SERVER = (HeaderServer)BUILDIN_HEADERS.get("Server");
    private static HeaderDate HEADER_DATE   = (HeaderDate)BUILDIN_HEADERS.get("Date");
    private static byte[] LINE = new byte[]{'\r', '\n'};
    private HttpNioSession nioSession;


    public HttpResponse() {
        headers = new HashMap<>();
    }

    public HttpResponse setRequest(HttpRequest request) {
        bufPool = request.getHttpNioSession().getBufPool();
        nioSession = request.getHttpNioSession();
        version = request.getVersion();
        return this;
    }

    public HttpResponse setNioSession(HttpNioSession nioSession) {
        this.nioSession = nioSession;
        return this;
    }

    public HttpResponse contentType(ContentType contentType) {
        this.contentType = contentType;
        return this;
    }

    public HttpResponse write(String body) {
        byte[] data = body.getBytes(DEFAULT_CHARSET);
        int bodyLen = data.length;
        FastBuf _buf = buf;
        try {
            write0(_buf, version.bytes());
            write0(_buf, ResponseStatusCode.get(200));
            write0(_buf, contentType.getBytes());
            write0(_buf, ContentLength.getByteData(bodyLen));
            write0(_buf, HEADER_DATE.getBytes());
            write0(_buf, HEADER_SERVER.getBytes());
            write0(_buf, LINE);
            write0(_buf, data);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return this;
    }

    public HttpResponse write(byte[] data) {
        int bodyLen = data.length;
        FastBuf _buf = buf;
        try {
            write0(_buf, version.bytes());
            write0(_buf, ResponseStatusCode.get(200));
            write0(_buf, contentType.getBytes());
            write0(_buf, ContentLength.getByteData(bodyLen));
            write0(_buf, HEADER_DATE.getBytes());
            write0(_buf, HEADER_SERVER.getBytes());
            write0(_buf, LINE);
            write0(_buf, data);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return this;
    }

    public HttpResponse write(Object obj) {
        if (contentType == null) {
            contentType = ContentType.from("application/json; charset=UTF-8");
        }
        FastBuf _buf;
        switch (contentType.getValueEnum()) {
            case JSON:
                _buf = buf;
                _buf.wpos(_buf.address());
                JsonWriter writer = Eson.THREAD_WRITER.get();
                try {
                    writer.reset();
                    Eson.serialize(obj, writer);
                    int len = writer.size();
                    write0(_buf, version.bytes());
                    write0(_buf, ResponseStatusCode.get(200));
                    write0(_buf, contentType.getBytes());
                    write0(_buf, ContentLength.getByteData(len));
                    write0(_buf, HEADER_DATE.getBytes());
                    write0(_buf, HEADER_SERVER.getBytes());
                    write0(_buf, LINE);
                    write0(_buf, writer);
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
                break;
            case PROTOBUF:
                _buf = buf;
                _buf.wpos(_buf.address());
                ProtoBufEncoder codec = INSTANCE.getEncoder(obj.getClass());
                ProtoBufWriter protoWriter = THREAD_WRITER.get();
                protoWriter.reset();
                BufOut out = protoWriter.getBufOut();
                out.reset();
                byte[] bs;
                try {
                    codec.encode(protoWriter, obj);
                    int len = protoWriter.size();
                    write0(_buf, version.bytes());
                    write0(_buf, ResponseStatusCode.get(200));
                    write0(_buf, contentType.getBytes());
                    write0(_buf, ContentLength.getByteData(len));
                    write0(_buf, HEADER_DATE.getBytes());
                    write0(_buf, HEADER_SERVER.getBytes());
                    write0(_buf, LINE);
                    write0(_buf, protoWriter);
                } catch (EncodeException e) {
                    //throw e;
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                } finally {
                    THREAD_WRITER.set(protoWriter);
                }
                break;
            default:

        }
        return this;
    }

    private boolean write0(FastBuf buf, byte[] data) throws IOException {
        int len = data.length;
        int wlen = buf.write(data, 0, data.length);
        if (wlen >= len) {
            return true;
        }
        // 如果buf剩余容量不足则先把buf的数据写出后再填充新的数据
        while (wlen < len) {
            writeToChannel(buf);
            buf.wpos(buf.address());
            wlen += buf.write(data, wlen, len - wlen);
        }
        return true;
    }

    private boolean write0(FastBuf buf, BufWriter writer) throws IOException {
        int len = writer.size();
        int wlen = writer.toFastBuf(buf);
        if (wlen >= len) {
            return true;
        }
        // 如果buf剩余容量不足则先把buf的数据写出后再填充新的数据
        while (wlen < len) {
            writeToChannel(buf);
            buf.wpos(buf.address());
            wlen += writer.toFastBuf(buf);
        }
        return true;
    }

    private boolean write0(FastBuf buf, ByteData byteData) throws IOException {
        int len = byteData.getLength();
        int wlen = buf.write(byteData);
        if (wlen >= len) {
            return true;
        }
        // 如果buf剩余容量不足则先把buf的数据写出后再填充新的数据
        while (wlen < len) {
            writeToChannel(buf);
            buf.wpos(buf.address());
            wlen += buf.write(byteData);
        }
        return true;
    }

    private void writeToChannel(FastBuf buf) throws IOException {
        int len = (int)(buf.wpos() - buf.address());
        buf.wpos(buf.address());
        int wlen = nioSession.fastWrite(buf);
        while (wlen < len) {
            buf.wpos(buf.address() + wlen);
            wlen += nioSession.fastWrite(buf);
        }
    }

    public HttpResponse write(ByteData data) {
        return this;
    }

    public HttpResponse header(String name, String value) {
        if (!BUILDIN_HEADERS.containsKey(name)) {
            headers.put(name, value);
        }
        return this;
    }

    public HttpResponse header(Header header) {
        return this;
    }

    public void setBuf(FastBuf buf) {
        this.buf = buf;
    }
}
