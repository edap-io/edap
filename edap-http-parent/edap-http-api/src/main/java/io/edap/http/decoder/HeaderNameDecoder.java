package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderName;
import io.edap.http.HttpRequest;
import io.edap.http.cache.HeaderNameCache;
import io.edap.http.codec.HttpFastBufDataRange;

import static io.edap.http.AbstractHttpDecoder.FINISH_HEADERNAME;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

/**
 * HeaderName的解析器
 */
public class HeaderNameDecoder implements TokenDecoder<HeaderName> {


    static HeaderNameCache CACHE = HeaderNameCache.instance();

    @Override
    public HeaderName decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        long rpos = _buf.rpos();
        byte b = _buf.get(rpos);
        if (b == '\r') {
            if (remain > 1 && _buf.get(rpos + 1) == '\n') {
                _buf.rpos(rpos+2);
                return FINISH_HEADERNAME;
            } else {
                return null;
            }
        }

        dataRange.start(rpos);
        dataRange.first(b);
        long hashCode = FNV_1a_INIT_VAL;
        hashCode ^= b;
        hashCode *= FNV_1a_FACTOR_VAL;
        for (int i=1;i<remain;i++) {
            b = _buf.get(rpos+i);
            if (b == ':') {
                dataRange.length(i);
                dataRange.hashCode((int)hashCode);
                dataRange.last();
                _buf.rpos(rpos+i+1);
                return CACHE.get(dataRange);
            } else if (b == ' ') {
                dataRange.length(i);
                dataRange.hashCode((int)hashCode);
                dataRange.last();
                for (int j=i+1;j<remain;j++) {
                    b = _buf.get(rpos+j);
                    switch (b) {
                        case ' ':
                            break;
                        case ':':
                            _buf.rpos(rpos+j+1);
                            return CACHE.get(dataRange);
                        default:
                            int l = (int)(_buf.limit() - dataRange.start());
                            byte[] bs = new byte[l];
                            _buf.get(dataRange.start().longValue(), bs);
                            System.out.println("" + new String(bs));
                            throw new IllegalArgumentException("HeaderName: Illegal name can't have space!");
                    }
                }
            } else {
                hashCode ^= b;
                hashCode *= FNV_1a_FACTOR_VAL;
            }
//            switch (b) {
//                case ':':
//                    dataRange.length = i;
//                    dataRange.hash = (int)hashCode;
//                    dataRange.end = rpos + i;
//                    _buf.rpos(rpos+i+1);
//                    return CACHE.get(dataRange);
//                case ' ':
//                    dataRange.length = i;
//                    dataRange.hash = (int)hashCode;
//                    dataRange.end = rpos + i;
//                    for (int j=i+1;j<remain;j++) {
//                        b = _buf.get(rpos+j);
//                        switch (b) {
//                            case ' ':
//                                break;
//                            case ':':
//                                _buf.rpos(rpos+j+1);
//                                return CACHE.get(dataRange);
//                            default:
//                                int l = (int)(_buf.limit() - dataRange.start);
//                                byte[] bs = new byte[l];
//                                _buf.get(dataRange.start, bs);
//                                System.out.println("" + new String(bs));
//                                throw new IllegalArgumentException("HeaderName: Illegal name can't have space!");
//                        }
//                    }
//                default:
//                    hashCode ^= b;
//                    hashCode *= FNV_1a_FACTOR_VAL;
//            }
        }
        return null;
    }
}
