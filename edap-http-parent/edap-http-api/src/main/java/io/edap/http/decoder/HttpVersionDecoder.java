package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.codec.FastBufDataRange;
import io.edap.http.HttpRequest;
import io.edap.http.HttpVersion;
import io.edap.http.codec.HttpFastBufDataRange;

import static io.edap.http.HttpVersion.*;

public class HttpVersionDecoder implements TokenDecoder<HttpVersion> {

    @Override
    public HttpVersion decode(FastBuf buf, HttpFastBufDataRange dataRange, HttpRequest request) {
        long pos = buf.rpos();
        int  len = buf.remain();
        if (len < 10) return null;
        if (buf.get(pos + 4) == '/' && buf.get(pos + 6) == '.'
                && buf.get(pos + 8)== '\r' && buf.get(pos + 9) == '\n'
                && ((buf.get(pos) == 'H' && buf.get(pos+1) == 'T'
                && buf.get(pos+2) == 'T' && buf.get(pos+3) == 'P')
                || (buf.get(pos) == 'h'  && buf.get(pos+1) == 't'
                && buf.get(pos+2) == 't' && buf.get(pos+3) == 'p'))) {
            byte b = buf.get(pos + 5);
            if (b == '0') {
                buf.rpos(pos+10);
                return HTTP_0_9;
            } else if (b == '1') {
                byte b2 = buf.get(pos + 7);
                if (b2 == '0') {
                    buf.rpos(pos+10);
                    return HTTP_1_0;
                } else if (b2 == '1') {
                    buf.rpos(pos+10);
                    return HTTP_1_1;
                }
            } else if (b == '2') {
                byte b2 = buf.get(pos + 7);
                if (b2 == '0') {
                    buf.rpos(pos+10);
                    return HTTP_2_0;
                }
            }
//            switch (buf.get(pos + 5)) {
//                case '0':
//                    buf.rpos(pos+10);
//                    return HTTP_0_9;
//                case '1':
//                    switch (buf.get(pos + 7)) {
//                        case '0':
//                            buf.rpos(pos+10);
//                            return HTTP_1_0;
//                        case '1':
//                            buf.rpos(pos+10);
//                            return HTTP_1_1;
//                    }
//                    break;
//                case '2':
//                    switch (buf.get(pos + 7)) {
//                        case '0':
//                            buf.rpos(pos+10);
//                            return HTTP_2_0;
//                    }
//                    break;
//            }
        }
        buf.rpos(pos+10);
        return NOT_SUPPORT_VERSION;
    }
}
