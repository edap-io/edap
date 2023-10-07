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
import io.edap.codec.FastBufDataRange;
import io.edap.http.HttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;

/**
 *
 */
public class HeaderDecoder implements TokenDecoder<byte[]> {

    @Override
    public byte[] decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        long rpos = _buf.rpos();
        for (int i=0;i<remain;i++) {
            if (_buf.get(rpos+i) == '\r' && i <= remain - 3) {
                if (_buf.get(rpos+i+1) == '\n' && _buf.get(rpos+i+2) == '\r' && _buf.get(rpos+i+3) == '\n') {
                    byte[] data = new byte[i];
                    buf.get(data);
                    _buf.rpos(rpos+i+4);
                    return data;
                }
            }
        }

        return null;
    }
}
