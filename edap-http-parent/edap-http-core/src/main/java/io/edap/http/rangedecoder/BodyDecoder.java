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

package io.edap.http.rangedecoder;


import io.edap.buffer.FastBuf;
import io.edap.http.AbstractHttpDecoder;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.HeaderValue;
import io.edap.http.HttpNioSession;
import io.edap.http.HttpRequest;
import io.edap.http.headervalue.ContentTypeValue;
import io.edap.nio.ParseResult;
import io.edap.util.ByteData;

public class BodyDecoder {

    public void decode(HttpRequest request, FastBuf buf, HttpFastBufDataRange dataRange,
					   ParseResult<HttpRequest> result, HttpNioSession httpNioSession) {
        String method = request.getMethod();
        int contentLength = request.getContentLength();
        String transferEncoding = null;
        if ("GET".equals(method)) {
            if (request.getContentLength() <= 0) {
				result.setFinished(true);
            }
        } else {
            if (contentLength == 0) {
				result.setFinished(true);
            } else if (contentLength > 0) {
                String typeValue = "";
                HeaderValue hv = request.getHeaderValue(ContentTypeHeader.NAME);
                if (hv == null) {
                    typeValue = ContentTypeHeader.FORM_URLENCODED.getContentType();
                } else {
                    if (hv instanceof ContentTypeValue) {
                        ContentTypeValue htv = (ContentTypeValue) hv;
                        typeValue = htv.getContentType();
                    } else {
                        ContentTypeValue htv = ContentTypeValue.fromHeaderValue(hv);
                        typeValue = htv.getContentType();
                    }
                }
				result.setFinished(decodeFixedBody(typeValue, buf, contentLength, request, httpNioSession));
            } else {
                HeaderValue encodingVal = request.getHeaderValue("Transfer-Encoding");
                if (encodingVal != null) {
                    transferEncoding = encodingVal.getValue();
                }
            }
        }
    }

    private boolean decodeFixedBody(String typeValue, FastBuf buf, int length,
                                    HttpRequest request, HttpNioSession httpNioSession) {
        ByteData data = httpNioSession.getTmpData();
        int hasLen = 0;
        if (httpNioSession.getTmpData() == null) {
            data = new ByteData(length);
            httpNioSession.setTmpData(data);
        } else {
            hasLen = data.getLength();
        }
        if (buf.remain() + hasLen >= length) {
            int readLen = buf.get(data.getBytes(), hasLen);
            buf.rpos(buf.rpos() + (length - hasLen));
            data.setLength(data.getLength() + readLen);
            request.setBody(data);
            httpNioSession.setTmpData(null);
            return true;
        } else {
            int readLen = buf.get(data.getBytes(), hasLen);
            buf.rpos(buf.rpos() + readLen);
        }
        return false;
    }
}
