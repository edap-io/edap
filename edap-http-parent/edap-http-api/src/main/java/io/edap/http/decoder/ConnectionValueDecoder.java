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

package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;

public class ConnectionValueDecoder implements TokenDecoder<HeaderValue> {

    private static final HeaderValue KEEP_ALIVE = new HeaderValue("keep-alive");
    private static final HeaderValue CLOSE = new HeaderValue("close");

    @Override
    public HeaderValue decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        int i;
        long rpos = _buf.rpos();
        byte b = 0;
        for (i=0;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b != ' ') {
                break;
            }
        }
        if (i == remain - 1) {
            return null;
        }
        dataRange.start(rpos + i);
        for (;i<remain;i++) {
            b = _buf.get(rpos + i);
            switch (b) {
                case '\r':
                    if (i < remain - 1) {
                        if (_buf.get(rpos+i+1) == '\n') {
                            int len = (int)((rpos+i)-dataRange.start());
                            if (len == 5 && b == 'c' && _buf.get(rpos+i) == 'e') {
                                return CLOSE;
                            }
                        } else {
                            throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
                        }
                    } else {
                        return null;
                    }
                default:

            }
        }
        return KEEP_ALIVE;
    }
}
