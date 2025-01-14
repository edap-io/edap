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

import io.edap.NioSession;
import io.edap.ParseResult;
import io.edap.codec.BytesDataRange;
import io.edap.codec.FastBufDataRange;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.handler.NotFoundHandler;
import io.edap.http.handler.NotSupportMethodHandler;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.ByteData;
import io.edap.util.FastList;

import java.util.List;

/**
 * @author: louis.lu
 * @date : 2019-07-17 15:42
 */
public class HttpNioSession extends NioSession<HttpRequest> {

    static Logger LOG = LoggerManager.getLogger(HttpNioSession.class);

    static final HttpHandler NOT_SUPPORT_METHO_HANDLER = new NotSupportMethodHandler();
    static final HttpHandler NOT_FOUND_HANDLER = new NotFoundHandler();

    private HttpRequest request;
    private ParseResult<HttpRequest> parseResult;
    private HttpDecoder.State decodeState;
    private HttpFastBufDataRange dataRange;
    private BytesDataRange bytesDataRange;
    private ByteData tmpData;
    private HttpResponse response;
    private List<ValueHttpRequest> valueRequestPool;
    private List<RangeHttpRequest> rangeRequestPool;
    private List<HttpRequest> reqeustList;

    public HttpNioSession() {
        valueRequestPool = new FastList<>(32);
        for (int i=0;i<32;i++) {
            ValueHttpRequest req = new ValueHttpRequest();
            req.setHttpNioSession(this);
            valueRequestPool.add(req);
        }
        rangeRequestPool = new FastList<>(32);
        for (int i=0;i<32;i++) {
            RangeHttpRequest req = new RangeHttpRequest();
            req.setHttpNioSession(this);
            rangeRequestPool.add(req);
        }
        reqeustList = new FastList<>(32);
    }

    public void reset() {

    }

    public void handle(HttpRequest request) {
        PathInfo pathInfo = request.getPath();
        HttpHandler handler = null;
        if (pathInfo.isFound()) {
            try {
                handler = pathInfo.getHttpHandlers()[request.getMethodInfo().getMethodIndex()];
            } catch (Exception e) {
                LOG.warn("get HttpHandler error", e);
            }
            if (handler == null) {
                handler = NOT_SUPPORT_METHO_HANDLER;
            }
        } else { // 请求的路径不存在
            handler = NOT_FOUND_HANDLER;
        }
        HttpResponse resp = new HttpResponse();
        resp.setNioSession(this);
        resp.setRequest(request);
        resp.setBuf(getBuf());

        handler.handle(request, resp);
    }

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

    public List<ValueHttpRequest> getValueRequestPool() {
        return valueRequestPool;
    }

    public void setRequestPool(List<ValueHttpRequest> requestPool) {
        this.valueRequestPool = requestPool;
    }

    public List<HttpRequest> getReqeustList() {
        return reqeustList;
    }

    public void setReqeustList(List<HttpRequest> reqeustList) {
        this.reqeustList = reqeustList;
    }

    public List<RangeHttpRequest> getRangeRequestPool() {
        return rangeRequestPool;
    }

    public void setRangeRequestPool(List<RangeHttpRequest> rangeRequestPool) {
        this.rangeRequestPool = rangeRequestPool;
    }
}
