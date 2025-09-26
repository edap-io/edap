package io.edap.http.codec;

import io.edap.buffer.FastBuf;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.edap.http.HttpConsts.BYTE_VALUES;
import static io.edap.util.Constants.*;

public class HttpFastBufDataRange extends FastBufDataRange {

    private boolean urlEncoded;

    private int urlEncoderLen;

    private String value;

    public HttpFastBufDataRange() {

    }

    public HttpFastBufDataRange urlEncoded(boolean urlEncoded) {
        this.urlEncoded = urlEncoded;

        return this;
    }

    public static HttpFastBufDataRange from(String v) {
        if (StringUtil.isEmpty(v)) {
            return null;
        }
        HttpFastBufDataRange dr = new HttpFastBufDataRange();
        byte[] bytes = v.getBytes(DEFAULT_CHARSET);
        long hashCode = FNV_1a_INIT_VAL;
        FastBuf buf = new FastBuf(bytes.length);
        buf.write(bytes,0, bytes.length);
        dr.start(buf.address());
        dr.first(bytes[0]);
        dr.last(bytes[bytes.length-1]);
        dr.buffer(buf);
        for (byte b : bytes) {
            hashCode ^= b;
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        dr.length(bytes.length);
        dr.hash(hashCode);
        return dr;
    }

    public boolean urlEncoded() {
        return urlEncoded;
    }

    @Override
    public String getString(Charset charset) {
        if (value != null) {
            return value;
        }
        boolean encode = urlEncoded;
        if (encode) {
            int l = urlEncoderLen;
            FastBuf _buf = buf;
            byte[] data = new byte[l];
            int c = 0;
            long _pos = start;
            byte b;
            for (int i = 0; i < l; i++) {
                b = _buf.get(_pos + i);
                if (b == (byte) '+') {
                    data[c++] = ' ';
                } else if (b == (byte) '%') {
                    int v = (BYTE_VALUES[_buf.get(_pos + i + 1)] << 4) + BYTE_VALUES[_buf.get(_pos + i + 2)];
                    data[c++] = (byte) v;
                    i += 2;
                } else {
                    data[c++] = b;
                }
            }
            value = new String(data, 0, c, charset);
            return value;
        } else {
            value = super.getString(charset);
            return value;
            //return "";
        }
    }

    @Override
    public void reset() {
        //boolean _urlEncoded = false;
        urlEncoded = false;
        value = null;
        super.reset();
    }

    public int getUrlEncoderLen() {
        return urlEncoderLen;
    }

    public void setUrlEncoderLen(int urlEncoderLen) {
        this.urlEncoderLen = urlEncoderLen;
    }
}
