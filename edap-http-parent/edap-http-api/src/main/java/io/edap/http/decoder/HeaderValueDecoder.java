package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;

/**
 * HeaderValue的解析器
 */
public class HeaderValueDecoder implements TokenDecoder<HeaderValue> {


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
        HeaderValue hv = null;
        dataRange.start(rpos + i);
        for (;i<remain;i++) {
            FAST_BREAK: if (remain - i > 10) {
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
            b = _buf.get(rpos + i);
            if (b == '\r') {
                if (i < remain - 1) {
                    if (_buf.get(rpos+i+1) == '\n') {
                        hv = new HeaderValue();
                        _buf.rpos(rpos+i+2);
                        byte[] data = new byte[(int)((rpos+i)-dataRange.start())];
                        _buf.get(dataRange.start(), data);
                        hv.setData(data);
                        return hv;
                    } else {
                        throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
                    }
                } else {
                    return null;
                }
            }
//            switch (b) {
//                case '\r':
//                    if (i < remain - 1) {
//                        if (_buf.get(rpos+i+1) == '\n') {
//                            hv = new HeaderValue();
//                            _buf.rpos(rpos+i+2);
//                            byte[] data = new byte[(int)((rpos+i)-dataRange.start)];
//                            _buf.get(dataRange.start, data);
//                            hv.setData(data);
//                            return hv;
//                        } else {
//                            throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
//                        }
//                    } else {
//                        return null;
//                    }
//                default:
//
//            }
        }
        return hv;
    }
}
