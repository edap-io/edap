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

import io.edap.NioServerSession;
import io.edap.buffer.FastBuf;
import io.edap.nio.ParseResult;
import io.edap.nio.codec.BytesDataRange;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.ByteData;
import io.edap.util.FastList;

import java.io.IOException;
import java.util.List;

/**
 * @author: louis.lu
 * @date : 2019-07-17 15:42
 */
public abstract class HttpNioSession extends NioServerSession<HttpRequest> {

    static Logger LOG = LoggerManager.getLogger(HttpNioSession.class);

    private HttpRequest              request;
    private ParseResult<HttpRequest> parseResult;
    private HttpDecoder.State        decodeState;
    private HttpFastBufDataRange     dataRange;
    private BytesDataRange           bytesDataRange;
    private ByteData                 tmpData;

    public HttpNioSession() {

    }

    public void reset() {

    }

    public abstract void handle(HttpRequest request);

    public HttpRequest getRequest() {
        return request;
    }

    public void setRequest(HttpRequest request) {
        this.request = request;
    }

    public HttpDecoder.State getDecodeState() {
        return decodeState;
    }

    public void setDecodeState(HttpDecoder.State decodeState) {
        this.decodeState = decodeState;
    }

    public HttpFastBufDataRange getDataRange() {
        return dataRange;
    }

    public void setDataRange(HttpFastBufDataRange dataRange) {
        this.dataRange = dataRange;
    }

    public ByteData getTmpData() {
        return tmpData;
    }

    public void setTmpData(ByteData tmpData) {
        this.tmpData = tmpData;
    }

    public ParseResult<HttpRequest> getParseResult() {
        return parseResult;
    }

    public void setParseResult(ParseResult<HttpRequest> parseResult) {
        this.parseResult = parseResult;
    }

    public BytesDataRange getBytesDataRange() {
        return bytesDataRange;
    }

    public void setBytesDataRange(BytesDataRange bytesDataRange) {
        this.bytesDataRange = bytesDataRange;
    }


}
