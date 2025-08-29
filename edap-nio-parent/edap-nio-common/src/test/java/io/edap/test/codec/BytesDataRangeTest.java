package io.edap.test.codec;

import io.edap.buffer.FastBuf;
import io.edap.nio.codec.BytesDataRange;
import io.edap.nio.codec.FastBufDataRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;
import static org.junit.jupiter.api.Assertions.*;

public class BytesDataRangeTest {

    @Test
    public void testFrom() {
        BytesDataRange fdr = BytesDataRange.from("");
        assertNull(fdr);

        String str = new Random().nextLong() + "";
        fdr = BytesDataRange.from(str);
        assertEquals(fdr.first(), str.getBytes()[0]);
        assertEquals(fdr.last(), str.getBytes()[str.length()-1]);
        assertEquals(fdr.hash(), fnv1aHash(str.getBytes()));
        assertEquals(fdr.length(), str.length());
        assertFalse(fdr.matchStrict());
    }

    @Test
    public void testConstructor() {
        String str = new Random().nextLong() + "";
        BytesDataRange fdr = new BytesDataRange(str);
        assertEquals(fdr.first(), str.getBytes()[0]);
        assertEquals(fdr.last(), str.getBytes()[str.length()-1]);
        assertEquals(fdr.hash(), fnv1aHash(str.getBytes()));
        assertEquals(fdr.length(), str.length());
        assertFalse(fdr.matchStrict());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    new BytesDataRange(null);
                });
        assertEquals(thrown.getMessage(), "Cann't empty!");
    }

    @Test
    public void testEquals() {
        BytesDataRange fdr = BytesDataRange.from("Host");

        FastBufDataRange bdr = FastBufDataRange.from("Host");
        assertEquals(fdr.equals(bdr), false);


        BytesDataRange nfdr = fdr;
        assertEquals(fdr.equals(nfdr), true);

        BytesDataRange other = new BytesDataRange();
        other.buffer("Host".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(other.buffer(), "Host".getBytes(StandardCharsets.UTF_8));
        other.first((byte)'H');
        other.last((byte)'t');
        other.hash(fnv1aHash("Host".getBytes()));
        other.length(3);
        assertEquals(fdr.equals(other), false);
        fdr.start(0);
        assertEquals(fdr.start(), 0);

        other.length(4);
        other.first((byte)'h');
        assertEquals(fdr.equals(other), false);

        other.first((byte)'H');
        other.last((byte)'T');
        assertEquals(fdr.equals(other), false);

        other.last((byte)'t');
        other.hash(101);
        assertEquals(fdr.equals(other), false);

        other.hash(fnv1aHash("Host".getBytes()));
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
        other.hash(-101L);
        assertEquals(fdr.equals(other), false);

        fdr.matchStrict(false);
        other.hash(-2798444378225708657L);
        assertEquals(fdr.equals(other), true);

        fdr.matchStrict(true);
        other.hash(-2798444378225708657L);
        assertEquals(fdr.equals(other), true);

        other.buffer("Hast".getBytes(StandardCharsets.UTF_8));
        assertEquals(fdr.equals(other), false);

        other.buffer("Hobt".getBytes(StandardCharsets.UTF_8));
        assertEquals(fdr.equals(other), false);


        fdr = BytesDataRange.from("Ht");
        other = BytesDataRange.from("Ht");
        fdr.matchStrict(true);
        assertEquals(fdr.equals(other), true);
    }

    @Test
    public void testGetString() {
        String str = (100000 + new Random().nextLong()) + "";
        BytesDataRange fdr = BytesDataRange.from(str.substring(2, 6));

        assertEquals(fdr.getString(), str.substring(2, 6));
    }

    @Test
    public void testReset() {
        BytesDataRange fdr = BytesDataRange.from("io/edap");
        fdr.reset();
    }

    private long fnv1aHash(byte[] bytes) {
        long hashCode = FNV_1a_INIT_VAL;
        for (byte b : bytes) {
            hashCode ^= b;
            hashCode *= FNV_1a_FACTOR_VAL;
        }

        return hashCode;
    }

}
