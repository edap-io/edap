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

import io.edap.Decoder;
import io.edap.ParseResult;
import io.edap.buffer.FastBuf;
import io.edap.http.cache.HeaderNameCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.decoder.*;
import io.edap.http.model.QueryInfo;
import io.edap.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP协议解析器
 */
public class HttpDecoder extends AbstractHttpDecoder implements Decoder<HttpRequest, HttpNioSession> {

    static MethodDecoder METHOD_DECODER      = new MethodDecoder();
    static PathDecoder PATH_DECODER        = new PathDecoder();
    static HttpVersionDecoder VERSION_DECODER     = new HttpVersionDecoder();

    static QueryStringDecoder QUERY_DECODER       = new QueryStringDecoder();
    static HeaderNameDecoder HEADERNAME_DECODER  = new HeaderNameDecoder();
    static HeaderValueDecoder HEADERVALUE_DECODER = new HeaderValueDecoder();
    static BodyDecoder        BODY_DECODER        = new BodyDecoder();
    static HeaderDecoder      HEAADER_DACODER     = new HeaderDecoder();

    static ContentTypeValueDecoder CONTENT_TYPE_VALUE_DECODER = new ContentTypeValueDecoder();
    static HeaderValueCacheDecoder HEADER_VALUE_CACHE_DECODER = new HeaderValueCacheDecoder();
    static ConnectionValueDecoder CONNECTION_VALUE_DECODER    = new ConnectionValueDecoder();

    static ThreadLocal<List<ValueHttpRequest>> THREAD_RANGE_REQUEST;
    static ThreadLocal<List<HttpRequest>> THREAD_USED_REQUEST;

    static {
        THREAD_RANGE_REQUEST = ThreadLocal.withInitial(() -> {
            List<ValueHttpRequest> reqs = new ArrayList(32);
            for (int i=0;i<32;i++) {
                reqs.add(new ValueHttpRequest());
            }
            return reqs;
        });

        THREAD_USED_REQUEST = ThreadLocal.withInitial(() -> {
            List<HttpRequest> reqs = new ArrayList<>(128);
            return reqs;
        });
    }

    static {
        HeaderNameCache HEADER_NAME_CACHE = HeaderNameCache.instance();
        HeaderName contentTypeInfo = HEADER_NAME_CACHE.get(HttpFastBufDataRange.from("Content-Type"));
        contentTypeInfo.valueDecoder = CONTENT_TYPE_VALUE_DECODER;
//        HeaderName host = HEADER_NAME_CACHE.get(DataRange.from("Host"));
//        host.valueDecoder = HEADER_VALUE_CACHE_DECODER;
//        HeaderName accept = HEADER_NAME_CACHE.get(DataRange.from("Accept"));
//        accept.valueDecoder = HEADER_VALUE_CACHE_DECODER;
//        HeaderName ua = HEADER_NAME_CACHE.get(DataRange.from("User-Agent"));
//        ua.valueDecoder = HEADER_VALUE_CACHE_DECODER;
//        HeaderName acceptLang = HEADER_NAME_CACHE.get(DataRange.from("Accept-Language"));
//        acceptLang.valueDecoder = HEADER_VALUE_CACHE_DECODER;
        HeaderName connection = HEADER_NAME_CACHE.get(HttpFastBufDataRange.from("Connection"));
        connection.valueDecoder = CONNECTION_VALUE_DECODER;
    }

    @Override
    public ParseResult<HttpRequest> decode(FastBuf buf, HttpNioSession httpNioSession) {

        ParseResult<HttpRequest> result = new ParseResult<>();
        State state = httpNioSession.getDecodeState();
        if (state == null) {
            state = State.SKIP_CONTROL_CHARS;
        }
        List<ValueHttpRequest> requests = httpNioSession.getValueRequestPool();
        int index = 0;
        ValueHttpRequest request = requests.get(index++);
        HttpFastBufDataRange dataRange = httpNioSession.getDataRange();
        if (dataRange == null) {
            dataRange = new HttpFastBufDataRange();
            httpNioSession.setDataRange(dataRange);
        }
        request.reset();
        Result res = parseHttpRequest(buf, state, dataRange, request, httpNioSession);
        List<HttpRequest> reqs = httpNioSession.getReqeustList();
        reqs.clear();
        while (res.finish) {
            reqs.add(request);
            if (buf.remain() > 0) {
                request = requests.get(index++);
                request.reset();
                state = State.SKIP_CONTROL_CHARS;
                res = parseHttpRequest(buf, state, dataRange, request, httpNioSession);
            } else {
                break;
            }
        }
        if (!CollectionUtils.isEmpty(reqs)) {
            result.setMessages(reqs);
            result.setFinished(true);
        } else {
            result.setFinished(false);
        }
        return result;
    }


    public Result parseHttpRequest(FastBuf buf, State state, HttpFastBufDataRange dataRange,
                                   ValueHttpRequest request, HttpNioSession httpNioSession) {
        Result result = new Result();
        result.state = state;
        dataRange.buffer(buf);
        switch (state) {
            case SKIP_CONTROL_CHARS:
                long pos = skipControlCharacters(buf);
                if (pos == -1) {
                    result.state = State.SKIP_CONTROL_CHARS;
                    break;
                } else {
                    buf.rpos();
                }
            case READ_METHOD:
                MethodInfo methodInfo = METHOD_DECODER.decode(buf, dataRange, request);
                if (methodInfo == null) {
                    result.state = State.READ_METHOD;
                    break;
                }
                request.methodInfo = methodInfo;

            case READ_PATH:
                PathInfo path = PATH_DECODER.decode(buf, dataRange, request);
                if (path == null) {
                    result.state = State.READ_PATH;
                    break;
                }
                request.pathInfo = path;

            case READ_QUERY_STRING:
                QueryInfo query = QUERY_DECODER.decode(buf, dataRange, request);
                if (query == null) {
                    result.state = State.READ_QUERY_STRING;
                    break;
                }
                request.queryInfo = query;
            case READ_HTTP_VERSION:
                HttpVersion version = VERSION_DECODER.decode(buf, dataRange, request);
                if (version == null) {
                    result.state = State.READ_HTTP_VERSION;
                    break;
                }
                request.version = version;
            case READ_HEADER:
//                byte[] headerData = HEAADER_DACODER.decode(buf, dataRange, request);
//                if (headerData != null) {
//                    request.setHeaderData(headerData);
//                } else {
//                    break;
//                }

                HeaderName name = HEADERNAME_DECODER.decode(buf, dataRange, request);
                if (name == null) {
                    break;
                }
                while (!name.finish) {
                    HeaderValue value;
                    value = HEADERVALUE_DECODER.decode(buf, dataRange, request);
                    if (value == null) {
                        result.state = State.READ_HEADER;
                        break;
                    }
                    request.addHeader(name.name, value);
                    name = HEADERNAME_DECODER.decode(buf, dataRange, request);
                    if (name == null) {
                        result.finish = false;
                        return result;
                    }
                }
            case READ_BODY:
                BODY_DECODER.decode(request, buf, dataRange, result, httpNioSession);
            default:


        }

        return result;
    }

    @Override
    public void reset() {

    }
}
