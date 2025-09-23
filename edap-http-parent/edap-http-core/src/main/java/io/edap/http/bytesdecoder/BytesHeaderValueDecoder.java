package io.edap.http.bytesdecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.util.ByteArrayBuilder;

/**
 * HeaderValue的解析器
 */
public class BytesHeaderValueDecoder implements BytesTokenDecoder<HeaderValue> {


    @Override
    public HeaderValue decode(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
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
//        sb.setLength(0);
//        sb.ensureCapacity(remain-i);
        int start = i;
        for (;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b == '\r') {
                if (i < remain - 1) {
                    if (_buf.get(rpos+i+1) == '\n') {
                        hv = new HeaderValue();
                        byte[] data = new byte[i-start];
                        _buf.get(rpos + start, data);
                        hv.setData(data);
                        _buf.rpos(rpos + i + 2);
                        return hv;
                    } else {
                        throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
                    }
                } else {
                    return null;
                }
            }
//            else {
//                sb.uncheckAppend(b);
//            }
        }
        return null;
    }

    //@Override
    public HeaderValue decode2(FastBuf buf, ByteArrayBuilder sb, HttpRequest request) {
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
//        sb.setLength(0);
//        sb.ensureCapacity(remain-i);
        int start = i;
        for (;i<remain;i++) {
            b = _buf.get(rpos + i);
            if (b == '\r') {
                if (i < remain - 1) {
                    if (_buf.get(rpos+i+1) == '\n') {
                        hv = new HeaderValue();
                        byte[] data = new byte[i-start];
                        _buf.get(rpos + start, data);
                        hv.setData(data);
                        _buf.rpos(rpos + i + 2);
                        return hv;
                    } else {
                        throw new IllegalArgumentException("HeaderValue: Illegal name can't have \\r!");
                    }
                } else {
                    return null;
                }
            }
//            else {
//                sb.uncheckAppend(b);
//            }
        }
        return null;
    }
}
