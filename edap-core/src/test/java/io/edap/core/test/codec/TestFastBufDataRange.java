package io.edap.core.test.codec;

import io.edap.buffer.FastBuf;
import io.edap.codec.BytesDataRange;
import io.edap.codec.FastBufDataRange;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;
import static org.junit.jupiter.api.Assertions.*;

public class TestFastBufDataRange {

    @Test
    public void testFrom() {
        FastBufDataRange fdr = FastBufDataRange.from("");
        assertNull(fdr);

        String str = new Random().nextLong() + "";
        fdr = FastBufDataRange.from(str);
        assertEquals(fdr.first(), str.getBytes()[0]);
        assertEquals(fdr.last(), str.getBytes()[str.length()-1]);
        assertEquals(fdr.hashCode(), fnv1aHash(str.getBytes()));
        assertEquals(fdr.length(), str.length());
        assertFalse(fdr.matchStrict());
    }

    @Test
    public void testConstructor() {
        String str = new Random().nextLong() + "";
        FastBuf fb = new FastBuf(str.length());
        fb.write(str.getBytes(), 0, str.length());
        FastBufDataRange fdr = new FastBufDataRange(fb, fb.address(), str.length(), fnv1aHash(str.getBytes()));
        assertEquals(fdr.first(), str.getBytes()[0]);
        assertEquals(fdr.last(), str.getBytes()[str.length()-1]);
        assertEquals(fdr.hashCode(), fnv1aHash(str.getBytes()));
        assertEquals(fdr.length(), str.length());
        assertFalse(fdr.matchStrict());
        assertEquals(fdr.buffer(), fb);
        assertEquals(fdr.start(), fb.address());
    }

    @Test
    public void testEquals() {
        FastBufDataRange fdr = FastBufDataRange.from("Host");

        BytesDataRange bdr = BytesDataRange.from("Host");
        assertEquals(fdr.equals(bdr), false);


        FastBufDataRange nfdr = fdr;
        assertEquals(fdr.equals(nfdr), true);

        FastBufDataRange other = new FastBufDataRange();
        other.first((byte)'H');
        other.last((byte)'t');
        other.hashCode(fnv1aHash("Host".getBytes()));
        other.length(3);
        assertEquals(fdr.equals(other), false);

        other.length(4);
        other.first((byte)'h');
        assertEquals(fdr.equals(other), false);

        other.first((byte)'H');
        other.last((byte)'T');
        assertEquals(fdr.equals(other), false);

        other.last((byte)'t');
        other.hashCode(101);
        assertEquals(fdr.equals(other), false);

        other.hashCode(fnv1aHash("Host".getBytes()));
        assertEquals(fdr.equals(other), true);

        fdr.matchStrict(true);
        assertEquals(fdr.equals(nfdr), true);

        other.length(3);
        assertEquals(fdr.equals(other), false);

        other.length(4);
        other.first((byte)'h');
        assertEquals(fdr.equals(other), false);

        other.first((byte)'H');
        other.last((byte)'T');
        assertEquals(fdr.equals(other), false);

        other.last((byte)'t');
        other.hashCode(101);
        assertEquals(fdr.equals(other), false);

        other.hashCode(fnv1aHash("Host".getBytes()));
        FastBuf fb = new FastBuf(4);
        fb.write("Host".getBytes(), 0, 4);
        other.buffer(fb);
        other.start(fb.address());
        assertEquals(fdr.equals(other), true);

        fb = new FastBuf(4);
        fb.write("Ho1t".getBytes(), 0, 4);
        other.buffer(fb);
        other.start(fb.address());
        assertEquals(fdr.equals(other), false);
    }

    @Test
    public void testGetString() {
        String str = (100000 + new Random().nextLong()) + "";
        FastBufDataRange fdr = FastBufDataRange.from(str.substring(2, 6));

        assertEquals(fdr.getString(), str.substring(2, 6));
    }

    @Test
    public void testReset() {
        FastBufDataRange fdr = FastBufDataRange.from("edap");
        fdr.reset();
    }

    private int fnv1aHash(byte[] bytes) {
        long hashCode = FNV_1a_INIT_VAL;
        for (byte b : bytes) {
            hashCode ^= b;
            hashCode *= FNV_1a_FACTOR_VAL;
        }

        return (int)hashCode;
    }

}
