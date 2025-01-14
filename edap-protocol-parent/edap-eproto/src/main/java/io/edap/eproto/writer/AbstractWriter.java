package io.edap.eproto.writer;

import io.edap.buffer.FastBuf;
import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.EprotoWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.wire.Field;
import io.edap.util.StringUtil;
import io.edap.util.UnsafeUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;

import static io.edap.eproto.EprotoWriter.encodeZigZag32;
import static io.edap.eproto.EprotoWriter.encodeZigZag64;
import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.StringUtil.*;

public abstract class AbstractWriter implements EprotoWriter {

    /**
     * ZigZag32编码的0对应的byte
     */
    public static final byte ZIGZAG32_ZERO = 0;

    /**
     * ZigZag32编码的1对应的byte
     */
    public static final byte ZIGZAG32_ONE = 2;

    public static final byte ZIGZAG32_TWO = 4;

    /**
     * ZigZag32编码的-11对应的byte
     */
    public static final byte ZIGZAG32_NEGATIVE_ONE = 1; //negative


    public BufOut.WriteBuf wbuf;

    protected BufOut out;
    protected byte[] bs;
    protected int pos;
    private int wpos = 0;

    public AbstractWriter(BufOut out) {
        this.out = out;
        wbuf = out.getWriteBuf();
        bs = wbuf.bs;
        pos = wbuf.start;
    }

    /**
     * 字符串编码方式为一个字节的byte的编码类型，0为letin1，1为utf16，2为utf8，长度(letin1，utf16的时候为
     * byte[]数组长度，utf8时为字符的个数) + byte数组，长度使用ZigZag编码的int，如果字符串为null则值为-1
     * @param s 需要编码的字符串
     */
    @Override
    public void writeString(String s) {
        if (s == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else if (s.isEmpty()) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            writeString0(s);
        }
    }

    /**
     * 将不为空的字符串写入到缓存中
     * @param v
     */
    private void writeString0(final String v) {
        //String v = value;
        // 如果jvm是9以上版本，并且字符串为Latin1的编码，长度大于5时直接copy字符串对象额value字节数组
        if (IS_BYTE_ARRAY) {
            writeByteArrayString(v);
        } else {
            int len = v.length();
//            if (len <= 10) {
                writeCharArrayString(v, len);
//            } else {
//                writeUtf16String(v, len);
//            }
        }
    }

    private void writeUtf16String(String v, int len) {
        expand(MAX_VARINT_SIZE + (len*2) + 1);
        int _pos = pos;
        byte[] _bs = bs;
        bs[pos++] = ZIGZAG32_TWO;
        writeUInt32_0(len);
        UnsafeUtil.copyUtf16le(getCharValue(v), 0, _bs, _pos, len);
        pos = _pos + len * 2;
    }

    private void writeByteArrayString(String v) {
        byte[] data = StringUtil.getValue(v);
        int len = data.length;
        expand(MAX_VARINT_SIZE + len + 1);
        // latin1编码的byte[]
        if (isLatin1(v)) {
            bs[pos++] = ZIGZAG32_ONE;
        } else {
            // utf16编码的byte[]
            bs[pos++] = ZIGZAG32_TWO;
        }
        writeUInt32_0(len);
        System.arraycopy(data, 0, bs, pos, len);
        pos += len;
    }

    private void writeCharArrayString(String v, int charLen) {
        // 转为utf8后最大的所需字节数
        int maxBytes = charLen * 3 + 1;
        // 如果所需最大字节数小于3k + 编码int最大字节数 则直接扩容所需最大字节数
        if (maxBytes <= 3072) {
            expand(MAX_VARINT_SIZE + maxBytes);
            bs[pos++] = ZIGZAG32_TWO;
            writeUInt32_0(encodeZigZag32(charLen));
            if (charLen < 16) {
                pos += writeChars(v, 0, charLen, pos);
            } else {
                char[] cs = getCharValue(v);
                pos += writeChars(cs, 0, charLen, pos);
            }
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);
        char[] cs = getCharValue(v);

        expand(maxBytes + MAX_VARINT_SIZE);
        bs[pos++] = ZIGZAG32_TWO;
        writeUInt32_0(encodeZigZag32(charLen));
        while (start < charLen) {
            expand(3072);
            pos += writeChars(cs, 0, end, pos);
            start += 1024;
            end = Math.min((start + 1024), charLen);
        }
    }

    protected final int writeChars(final String value, int start, int end, int pos){
        String v = value;
        int p = pos;
        byte[] _bs = this.bs;
        int i = start;
        for (;i < end; i++) {
            char c = v.charAt(i);
            if (c < 128) {
                UnsafeUtil.writeByte(_bs, p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(_bs, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i + 1));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0xF0 | ((codePoint >> 18) & 0x07)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | ((codePoint >> 12) & 0x3F)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | ((codePoint >> 6) & 0x3F)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (codePoint & 0x3F)));
                i++;
            } else {
                UnsafeUtil.writeByte(_bs, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return p - pos;
    }

    protected final int writeChars( final char[] value, int start, int end, int pos){
        int p = pos;
        byte[] _bs = this.bs;
        int i = start;
        for (;i < end; i++) {
            char c = value[i];
            if (c < 128) {
                UnsafeUtil.writeByte(_bs, p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(_bs, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int codePoint = Character.toCodePoint((char) c, (char) value[i + 1]);
                UnsafeUtil.writeByte(_bs, p++, (byte) (0xF0 | ((codePoint >> 18) & 0x07)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | ((codePoint >> 12) & 0x3F)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | ((codePoint >> 6) & 0x3F)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (codePoint & 0x3F)));
                i++;
            } else {
                UnsafeUtil.writeByte(_bs, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return p - pos;
    }

    @Override
    public void writeBytes(byte[] data) {
        if (data == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = data.length;
            expand(data.length + MAX_VARINT_SIZE);
            writeUInt32_0(encodeZigZag32(len));
            if (len > 0) {
                System.arraycopy(data, 0, this.bs, pos, len);
                pos += len;
            }
        }
    }

    @Override
    public void writeBool(Boolean value) {
        if (value == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            writeBool(value.booleanValue());
        }
    }

    @Override
    public void writeBool(boolean value) {
        expand(1);
        bs[pos++] = value?ZIGZAG32_ONE:ZIGZAG32_ZERO;
    }

    @Override
    public void writePackedBools(List<Boolean> values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else if (values.isEmpty()) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            int len = values.size();
            expand(MAX_VARINT_SIZE + len);
            writeUInt32_0(encodeZigZag32(len));
            byte[] _bs = bs;
            int _pos = pos;
            for (int i=0;i<len;i++) {
                Boolean v = values.get(i);
                if (v == null) {
                    _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
                } else if (v) {
                    _bs[_pos++] = ZIGZAG32_ONE;
                } else {
                    _bs[_pos++] = ZIGZAG32_ZERO;
                }
            }
            pos = _pos;
        }
    }

    @Override
    public void writePackedBools(Iterable<Boolean> values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = 0;
            Iterator<Boolean> itr = values.iterator();
            while (itr.hasNext()) {
                len++;
                itr.next();
            }
            expand(len + MAX_VARINT_SIZE);
            writeUInt32_0(encodeZigZag32(len));
            if (len > 0) {
                itr = values.iterator();
                byte[] _bs = bs;
                int _pos = pos;
                while (itr.hasNext()) {
                    Boolean v = itr.next();
                    if (v == null) {
                        _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
                    } else if (v) {
                        _bs[_pos++] = ZIGZAG32_ONE;
                    } else {
                        _bs[_pos++] = ZIGZAG32_ZERO;
                    }
                }
                pos = _pos;
            }
        }
    }

    @Override
    public void writeInt32(Integer value) {
        if (value == null || value == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(MAX_VARINT_SIZE + 1);
            writeUInt32_0(value);
        }
    }

    @Override
    public void writeInt32(int value) {
        expand(MAX_VARINT_SIZE);
        writeUInt32_0(value);
    }

    @Override
    public void writeUInt32(int value) {
        expand(MAX_VARINT_SIZE);
        writeUInt32_0(value);
    }

    @Override
    public void writeUInt32(Integer value) {
        if (value == null || value == 0) {
            expand( 1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(value);
        }
    }

    @Override
    public void writeSInt32(Integer value) {
        if (value == null || value == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(encodeZigZag32(value));
        }
    }

    @Override
    public void writeSInt32(int value) {
        expand(MAX_VARINT_SIZE);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeFixed32(Integer value) {
        if (value == null || value == 0) {
            expand(4);
            bs[pos++] = ZIGZAG32_ZERO;
            bs[pos++] = ZIGZAG32_ZERO;
            bs[pos++] = ZIGZAG32_ZERO;
            bs[pos++] = ZIGZAG32_ZERO;
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
        _bs[_pos++] = (byte) ((value      ) & 0xFF);
        _bs[_pos++] = (byte) ((value >>  8) & 0xFF);
        _bs[_pos++] = (byte) ((value >> 16) & 0xFF);
        _bs[_pos++] = (byte) ((value >> 24) & 0xFF);
        pos = _pos;
    }

    @Override
    public void writeSFixed32(Integer value) {
        if (value == null || value == 0) {
            expand(4);
            bs[pos++] = ZIGZAG32_ZERO;
            bs[pos++] = ZIGZAG32_ZERO;
            bs[pos++] = ZIGZAG32_ZERO;
            bs[pos++] = ZIGZAG32_ZERO;
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
        if (value == null || value == 0) {
            expand(4);
            byte[] _bs  = bs;
            int    _pos = pos;
            _bs[_pos++] = 0;
            _bs[_pos++] = 0;
            _bs[_pos++] = 0;
            _bs[_pos++] = 0;
            pos = _pos;
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
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            switch (type) {
                case INT32:
                case UINT32:
                    writeUInt32Array(values);
                    break;
                case SINT32:
                    writeSInt32Array(values);
                    break;
                case FIXED32:
                case SFIXED32:
                    writeFixed32Array(values);
                    break;
                default:
            }
        }
    }

    private void writeUInt32Array(int[] values) {
        int len = values.length;
        expand(MAX_VARINT_SIZE + len * MAX_VARINT_SIZE);
        writeUInt32_0(encodeZigZag32(len));
        int p = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            int value = values[i];
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeSInt32Array(int[] values) {
        int len = values.length;
        expand(MAX_VARINT_SIZE + (len << 2));
        writeUInt32_0(encodeZigZag32(len));
        int p = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            int value = encodeZigZag32(values[i]);
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeFixed32Array(int[] values) {
        int len = values.length;
        expand(MAX_VARINT_SIZE + (len << 2));
        writeUInt32_0(encodeZigZag32(len));
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            int value = values[i];
            _bs[_pos++] = ((byte) ((value      ) & 0xFF));
            _bs[_pos++] = ((byte) ((value >>  8) & 0xFF));
            _bs[_pos++] = ((byte) ((value >> 16) & 0xFF));
            _bs[_pos++] = ((byte) ((value >> 24) & 0xFF));
        }
        pos = _pos;
    }

    @Override
    public void writePackedInts(Integer[] values, Field.Type type) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            switch (type) {
                case INT32:
                case UINT32:
                    writeUInt32ObjArray(values);
                    break;
                case SINT32:
                    writeSInt32ObjArray(values);
                    break;
                case FIXED32:
                case SFIXED32:
                    writeFixed32ObjArray(values);
                    break;
                default:
            }
        }
    }

    private void writeUInt32ObjArray(Integer[] values) {
        int len = values.length;
        expand(MAX_VARINT_SIZE + len * MAX_VARINT_SIZE);
        writeUInt32_0(encodeZigZag32(len));
        int p = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Integer v = values[i];
            if (v == null || v == 0) {
                _bs[p++] = ZIGZAG32_ZERO;
                continue;
            }
            int value = v;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeSInt32ObjArray(Integer[] values) {
        int len = values.length;
        expand(MAX_VARINT_SIZE + (len << 2));
        writeUInt32_0(encodeZigZag32(len));
        int p = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            int value = encodeZigZag32(values[i]);
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeFixed32ObjArray(Integer[] values) {
        int len = values.length;
        expand(MAX_VARINT_SIZE + (len << 2) + len);
        writeUInt32_0(encodeZigZag32(len));
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Integer value = values[i];
            if (value == null || value == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                _bs[_pos++] = ZIGZAG32_ONE;
                _bs[_pos++] = ((byte) ((value)       & 0xFF));
                _bs[_pos++] = ((byte) ((value >> 8)  & 0xFF));
                _bs[_pos++] = ((byte) ((value >> 16) & 0xFF));
                _bs[_pos++] = ((byte) ((value >> 24) & 0xFF));
            }
        }
        pos = _pos;
    }

    @Override
    public void writePackedInts(List<Integer> values, Field.Type type) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            switch (type) {
                case INT32:
                case UINT32:
                    writeUInt32List(values);
                    break;
                case SINT32:
                    writeSInt32List(values);
                    break;
                case FIXED32:
                case SFIXED32:
                    writeFixed32List(values);
                    break;
                default:
            }
        }
    }

    private void writeUInt32List(List<Integer> values) {
        int len = values.size();
        expand(MAX_VARINT_SIZE + len * (MAX_VARINT_SIZE + 1));
        writeUInt32_0(encodeZigZag32(len));
        int p = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Integer v = values.get(i);
            if (v == null || v == 0) {
                _bs[p++] = ZIGZAG32_ZERO;
                continue;
            }
            int value = v;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeSInt32List(List<Integer> values) {
        int len = values.size();
        expand(MAX_VARINT_SIZE + (len << 2));
        writeUInt32_0(encodeZigZag32(len));
        int p = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Integer v = values.get(i);
            if (v == null || v == 0) {
                _bs[p++] = ZIGZAG32_ZERO;
                continue;
            }
            int value = encodeZigZag32(values.get(i));
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeFixed32List(List<Integer> values) {
        int len = values.size();
        expand(MAX_VARINT_SIZE + (len << 2) + len);
        writeUInt32_0(encodeZigZag32(len));
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Integer value = values.get(i);
            if (value == null || value == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
            _bs[_pos++] = ZIGZAG32_ONE;
            _bs[_pos++] = ((byte) ((value      ) & 0xFF));
            _bs[_pos++] = ((byte) ((value >>  8) & 0xFF));
            _bs[_pos++] = ((byte) ((value >> 16) & 0xFF));
            _bs[_pos++] = ((byte) ((value >> 24) & 0xFF));
        }
        pos = _pos;
    }

    @Override
    public void writePackedInts(Iterable<Integer> values, Field.Type type) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = 0;
        Iterator<Integer> itr = values.iterator();
        while (itr.hasNext()) {
            len++;
            itr.next();
        }
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(MAX_VARINT_SIZE + len * MAX_VARINT_SIZE);
            writeUInt32_0(encodeZigZag32(len));
            switch (type) {
                case INT32:
                case UINT32:
                    writeUInt32Iterator(itr);
                    break;
                case SINT32:
                    writeSInt32Iterator(itr);
                    break;
                case FIXED32:
                case SFIXED32:
                    writeFixed32Iterator(itr);
                    break;
                default:
            }
        }
    }

    private void writeUInt32Iterator(Iterator<Integer> values) {
        int p = pos;
        byte[] _bs = bs;
        while (values.hasNext()) {
            Integer v = values.next();
            if (v == null || v == 0) {
                _bs[p++] = ZIGZAG32_ZERO;
                continue;
            }
            int value = v;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeSInt32Iterator(Iterator<Integer> values) {
        int p = pos;
        byte[] _bs = bs;
        while (values.hasNext()) {
            Integer v = values.next();
            if (v == null || v == 0) {
                _bs[p++] = ZIGZAG32_ZERO;
                continue;
            }
            int value = encodeZigZag32(v);
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
                continue;
            }
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            }
        }
        pos = p;
    }

    private void writeFixed32Iterator(Iterator<Integer> values) {
        int _pos = pos;
        byte[] _bs = bs;
        while (values.hasNext()) {
            Integer value = values.next();
            if (value == null || value == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
            _bs[_pos++] = ((byte) ((value      ) & 0xFF));
            _bs[_pos++] = ((byte) ((value >>  8) & 0xFF));
            _bs[_pos++] = ((byte) ((value >> 16) & 0xFF));
            _bs[_pos++] = ((byte) ((value >> 24) & 0xFF));
        }
        pos = _pos;
    }

    @Override
    public void writePackedFloats(float[] values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = values.length;
            if (len == 0) {
                expand(1);
                bs[pos++] = ZIGZAG32_ZERO;
                return;
            }

            expand(MAX_VARINT_SIZE + (len << 2));
            writeUInt32_0(len);
            byte[] _bs = bs;
            int _pos = pos;
            for (int i=0;i<len;i++) {
                int value = Float.floatToRawIntBits(values[i]);
                _bs[_pos++] = (byte) ((value)       & 0xFF);
                _bs[_pos++] = (byte) ((value >> 8)  & 0xFF);
                _bs[_pos++] = (byte) ((value >> 16) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 24) & 0xFF);
            }
            pos = _pos;
        }
    }

    @Override
    public void writePackedFloats(Float[] values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }

        expand(MAX_VARINT_SIZE + (len << 2) + len);
        writeUInt32_0(len);
        byte[] _bs = bs;
        int _pos = pos;
        for (int i=0;i<len;i++) {
            Float f = values[i];
            if (f == null || f == null) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                int value = Float.floatToRawIntBits(f);
                _bs[_pos++] = (byte) ((value) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 8) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 16) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 24) & 0xFF);
            }
        }
        pos = _pos;
    }

    @Override
    public void writePackedFloats(List<Float> values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }

        expand(MAX_VARINT_SIZE + (len << 2) + len);
        writeUInt32_0(len);
        byte[] _bs = bs;
        int _pos = pos;
        for (int i=0;i<len;i++) {
            Float f = values.get(i);
            if (f == null || f == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                int value = Float.floatToRawIntBits(f);
                _bs[_pos++] = (byte) ((value) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 8) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 16) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 24) & 0xFF);
            }
        }
        pos = _pos;
    }

    @Override
    public void writePackedFloats(Iterable<Float> values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = 0;
        for (Float aFloat : values) {
            len++;
        }
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }

        expand(MAX_VARINT_SIZE + (len << 2));
        writeUInt32_0(len);
        byte[] _bs = bs;
        int _pos = pos;
        for (Float f : values) {
            if (f == null || f == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                int value = Float.floatToRawIntBits(f);
                _bs[_pos++] = (byte) ((value) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 8) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 16) & 0xFF);
                _bs[_pos++] = (byte) ((value >> 24) & 0xFF);
            }
        }
        pos = _pos;
    }

    @Override
    public void writePackedBooleans(boolean[] values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = values.length;
            if (len == 0) {
                expand(1);
                bs[pos++] = ZIGZAG32_ZERO;
            } else {
                expand(MAX_VARINT_SIZE + len);
                writeUInt32_0(encodeZigZag32(len));
                byte[] _bs = bs;
                int _pos = pos;
                for (int i=0;i<len;i++) {
                    _bs[_pos++] = values[i]?ZIGZAG32_ONE:ZIGZAG32_ZERO;
                }
                pos = _pos;
            }
        }
    }

    @Override
    public void writePackedBooleans(Boolean[] values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(MAX_VARINT_SIZE + len);
            writeUInt32_0(encodeZigZag32(len));
            byte[] _bs = bs;
            int _pos = pos;
            for (int i=0;i<len;i++) {
                Boolean b = values[i];
                if (b == null) {
                    _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
                } else {
                    _bs[_pos++] = b ? ZIGZAG32_ONE : ZIGZAG32_ZERO;
                }
            }
            pos = _pos;
        }
    }

    @Override
    public void writeLong(Long value) {
        if (value == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            if (value == 0) {
                expand(1);
                bs[pos++] = ZIGZAG32_ZERO;
            } else {
                expand(FIXED_64_SIZE + 1);
                bs[pos++] = ZIGZAG32_ONE;
                writeUInt64_0(value);
            }
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
        if (value == null || value == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
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

    protected void writeUInt64_02(long value) {
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

    protected void writeUInt64_01(long value) {
        byte[] _bs = bs;
        int _pos = pos;
        int a = (int) (value);
        int b = (int) (value >>> 7);
        if (b == 0) {
            _bs[_pos++] = (byte) a;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (a | 0x80);
        a = (int) (value >>> 14);
        if (a == 0) {
            _bs[_pos++] = (byte) b;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (b | 0x80);
        b = (int) (value >>> 21);
        if (b == 0) {
            _bs[_pos++] = (byte) a;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (a | 0x80);
        a = (int) (value >>> 28);
        if (a == 0) {
            _bs[_pos++] = (byte) b;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (b | 0x80);
        b = (int) (value >>> 35);
        if (b == 0) {
            _bs[_pos++] = (byte) a;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (a | 0x80);
        a = (int) (value >>> 42);
        if (a == 0) {
            _bs[_pos++] = (byte) b;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (b | 0x80);
        b = (int) (value >>> 49);
        if (b == 0) {
            _bs[_pos++] = (byte) a;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (a | 0x80);
        a = (int) (value >>> 56);
        if (a == 0) {
            _bs[_pos++] = (byte) b;
            pos = _pos;
            return;
        }

        _bs[_pos++] = (byte) (b | 0x80);
        _bs[_pos++] = (byte) a;
        pos = _pos;
    }

    protected void writeUInt64_1(long value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        if ((value & ~0x7F) == 0) {
            _bs[_pos++] = (byte) value;
        } else {
            _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[_pos++] = (byte) value;
            } else {
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                } else {
                    _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        _bs[_pos++] = (byte) value;
                    } else {
                        _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            _bs[_pos++] = (byte) value;
                        }
                    }
                }
            }
        }
        pos = _pos;
    }

    protected void writeUInt64_2(long value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = (byte) (value        | 0x80);
        _bs[_pos++] = (byte) (value >>> 7  | 0x80);
        _bs[_pos++] = (byte) (value >>> 14 | 0x80);
        _bs[_pos++] = (byte) (value >>> 21 | 0x80);
        _bs[_pos++] = (byte) (value >>> 28 | 0x80);

        value >>>= 35;
        if ((value & ~0x7F) == 0) {
            _bs[_pos++] = (byte) value;
        } else {
            _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[_pos++] = (byte) value;
            } else {
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                } else {
                    _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        _bs[_pos++] = (byte) value;
                    } else {
                        _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            _bs[_pos++] = (byte) value;
                        }
                    }
                }
            }
        }
        pos = _pos;
    }

    protected void writeUInt64_0(long value) {
        if (value >>> 35 == 0) {
            writeUInt64_1(value);
        } else {
            writeUInt64_2(value);
        }
    }

    @Override
    public void writeSInt64(Long value) {
        if (value == null || value == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(MAX_VARLONG_SIZE + 1);
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
        if (value == null || value == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(FIXED_64_SIZE + 1);
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
        if (value == null || value == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
        } else {
            expand(FIXED_64_SIZE + 1);
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
        if (value == null || value == 0) {
            expand( 1);
            bs[pos++] = ZIGZAG32_ZERO;
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
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
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
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                long lv = v;
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

    private void writeSInt64ObjArray(Long[] values) {
        int len = values.length;
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values[i];
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                long lv = v;
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
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
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
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
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
            long lv = values[i];
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
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
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
                writeSInt64List(values);
                break;
            case FIXED64:
            case SFIXED64:
                writeFixedList(values);
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
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
            long lv = v;
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

    private void writeSInt64List(List<Long> values) {
        int len = values.size();
        int _pos = pos;
        byte[] _bs = bs;
        for (int i=0;i<len;i++) {
            Long v = values.get(i);
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
            long lv = values.get(i);
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
            Long v = values.get(i);
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
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
        pos = _pos;
    }

    @Override
    public void writePackedLongs(Iterable<Long> values, Field.Type type) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = 0;
        for (Long l : values) {
            len++;
        }
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        switch (type) {
            case INT64:
            case UINT64:
                writeUInt64Iterator(values);
                break;
            case SINT64:
                writeSInt64Iterator(values);
                break;
            case FIXED64:
            case SFIXED64:
                writeFixedIterator(values);
                break;
            default:
        }
    }

    private void writeUInt64Iterator(Iterable<Long> values) {
        int _pos = pos;
        byte[] _bs = bs;
        for (Long v : values) {
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
            long lv = v;
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

    private void writeSInt64Iterator(Iterable<Long> values) {
        int _pos = pos;
        byte[] _bs = bs;
        for (Long v : values) {
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
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
        pos = _pos;
    }

    private void writeFixedIterator(Iterable<Long> values) {
        int _pos = pos;
        byte[] _bs = bs;
        for (Long v : values) {
            if (v == null || v == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
                continue;
            }
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
        pos = _pos;
    }

    @Override
    public void writePackedDoubles(double[] values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (int i=0;i<len;i++) {
            long value = Double.doubleToRawLongBits(values[i]);
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
    public void writePackedDoubles(Double[] values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (int i=0;i<len;i++) {
            Double l = values[i];
            if (l == null || l == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                long value = Double.doubleToRawLongBits(l);
                _bs[_pos++] = ((byte) ((int) (value)       & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 8)  & 0xFF));
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
    public void writePackedDoubles(List<Double> values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = values.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (int i=0;i<len;i++) {
            Double l = values.get(i);
            if (l == null || l == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                long value = Double.doubleToRawLongBits(l);
                _bs[_pos++] = ((byte) ((int) (value)       & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 8)  & 0xFF));
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
    public void writePackedDoubles(Iterable<Double> values) {
        if (values == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = 0;
        for (Double d : values) {
            len++;
        }
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (Double l : values) {
            if (l == null || l == 0) {
                _bs[_pos++] = ZIGZAG32_ZERO;
            } else {
                long value = Double.doubleToRawLongBits(l);
                _bs[_pos++] = ((byte) ((int) (value)       & 0xFF));
                _bs[_pos++] = ((byte) ((int) (value >> 8)  & 0xFF));
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
    public <E extends Enum<E>> void writeArrayEnum(E[] vs) {
        if (vs == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = vs.length;
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (int i=0;i<len;i++) {
            E v = vs[i];
            if (v == null) {
                _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
            } else {
                int value;
                value = v.ordinal();
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                }
            }
        }
        pos = _pos;
    }

    @Override
    public <E extends Enum<E>> void writeListEnum(List<E> vs) {
        if (vs == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = vs.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE + len);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (int i=0;i<len;i++) {
            E v = vs.get(i);
            if (v == null) {
                _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
            } else {
                int value;
                value = v.ordinal();
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                }
            }
        }
        pos = _pos;
    }

    @Override
    public <E extends Enum<E>> void writeListEnum(Iterable<E> vs) {
        if (vs == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = 0;
        for (E e : vs) {
            len++;
        }
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE + len);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (E v : vs) {
            if (v == null) {
                _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
            } else {
                int value;
                value = v.ordinal();
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                }
            }
        }
        pos = _pos;
    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(List<E> vs) {
        if (vs == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = vs.size();
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARINT_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (int i=0;i<len;i++) {
            E v = vs.get(i);
            if (v == null) {
                _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
            } else {
                int value;
                value = v.getValue();
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                }
            }
        }
        pos = _pos;
    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(Iterable<E> vs) {
        if (vs == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
            return;
        }
        int len = 0;
        for (ProtoBufEnum pe : vs) {
            len++;
        }
        if (len == 0) {
            expand(1);
            bs[pos++] = ZIGZAG32_ZERO;
            return;
        }
        expand(MAX_VARINT_SIZE + len * MAX_VARLONG_SIZE);
        writeUInt32_0(len);
        byte[] _bs  = bs;
        int    _pos = pos;
        for (ProtoBufEnum v : vs) {
            if (v == null) {
                _bs[_pos++] = ZIGZAG32_NEGATIVE_ONE;
            } else {
                int value;
                value = v.getValue();
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                    continue;
                }
                _bs[_pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[_pos++] = (byte) value;
                }
            }
        }
        pos = _pos;
    }

    @Override
    public void writeBytes(Byte[] value) {
        if (value == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int length = value.length;
            if (length == 0) {
                expand(1);
                bs[pos++] = ZIGZAG32_ZERO;
            } else {
                expand(length + 1);
                writeUInt32_0(length);
                System.arraycopy(value, 0, bs, pos, length);
                pos += length;
            }
        }
    }

    @Override
    public void writeByteArray(byte[] value, int offset, int length) {
        if (value == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            if (length == 0) {
                expand(1);
                bs[pos++] = ZIGZAG32_ZERO;
            } else {
                expand(length + 1);
                writeUInt32_0(length);
                System.arraycopy(value, offset, bs, pos, length);
                pos += length;
            }
        }
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
        writeMessage0(msg, encoder);
    }

    private <T> void writeMessage0(T msg, EprotoEncoder<T> encoder) throws EncodeException {
        if (msg == null) {
            expand(FIXED_32_SIZE);
            writeFixed32_0(-1);
        } else {
            int oldPos = pos;
            pos += FIXED_32_SIZE;
            encoder.encode(this, msg);
            int len = pos - oldPos - 4;
            byte[] _bs = bs;
            _bs[oldPos++] = (byte) ((len      ) & 0xFF);
            _bs[oldPos++] = (byte) ((len >>  8) & 0xFF);
            _bs[oldPos++] = (byte) ((len >> 16) & 0xFF);
            _bs[oldPos]   = (byte) ((len >> 24) & 0xFF);
        }
    }

    @Override
    public <T> void writeMessages(T[] msg, EprotoEncoder<T> encoder) throws EncodeException {
        if (msg == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = msg.length;
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(len);
            if (len > 0) {
                for (int i=0;i<len;i++) {
                    writeMessage0(msg[i], encoder);
                }
            }
        }
    }

    @Override
    public <T> void writeMessages(List<T> msg, EprotoEncoder<T> encoder) throws EncodeException {
        if (msg == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = msg.size();
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(len);
            if (len > 0) {
                for (int i=0;i<len;i++) {
                    writeMessage0(msg.get(i), encoder);
                }
            }
        }
    }

    @Override
    public <T> void writeMessages(Iterable<T> msg, EprotoEncoder<T> encoder) throws EncodeException {
        if (msg == null) {
            expand(1);
            bs[pos++] = ZIGZAG32_NEGATIVE_ONE;
        } else {
            int len = 0;
            for (T m : msg) {
                len++;
            }
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(len);
            if (len > 0) {
                for (T m : msg) {
                    writeMessage0(m, encoder);
                }
            }
        }
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

    public void reset() {
        pos = 0;
    }

    public int size() {
        return pos;
    }

    @Override
    public int toFastBuf(FastBuf buf) {
        int len = buf.write(bs, wpos, pos);
        wpos += len;
        return len;
    }

    @Override
    public void setWPos(int wPos) {

    }

    @Override
    public void toStream(OutputStream stream) throws IOException {
        stream.write(bs, 0, pos);
    }

    @Override
    public byte[] toByteArray() {
        byte[] data = new byte[pos];
        System.arraycopy(bs, 0, data, 0, pos);
        return data;
    }

    @Override
    public BufOut getBufOut() {
        return out;
    }
}
