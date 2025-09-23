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
import io.edap.http.bytesdecoder.BytesTokenDecoder;
import io.edap.http.model.QueryInfo;
import io.edap.util.ByteArrayBuilder;

import static io.edap.http.server.rangedecoder.QueryStringDecoder.EMPTY_QUERY_INFO;

public class BytesQueryStringDecoder implements BytesTokenDecoder<QueryInfo> {
    @Override
    public QueryInfo decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        byte b = _buf.get();
        switch (b) {
            case '?':
                break;
            case ' ':
                return EMPTY_QUERY_INFO;
        }

        return null;
    }
}
