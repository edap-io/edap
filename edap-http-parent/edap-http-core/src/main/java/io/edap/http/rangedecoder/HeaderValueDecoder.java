package io.edap.http.rangedecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.cache.HeaderValueCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.util.ByteArrayBuilder;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

/**
 * HeaderValue的解析器
 */
public class HeaderValueDecoder implements RangeTokenDecoder<HeaderValue> {

    public HeaderValue decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf   = buf;
        int     remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        int  i    = 0;
        long rpos = _buf.rpos();
        // 忽略HeaderValue的第一个空格字符
        byte b = _buf.get(rpos + i);
        if (b == ' ') {
            i++;
        }
        if (i == remain) {
            return null;
        }
        HeaderValue hv;
        long start = rpos + i;
        for (;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b == '\r') {
                if (i < remain - 1) {
                    if (_buf.get(rpos+i+1) == '\n') {
                        hv = new HeaderValue();
                        _buf.rpos(rpos+i+2);
                        byte[] data = new byte[(int)((rpos+i)-start)];
                        _buf.get(start, data);
                        hv.setData(data);
                        return hv;
                    } else {
                        throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
                    }
                } else {
                    return null;
                }
            }
        }
        return null;
    }
}
