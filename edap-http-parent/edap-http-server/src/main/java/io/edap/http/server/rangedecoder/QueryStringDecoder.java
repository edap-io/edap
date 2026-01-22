package io.edap.http.server.rangedecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.ParameterValue;
import io.edap.http.ValueHttpRequest;
import io.edap.http.cache.ParamKeyCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.model.QueryInfo;
import io.edap.http.rangedecoder.RangeTokenDecoder;
import io.edap.util.FastList;
import io.edap.util.StringUtil;

import java.util.List;
import java.util.Map;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;


public class QueryStringDecoder implements RangeTokenDecoder<QueryInfo> {

    public static QueryInfo EMPTY_QUERY_INFO = new QueryInfo();

    static ParamKeyCache KEY_CACHE = ParamKeyCache.instance();

    @Override
    public QueryInfo decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf   = buf;
        int     remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        byte b = _buf.get();
        switch (b) {
            case '?':
                break;
            case ' ':
            case '#':
                return EMPTY_QUERY_INFO;
        }
        remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        return decodeQuery(_buf, dataRange, (ValueHttpRequest)request);
    }

    private QueryInfo decodeQuery(FastBuf buf, HttpFastBufDataRange dataRange, ValueHttpRequest request) {
        FastBuf         _buf       = buf;
        Map<String, List<ParameterValue>> parameters = request.getParameters();
        QueryInfo       query      = new QueryInfo();
        int             remain     = _buf.remain();
        long            rpos       = _buf.rpos();
        long            queryPos   = rpos;
        dataRange.reset();
        dataRange.buffer(_buf);
        dataRange.start(rpos);
        dataRange.first(_buf.get(rpos));
        String key = null;
        byte  b;
        long  hashCode = FNV_1a_INIT_VAL;
        byte  decodeByte;
        int   len = 0;
        boolean parseKey = true;
        for (int i=0;i<remain;i++) {
            b = _buf.get(rpos++);
            switch (b) {
                case '=':
                    dataRange.length(len);
                    if (dataRange.urlEncoded()) {
                        dataRange.setUrlEncoderLen((int) (rpos - dataRange.start() - 1));
                    }
                    _buf.rpos(dataRange.start());
                    dataRange.last(_buf.get(rpos-2));
                    dataRange.hash(hashCode);
                    key = KEY_CACHE.get(dataRange);
                    dataRange.reset();
                    dataRange.start(rpos);
                    hashCode = FNV_1a_INIT_VAL;
                    len = 0;
                    parseKey = false;
                    break;
                case '&':
                    dataRange.length(len);
                    if (dataRange.urlEncoded()) {
                        dataRange.setUrlEncoderLen((int) (rpos - dataRange.start() - 1));
                    }
                    dataRange.last(_buf.get(rpos-2));
                    _buf.rpos(dataRange.start());
                    if (!StringUtil.isEmpty(key)) {
                        dataRange.hash(hashCode);
                        ParameterValue pv;
                        if (dataRange.urlEncoded()) {
                            pv = new ParameterValue(dataRange.getString());
                        } else {
                            byte[] data = new byte[dataRange.length()];
                            _buf.get(dataRange.start(), data);
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
                    parseKey = true;
                    key = null;
                    len = 0;
                    dataRange.reset();
                    dataRange.start(rpos);
                    hashCode = FNV_1a_INIT_VAL;
                    break;
                case ' ':
                    _buf.rpos(dataRange.start());
                    if (!StringUtil.isEmpty(key)) {
                        dataRange.last();
                        dataRange.length(len);
                        if (dataRange.urlEncoded()) {
                            dataRange.setUrlEncoderLen((int)(rpos - dataRange.start() - 1));
                        }
                        dataRange.hash(hashCode);
                        ParameterValue pv;
                        if (dataRange.urlEncoded()) {
                            pv = new ParameterValue(dataRange.getString());
                        } else {
                            byte[] data = new byte[dataRange.length()];
                            _buf.get(dataRange.start(), data);
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
                    _buf.rpos(queryPos + i +1);
                    return query;
                case '+':
                    decodeByte = ' ';
                    hashCode ^= decodeByte;
                    hashCode *= FNV_1a_FACTOR_VAL;
                    len++;
                    dataRange.urlEncoded(true);
                    break;
                case '%':
                    if (i < remain - 2) {
                        int v = BYTE_VALUES[_buf.get(rpos++)] * 16 + BYTE_VALUES[_buf.get(rpos++)];
                        if (v < 0) {
                            throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value");
                        }
                        decodeByte = (byte)v;
                        hashCode ^= decodeByte;
                        hashCode *= FNV_1a_FACTOR_VAL;
                        dataRange.urlEncoded(true);
                        i += 2;
                        len++;
                        break;
                    } else {
                        return null;
                    }
                default:
                    if (parseKey) {
                        hashCode ^= b;
                        hashCode *= FNV_1a_FACTOR_VAL;
                    }
                    len++;
            }
        }
        return null;
    }
}
