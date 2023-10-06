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
import io.edap.http.RangeHttpRequest;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.BKDR_HASH_SEED;

/**
 */
public class RangeQueryStringDecoder implements TokenDecoder<FastBufDataRange> {

    static FastBufDataRange EMPTY_QUERY_INFO = new FastBufDataRange();
    static {
        EMPTY_QUERY_INFO.length(0);
    }

    @Override
    public FastBufDataRange decode(FastBuf buf, FastBufDataRange dataRange, HttpRequest request) {
        RangeHttpRequest rangeHttpRequest = (RangeHttpRequest)request;
        switch (buf.get()) {
            case '?':
                break;
            case ' ':
                rangeHttpRequest.queryStringRange.length(0);
                return EMPTY_QUERY_INFO;
        }

        return decodeQuery(buf, dataRange, rangeHttpRequest);
    }

    private FastBufDataRange decodeQuery(FastBuf buf, FastBufDataRange dr, RangeHttpRequest rangeHttpRequest) {
        FastBufDataRange queryRange = rangeHttpRequest.queryStringRange;
        FastBuf _buf = buf;
        int remain = _buf.remain();
        long rpos = _buf.rpos();
        queryRange.buffer(_buf);
        queryRange.start(rpos);

        int index = rangeHttpRequest.parameterRanges.length;

        FastBufDataRange keyRange = rangeHttpRequest.parameterRanges.keys[index];
        FastBufDataRange valRange = rangeHttpRequest.parameterRanges.values[index];
        byte b;
        int hashCode = 0;
        int queryHash = 0;
        for (int i=0;i<remain;i++) {
            b = _buf.get(rpos++);
            switch (b) {
                case '=':
                    keyRange.length((int)(rpos - queryRange.start() - 1));
                    keyRange.last();
                    keyRange.hashCode(hashCode);
                    keyRange.urlEncoded(dr.urlEncoded());
                    hashCode = 0;
                    valRange.start(rpos);
                    dr.urlEncoded(false);
                    break;
                case '&':
                    valRange.length((int)(rpos - valRange.start() - 1));
                    valRange.last();
                    hashCode = 0;
                    dr.urlEncoded(false);
                    rangeHttpRequest.parameterRanges.add();

                    index = rangeHttpRequest.parameterRanges.length;
                    keyRange = rangeHttpRequest.parameterRanges.keys[index];
                    valRange = rangeHttpRequest.parameterRanges.values[index];
                    keyRange.length(0);
                    valRange.length(0);
                    break;
                case ' ':
                    queryRange.hashCode(queryHash);
                    return queryRange;
                case '+':
                    hashCode = BKDR_HASH_SEED * hashCode + ' ';
                    queryHash *= BKDR_HASH_SEED * hashCode + b;
                    queryRange.urlEncoded(true);
                    dr.urlEncoded(true);
                    break;
                case '%':
                    if (i < remain - 2) {
                        int v = BYTE_VALUES[_buf.get(rpos++)] * 16 + BYTE_VALUES[_buf.get(rpos++)];
                        if (v < 0) {
                            throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value");
                        }
                        i += 2;
                        hashCode = BKDR_HASH_SEED * hashCode + v;
                        queryHash = BKDR_HASH_SEED * hashCode + v;
                        queryRange.urlEncoded(true);
                        dr.urlEncoded(true);
                        break;
                    } else {
                        return null;
                    }
                default:
                    hashCode *= BKDR_HASH_SEED * hashCode + b;
                    queryHash *= BKDR_HASH_SEED * hashCode + b;
            }
        }
        return null;
    }
}
