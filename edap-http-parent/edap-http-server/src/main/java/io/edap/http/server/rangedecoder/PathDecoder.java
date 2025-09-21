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

package io.edap.http.server.rangedecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.PathInfo;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.rangedecoder.RangeTokenDecoder;
import io.edap.http.server.PathInfoMatcher;
import io.edap.util.ByteArrayBuilder;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class PathDecoder implements RangeTokenDecoder<PathInfo> {

    static PathInfoMatcher PATH_INFO_MATCHER = PathInfoMatcher.instance();

    @Override
    public PathInfo decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf   = buf;
        int     remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        byte b;
        long pos = _buf.rpos();
        dataRange.buffer(buf);
        dataRange.start(pos);
        long hashCode = FNV_1a_INIT_VAL;
        dataRange.first(_buf.get(pos));
        byte decodeByte;
        ByteArrayBuilder builder = null;
        for (int i=0;i<remain;i++) {
            b = _buf.get(pos+i);
            switch (b) {
                case ' ':
                case '?':
                case '#':
                    if (dataRange.urlEncoded()) {
                        dataRange.length(builder.length());
                    } else {
                        dataRange.length(i);
                    }
                    dataRange.hash(hashCode);
                    dataRange.last();
                    _buf.rpos(pos+i);
                    return PATH_INFO_MATCHER.match(dataRange);
                case '+':
                    decodeByte = (byte)' ';
                    hashCode ^= decodeByte;
                    hashCode *= FNV_1a_FACTOR_VAL;
                    if (i == 0) {
                        dataRange.first(decodeByte);
                    }
                    if (dataRange.urlEncoded()) {
                        builder.append(decodeByte);
                    } else {
                        if (i > 0) {
                            builder = dataRange.getBytesBuilder();
                            builder.ensureCapacity(i);
                            byte[] data = builder.getValue();
                            buf.get(data, i);
                            builder.setLength(builder.length()+i);
                        }
                        builder.append(decodeByte);
                        dataRange.urlEncoded(true);
                    }
                    break;
                case '%':
                    if (i < remain - 2) {
                        int v = BYTE_VALUES[_buf.get(pos+i+1)] * 16 + BYTE_VALUES[_buf.get(pos+i+2)];
                        if (v < 0) {
                            throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value");
                        }
                        decodeByte = (byte)v;
                        if (i == 0) {
                            dataRange.first(decodeByte);
                        }

                        hashCode ^= decodeByte;
                        hashCode *= FNV_1a_FACTOR_VAL;
                        if (dataRange.urlEncoded()) {
                            builder.append(decodeByte);
                        } else {
                            if (i > 0) {
                                builder = dataRange.getBytesBuilder();
                                builder.ensureCapacity(i);
                                byte[] data = builder.getValue();
                                buf.get(data, i);
                                builder.setLength(builder.length()+i);
                            } else {
                                builder = dataRange.getBytesBuilder();
                            }
                            builder.append(decodeByte);
                            dataRange.urlEncoded(true);
                        }
                        i += 2;
                        break;
                    } else {
                        return null;
                    }
                default:
                    hashCode ^= b;
                    hashCode *= FNV_1a_FACTOR_VAL;
                    if (dataRange.urlEncoded()) {
                        builder.append(b);
                    }
            }
        }
        return null;
    }
}
