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
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.HeaderValue;
import io.edap.http.HttpNioSession;
import io.edap.http.HttpRequest;
import io.edap.http.body.DirectBufferHttpBody;
import io.edap.http.body.MappedFileHttpBody;
import io.edap.http.headervalue.ContentTypeValue;
import io.edap.nio.ParseResult;

import java.nio.ByteBuffer;

public class BodyDecoder {

    public void decode(HttpRequest request, FastBuf buf, HttpFastBufDataRange dataRange,
					   ParseResult<HttpRequest> result, HttpNioSession httpNioSession) {
        String method = request.getMethod();
        if ("GET".equals(method)) {
            result.setFinished(true);
        } else {
            int contentLength = request.getContentLength();
            String transferEncoding = null;
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

    /**
     * 累积接收 Content-Length 字节的 body。每次 socket read 后调用一次：
     * - 已收齐：构造 HttpBody（按 FastBuf 类型选 DirectBufferHttpBody 或
     *   MappedFileHttpBody），挂到 request 上，返回 true。
     * - 未收齐：把本次 read 拷到堆外累加 buffer，记录累积长度，返回 false。
     *
     * <p>修复了原实现的三个 bug：
     * 1. 原 4096 字节 ByteData 越界写 → 现按 contentLength 分配 direct buffer，无越界。
     * 2. 原 readPos 双推进 → 现只依赖 {@link FastBuf#get(ByteBuffer)} 自身的推进。
     * 3. 原部分读 hasLen 不更新 → 现通过 {@link HttpNioSession#accumulateBodyWritten(int)}
     *    显式累积，下一轮 read 写入偏移正确推进。
     */
    private boolean decodeFixedBody(String typeValue, FastBuf buf, int length,
                                    HttpRequest request, HttpNioSession httpNioSession) {
        int written = httpNioSession.getBodyAccumWritten();
        int remainInBuf = buf.remain();
        int need = length - written;

        // 单段切片快路径：无累积历史 + 一次 read 收齐 + FastBuf 是 MAPPED_FILE
        // 直接把 FastBuf 切片包成 MappedFileHttpBody，零拷贝最强形态（page cache 直接出）
        if (written == 0 && remainInBuf >= need && buf.getType() == FastBuf.BufType.MAPPED_FILE) {
            long startAddr = buf.rpos();
            ByteBuffer slice = buf.byteBufferSlice(startAddr, length);
            buf.rpos(startAddr + length);
            request.setBody(new MappedFileHttpBody((java.nio.MappedByteBuffer) slice));
            httpNioSession.clearBodyAccum();
            return true;
        }

        // 累加路径：保证堆外 buffer 存在并处于"待写入"状态（position=written, limit=length）
        ByteBuffer acc = httpNioSession.getOrCreateBodyAccum(length);

        if (remainInBuf >= need) {
            // 本次 read 收齐 body
            int n = buf.get(acc);
            httpNioSession.accumulateBodyWritten(n);
            ByteBuffer view = httpNioSession.flipBodyAccumToRead();
            request.setBody(new DirectBufferHttpBody(view));
            httpNioSession.clearBodyAccum();
            // 收齐后必须把 decodeState 重置回 SKIP_CONTROL_CHARS,
            // 否则下一个 keep-alive 请求会被当成上一个请求的 body 继续累计,
            // 导致 GET 等小请求被错误解析、客户端超时。
            httpNioSession.setDecodeState(io.edap.http.HttpDecoder.State.SKIP_CONTROL_CHARS);
            return true;
        } else {
            // 部分读：把 FastBuf 全部剩余字节拷到累加 buffer
            int n = buf.get(acc);
            httpNioSession.accumulateBodyWritten(n);
            httpNioSession.setDecodeState(io.edap.http.HttpDecoder.State.READ_BODY);
            return false;
        }
    }
}
