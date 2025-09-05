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

import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.util.ByteData;

/**
 */
public class RangeHttpRequest implements HttpRequest {

    public HttpFastBufDataRange queryStringRange = new HttpFastBufDataRange();
    public KeyValueRanges parameterRanges = new KeyValueRanges(128);
    public KeyValueRanges headerRanges = new KeyValueRanges(128);
    public HttpVersion version;
    private HttpNioSession httpNioSession;
    private int contentLength = -2;
    protected MethodInfo methodInfo;
    protected PathInfo pathInfo;

    public void setHttpNioSession(HttpNioSession httpNioSession) {
        this.httpNioSession = httpNioSession;
    }

    @Override
    public PathInfo getPath() {
        return pathInfo;
    }

    @Override
    public HeaderValue getHeaderValue(String name) {
        return null;
    }

    @Override
    public ByteData getBody() {
        return null;
    }

    @Override
    public String getMethod() {
        return methodInfo==null?null:methodInfo.getMethod();
    }

    @Override
    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    @Override
    public int getContentLength() {
        return 0;
    }

    @Override
    public void setBody(ByteData data) {

    }

    @Override
    public void reset() {
        queryStringRange.reset();
        headerRanges.reset();
        parameterRanges.reset();
    }

    @Override
    public HttpVersion getVersion() {
        return version;
    }

    @Override
    public HttpNioSession getHttpNioSession() {
        return httpNioSession;
    }
}
