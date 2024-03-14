package io.edap.eproto.write;

import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.EprotoWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.wire.Field;
import io.edap.util.StringUtil;

import java.util.List;

import static io.edap.util.StringUtil.IS_BYTE_ARRAY;
import static io.edap.util.StringUtil.isLatin1;

public class AbstractWriter implements EprotoWriter {

    public BufOut.WriteBuf wbuf;
    protected BufOut out;
    protected byte[] bs;
    protected int pos;

    public AbstractWriter(BufOut out) {
        this.out = out;
        wbuf = out.getWriteBuf();
        bs = wbuf.bs;
        pos = wbuf.start;
    }

    @Override
    public void writeString(String s) {
        if (s == null) {
            writeNull();
            return;
        }
        if (s.isEmpty()) {
            writeByte((byte)'0');
            return;
        }
        writeString0(s);
    }

    protected void writeNull() {
        writeByte((byte)'\0');
    }
    /**
     * 将不为空的字符串写入到缓存中
     * @param value
     */
    private void writeString0( final String value) {
        String v = value;
        int charLen = v.length();
        // 如果jvm是9以上版本，并且字符串为Latin1的编码，长度大于5时直接copy字符串对象额value字节数组
        if (IS_BYTE_ARRAY && isLatin1(v) && charLen > 5) {
            byte[] data = StringUtil.getValue(v);
            writeByteArray(data, 0, charLen);
            return;
        }
        // 转为utf8后最大的所需字节数
        int maxBytes = charLen * 3;
        // 如果所需最大字节数小于3k + 编码int最大字节数 则直接扩容所需最大字节数
        if (maxBytes <= 3072) {
            expand(maxBytes + 1);
            int len = writeChars(v, 0, charLen, pos);
            pos += len;
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);

        int oldPos = pos;
        pos++;
        int _pos = oldPos + 1;
        expand(maxBytes + 1);
        while (start < charLen) {
            expand(3072);
            _pos += writeChars(v, start, end, _pos);
            pos = _pos;
            start += 1024;
            end = Math.min((start + 1024), charLen);
        }
        int len = _pos - oldPos - 1;
        pos += writeLenMoveBytes(bs, oldPos, len);
    }

    protected final int writeChars ( final String value, int start, int end, int pos){
        String v = value;
        int p = pos;
        byte[] _bs = this.bs;
        for (int i = start; i < end; i++) {
            char c = v.charAt(i);
            if (c < 128) {
                _bs[p++] = (byte) c;
            } else if (c < 0x800) {
                _bs[p++] = (byte) ((0xF << 6) | (c >>> 6));
                _bs[p++] = (byte) (0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i + 1 < end
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i + 1));
                _bs[p++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                _bs[p++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                _bs[p++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                _bs[p++] = (byte) (0x80 | (codePoint & 0x3F));
                i++;
            } else {
                _bs[p++] = (byte) ((0xF << 5) | (c >>> 12));
                _bs[p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                _bs[p++] = (byte) (0x80 | (0x3F & c));
            }
        }
        return p - pos;
    }

    @Override
    public void writeBytes(byte[] bs) {

    }

    @Override
    public void writeBool(Boolean value) {

    }

    @Override
    public void writeBool(boolean value) {

    }

    @Override
    public void writePackedBools(List<Boolean> values) {

    }

    @Override
    public void writePackedBools(Iterable<Boolean> values) {

    }

    @Override
    public void writeInt32(Integer value) {

    }

    @Override
    public void writeInt32(int value) {

    }

    @Override
    public void writeUInt32(int value) {

    }

    @Override
    public void writeUInt32(Integer value) {

    }

    @Override
    public void writeSInt32(Integer value) {

    }

    @Override
    public void writeSInt32(int value) {

    }

    @Override
    public void writeFixed32(Integer value) {

    }

    @Override
    public void writeFixed32(int value) {

    }

    @Override
    public void writeSFixed32(Integer value) {

    }

    @Override
    public void writeSFixed32(int value) {

    }

    @Override
    public void writeFloat(Float value) {

    }

    @Override
    public void writeFloat(float value) {

    }

    @Override
    public void writePackedInts(int[] values, Field.Type type) {

    }

    @Override
    public void writePackedInts(Integer[] values, Field.Type type) {

    }

    @Override
    public void writePackedInts(List<Integer> value, Field.Type type) {

    }

    @Override
    public void writePackedInts(Iterable<Integer> value, Field.Type type) {

    }

    @Override
    public void writePackedFloats(float[] values) {

    }

    @Override
    public void writePackedFloats(Float[] values) {

    }

    @Override
    public void writePackedFloats(List<Float> values) {

    }

    @Override
    public void writePackedFloats(Iterable<Float> values) {

    }

    @Override
    public void writePackedBooleans(boolean[] values) {

    }

    @Override
    public void writePackedBooleans(Boolean[] values) {

    }

    @Override
    public void writePackedBooleans(List<Boolean> values) {

    }

    @Override
    public void writePackedBooleans(Iterable<Boolean> values) {

    }

    @Override
    public void writeLong(Long value) {

    }

    @Override
    public void writeLong(long value) {

    }

    @Override
    public void writeInt64(long value) {

    }

    @Override
    public void writeUInt64(Long value) {

    }

    @Override
    public void writeUInt64(long value) {

    }

    @Override
    public void writeSInt64(Long value) {

    }

    @Override
    public void writeSInt64(long value) {

    }

    @Override
    public void writeFixed64(Long value) {

    }

    @Override
    public void writeFixed64(long value) {

    }

    @Override
    public void writeSFixed64(Long value) {

    }

    @Override
    public void writeSFixed64(long value) {

    }

    @Override
    public void writeDouble(Double value) {

    }

    @Override
    public void writeDouble(double value) {

    }

    @Override
    public void writePackedLongs(Long[] values, Field.Type type) {

    }

    @Override
    public void writePackedLongs(long[] values, Field.Type type) {

    }

    @Override
    public void writePackedLongs(List<Long> values, Field.Type type) {

    }

    @Override
    public void writePackedLongs(Iterable<Long> values, Field.Type type) {

    }

    @Override
    public void writePackedDoubles(double[] values) {

    }

    @Override
    public void writePackedDoubles(Double[] values) {

    }

    @Override
    public void writePackedDoubles(List<Double> values) {

    }

    @Override
    public void writePackedDoubles(Iterable<Double> values) {

    }

    @Override
    public void writeEnum(byte[] fieldData, Integer value) {

    }

    @Override
    public <E extends Enum<E>> void writeArrayEnum(byte[] fieldData, E[] vs) {

    }

    @Override
    public <E extends Enum<E>> void writeListEnum(byte[] fieldData, List<E> vs) {

    }

    @Override
    public <E extends Enum<E>> void writeListEnum(byte[] fieldData, Iterable<E> vs) {

    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, List<E> vs) {

    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, Iterable<E> vs) {

    }

    @Override
    public void writeBytes(Byte[] value) {

    }

    @Override
    public void writeByteArray(byte[] value, int offset, int length) {

    }

    @Override
    public void writeByte(byte b) {

    }

    @Override
    public void writeObject(Object v) throws EncodeException {

    }

    @Override
    public <T> void writeMessage(T msg, EprotoEncoder<T> encoder) throws EncodeException {

    }

    @Override
    public <T> void writeMessages(T[] msg, EprotoEncoder<T> encoder) throws EncodeException {

    }

    @Override
    public <T> void writeMessages(List<T> msg, EprotoEncoder<T> encoder) throws EncodeException {

    }

    @Override
    public <T> void writeMessages(Iterable<T> msg, EprotoEncoder<T> encoder) throws EncodeException {

    }

    protected void expand(int minLength) {
        if (bs.length - pos < minLength) {
            if (wbuf.out.hasBuf()) {
                wbuf.out.write(wbuf.bs, 0, wbuf.start);
                wbuf.writeLen += wbuf.start;
                wbuf.start = 0;
            } else {
                int len = wbuf.len * 2;
                if (len < minLength + pos) {
                    len = minLength + pos;
                }
                byte[] res = new byte[len];
                System.arraycopy(bs, 0, res, 0, pos);

                wbuf.bs = res;
                wbuf.out.setLocalBytes(res);
                bs = res;
            }
        }
    }
}
