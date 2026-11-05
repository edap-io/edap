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

package io.edap.http.http2;

/**
 * HTTP/2 协议错误。携带 HTTP/2 错误码（RFC 9113 §7），
 * 由连接层翻译成 GOAWAY（连接级错误）或 RST_STREAM（流级错误）。
 *
 * <p><b>连接级 vs 流级</b>（RFC 9113 §5.4.1 / §5.4.2）：</p>
 * <ul>
 *   <li><b>连接级</b>：帧格式错误、HPACK 解码失败、SETTINGS 非法、连接级流控违规
 *       ⇒ 发 GOAWAY 并关闭连接；</li>
 *   <li><b>流级</b>：请求本身不合法（缺伪头、header 名大写、伪头顺序错）、
 *       流级流控违规、被取消的流 ⇒ 只发 RST_STREAM，<b>连接继续可用</b>。</li>
 * </ul>
 */
public class Http2Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    /**
     * true = 流级错误（发 RST_STREAM，连接继续）；false = 连接级错误（发 GOAWAY）。
     */
    private final boolean streamError;

    public Http2Exception(int errorCode, String message) {
        this(errorCode, message, false, null);
    }

    public Http2Exception(int errorCode, String message, Throwable cause) {
        this(errorCode, message, false, cause);
    }

    public Http2Exception(int errorCode, String message, boolean streamError) {
        this(errorCode, message, streamError, null);
    }

    public Http2Exception(int errorCode, String message, boolean streamError, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.streamError = streamError;
    }

    public int getErrorCode() {
        return errorCode;
    }

    /**
     * @return true 表示这是流级错误 —— 连接层应发 RST_STREAM 而不是 GOAWAY。
     */
    public boolean isStreamError() {
        return streamError;
    }
}
