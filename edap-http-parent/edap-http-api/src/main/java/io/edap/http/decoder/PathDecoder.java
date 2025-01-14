package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.PathInfo;
import io.edap.http.cache.PathCache;
import io.edap.http.codec.HttpFastBufDataRange;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class PathDecoder implements TokenDecoder<PathInfo> {

    static PathCache CACHE = PathCache.instance();

    @Override
    public PathInfo decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        byte b;
        long pos = _buf.rpos();
        dataRange.buffer(buf);
        dataRange.start(pos);
        long hashCode = FNV_1a_INIT_VAL;
        int len = 0;
        dataRange.first(_buf.get(pos));
        for (int i=0;i<remain;i++) {
            b = _buf.get(pos+i);
            switch (b) {
                case ' ':
                case '?':
                case '#':
                    dataRange.length(len);
                    dataRange.hashCode((int)hashCode);
                    dataRange.last();
                    _buf.rpos(pos+i);
                    return CACHE.get(dataRange);
                case '+':
                    hashCode ^= ' ';
                    hashCode *= FNV_1a_FACTOR_VAL;
                    len++;
                    //dataRange.urlEncoded(true);
                    break;
                case '%':
                    if (i < remain - 2) {
                        int v = BYTE_VALUES[_buf.get(pos+i+1)] * 16 + BYTE_VALUES[_buf.get(pos+i+2)];
                        if (v < 0) {
                            throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value");
                        }
                        i += 2;
                        hashCode ^= (byte) v;
                        hashCode *= FNV_1a_FACTOR_VAL;
                        len++;
                        //dataRange.urlEncoded(true);
                        break;
                    } else {
                        return null;
                    }
                default:
                    hashCode ^= b;
                    hashCode *= FNV_1a_FACTOR_VAL;
                    len++;
            }
        }
        return null;
    }
}
