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
import io.edap.http.MethodInfo;
import io.edap.http.cache.MethodCache;
import io.edap.util.ByteArrayBuilder;

import static io.edap.http.HttpConsts.*;

public class BytesMethodDecoder implements BytesTokenDecoder<MethodInfo> {

    static final MethodCache METHOD_CACHE = MethodCache.instance();

    @Override
    public MethodInfo decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();

        long pos = _buf.rpos();
        long startPos = pos;
        if (remain > 7) {
            byte b1 = _buf.get(pos);
            byte b2 = _buf.get(pos+1);
            if (b2 == ' ') {
                _buf.rpos(pos+2);
                return getMethodInfo(b1);
            }
            byte b3 = _buf.get(pos+2);
            if (b3 == ' ') {
                _buf.rpos(pos+3);
                return getMethodInfo(b1, b2);
            }
            byte b4 = _buf.get(pos+3);
            if (b4 == ' ') {
                _buf.rpos(pos+4);
                if (b1 == 'G' && b2 == 'E' && b3 == 'T') {
                    return GET;
                } else if (b1 == 'P' && b2 == 'U' && b3 == 'T') {
                    return PUT;
                } else {
                    return getMethodInfo(b1, b2, b3);
                }
            }
            byte b5 = _buf.get(pos+4);
            if (b5 == ' ') {
                _buf.rpos(pos+5);
                if (b1 == 'P' && b2 == 'O' && b3 == 'S' && b4 == 'T') {
                    return POST;
                } else if (b1 == 'H' && b2 == 'E' && b3 == 'A' && b4 == 'D') {
                    return HEAD;
                } else {
                    return getMethodInfo(b1, b2, b3, b4);
                }
            }

            byte b6 = _buf.get(pos+5);
            if (b6 == ' ') {
                _buf.rpos(pos+6);
                if (b1 == 'T' && b2 == 'R' && b3 == 'A' && b4 == 'C' && b5 == 'E') {
                    return TRACE;
                } else {
                    return getMethodInfo(b1, b2, b3, b4, b5);
                }
            }

            byte b7 = _buf.get(pos+6);
            if (b7 == ' ') {
                _buf.rpos(pos+7);
                if (b1 == 'D' && b2 == 'E' && b3 == 'L' && b4 == 'E' && b5 == 'T'
                        && b6 == 'E') {
                    return DELETE;
                } else {
                    return getMethodInfo(b1, b2, b3, b4, b5, b6);
                }
            }
            byte b8 = _buf.get(pos+7);
            if (b8 == ' ') {
                _buf.rpos(pos+8);
                if (b1 == 'C' && b2 == 'O' && b3 == 'N' && b4 == 'N' && b5 == 'E'
                        && b6 == 'C' && b7 == 'T') {
                    return CONNECT;
                } else if (b1 == 'O' && b2 == 'P' && b3 == 'T' && b4 == 'I'
                        && b5 == 'O' && b6 == 'N' && b7 == 'S') {
                    return OPTIONS;
                } else {
                    return getMethodInfo(b1, b2, b3, b4, b5, b6, b7);
                }
            }
        }
        for (int i = 0;i<remain;i++) {
            byte b = _buf.get(pos + i);
            if (b == ' ') {
                byte[] bs = new byte[i];
                _buf.get(startPos, bs);
                return METHOD_CACHE.getMethodInfo(new String(bs));
            }
        }
        return null;
    }

    private MethodInfo getMethodInfo(byte... bs) {
        return METHOD_CACHE.getMethodInfo(new String(bs));
    }
}
