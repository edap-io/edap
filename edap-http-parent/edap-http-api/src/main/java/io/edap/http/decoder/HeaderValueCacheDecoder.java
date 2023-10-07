package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.cache.HeaderValueCache;
import io.edap.http.codec.HttpFastBufDataRange;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

/**
 * 使用缓存的HeaderValue的解析器，使用该解析器的headerValue的值应该相对来说不怎么变化而且值不会太多的header时使用
 */
public class HeaderValueCacheDecoder implements TokenDecoder<HeaderValue> {

    static HeaderValueCache CACHE = HeaderValueCache.instance();

    @Override
    public HeaderValue decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        int i;
        long rpos = _buf.rpos();
        byte b = 0;
        for (i=0;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b != ' ') {
                break;
            }
        }
        if (i == remain - 1) {
            return null;
        }
        long hashCode = FNV_1a_INIT_VAL;
//        hashCode ^= b;
//        hashCode *= FNV_1a_FACTOR_VAL;
        HeaderValue hv = null;
        dataRange.start(rpos + i);
        dataRange.first(b);
        int len;
        for (;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b == '\r') {
                if (i < remain - 1) {
                    if (_buf.get(rpos+i+1) == '\n') {
                        _buf.rpos(rpos+i+2);
                        len = (int)((rpos+i)-dataRange.start());
                        dataRange.hashCode((int)hashCode);
                        dataRange.last();
                        dataRange.length(len);
                        return CACHE.get(dataRange);
                    } else {
                        throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
                    }
                } else {
                    return null;
                }
            } else {
                hashCode ^= b;
                hashCode *= FNV_1a_FACTOR_VAL;
            }
//            switch (b) {
//                case '\r':
//                    if (i < remain - 1) {
//                        if (_buf.get(rpos+i+1) == '\n') {
//                            _buf.rpos(rpos+i+2);
//                            len = (int)((rpos+i)-dataRange.start);
//                            dataRange.hash   = (int)hashCode;
//                            dataRange.end    = rpos+i;
//                            dataRange.length = len;
//                            return CACHE.get(dataRange);
//                        } else {
//                            throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
//                        }
//                    } else {
//                        return null;
//                    }
//                default:
//                    hashCode ^= b;
//                    hashCode *= FNV_1a_FACTOR_VAL;
//            }
        }
        return null;
    }
}
