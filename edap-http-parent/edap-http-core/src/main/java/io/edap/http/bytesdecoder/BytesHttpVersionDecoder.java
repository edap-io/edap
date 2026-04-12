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

package io.edap.http.bytesdecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.HttpVersion;
import io.edap.util.ByteArrayBuilder;

import static io.edap.http.HttpVersion.*;
import static io.edap.http.HttpVersion.HTTP_2_0;
import static io.edap.http.HttpVersion.NOT_SUPPORT_VERSION;

public class BytesHttpVersionDecoder implements BytesTokenDecoder<HttpVersion> {
    @Override
    public HttpVersion decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
        long pos = buf.rpos();
        int  len = buf.remain();
        if (len < 10) return null;
        if (buf.get(pos + 4) == '/' && buf.get(pos + 6) == '.'
                && buf.get(pos + 8)== '\r' && buf.get(pos + 9) == '\n'
                && ((buf.get(pos) == 'H' && buf.get(pos+1) == 'T'
                && buf.get(pos+2) == 'T' && buf.get(pos+3) == 'P')
                || (buf.get(pos) == 'h'  && buf.get(pos+1) == 't'
                && buf.get(pos+2) == 't' && buf.get(pos+3) == 'p'))) {
            byte b = buf.get(pos + 5);
            if (b == '0') {
                buf.rpos(pos+10);
                return HTTP_0_9;
            } else if (b == '1') {
                byte b2 = buf.get(pos + 7);
                if (b2 == '0') {
                    buf.rpos(pos+10);
                    return HTTP_1_0;
                } else if (b2 == '1') {
                    buf.rpos(pos+10);
                    return HTTP_1_1;
                }
            } else if (b == '2') {
                byte b2 = buf.get(pos + 7);
                if (b2 == '0') {
                    buf.rpos(pos+10);
                    return HTTP_2_0;
                }
            }
        } else {
            return null;
        }
        buf.rpos(pos+10);
        return NOT_SUPPORT_VERSION;
    }
}
