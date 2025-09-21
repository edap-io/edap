package io.edap.http.codec;

import io.edap.buffer.FastBuf;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static io.edap.util.Constants.*;

public class HttpFastBufDataRange extends FastBufDataRange {

    private boolean urlEncoded;

    private ByteArrayBuilder bytesBuilder;

    public HttpFastBufDataRange() {
        bytesBuilder = new ByteArrayBuilder();
    }

    public HttpFastBufDataRange urlEncoded(boolean urlEncoded) {
        this.urlEncoded = urlEncoded;

        return this;
    }

    public ByteArrayBuilder getBytesBuilder() {
        return bytesBuilder;
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

    public void append(byte[] data) {
        bytesBuilder.append(data);
    }

    public void append(byte b) {
        bytesBuilder.append(b);
    }

    @Override
    public String getString(Charset charset) {
        if (urlEncoded) {
            return new String(bytesBuilder.toByteArray(), StandardCharsets.UTF_8);
        } else {
            return super.getString(charset);
        }
    }

    @Override
    public void reset() {
        bytesBuilder.reset();
        boolean _urlEncoded = false;
        urlEncoded = _urlEncoded;
        super.reset();
    }
}
