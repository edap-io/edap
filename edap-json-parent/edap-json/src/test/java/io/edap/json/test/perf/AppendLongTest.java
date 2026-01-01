package io.edap.json.test.perf;

import io.edap.util.FastNum;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.Buffer;
import java.util.Random;

public class AppendLongTest {

    private final static int[] DIGITS = new int[1000];

    public static long[] values;

    static {
        Random random = new Random();
        values = new long[100];
        for (int i=0;i<100;i++) {
            long l = random.nextInt();
            values[i] = 112;
            System.out.println(values[i]);
        }

        for (int i = 0; i < DIGITS.length; i++) {
            DIGITS[i] = (i < 10 ? (2 << 24) : i < 100 ? (1 << 24) : 0)
                    + (((i / 100) + '0') << 16)
                    + ((((i / 10) % 10) + '0') << 8)
                    + i % 10 + '0';
        }
    }


    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(AppendLongTest.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                //.addProfiler(StackProfiler.class)
                .build();

        new Runner(opt).run();
    }


    @Benchmark
    public int writeLong2() {
        byte[] buf = new byte[30];
        for (long l : values) {
            dowriteLong2(buf, l);
        }
        return 100;
    }

    public int dowriteLong2(byte[] buf, long v) {
        return uncheckWriteLong(buf, 0, v);
    }

    @Benchmark
    public int writeLong1() {
        byte[] buf = new byte[30];
        for (long l : values) {
            int len = dowriteLong1(buf, l);
            if (len > 1) {
                System.arraycopy(buf, len, buf, 0, 20-len);
            }
        }
        return 100;
    }

    public int dowriteLong1(byte[] numberBuffer, long num) {
        numberBuffer[19] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 19;
        numberBuffer[18] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 18;
        numberBuffer[17] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 17;
        numberBuffer[16] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 16;
        numberBuffer[15] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 15;
        numberBuffer[14] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 14;
        numberBuffer[13] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 13;
        numberBuffer[12] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 12;
        numberBuffer[11] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 11;
        numberBuffer[10] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 10;
        numberBuffer[9] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 9;
        numberBuffer[8] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 8;
        numberBuffer[7] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 7;
        numberBuffer[6] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 6;
        numberBuffer[5] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 5;
        numberBuffer[4] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 4;
        numberBuffer[3] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 3;
        numberBuffer[2] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 2;
        numberBuffer[1] = (byte) (num % 10L + '0');
        return 1;
    }

    public static int uncheckWriteLong(byte[] buf, int pos, long i) {
        final long q1 = i / 1000;
        if (q1 == 0) {
            return writeFirstBuf(buf, pos, DIGITS[(int) i]);
        }
        final int r1 = (int) (i - q1 * 1000);
        final long q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[(int) q1];
            pos = writeFirstBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r2 = (int) (q1 - q2 * 1000);
        final long q3 = q2 / 1000;
        if (q3 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[(int) q2];
            pos = writeFirstBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r3 = (int) (q2 - q3 * 1000);
        final int q4 = (int) (q3 / 1000);
        if (q4 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[(int) q3];
            pos = writeFirstBuf(buf, pos, v4);
            pos = writeBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r4 = (int) (q3 - q4 * 1000);
        final int q5 = q4 / 1000;
        if (q5 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[r4];
            final int v5 = DIGITS[q4];
            pos = writeFirstBuf(buf, pos, v5);
            pos = writeBuf(buf, pos, v4);
            pos = writeBuf(buf, pos, v3);
            pos = writeBuf(buf, pos, v2);
            pos = writeBuf(buf, pos, v1);
            return pos;
        }
        final int r5 = q4 - q5 * 1000;
        final int q6 = q5 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        final int v3 = DIGITS[r3];
        final int v4 = DIGITS[r4];
        final int v5 = DIGITS[r5];
        if (q6 == 0) {
            pos = writeFirstBuf(buf, pos, DIGITS[q5]);
        } else {
            final int r6 = q5 - q6 * 1000;
            buf[pos++] = (byte) (q6 + '0');
            pos = writeBuf(buf, pos, DIGITS[r6]);
        }
        pos = writeBuf(buf, pos, v5);
        pos = writeBuf(buf, pos, v4);
        pos = writeBuf(buf, pos, v3);
        pos = writeBuf(buf, pos, v2);
        pos = writeBuf(buf, pos, v1);
        return pos;
    }

    private static int writeFirstBuf(byte[] buf, final int index, int v) {
        final int start = v >> 24;
        int pos = index;
        if (start == 0) {
            buf[pos++] = (byte)(v >> 16);
            buf[pos++] = (byte)(v >>  8);
        } else if (start == 1) {
            buf[pos++] = (byte)(v >> 8);
        }
        buf[pos++] = (byte)v;
        return pos;
    }

    private static int writeBuf(byte[] buf, final int index, int v) {
        int pos = index;
        buf[pos++] = ((byte)(v >> 16));
        buf[pos++] = ((byte)(v >> 8));
        buf[pos++] = ((byte)v);
        return pos;
    }
}
