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

import java.io.IOException;
import java.nio.ByteBuffer;

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
    /**
     * BodyDecoder 跨多次 socket read 累积 body 时使用的堆外 ByteBuffer。
     * 按 Content-Length 一次性分配，复用至 body 收齐后清空。
     */
    private ByteBuffer               bodyAccumBuf;
    private int                      bodyAccumWritten;

    public HttpNioSession() {

    }

    public void reset() {

    }

    public abstract void handle(HttpRequest request) throws IOException;

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
        if (tmpData == null) {
            tmpData = new ByteData();
        }
        return tmpData;
    }

    public void setTmpData(ByteData tmpData) {
        this.tmpData = tmpData;
    }

    /**
     * 获取（或按需懒分配）body 累积用的堆外 ByteBuffer。已存在且容量足够时
     * 只 flip 到"待写入"状态（position=bodyAccumWritten，limit=capacity），
     * 不重置 bodyAccumWritten —— 跨多次 decode 调用必须保留已写字节数。
     *
     * @param capacity 期望容量（=Content-Length）
     * @return 处于"待写入"状态的 ByteBuffer（position=bodyAccumWritten，
     *         limit=capacity）
     */
    public ByteBuffer getOrCreateBodyAccum(int capacity) {
        if (bodyAccumBuf == null || bodyAccumBuf.capacity() < capacity) {
            bodyAccumBuf = ByteBuffer.allocateDirect(capacity);
            bodyAccumWritten = 0;
        } else {
            // 已存在的累加 buffer：position 复位到已写入字节数，limit 设为容量，
            // 让下一段 read 从 bodyAccumWritten 处继续写
            bodyAccumBuf.position(bodyAccumWritten);
            bodyAccumBuf.limit(bodyAccumBuf.capacity());
        }
        return bodyAccumBuf;
    }

    /**
     * 把当前累积 buffer 的 position 复位到 position=0、limit=length，并把内部计数归零，
     * 准备作为 HttpBody 暴露出去。
     */
    public ByteBuffer flipBodyAccumToRead() {
        ByteBuffer b = bodyAccumBuf;
        if (b == null) {
            return null;
        }
        b.position(0);
        b.limit(bodyAccumWritten);
        return b;
    }

    /**
     * body 累积完毕后清空，释放累积 buffer。
     */
    public void clearBodyAccum() {
        bodyAccumBuf = null;
        bodyAccumWritten = 0;
    }

    public int getBodyAccumWritten() {
        return bodyAccumWritten;
    }

    public void accumulateBodyWritten(int n) {
        bodyAccumWritten += n;
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
