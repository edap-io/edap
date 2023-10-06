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


import io.edap.buffer.BytesBuf;
import io.edap.buffer.FastBuf;

import static java.lang.Character.isISOControl;
import static java.lang.Character.isWhitespace;

/**
 */
public abstract class AbstractHttpDecoder {



    public static HeaderName  FINISH_HEADERNAME;
    public static HeaderName  EMPTY_HEADERNAME;
    public static HeaderValue EMPTY_HEADERVALUE;

    static {
        FINISH_HEADERNAME = new HeaderName("");
        FINISH_HEADERNAME.finish = true;
        EMPTY_HEADERNAME = new HeaderName("");
        EMPTY_HEADERVALUE = new HeaderValue("");
    }

    protected int skipControlCharacters(BytesBuf buf) {
        int pos   = buf.getPos();
        int limit = buf.getBuf().length;
        byte[] bs = buf.getBuf();
        while (pos < limit) {
            int b = bs[pos] & 0xFF;
            if (isISOControl(b) || isWhitespace(b)) {
                pos++;
            } else {
                return pos;
            }
        }
        return -1;
    }

    /**
     * 跳过无用的控制或者空字符
     * @return
     */
    protected long skipControlCharacters(FastBuf buf) {
        long pos   = buf.rpos();
        long limit = buf.limit();
        while (pos < limit) {
            int b = buf.get(pos) & 0xFF;
            if (isISOControl(b) || isWhitespace(b)) {
                pos++;
            } else {
                return pos;
            }
        }
        return -1;
    }

    public class Result {
        public State state;
        public boolean finish;
    }

    /**
     * 解析http请求的状态
     */
    public enum State {

        SKIP_CONTROL_CHARS, //http请求开始的控制字符
        READ_METHOD, //http请求的Method
        READ_PATH, //http请求的URI地址
        READ_QUERY_STRING, //http请求的query字符串
        READ_HTTP_VERSION, //http请求的http版本
        READ_HEADER, //http请求的header
        READ_BODY, //http请求的BODY
        READ_FIXED_LENGTH_CONTENT, //读取到固定长度的内容部分
        READ_CHUNK, //如果http为chunked编码
        BAD_MESSAGE, //不符合标准的消息
        UPGRADED                   //用于升级为websocket请求的升级标示
    }
}
