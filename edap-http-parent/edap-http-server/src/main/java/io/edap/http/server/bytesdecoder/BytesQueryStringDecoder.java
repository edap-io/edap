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
import io.edap.http.ParameterValue;
import io.edap.http.ValueHttpRequest;
import io.edap.http.bytesdecoder.BytesTokenDecoder;
import io.edap.http.cache.ParamKeyCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.model.QueryInfo;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.FastList;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.http.server.rangedecoder.QueryStringDecoder.EMPTY_QUERY_INFO;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;
import static io.edap.util.StringUtil.fastInstance;

public class BytesQueryStringDecoder implements BytesTokenDecoder<QueryInfo> {

    @Override
    public QueryInfo decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        byte b = _buf.get();
        if (b != '?') {
            return EMPTY_QUERY_INFO;
        }
        remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }

        return decodeQuery(buf, sb, (ValueHttpRequest) request);
    }

    private QueryInfo decodeQuery(FastBuf buf, ByteArrayBuilder sb, ValueHttpRequest request) {
        FastBuf         _buf       = buf;
        Map<String, List<ParameterValue>> parameters = request.getParameters();
        QueryInfo       query      = new QueryInfo();
        int             remain     = _buf.remain();
        long            rpos       = _buf.rpos();
        long            queryPos   = rpos;
        String key = null;
        byte  b;
        int   len;
        long  start =  queryPos;
        boolean urlEncode = false;
        for (int i=0;i<remain;i++) {
            b = _buf.get(rpos++);
            switch (b) {
                case '=':
                    len = (int)(rpos - start - 1);
                    byte[] data = new byte[len];
                    _buf.get(start, data);
                    if (urlEncode) {
                        key = urlDecode(data, sb);
                    } else {
                        key = fastInstance(data, (byte)0);
                    }
                    urlEncode = false;
                    start = rpos;
                    break;
                case '&':
                    if (!StringUtil.isEmpty(key)) {
                        ParameterValue pv;
                        len = (int)(rpos - start - 1);
                        data = new byte[len];
                        _buf.get(start, data);
                        if (urlEncode) {
                            pv = new ParameterValue(urlDecode(data, sb));
                        } else {
                            pv = new ParameterValue(data);
                        }
                        List list = parameters.get(key);
                        if (list == null) {
                            list = new FastList();
                            parameters.put(key, list);
                        }
                        list.add(pv);
                        parameters.put(key, list);
                    }
                    key = null;
                    urlEncode = false;
                    start = rpos;
                    break;
                case ' ':
                    if (!StringUtil.isEmpty(key)) {
                        len = (int)(rpos - start - 1);
                        data = new byte[len];
                        _buf.get(start, data);
                        ParameterValue pv;
                        if (urlEncode) {
                            pv = new ParameterValue(urlDecode(data, sb));
                        } else {
                            _buf.get(start, data);
                            pv = new ParameterValue(data);
                        }
                        List list = parameters.get(key);
                        if (list == null) {
                            list = new FastList();
                            parameters.put(key, list);
                        }
                        list.add(pv);
                        parameters.put(key, list);
                    }
                    byte[] queryBytes = new byte[i];
                    _buf.rpos(queryPos);
                    _buf.get(queryPos, queryBytes);
                    query.setQueryBytes(queryBytes);
                    return query;
                case '+':
                    urlEncode = true;
                    break;
                case '%':
                    urlEncode = true;
                    i    += 2;
                    rpos += 2;
                    break;
                default:
            }
        }
        return null;
    }

    private String urlDecode(byte[] data, ByteArrayBuilder sb) {
        sb.reset();
        sb.ensureCapacity(data.length);
        for (int i=0;i<data.length;i++) {
            byte b = data[i];
            if (b == '+') {
                sb.uncheckAppend((byte)' ');
            } else if (b == '%') {
                int v = BYTE_VALUES[data[i+1]] * 16 + BYTE_VALUES[data[i+2]];
                sb.uncheckAppend((byte)v);
                i += 2;
            } else {
                sb.uncheckAppend(b);
            }
        }
        return sb.toString(StandardCharsets.UTF_8);
    }
}
