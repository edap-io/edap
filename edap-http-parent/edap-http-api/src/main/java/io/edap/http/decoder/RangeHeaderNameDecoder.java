package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderName;
import io.edap.http.HttpRequest;
import io.edap.http.RangeHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;

import static io.edap.http.AbstractHttpDecoder.EMPTY_HEADERNAME;
import static io.edap.http.AbstractHttpDecoder.FINISH_HEADERNAME;
import static io.edap.util.Constants.BKDR_HASH_SEED;

public class RangeHeaderNameDecoder implements TokenDecoder<HeaderName> {
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
        int hashCode = 0;
        hashCode = BKDR_HASH_SEED * hashCode + b;
        for (int i=1;i<remain;i++) {
            b = _buf.get(rpos+i);
            HttpFastBufDataRange keyRange;
            RangeHttpRequest rangeHttpRequest;
            switch (b) {
                case ':':
                    rangeHttpRequest = (RangeHttpRequest)request;
                    keyRange = rangeHttpRequest.headerRanges.keys[rangeHttpRequest.headerRanges.length];
                    keyRange.length(i);
                    keyRange.hashCode(hashCode);
                    keyRange.last();
                    _buf.rpos(rpos+i+1);
                    return EMPTY_HEADERNAME;
                case ' ':
                    rangeHttpRequest = (RangeHttpRequest)request;
                    keyRange = rangeHttpRequest.headerRanges.keys[rangeHttpRequest.headerRanges.length];
                    keyRange.length(i);
                    keyRange.hashCode(hashCode);
                    keyRange.last();
                    for (int j=i+1;j<remain;j++) {
                        switch (_buf.get(rpos+j)) {
                            case ' ':
                                break;
                            case ':':
                                _buf.rpos(rpos+j+1);
                                return EMPTY_HEADERNAME;
                            default:
                                int l = (int)(_buf.limit() - dataRange.start());
                                byte[] bs = new byte[l];
                                _buf.get(dataRange.start(), bs);
                                System.out.println("" + new String(bs));
                                throw new IllegalArgumentException("HeaderName: Illegal name can't have space!");
                        }
                    }
                default:
                    hashCode = BKDR_HASH_SEED * hashCode + b;
            }
        }
        return null;
    }
}
