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
import io.edap.http.HttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.util.ByteData;

/**
 *
 */
public class HeaderDataFastDecoder implements RangeTokenDecoder<ByteData> {

    @Override
    public ByteData decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        long old  = _buf.rpos();
        long rpos = old + remain-4;
        byte b1 = _buf.get(rpos);
        byte b2 = _buf.get(rpos+1);
        byte b3 = _buf.get(rpos+2);
        byte b4 = _buf.get(rpos+3);
        if (b1 == '\r' && b2 == '\n' && b3 == '\r' && b4 == '\n') {
            ByteData headerData = request.getHeaderData();
            byte[] data = headerData.getBytes();
            int len = (int)(rpos - old);
            if (data.length < len) {
                data = new byte[len];
                headerData.setBytes(data);
            }
            headerData.setLength(len);
            _buf.get(old, data, 0, len);
            _buf.rpos(rpos);
            return headerData;
        }
        return null;
    }
}
