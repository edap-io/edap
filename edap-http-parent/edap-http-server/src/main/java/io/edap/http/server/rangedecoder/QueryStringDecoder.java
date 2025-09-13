package io.edap.http.server.rangedecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.cache.ParamKeyCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.model.ParamPair;
import io.edap.http.model.QueryInfo;
import io.edap.http.rangedecoder.RangeTokenDecoder;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.FastList;
import io.edap.util.StringUtil;

import java.util.List;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;


public class QueryStringDecoder implements RangeTokenDecoder<QueryInfo> {

    public static QueryInfo EMPTY_QUERY_INFO = new QueryInfo();

    static ParamKeyCache KEY_CACHE = ParamKeyCache.instance();

    @Override
    public QueryInfo decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
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
        return decodeQuery(_buf, dataRange);
    }

    private QueryInfo decodeQuery(FastBuf buf, HttpFastBufDataRange dataRange) {
        FastBuf         _buf       = buf;
        List<ParamPair> paramPairs = new FastList<>();
        QueryInfo       query      = new QueryInfo();
        int             remain     = _buf.remain();
        long            rpos       = _buf.rpos();
        long            queryPos   = rpos;
        dataRange.buffer(_buf);
        dataRange.start(rpos);
        dataRange.first(_buf.get(rpos));
        String key = null;
        byte   b;
        long   hashCode = FNV_1a_INIT_VAL;
        byte   decodeByte;
        for (int i=0;i<remain;i++) {
            b = _buf.get(rpos++);
            switch (b) {
                case '=':
                    dataRange.length((int)(rpos - dataRange.start() - 1));
                    _buf.rpos(dataRange.start());
                    dataRange.last(_buf.get(rpos-2));
                    dataRange.hash(hashCode);
                    key = KEY_CACHE.get(dataRange);
                    dataRange.reset();
                    dataRange.start(rpos);
                    hashCode = FNV_1a_INIT_VAL;
                    break;
                case '&':
                    dataRange.length((int)(rpos - dataRange.start() - 1));
                    dataRange.last();
                    _buf.rpos(dataRange.start());
                    if (!StringUtil.isEmpty(key)) {
                        dataRange.hash(hashCode);
                        ParamPair paramPair = new ParamPair();
                        paramPair.setKey(key);
                        paramPair.setValue(dataRange.getString());
                        paramPairs.add(paramPair);
                    }
                    key = null;
                    dataRange.reset();
                    dataRange.start(rpos);
                    hashCode = FNV_1a_INIT_VAL;
                    break;
                case ' ':
                    _buf.rpos(dataRange.start());
                    if (!StringUtil.isEmpty(key)) {
                        dataRange.last();
                        dataRange.length((int)(rpos - dataRange.start() - 1));
                        dataRange.hash(hashCode);
                        ParamPair paramPair = new ParamPair();
                        paramPair.setKey(key);
                        paramPair.setValue(dataRange.getString());
                        paramPairs.add(paramPair);
                    }
                    byte[] queryBytes = new byte[i];
                    _buf.rpos(queryPos);
                    _buf.get(queryPos, queryBytes);
                    query.setQueryBytes(queryBytes);
                    query.setParamPairs(paramPairs);
                    return query;
                case '+':
                    decodeByte = ' ';
                    hashCode ^= ' ';
                    hashCode *= FNV_1a_FACTOR_VAL;
                    if (dataRange.urlEncoded()) {
                        dataRange.append(decodeByte);
                    } else {
                        if (rpos > dataRange.start() + 1) {
                            ByteArrayBuilder builder = dataRange.getBytesBuilder();
                            builder.ensureCapacity(i);
                            int noDecodeLen = (int)(rpos - dataRange.start()-1);
                            byte[] data = builder.getValue();
                            buf.get(dataRange.start(), data, 0, noDecodeLen);
                            builder.setLength(noDecodeLen);
                        }
                        dataRange.append(decodeByte);
                        dataRange.urlEncoded(true);
                    }
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
                        if (dataRange.urlEncoded()) {
                            dataRange.append(decodeByte);
                        } else {
                            if (rpos > dataRange.start() + 1) {
                                ByteArrayBuilder builder = dataRange.getBytesBuilder();
                                builder.ensureCapacity(i);
                                int noDecodeLen = (int)(rpos - dataRange.start()-3);
                                byte[] data = builder.getValue();
                                buf.get(dataRange.start(), data, 0, noDecodeLen);
                                builder.setLength(noDecodeLen);
                            }
                            dataRange.append(decodeByte);
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
                        dataRange.append(b);
                    }
            }
        }
        return null;
    }
}
