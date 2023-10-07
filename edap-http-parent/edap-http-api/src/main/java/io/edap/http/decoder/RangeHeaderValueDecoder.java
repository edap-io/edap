package io.edap.http.decoder;


import io.edap.buffer.FastBuf;
import io.edap.codec.FastBufDataRange;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.RangeHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;

import static io.edap.http.AbstractHttpDecoder.EMPTY_HEADERVALUE;

public class RangeHeaderValueDecoder implements TokenDecoder<HeaderValue> {

    @Override
    public HeaderValue decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();
        if (remain <= 0) {
            return null;
        }
        int i;
        long rpos = _buf.rpos();
        byte b;
        for (i=0;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b != ' ') {
                break;
            }
        }
        if (i == remain - 1) {
            return null;
        }
        dataRange.start(rpos + i);
        RangeHttpRequest rangeHttpRequest = (RangeHttpRequest)request;
        for (;i<remain;i++) {
            FAST_BREAK: if (remain > i+10) {
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
                if (_buf.get(rpos + i) != '\r') {
                    i++;
                } else {
                    break FAST_BREAK;
                }
            }
            if (_buf.get(rpos + i) == '\r') {
                if (i < remain - 1 && _buf.get(rpos+i+1) == '\n') {
                    _buf.rpos(rpos+i+2);
                    FastBufDataRange valueRange = rangeHttpRequest.headerRanges.values[rangeHttpRequest.headerRanges.length];
                    valueRange.buffer(buf);
                    valueRange.start(dataRange.start());
                    valueRange.length((int)((rpos+i)-dataRange.start()));
                    rangeHttpRequest.headerRanges.add();
                    return EMPTY_HEADERVALUE;
                }
            }
        }
        return null;
    }
}
