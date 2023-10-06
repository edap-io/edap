package io.edap.http.decoder;


import io.edap.buffer.FastBuf;
import io.edap.codec.FastBufDataRange;
import io.edap.http.HttpRequest;
import io.edap.http.MethodInfo;
import io.edap.http.cache.MethodCache;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class MethodDecoder implements TokenDecoder<MethodInfo> {

    static final MethodCache METHOD_CACHE = MethodCache.instance();

    static MethodInfo GET     = METHOD_CACHE.getMethodInfo("GET");
    static MethodInfo PUT     = METHOD_CACHE.getMethodInfo("PUT");
    static MethodInfo HEAD    = METHOD_CACHE.getMethodInfo("HEAD");
    static MethodInfo POST    = METHOD_CACHE.getMethodInfo("POST");
    static MethodInfo TRACE   = METHOD_CACHE.getMethodInfo("TRACE");
    static MethodInfo DELETE  = METHOD_CACHE.getMethodInfo("DELETE");
    static MethodInfo CONNECT = METHOD_CACHE.getMethodInfo("CONNECT");
    static MethodInfo OPTIONS = METHOD_CACHE.getMethodInfo("OPTIONS");

    @Override
    public MethodInfo decode(FastBuf buf, FastBufDataRange dataRange, HttpRequest request) {
        FastBuf _buf = buf;
        int remain = _buf.remain();

        long pos = _buf.rpos();
        dataRange.start(pos);
        METHOD_FINISHED: if (remain > 7) {
            byte b1 = _buf.get(pos);
            byte b2 = _buf.get(pos+1);
            if (b2 == ' ') {
                _buf.rpos(pos+2);
                return getMethodInfo(pos, _buf, dataRange, b1);
            }
            byte b3 = _buf.get(pos+2);
            if (b3 == ' ') {
                _buf.rpos(pos+3);
                return getMethodInfo(pos, _buf, dataRange, b1, b2);
            }
            byte b4 = _buf.get(pos+3);
            if (b4 == ' ') {
                _buf.rpos(pos+4);
                if (b1 == 'G' && b2 == 'E' && b3 == 'T') {
                    return GET;
                } else if (b1 == 'P' && b2 == 'U' && b3 == 'T') {
                    return PUT;
                } else {
                    return getMethodInfo(pos, _buf, dataRange, b1, b2, b3);
                }
            }
            byte b5 = _buf.get(pos+4);
            if (b5 == ' ') {
                _buf.rpos(pos+5);
                if (b1 == 'H' && b2 == 'E' && b3 == 'A' && b4 == 'D') {
                    return HEAD;
                } else if (b1 == 'P' && b2 == 'O' && b3 == 'S' && b4 == 'T') {
                    return POST;
                } else {
                    return getMethodInfo(pos, _buf, dataRange, b1, b2, b3, b4);
                }
            }

            byte b6 = _buf.get(pos+5);
            if (b6 == ' ') {
                _buf.rpos(pos+6);
                if (b1 == 'T' && b2 == 'R' && b3 == 'A' && b4 == 'C' && b5 == 'E') {
                    return TRACE;
                } else {
                    return getMethodInfo(pos, _buf, dataRange, b1, b2, b3, b4, b5);
                }
            }

            byte b7 = _buf.get(pos+6);
            if (b7 == ' ') {
                _buf.rpos(pos+7);
                if (b1 == 'D' && b2 == 'E' && b3 == 'L' && b4 == 'E' && b5 == 'T'
                        && b6 == 'E') {
                    return DELETE;
                } else {
                    return getMethodInfo(pos, _buf, dataRange, b1, b2, b3, b4, b5, b6);
                }
            }
            byte b8 = _buf.get(pos+7);
            if (b8 == ' ') {
                _buf.rpos(pos+8);
                if (b1 == 'C' && b2 == 'O' && b3 == 'N' && b4 == 'N' && b5 == 'E'
                        && b6 == 'C' && b7 == 'T') {
                    return CONNECT;
                } else if (b1 == 'O' && b2 == 'P' && b3 == 'T' && b4 == 'I'
                        && b5 == 'O' && b6 == 'N' && b7 == 'S') {
                    return OPTIONS;
                } else {
                    return getMethodInfo(pos, _buf, dataRange, b1, b2, b3, b4, b5, b6, b7);
                }
            }
            break METHOD_FINISHED;
        }
        long hashCode = FNV_1a_INIT_VAL;
        for (int i = 0;i<remain;i++) {
            byte b = _buf.get(pos + i);
            if (b == ' ') {
                dataRange.first(_buf.get(pos));
                dataRange.hashCode((int)hashCode);
                dataRange.length(i);
                dataRange.start(pos);
                _buf.rpos(pos + i);
                return METHOD_CACHE.getMethodInfo(dataRange);
            } else {
                hashCode ^= b;
                hashCode *= FNV_1a_FACTOR_VAL;
            }
        }
        return null;
    }

    private MethodInfo getMethodInfo(long pos, FastBuf buf, FastBufDataRange dr, byte... bs) {
        long hashCode = FNV_1a_INIT_VAL;
        int len = bs.length;
        for (int i=0;i<len;i++) {
            hashCode ^= bs[i];
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        dr.first(bs[0]);
        dr.hashCode((int)hashCode);
        dr.length(len);
        dr.start(pos);
        dr.buffer(buf);

        return METHOD_CACHE.getMethodInfo(dr);
    }
}
