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
public class HeaderDataDecoder implements RangeTokenDecoder<ByteData> {

    @Override
    public ByteData decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        long rpos = _buf.rpos();

        int  headerRemain = remain - 3;
        for (int i=0;i<headerRemain;i++) {
            if (_buf.get(rpos++) == '\r') {
                if (_buf.get(rpos) == '\n' && _buf.get(rpos+1) == '\r' && _buf.get(rpos+2) == '\n') {
					ByteData headerData = request.getHeaderData();
                    byte[] data = headerData.getBytes();
					if (data.length < i) {
						data = new byte[i];
						headerData.setBytes(data);
					}
					headerData.setLength(i);
                    buf.get(data, i);
                    _buf.rpos(rpos+3);
                    return headerData;
                } else {
                    i    += 4;
                    rpos += 4;
                }
            }
        }

        return null;
    }
}
