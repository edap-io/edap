package io.edap.log.helps;

import io.edap.log.LogWriter;
import io.edap.log.io.BaseLogOutputStream;
import io.edap.util.Grisu3;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static io.edap.util.StringUtil.IS_BYTE_ARRAY;
import static io.edap.util.StringUtil.isLatin1;

/**
 * 为了减少字符串到byte数组的转换过程，为日志输出构建一个由日志message以及参数，直接转换为byte数组的API。
 */
public class ByteArrayBuilder {

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    public static final boolean[] CAN_DIRECT_WRITE = new boolean[128];

    private final Grisu3.FastDtoaBuilder doubleBuilder = new Grisu3.FastDtoaBuilder();

    private final static int[] DIGITS = new int[1000];
    private final static byte[] MIN_INT_BYTES;
    private final static byte[] MIN_LONG_BYTES;

    static {

        MIN_INT_BYTES = String.valueOf(Integer.MIN_VALUE).getBytes();
        MIN_LONG_BYTES = String.valueOf(Long.MIN_VALUE).getBytes();

        for (int i = 0; i < CAN_DIRECT_WRITE.length; i++) {
            if (i > 31 && i < 126 && i != '"' && i != '\\') {
                CAN_DIRECT_WRITE[i] = true;
            }
        }

        for (int i = 0; i < DIGITS.length; i++) {
            DIGITS[i] = (i < 10 ? (2 << 24) : i < 100 ? (1 << 24) : 0)
                    + (((i / 100) + '0') << 16)
                    + ((((i / 10) % 10) + '0') << 8)
                    + i % 10 + '0';
        }
    }

    private byte[] value;
    /**
     * 使用的字节个数
     */
    private int count;

    private int initCount;

    private BaseLogOutputStream outputStream;

    public ByteArrayBuilder() {
        initValue(128);
        this.count = 0;
    }

    /**
     * 获取缓存未使用的字节数
     * @return
     */
    public int remain() {
        return value.length - count;
    }

    public int length() {
        return count;
    }

    public ByteArrayBuilder(int cap) {
        initValue(cap);
        this.count = 0;
    }

    public int cap() {
        return value.length;
    }

    private void initValue(int cap) {
        this.value = new byte[cap];
        this.initCount = cap;
    }

    public ByteArrayBuilder append(ByteArrayBuilder other) {
        ensureCapacity(count + other.count);
        System.arraycopy(other.value, 0, value, count, other.count);
        count += other.count;

        return this;
    }

    /**
     * 将一个byte追加到当前数据的最后
     * @param b
     * @return
     */
    public ByteArrayBuilder append(byte b) {
        ensureCapacity(count+1);
        value[count++] = b;
        return this;
    }

    public ByteArrayBuilder append(boolean bool) {
        if (bool) {
            return uncheckAppend((byte)'t', (byte)'r', (byte)'u', (byte)'e');
        } else {
            ensureCapacity(5);
            uncheckAppend((byte)'f', (byte)'a', (byte)'l', (byte)'s');
            return uncheckAppend((byte)'e');
        }
    }

    public ByteArrayBuilder append(Boolean bool) {
        if (bool == null) {
            return appendNull();
        } else {
            return append(bool.booleanValue());
        }
    }

    /**
     * 将二个byte追加到当前数据的最后
     * @param b1
     * @param b2
     * @return
     */
    public ByteArrayBuilder append(byte b1, byte b2) {
        ensureCapacity(count+2);
        value[count++] = b1;
        value[count++] = b2;
        return this;
    }
    /**
     * 将一个byte追加到当前数据的最后
     * @param b1
     * @param b2
     * @param b3
     * @return
     */
    public ByteArrayBuilder append(byte b1, byte b2, byte b3) {
        ensureCapacity(count+3);
        value[count++] = b1;
        value[count++] = b2;
        value[count++] = b3;
        return this;
    }

    /**
     * 将一个byte追加到当前数据的最后
     * @param b1
     * @param b2
     * @param b3
     * @param b4
     * @return
     */
    public ByteArrayBuilder append(byte b1, byte b2, byte b3, byte b4) {
        ensureCapacity(count+4);
        value[count++] = b1;
        value[count++] = b2;
        value[count++] = b3;
        value[count++] = b4;
        return this;
    }

    /**
     * 将一个byte追加到当前数据的最后
     * @param b
     * @return
     */
    public ByteArrayBuilder uncheckAppend(byte b) {
        value[count++] = b;
        return this;
    }

    /**
     * 不检查数组是否还有空间，将二个byte追加到当前数据的最后，该操作不安全需要在外层检测数组是否越界
     * @param b1
     * @param b2
     * @return
     */
    public ByteArrayBuilder uncheckAppend(byte b1, byte b2) {
        value[count++] = b1;
        value[count++] = b2;
        return this;
    }
    /**
     * 将一个byte追加到当前数据的最后
     * @param b1
     * @param b2
     * @param b3
     * @return
     */
    public ByteArrayBuilder uncheckAppend(byte b1, byte b2, byte b3) {
        value[count++] = b1;
        value[count++] = b2;
        value[count++] = b3;
        return this;
    }

    public ByteArrayBuilder uncheckAppend(byte b1, byte b2, byte b3, byte b4) {
        value[count++] = b1;
        value[count++] = b2;
        value[count++] = b3;
        value[count++] = b4;
        return this;
    }

    public ByteArrayBuilder appendNull() {
        ensureCapacity(count+4);
        value[count++] = 'n';
        value[count++] = 'u';
        value[count++] = 'l';
        value[count++] = 'l';
        return this;
    }

    public ByteArrayBuilder append(final short v) {
        return append((int)v);
    }

    public ByteArrayBuilder append(final Short v) {
        if (v == null) {
            return appendNull();
        }
        return append((int)v);
    }

    public ByteArrayBuilder append(final float v) {
        return append(String.valueOf(v));
    }

    public ByteArrayBuilder append(final Float v) {
        if (v == null) {
            return appendNull();
        }
        return append(String.valueOf(v.floatValue()));
    }

    public ByteArrayBuilder append(final int v) {
        ensureCapacity(count+11);
        if (v == 0) {
            value[count++] = '0';
            return this;
        } else if (v == Integer.MIN_VALUE) {
            System.arraycopy(MIN_INT_BYTES, 0, value, count, MIN_INT_BYTES.length);
            count += MIN_INT_BYTES.length;
            return this;
        }
        int i;
        byte[] buf = value;
        int    pos = count;
        if (v < 0) {
            i = -v;
            buf[pos++] = '-';
        } else {
            i = v;
        }
        final int q1 = i / 1000;
        if (q1 == 0) {
            pos += writeFirstBuf(buf, DIGITS[i], pos);
            count = pos;
            return this;
        }
        final int r1 = i - q1 * 1000;
        final int q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[q1];
            int off = writeFirstBuf(buf, v2, pos);
            writeBuf(buf, v1, pos + off);
            count = pos + 3 + off;
            return this;
        }
        final int r2 = q1 - q2 * 1000;
        final long q3 = q2 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        if (q3 == 0) {
            pos += writeFirstBuf(buf, DIGITS[q2], pos);
        } else {
            final int r3 = (int) (q2 - q3 * 1000);
            buf[pos++] = (byte) (q3 + '0');
            writeBuf(buf, DIGITS[r3], pos);
            pos += 3;
        }
        writeBuf(buf, v2, pos);
        writeBuf(buf, v1, pos + 3);

        count =  pos + 6;
        return this;
    }

    public ByteArrayBuilder append(final Integer v) {
        if (v == null) {
            return appendNull();
        }
        return append(v.intValue());
    }

    public ByteArrayBuilder append(final long v) {
        ensureCapacity(count+21);
        if (v == 0) {
            value[count++] = '0';
            return this;
        } else if (v == Long.MIN_VALUE) {
            System.arraycopy(MIN_LONG_BYTES, 0, value, count, MIN_LONG_BYTES.length);
            count += MIN_LONG_BYTES.length;
            return this;
        }
        long i;
        byte[] buf = value;
        int pos = count;
        if (v < 0) {
            i = -v;
            buf[pos++] = '-';
        } else {
            i = v;
        }
        final long q1 = i / 1000;
        if (q1 == 0) {
            pos += writeFirstBuf(buf, DIGITS[(int) i], pos);
            count = pos;
            return this;
        }
        final int r1 = (int) (i - q1 * 1000);
        final long q2 = q1 / 1000;
        if (q2 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[(int) q1];
            int off = writeFirstBuf(buf, v2, pos);
            writeBuf(buf, v1, pos + off);
            count = pos + 3 + off;
            return this;
        }
        final int r2 = (int) (q1 - q2 * 1000);
        final long q3 = q2 / 1000;
        if (q3 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[(int) q2];
            pos += writeFirstBuf(buf, v3, pos);
            writeBuf(buf, v2, pos);
            writeBuf(buf, v1, pos + 3);
            count = pos + 6;
            return this;
        }
        final int r3 = (int) (q2 - q3 * 1000);
        final int q4 = (int) (q3 / 1000);
        if (q4 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[(int) q3];
            pos += writeFirstBuf(buf, v4, pos);
            writeBuf(buf, v3, pos);
            writeBuf(buf, v2, pos + 3);
            writeBuf(buf, v1, pos + 6);
            count = pos + 9;
            return this;
        }
        final int r4 = (int) (q3 - q4 * 1000);
        final int q5 = q4 / 1000;
        if (q5 == 0) {
            final int v1 = DIGITS[r1];
            final int v2 = DIGITS[r2];
            final int v3 = DIGITS[r3];
            final int v4 = DIGITS[r4];
            final int v5 = DIGITS[q4];
            pos += writeFirstBuf(buf, v5, pos);
            writeBuf(buf, v4, pos);
            writeBuf(buf, v3, pos + 3);
            writeBuf(buf, v2, pos + 6);
            writeBuf(buf, v1, pos + 9);
            count = pos + 12;
            return this;
        }
        final int r5 = q4 - q5 * 1000;
        final int q6 = q5 / 1000;
        final int v1 = DIGITS[r1];
        final int v2 = DIGITS[r2];
        final int v3 = DIGITS[r3];
        final int v4 = DIGITS[r4];
        final int v5 = DIGITS[r5];
        if (q6 == 0) {
            pos += writeFirstBuf(buf, DIGITS[q5], pos);
        } else {
            final int r6 = q5 - q6 * 1000;
            buf[pos++] = (byte) (q6 + '0');
            writeBuf(buf, DIGITS[r6], pos);
            pos += 3;
        }
        writeBuf(buf, v5, pos);
        writeBuf(buf, v4, pos + 3);
        writeBuf(buf, v3, pos + 6);
        writeBuf(buf, v2, pos + 9);
        writeBuf(buf, v1, pos + 12);
        count = pos + 15;
        return this;
    }

    public ByteArrayBuilder append(final Long v) {
        if (v == null) {
            return appendNull();
        }
        return append(v.longValue());
    }

    private static int writeFirstBuf(final byte[] buf, final int v, int pos) {
        final int start = v >> 24;
        if (start == 0) {
            buf[pos++] = (byte) (v >> 16);
            buf[pos++] = (byte) (v >> 8);
        } else if (start == 1) {
            buf[pos++] = (byte) (v >> 8);
        }
        buf[pos] = (byte) v;
        return 3 - start;
    }

    private static void writeBuf(final byte[] buf, final int v, int pos) {
        buf[pos] = (byte) (v >> 16);
        buf[pos + 1] = (byte) (v >> 8);
        buf[pos + 2] = (byte) v;
    }

    public ByteArrayBuilder append(Object obj) {
        if (obj == null) {
            return appendNull();
        }
        if (obj instanceof Integer) {
            return append((Integer)obj);
        } else if (obj instanceof Short) {
            return append((Short)obj);
        } else if (obj instanceof Float) {
            return append((Float)obj);
        } else if (obj instanceof Long) {
            return append((Long)obj);
        } else if (obj instanceof Double) {
            return append((Double)obj);
        } else if (obj instanceof Boolean) {
            return append((Boolean)obj);
        } else if (obj instanceof Throwable) {

        }
        return append(obj.toString());
    }

    /**
     * 将字符串以utf8编码后附加到当前的数据中
     * @param str
     * @return
     */
    public ByteArrayBuilder append(String str) {
        if (str == null) {
            return appendNull();
        }
        if (IS_BYTE_ARRAY && str.length() > 5) {
            if (isLatin1(str)) {
                return append(StringUtil.getValue(str));
            } else {
                return appendStringSlow(str, 0, str.length());
            }
        }
        return appendSlow(str);
    }

    public ByteArrayBuilder append(String str, int pos, int len) {
        if (IS_BYTE_ARRAY && len > 5) {
            if (isLatin1(str)) {
                int index = count;
                ensureCapacity(index + len);
                byte[] _buf = value;

                for (int i=0;i<len;i++) {
                    _buf[index++] = (byte)str.charAt(pos++);
                }
                count = index;
                return this;
            } else {
                return appendStringSlow(str, pos, pos+len);
            }
        }
        byte[] _buf = value;
        int i = 0;
        int index = count;
        int oldPos = pos;
        for (;i<len;i++) {
            char c = str.charAt(pos++);
            if (c < 128) {
                _buf[index++] = (byte) c;
            } else {
                count = index;
                return appendStringSlow(str, pos-1, oldPos+len);
            }
        }
        count = index;
        return this;
    }

    private ByteArrayBuilder appendSlow(String s) {
        int slen = s.length();
        ensureCapacity(count + (slen << 2) + (slen << 1));
        byte[] _buf = value;
        int index = count;
        count = index;
        int i = 0;
        //char[] cs = s.toCharArray();
        for (;i<slen;i++) {
            char c = s.charAt(i);
            if (c < 128) {
                _buf[index++] = (byte) c;
            } else {
                count = index;
                return appendStringSlow(s, i, slen);
            }
        }
        count = index;

        return this;
    }

    private ByteArrayBuilder appendStringSlow(String v, int start, int end) {
        byte[] _bs = value;
        char c;
        int p = count;
        for (int i=start;i<end;i++) {
            c = v.charAt(i);
            if (c < 128) {
                _bs[p++] = (byte) c;
            } else if (c < 0x800) {
                _bs[p++] = (byte) ((0xF << 6) | (c >>> 6));
                _bs[p++] = (byte) ( 0x80      | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i+1<end
                    && Character.isLowSurrogate(v.charAt(i+1))) {
                int codePoint = Character.toCodePoint((char) c, (char) v.charAt(i+1));
                _bs[p++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                _bs[p++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                _bs[p++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
                _bs[p++] = (byte) (0x80 | ( codePoint        & 0x3F));
                i++;
            } else {
                _bs[p++] = (byte) ((0xF << 5) | (        c >>> 12));
                _bs[p++] = (byte) (0x80       | (0x3F & (c >>> 6)));
                _bs[p++] = (byte) (0x80       | (0x3F &  c));
            }
        }
        count = p;
        return this;
    }

    public ByteArrayBuilder append(Double value) {
        if (value == null) {
            return appendNull();
        }
        return append(value.doubleValue());
    }

    public ByteArrayBuilder append(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return appendAscii("Infinity");
        } else if (value == Double.NEGATIVE_INFINITY) {
            return appendAscii("-Infinity");
        } else if (value != value) {
            return appendAscii("NaN");
        } else if (value == 0.0) {
            return append((byte)'0', (byte)'.', (byte)'0');
        } else {
            if (Grisu3.tryConvert(value, doubleBuilder)) {
                ensureCapacity(count + 24);
                final int len = doubleBuilder.copyTo(this.value, count);
                count += len;
            } else {
                return appendAscii(Double.toString(value));
            }
        }
        return this;
    }

    private ByteArrayBuilder appendAscii(String s) {
        int slen = s.length();
        int index = count;
        ensureCapacity(index + slen);
        byte[] _buf = value;
        for (int i = 0;i<slen;i++) {
            _buf[index++] = (byte)s.charAt(i);
        }
        count = index;

        return this;
    }

    public ByteArrayBuilder append(byte[] bytes) {
        return append(bytes, 0, bytes.length);
    }

    public ByteArrayBuilder append(byte[] bytes, int offset, int len) {
        ensureCapacity(count + len);
        System.arraycopy(bytes, offset, value, count, len);
        count += len;
        return this;
    }

    /**
     * 重设当前Builder的对象，方便复用，如果当前数组大小是初始化大小的2倍时，重新设置时将数组的
     * 大小恢复为初始化的数组大小，避免对象重用时一直保持大内存的占用。
     */
    public void reset() {
        count = 0;
        if (initCount * 2 < value.length) {
            value = new byte[initCount];
        }
    }

    /**
     * 检查当前数组容量是否满足指定最小容量，如果小于指定最小容量则扩容数组
     * @param minCap
     */
    public void ensureCapacity(int minCap) {
        if (minCap - value.length > 0) {
            value = Arrays.copyOf(value, newCapacity(minCap));
        }
    }

    private int newCapacity(int minCapacity) {
        // overflow-conscious code
        int newCapacity = (value.length << 1);
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity;
        }
        return (newCapacity <= 0 || MAX_ARRAY_SIZE - newCapacity < 0)
                ? hugeCapacity(minCapacity)
                : newCapacity;
    }

    private int hugeCapacity(int minCapacity) {
        return (minCapacity > MAX_ARRAY_SIZE)
                ? minCapacity : MAX_ARRAY_SIZE;
    }

    public byte[] toByteArray() {
        byte[] bs = new byte[count];
        System.arraycopy(value, 0, bs, 0, count);
        return bs;
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(value, 0, count);
    }

    public void writeToLogOut(LogWriter out) throws IOException {
        out.writeLog(value, 0, count);
    }

    public BaseLogOutputStream getOutputStream() {
        return outputStream;
    }

    public void setOutputStream(BaseLogOutputStream outputStream) {
        this.outputStream = outputStream;
    }
}
