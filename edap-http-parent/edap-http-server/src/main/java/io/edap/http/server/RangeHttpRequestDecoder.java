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

import io.edap.Decoder;
import io.edap.http.*;
import io.edap.http.HttpDecoder.State;
import io.edap.http.rangedecoder.*;
import io.edap.http.server.rangedecoder.*;
import io.edap.nio.ParseResult;
import io.edap.buffer.FastBuf;
import io.edap.http.cache.HeaderNameCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.model.QueryInfo;
import io.edap.util.ByteData;

import java.util.ArrayList;
import java.util.List;

import static io.edap.nio.NioSession.THREAD_WRITE_BUF;

/**
 * HTTP协议解析器
 */
public class RangeHttpRequestDecoder extends AbstractHttpDecoder implements Decoder<HttpRequest, HttpNioSession> {

    static MethodDecoder         METHOD_DECODER      = new MethodDecoder();
    static PathDecoder           PATH_DECODER        = new PathDecoder();
    static QueryStringDecoder    QUERY_DECODER       = new QueryStringDecoder();
    static HttpVersionDecoder    VERSION_DECODER     = new HttpVersionDecoder();
    static HeaderDataDecoder     HEADER_DECODER      = new HeaderDataDecoder();
    static HeaderDataFastDecoder HEADER_FAST_DECODER = new HeaderDataFastDecoder();
    static HeaderNameDecoder     HEADERNAME_DECODER  = new HeaderNameDecoder();
    static HeaderValueDecoder    HEADERVALUE_DECODER = new HeaderValueDecoder();
    static BodyDecoder           BODY_DECODER        = new BodyDecoder();

    static ContentTypeValueDecoder CONTENT_TYPE_VALUE_DECODER = new ContentTypeValueDecoder();
    static HeaderValueCacheDecoder HEADER_VALUE_CACHE_DECODER = new HeaderValueCacheDecoder();
    static ConnectionValueDecoder  CONNECTION_VALUE_DECODER   = new ConnectionValueDecoder();

    static ThreadLocal<DecodeContext> THREAD_DECODE_CONTEXT;
    static ThreadLocal<List<HttpRequest>>      THREAD_USED_REQUEST;

    static {
        THREAD_DECODE_CONTEXT = ThreadLocal.withInitial(() -> {
            DecodeContext dc = new DecodeContext();
            ValueHttpRequest request = new ValueHttpRequest();
            request.setResponse(new HttpResponse());
            dc.dataRange = new HttpFastBufDataRange();
            dc.request = request;
            dc.result = new ParseResult<>();
            return dc;
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
        HttpDecoder.State state = httpNioSession.getDecodeState();
        if (state == null) {
            state = State.SKIP_CONTROL_CHARS;
        }
        DecodeContext dc = THREAD_DECODE_CONTEXT.get();
        HttpFastBufDataRange dataRange = dc.dataRange;
        ValueHttpRequest request = dc.request;
        request.setHttpNioSession(httpNioSession);
        HttpResponse response = request.getResponse();
        response.setBuf(THREAD_WRITE_BUF.get());
        ParseResult<HttpRequest> result = dc.result;
        if (state == State.SKIP_CONTROL_CHARS) {
            request.reset();
        }
       	parseHttpRequest(buf, state, dataRange, request, httpNioSession, result);
        if (result.isFinished()) {
            result.setMessage(request);
            result.setFinished(true);
        } else {
            result.setFinished(false);
        }

        return result;
    }

    static class DecodeContext {
        ParseResult<HttpRequest> result;
        ValueHttpRequest request;
        HttpFastBufDataRange dataRange;
    }


    public void parseHttpRequest(FastBuf buf, State state, HttpFastBufDataRange dataRange,
                                   ValueHttpRequest request, HttpNioSession httpNioSession,
								   ParseResult<HttpRequest> result) {
        dataRange.buffer(buf);
        switch (state) {
            case SKIP_CONTROL_CHARS:
                long pos = skipControlCharacters(buf);
                if (pos == -1) {
					httpNioSession.setDecodeState(State.SKIP_CONTROL_CHARS);
                    break;
                } else {
                    buf.rpos();
                }
            case READ_METHOD:
                MethodInfo methodInfo = METHOD_DECODER.decode(buf, dataRange, request);
                if (methodInfo == null) {
					httpNioSession.setDecodeState(State.READ_METHOD);
                    break;
                } else {
                    request.methodInfo = methodInfo;
                }

            case READ_PATH:
                PathInfo path = PATH_DECODER.decode(buf, dataRange, request);
                if (path == null) {
					httpNioSession.setDecodeState(State.READ_PATH);
                    break;
                } else {
                    request.pathInfo = path;
                }

            case READ_QUERY_STRING:
                QueryInfo query = QUERY_DECODER.decode(buf, dataRange, request);
                if (query == null) {
					httpNioSession.setDecodeState(State.READ_QUERY_STRING);
                    break;
                }
                request.queryInfo = query;
            case READ_HTTP_VERSION:
                HttpVersion version = VERSION_DECODER.decode(buf, dataRange, request);
                if (version == null) {
					httpNioSession.setDecodeState(State.READ_HTTP_VERSION);
                    break;
                }
                request.setVersion(version);
            case READ_HEADER:
                if (request.pathInfo.getHandlerOption() != null && request.pathInfo.getHandlerOption().isLazyParseHeader()) {
                    ByteData headerData;
                    if (!request.pathInfo.getHandlerOption().isEnablePipelining() && "GET".equals(request.methodInfo.getMethod())) {
                        headerData = HEADER_FAST_DECODER.decode(buf, dataRange, request);
                    } else {
                        headerData = HEADER_DECODER.decode(buf, dataRange, request);
                    }
                    if (headerData != null) {
                        request.setHeaderData(headerData);
                        result.setFinished(true);
                    } else {
                        break;
                    }
                } else {
                    dataRange.reset();
                    HeaderNameDecoder nameDecoder = HEADERNAME_DECODER;
                    HeaderValueDecoder valueDecoder = HEADERVALUE_DECODER;
                    HeaderName name = nameDecoder.decode(buf, dataRange, request);
                    while (!name.finish) {
                        HeaderValue value;
                        dataRange.reset();
                        value = valueDecoder.decode(buf, dataRange, request);
                        if (value == null) {
							httpNioSession.setDecodeState(State.READ_HEADER);
                            break;
                        }
                        request.putHeader(name.name, value);
                        dataRange.reset();
                        name = nameDecoder.decode(buf, dataRange, request);
                        if (name == null) {
                            result.setFinished(false);
                            return;
                        }
                    }
                }
            case READ_BODY:
                BODY_DECODER.decode(request, buf, dataRange, result, httpNioSession);
            default:


        }

    }

    @Override
    public void reset() {

    }
}
