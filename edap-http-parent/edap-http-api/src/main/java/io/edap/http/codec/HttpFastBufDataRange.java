package io.edap.http.codec;

import io.edap.buffer.FastBuf;
import io.edap.codec.FastBufDataRange;
import io.edap.util.StringUtil;

import static io.edap.util.Constants.*;

public class HttpFastBufDataRange extends FastBufDataRange {

    private boolean urlEncoded;

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
        dr.hashCode((int)hashCode);
        return dr;
    }

    public boolean urlEncoded() {
        return urlEncoded;
    }
}
