package io.edap.eproto.write;

import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.EprotoWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.wire.Field;
import io.edap.util.StringUtil;

import java.util.List;

import static io.edap.eproto.EprotoWriter.encodeZigZag32;
import static io.edap.eproto.EprotoWriter.encodeZigZag64;
import static io.edap.protobuf.wire.WireFormat.*;
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
            expand(MAX_VARINT_SIZE + charLen);
            writeUInt32_0(charLen);
            writeByteArray(data, 0, charLen);
            return;
        }
        // 转为utf8后最大的所需字节数
        int maxBytes = charLen * 3;
        // 如果所需最大字节数小于3k + 编码int最大字节数 则直接扩容所需最大字节数
        if (maxBytes <= 3072) {
            expand(MAX_VARINT_SIZE + maxBytes);
            writeUInt32_0(charLen);
            pos += writeChars(v, 0, charLen, pos);
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);

        expand(maxBytes + MAX_VARINT_SIZE);
        writeUInt32_0(charLen);
        while (start < charLen) {
            expand(3072);
            pos += writeChars(v, start, end, pos);
            start += 1024;
            end = Math.min((start + 1024), charLen);
        }
    }

    protected final int writeChars( final String value, int start, int end, int pos){
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
        if (bs == null) {
            writeNull();
        } else {
            int len = bs.length;
            expand(bs.length + MAX_VARINT_SIZE);
            writeUInt32_0(len);
            System.arraycopy(bs, 0, this.bs, pos, len);
            pos += len;
        }
    }

    @Override
    public void writeBool(Boolean value) {
        if (value == null) {
            writeNull();
        } else {
            writeUInt32(value?1:0);
        }
    }

    @Override
    public void writeBool(boolean value) {
        writeUInt32(value?1:0);
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
        expand(MAX_VARINT_SIZE);
        writeUInt32_0(value);
    }

    @Override
    public void writeUInt32(Integer value) {
        if (value == null) {
            writeNull();
        } else {
            writeUInt32(value.intValue());
        }
    }

    @Override
    public void writeSInt32(Integer value) {
        if (value == null) {
            writeNull();
        } else {
            writeSInt32(value);
        }
    }

    @Override
    public void writeSInt32(int value) {
        expand(MAX_VARINT_SIZE);
        writeUInt32(encodeZigZag32(value));
    }

    @Override
    public void writeFixed32(Integer value) {
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_32_SIZE);
            writeFixed32_0(value);
        }
    }

    @Override
    public void writeFixed32(int value) {
        expand(FIXED_32_SIZE);
        writeFixed32_0(value);
    }

    protected void writeFixed32_0(int value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = (byte) ((int) (value      ) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >>  8) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 16) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 24) & 0xFF);
        pos = _pos;
    }

    @Override
    public void writeSFixed32(Integer value) {
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_32_SIZE);
            writeFixed32_0(value);
        }
    }

    @Override
    public void writeSFixed32(int value) {
        expand(FIXED_32_SIZE);
        writeFixed32_0(value);
    }

    @Override
    public void writeFloat(Float value) {
        if (value == null) {
          writeNull();
        } else {
            expand(FIXED_32_SIZE);
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writeFloat(float value) {
        expand(FIXED_32_SIZE);
        writeFixed32_0(Float.floatToRawIntBits(value));
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
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_64_SIZE);
            writeUInt64_0(value);
        }
    }

    @Override
    public void writeLong(long value) {
        expand(FIXED_64_SIZE);
        writeUInt64_0(value);
    }

    @Override
    public void writeInt64(long value) {
        expand(FIXED_64_SIZE);
        writeUInt64_0(value);
    }

    @Override
    public void writeUInt64(Long value) {
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_64_SIZE);
            writeUInt64_0(value);
        }
    }

    @Override
    public void writeUInt64(long value) {
        expand(FIXED_64_SIZE);
        writeUInt64_0(value);
    }

    protected void writeUInt64_0(long value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                _bs[_pos++] = (byte) value;
                pos = _pos;
                return;
            } else {
                _bs[_pos++] = ((byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    @Override
    public void writeSInt64(Long value) {
        if (value == null) {
            writeNull();
        } else {
            expand(MAX_VARLONG_SIZE);
            writeUInt64_0(encodeZigZag64(value));
        }
    }

    @Override
    public void writeSInt64(long value) {
        expand(MAX_VARLONG_SIZE);
        writeUInt64_0(encodeZigZag64(value));
    }

    @Override
    public void writeFixed64(Long value) {
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_64_SIZE);
            writeFixed64_0(value);
        }
    }

    @Override
    public void writeFixed64(long value) {
        expand(FIXED_64_SIZE);
        writeFixed64_0(value);
    }

    @Override
    public void writeSFixed64(Long value) {
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_64_SIZE);
            writeFixed64_0(value);
        }
    }

    @Override
    public void writeSFixed64(long value) {
        expand(FIXED_64_SIZE);
        writeFixed64_0(value);
    }

    @Override
    public void writeDouble(Double value) {
        if (value == null) {
            writeNull();
        } else {
            expand(FIXED_64_SIZE);
            writeFixed64_0(Double.doubleToRawLongBits(value));
        }
    }

    @Override
    public void writeDouble(double value) {
        expand(FIXED_64_SIZE);
        writeFixed64_0(Double.doubleToRawLongBits(value));
    }

    protected void writeFixed64_0(long value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = ((byte) ((int) (value      ) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >>  8) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 16) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 24) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 32) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 40) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 48) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 56) & 0xFF));
        pos = _pos;
    }

    @Override
    public void writePackedLongs(Long[] values, Field.Type type) {
        if (values == null) {
            writeNull();
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = 0;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        switch (type) {
            case INT64:
            case UINT64:
                writeUInt64ObjArray(values);
                break;
            case SINT64:
                writeSInt64ObjArray(values);
                break;
            case FIXED64:
            case SFIXED64:
                writeFixedObjArray(values);
                break;
            default:
        }
    }

    private void writeUInt64ObjArray(Long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values[i];
            if (v == null) {
                _bs[_pos++] = '\0';
            } else {
                long lv = v;
                while (true) {
                    if ((v & ~0x7FL) == 0) {
                        _bs[_pos++] = (byte)lv;
                        break;
                    } else {
                        _bs[_pos++] = ((byte) (((int) lv & 0x7F) | 0x80));
                        lv >>>= 7;
                    }
                }
            }
        }
        pos = _pos;
    }

    private void writeSInt64ObjArray(Long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values[i];
            if (v == null) {
                _bs[_pos++] = '\0';
            } else {
                long lv = encodeZigZag64(v);
                while (true) {
                    if ((lv & ~0x7FL) == 0) {
                        _bs[_pos++] = (byte)lv;
                        break;
                    } else {
                        _bs[_pos++] = ((byte) (((int) lv & 0x7F) | 0x80));
                        lv >>>= 7;
                    }
                }
            }
        }
        pos = _pos;
    }

    private void writeFixedObjArray(Long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values[i];
            if (v == null) {
                _bs[_pos++] = '\0';
            } else {
                long value = v;
                _bs[_pos++] = ((byte) ((int) (value      ) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >>  8) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 16) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 24) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 32) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 40) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 48) & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 56) & 0xFF));
            }
        }
        pos = _pos;
    }

    @Override
    public void writePackedLongs(long[] values, Field.Type type) {
        if (values == null) {
            writeNull();
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = 0;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        switch (type) {
            case INT64:
            case UINT64:
                writeUInt64Array(values);
                break;
            case SINT64:
                writeSInt64Array(values);
                break;
            case FIXED64:
            case SFIXED64:
                writeFixedArray(values);
                break;
            default:
        }
    }

    private void writeUInt64Array(long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values[i];
            if (v == null) {
                _bs[_pos++] = '\0';
            } else {
                long lv = v;
                while (true) {
                    if ((v & ~0x7FL) == 0) {
                        _bs[_pos++] = (byte)lv;
                        break;
                    } else {
                        _bs[_pos++] = ((byte) (((int) lv & 0x7F) | 0x80));
                        lv >>>= 7;
                    }
                }
            }
        }
        pos = _pos;
    }

    private void writeSInt64Array(long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            long lv = encodeZigZag64(values[i]);
            while (true) {
                if ((lv & ~0x7FL) == 0) {
                    _bs[_pos++] = (byte)lv;
                    break;
                } else {
                    _bs[_pos++] = ((byte) (((int) lv & 0x7F) | 0x80));
                    lv >>>= 7;
                }
            }
        }
        pos = _pos;
    }

    private void writeFixedArray(long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            long value = values[i];
            _bs[_pos++] = ((byte) ((int) (value      ) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >>  8) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 16) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 24) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 32) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 40) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 48) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 56) & 0xFF));
        }
        pos = _pos;
    }

    @Override
    public void writePackedLongs(List<Long> values, Field.Type type) {
        if (values == null) {
            writeNull();
            return;
        }
        int len = values.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = 0;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        switch (type) {
            case INT64:
            case UINT64:
                writeUInt64List(values);
                break;
            case SINT64:
                writeUInt64List(values);
                break;
            case FIXED64:
            case SFIXED64:
                writeUInt64List(values);
                break;
            default:
        }
    }

    private void writeUInt64List(List<Long> values) {
        int len = values.size();
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values.get(i);
            if (v == null) {
                _bs[_pos++] = '\0';
            } else {
                long lv = v;
                while (true) {
                    if ((v & ~0x7FL) == 0) {
                        _bs[_pos++] = (byte)lv;
                        break;
                    } else {
                        _bs[_pos++] = ((byte) (((int) lv & 0x7F) | 0x80));
                        lv >>>= 7;
                    }
                }
            }
        }
        pos = _pos;
    }

    private void writeSInt64List(List<Long> values) {
        int len = values.size();
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            long lv = encodeZigZag64(values.get(i));
            while (true) {
                if ((lv & ~0x7FL) == 0) {
                    _bs[_pos++] = (byte)lv;
                    break;
                } else {
                    _bs[_pos++] = ((byte) (((int) lv & 0x7F) | 0x80));
                    lv >>>= 7;
                }
            }
        }
        pos = _pos;
    }

    private void writeFixedList(List<Long> values) {
        int len = values.size();
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            long value = values.get(i);
            _bs[_pos++] = ((byte) ((int) (value      ) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >>  8) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 16) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 24) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 32) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 40) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 48) & 0xFF));
            _bs[_pos++] = ((byte) ((int) (value >> 56) & 0xFF));
        }
        pos = _pos;
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
        expand(1);
        bs[pos++] = b;
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

    /**
     * 不检查空间是否够用直接写uint32数据
     * @param value
     */
    protected void writeUInt32_0(int value) {
        /**/
        byte[] _bs = this.bs;
        int p = pos;
        if ((value & ~0x7F) == 0) {
            _bs[p++] = (byte) value;
        } else {
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            } else {
                _bs[p++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[p++] = (byte) value;
                } else {
                    _bs[p++] = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        _bs[p++] = (byte) value;
                    } else {
                        _bs[p++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            _bs[p++] = (byte) value;
                        }
                    }
                }
            }
        }
        pos = p;
        /**/
        /*
        byte[] bs = this.bs;
        int start = pos;
        while (true) {
            if ((value & ~0x7F) == 0) {
                bs[start++] = (byte) value;
                pos = start;
                return;
            } else {
                bs[start++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
        */
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
