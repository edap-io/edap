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

package io.edap.http.server.bytesdecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.PathInfo;
import io.edap.http.server.PathInfoMatcher;
import io.edap.http.bytesdecoder.BytesTokenDecoder;
import io.edap.util.ByteArrayBuilder;

import java.nio.charset.StandardCharsets;

import static io.edap.http.HttpConsts.BYTE_VALUES;

public class BytesPathDecoder implements BytesTokenDecoder<PathInfo> {

    static PathInfoMatcher PATH_INFO_MATCHER = PathInfoMatcher.instance();

    @Override
    public PathInfo decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
        FastBuf _buf   = buf;
        int     remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        byte b;
        long pos = _buf.rpos();
        long start = pos;
        sb.setLength(0);
        byte decodeByte;
        String path;
        boolean urlEncoder = false;
        for (int i=0;i<remain;i++) {
            b = _buf.get(pos+i);
            switch (b) {
                case ' ':
                case '?':
                case '#':
                    if (sb.length() > 0) {
                        path = sb.toString();
                    } else {
                        byte[] data = new byte[i];
                        buf.get(start, data);
                        path = new String(data);
                    }
                    return PATH_INFO_MATCHER.match(path);
                case '+':
                    decodeByte = (byte)' ';
                    if (urlEncoder) {
                        sb.append(decodeByte);
                    } else {
                        if (i > 0) {
                            byte[] data = new byte[i];
                            buf.get(start, data);
                            sb.append(data);
                        }
                        sb.append(decodeByte);
                        urlEncoder = true;
                    }
                    break;
                case '%':
                    if (i < remain - 2) {
                        int v = BYTE_VALUES[_buf.get(pos+i+1)] * 16 + BYTE_VALUES[_buf.get(pos+i+2)];
                        if (v < 0) {
                            throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value");
                        }
                        decodeByte = (byte)v;
                        if (urlEncoder) {
                            sb.append(decodeByte);
                        } else {
                            if (i > 0) {
                                byte[] data = new byte[i];
                                buf.get(start, data);
                                sb.append(data);
                            }
                            sb.append(decodeByte);
                            urlEncoder = true;
                        }
                        i += 2;
                        break;
                    } else {
                        return null;
                    }
                default:
                    if (urlEncoder) {
                        sb.append(b);
                    }
            }
        }
        return null;
    }
}
