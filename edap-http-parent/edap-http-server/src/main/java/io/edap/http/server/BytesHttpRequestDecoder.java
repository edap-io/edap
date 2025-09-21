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
import io.edap.buffer.FastBuf;
import io.edap.http.*;
import io.edap.http.bytesdecoder.BytesBodyDecoder;
import io.edap.http.bytesdecoder.BytesHeaderDataDecoder;
import io.edap.http.bytesdecoder.BytesHttpVersionDecoder;
import io.edap.http.bytesdecoder.BytesMethodDecoder;
import io.edap.http.model.QueryInfo;
import io.edap.http.server.bytesdecoder.BytesPathDecoder;
import io.edap.http.server.bytesdecoder.BytesQueryStringDecoder;
import io.edap.nio.ParseResult;
import io.edap.util.ByteArrayBuilder;

public class BytesHttpRequestDecoder extends AbstractHttpDecoder implements Decoder<HttpRequest, HttpNioSession> {

    static BytesMethodDecoder      METHOD_DECODER  = new BytesMethodDecoder();
    static BytesPathDecoder        PATH_DECODER    = new BytesPathDecoder();
    static BytesQueryStringDecoder QUERY_DECODER   = new BytesQueryStringDecoder();
    static BytesHttpVersionDecoder VERSION_DECODER = new BytesHttpVersionDecoder();
    static BytesHeaderDataDecoder  HEADER_DECODER  = new BytesHeaderDataDecoder();
    static BytesBodyDecoder        BODY_DECODER    = new BytesBodyDecoder();

    @Override
    public ParseResult<HttpRequest> decode(FastBuf bufIn, HttpNioSession nioSession) {
        return null;
    }

    public Result parseHttpRequest(FastBuf buf, HttpDecoder.State state, ByteArrayBuilder sb,
                                   ValueHttpRequest request, HttpNioSession httpNioSession) {
        Result result = new Result();
        result.state = state;
        switch (state) {
            case SKIP_CONTROL_CHARS:
                long pos = skipControlCharacters(buf);
                if (pos == -1) {
                    result.state = HttpDecoder.State.SKIP_CONTROL_CHARS;
                    break;
                } else {
                    buf.rpos();
                }
            case READ_METHOD:
                MethodInfo methodInfo = METHOD_DECODER.decode(buf, sb, request);
                if (methodInfo == null) {
                    result.state = HttpDecoder.State.READ_METHOD;
                    break;
                } else {
                    request.methodInfo = methodInfo;
                    //return result;
                }

            case READ_PATH:
                PathInfo path = PATH_DECODER.decode(buf, sb, request);
                if (path == null) {
                    result.state = HttpDecoder.State.READ_PATH;
                    break;
                } else {
                    request.pathInfo = path;
                    return result;
                }

            case READ_QUERY_STRING:
                QueryInfo query = QUERY_DECODER.decode(buf, sb, request);
                if (query == null) {
                    result.state = HttpDecoder.State.READ_QUERY_STRING;
                    break;
                }
                request.queryInfo = query;
            case READ_HTTP_VERSION:
                HttpVersion version = VERSION_DECODER.decode(buf, sb, request);
                if (version == null) {
                    result.state = HttpDecoder.State.READ_HTTP_VERSION;
                    break;
                }
                request.setVersion(version);
            case READ_HEADER:
                byte[] headerData = HEADER_DECODER.decode(buf, sb, request);
                if (headerData != null) {
                    request.setHeaderData(headerData);
                    result.finish = true;
                } else {
                    break;
                }

//                HeaderName name = HEADERNAME_DECODER.decode(buf, dataRange, request);
//                if (name == null) {
//                    break;
//                }
//                while (!name.finish) {
//                    HeaderValue value;
//                    value = HEADERVALUE_DECODER.decode(buf, dataRange, request);
//                    if (value == null) {
//                        result.state = State.READ_HEADER;
//                        break;
//                    }
//                    request.addHeader(name.name, value);
//                    name = HEADERNAME_DECODER.decode(buf, dataRange, request);
//                    if (name == null) {
//                        result.finish = false;
//                        return result;
//                    }
//                }
            case READ_BODY:
                BODY_DECODER.decode(request, buf, sb, result, httpNioSession);
            default:


        }

        return result;
    }

    @Override
    public void reset() {

    }
}
