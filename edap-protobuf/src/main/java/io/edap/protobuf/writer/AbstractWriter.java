/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.writer;

import io.edap.buffer.FastBuf;
import io.edap.io.BufOut;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import io.edap.util.UnsafeUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ProtoBufWriter.encodeZigZag64;
import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.CollectionUtils.isEmpty;
import static io.edap.util.StringUtil.IS_BYTE_ARRAY;
import static io.edap.util.StringUtil.isLatin1;


public abstract class AbstractWriter implements ProtoBufWriter {
    static final ThreadLocal<byte[]> LOCAL_TMP_BYTE_ARRAY =
            new ThreadLocal<byte[]>() {
                @Override
                protected byte[] initialValue() {
                    return new byte[1024];
                }
            };

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
    public void setPos(int pos) {
        this.pos = pos;
    }

    @Override
    public WriteOrder getWriteOrder() {
        return WriteOrder.SEQUENTIAL;
    }

    @Override
    public int size() {
        return pos;
    }

    public int getPos() {
        return pos;
    }

    @Override
    public BufOut getBufOut() {
        return out;
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

    public void writeFieldData(final byte[] fieldData) {
        int len = fieldData.length;
        byte[] _bs = bs;
        int _p = pos;
        if (len == 1) {
            _bs[_p++] = fieldData[0];
        } else if (len == 2) {
            _bs[_p++] = fieldData[0];
            _bs[_p++] = fieldData[1];
        } else {
            for (int i = 0; i < len; i++) {
                _bs[_p++] = fieldData[i];
            }
        }
        pos = _p;
    }

    public void writeFieldData2(final byte[] fieldData) {
        int len = fieldData.length;
        if (len == 1) {
            bs[pos++] = fieldData[0];
            bs[pos++] = fieldData[0];
        } else if (len == 2) {
            bs[pos++] = fieldData[0];
            bs[pos++] = fieldData[1];
            bs[pos++] = fieldData[0];
            bs[pos++] = fieldData[1];
        } else {
            for (int i = 0; i < len; i++) {
                bs[pos++] = fieldData[i];
                bs[pos+len] = fieldData[i];
            }
        }
    }

    @Override
    public void writeByte(final byte b) {
        expand(1);
        this.bs[pos++] = b;
    }

    @Override
    public void writeBool(final byte[] fieldData, final Boolean value) {
        if (value == null) {
            return;
        }
        writeBool(fieldData, value.booleanValue());
    }

    @Override
    public void writeBool(final byte[] fieldData, final boolean value) {
        if (!value) {
            return;
        }
        expand(fieldData.length + 1);
        writeFieldData(fieldData);
        bs[pos++] = (byte) (value ? 1 : 0);
    }

    @Override
    public void writeInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        writeInt32(fieldData, value.intValue());
    }

    @Override
    public void writeInt32(final byte[] fieldData, final int value) {
        if (value == 0) {
            return;
        }
        if (value > 0) {
            expand(MAX_VARLONG_SIZE);
            writeFieldData(fieldData);
            writeUInt32_0(value);
        } else {
            expand(MAX_VARLONG_SIZE + MAX_VARINT_SIZE);
            writeByteArray_0(fieldData, 0, fieldData.length);
            writeUInt64_0(value);
        }
    }

    @Override
    public void writeUInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        writeUInt32(fieldData, value.intValue());
    }

    @Override
    public void writeUInt32(final byte[] fieldData, final int value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(value);
    }

    @Override
    public final void writeInt32(int value) {
        if (0 == value) {
            return;
        }
        if (value >= 0) {
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(value);
        } else {
            expand(MAX_VARLONG_SIZE);
            writeUInt64_0(value);
        }
    }

    @Override
    public void writeInt32(int value, boolean needEncodeZero) {
        if (!needEncodeZero && value == 0) {
            return;
        }
        if (value >= 0) {
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(value);
        } else {
            expand(MAX_VARLONG_SIZE);
            writeUInt64_0(value);
        }
    }

    public final void writeInt32_0(int value) {
        if (value > 0) {
            writeUInt32_0(value);
        } else {
            writeUInt64_0(value);
        }
    }

    @Override
    public final void writeUInt32(int value) {
        expand(MAX_VARINT_SIZE);
        writeUInt32_0(value);
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        writeSInt32(fieldData, value.intValue());
    }

    @Override
    public void writeSInt32(final byte[] fieldData, final int value) {
        if (0 == value) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeSInt32(final int value, boolean needEncoderZero) {
        if (0 == value && !needEncoderZero) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final Integer value) {
        if (value == null || value.intValue() == 0) {
            return;
        }
        expand(fieldData.length + FIXED_32_SIZE);
        writeFieldData(fieldData);
        writeFixed32_0(value);
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final int value) {
        if (value == 0) {
            return;
        }
        expand(fieldData.length + FIXED_32_SIZE);
        writeFieldData(fieldData);
        writeFixed32_0(value);
    }

    @Override
    public void writeSFixed32(final byte[] fieldData, final Integer value) {
        if (value == null || value.intValue() == 0) {
            return;
        }
        writeFixed32(fieldData, value);
    }

    @Override
    public void writeSFixed32(final byte[] fieldData, final int value) {
        writeFixed32(fieldData, value);
    }

    @Override
    public void writeLong(byte[] fieldData, Long value) {
        if (value == null || value.longValue() == 0) {
            return;
        }
        writeLong(fieldData, value.longValue());
    }

    @Override
    public void writeLong(byte[] fieldData, long value) {
        writeInt64(fieldData, value);
    }

    @Override
    public final void writeInt64(final byte[] fieldData, final long value) {
        writeUInt64(fieldData, value);
    }

    @Override
    public void writeUInt64(final byte[] fieldData, final Long value) {
        if (value == null || value.longValue() == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt64_0(value);
    }

    @Override
    public void writeUInt64(final byte[] fieldData, final long value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt64_0(value);
    }

    @Override
    public final void writeSInt64(final byte[] fieldData, final Long value) {
        if (value == null || value.longValue() == 0) {
            return;
        }
        writeUInt64(fieldData, encodeZigZag64(value.longValue()));
    }

    @Override
    public final void writeSInt64(final byte[] fieldData, final long value) {
        writeUInt64(fieldData, encodeZigZag64(value));
    }

    @Override
    public void writePackedBools(byte[] fieldData, List<Boolean> values) {
        if (isEmpty(values)) {
            return;
        }
        int[] vs = new int[values.size()];
        for (int i=0;i<values.size();i++) {
            vs[i] = values.get(i)?1:0;
        }
        writePackedInts(fieldData, vs, Field.Type.UINT32);
    }

    @Override
    public void writePackedBools(byte[] fieldData, Iterable<Boolean> values) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        List<Integer> vs = new ArrayList<>();
        for (Boolean v : values) {
            vs.add(v!=null&&v.booleanValue()?1:0);
        }
        writePackedInts(fieldData, vs, Field.Type.UINT32);
    }

    @Override
    public void writeFixed64(final byte[] fieldData, final Long value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, value.longValue());
    }

    @Override
    public void writeFixed64(final byte[] fieldData, final long value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + FIXED_64_SIZE);
        writeFieldData(fieldData);
        writeFixed64_0(value);
    }

    @Override
    public final void writeSFixed64(final byte[] fieldData, final Long value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, value);
    }

    @Override
    public final void writeSFixed64(final byte[] fieldData, final long value) {
        writeFixed64(fieldData, value);
    }

    @Override
    public final void writeFloat(final byte[] fieldData, final Float value) {
        if (value == null) {
            return;
        }
        writeFixed32(fieldData, Float.floatToRawIntBits(value));
    }

    @Override
    public final void writeFloat(final byte[] fieldData, final float value) {
        writeFixed32(fieldData, Float.floatToRawIntBits(value));
    }

    @Override
    public final void writeDouble(final byte[] fieldData, final Double value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, Double.doubleToRawLongBits(value));
    }

    @Override
    public final void writeDouble(final byte[] fieldData, final double value) {
        writeFixed64(fieldData, Double.doubleToRawLongBits(value));
    }

    @Override
    public final void writeEnum(final byte[] fieldData, Integer value) {
        if (value == null || value.intValue() == 0) {
            return;
        }
        writeUInt32(fieldData, value.intValue());
    }

    @Override
    public <E extends Enum<E>> void writeListEnum(byte[] fieldData, List<E> vs) {
        if (isEmpty(vs)) {
            return;
        }
        int size = vs.size();
        int[] values = new int[size];
        for (int i=0;i<size;i++) {
            values[i] = vs.get(i).ordinal();
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public <E extends Enum<E>> void writeListEnum(byte[] fieldData, Iterable<E> vs) {
        if (vs == null || !vs.iterator().hasNext()) {
            return;
        }
        List<Integer> values = new ArrayList<>();
        int i = 0;
        for (E e : vs) {
            values.add(e.ordinal());
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, List<E> vs) {
        if (CollectionUtils.isEmpty(vs)) {
            return;
        }
        int size = vs.size();
        int[] values = new int[size];
        for (int i=0;i<size;i++) {
            values[i] = vs.get(i).getValue();
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, Iterable<E> vs) {
        if (vs == null || !vs.iterator().hasNext()) {
            return;
        }
        List<Integer> values = new ArrayList<>();
        for (E e : vs) {
            values.add(e.getValue());
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public void writeString(final byte[] fieldData, final String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        expand(fieldData.length);
        writeFieldData(fieldData);
        writeString(value);
    }

    public static int computeUTF8Size(final String str, final int index, final int len)
    {
        int size = len;
        for (int i = index; i < len; i++) {
            final char c = str.charAt(i);
            if (c < 0x0080) {
                continue;
            }
            if (c < 0x0800) {
                size++;
            } else if (Character.isHighSurrogate(c) && i+1<len
                    && Character.isLowSurrogate(str.charAt(i+1))) {
                size += 2;
                i++;
            } else {
                size += 2;
            }
        }
        return size;
    }
    public final void writeString1(final String value) {
        int charLen = value.length();
        if (charLen == 0) {
            writeUInt32(0);
            return;
        }
        int oldPos = pos;
        int start = pos + 1;
        expand(charLen * 4 + MAX_VARINT_SIZE);
        byte[] _bs = bs;
        for (int i=0;i<charLen;i++) {
            char c = value.charAt(i);
            if (c < 128) {
                _bs[start++] = (byte) c;
            } else if (c < 0x800) {
                _bs[start++] = (byte) ((0xF << 6) | (c >>> 6));
                _bs[start++] = (byte) (0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i+1<charLen
                    && Character.isLowSurrogate(value.charAt(i+1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i+1));
                _bs[start++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                _bs[start++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                _bs[start++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
                _bs[start++] = (byte) (0x80 | ( codePoint        & 0x3F));
                i++;
            } else {
                _bs[start++] = (byte) ((0xF << 5) | (c >>> 12));
                _bs[start++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                _bs[start++] = (byte) (0x80 | (0x3F & c));
            }
        }
        pos = start;
        int len = start - oldPos - 5;
        pos += writeLenMoveBytes(bs, oldPos, len);
    }

    @Override
    public void writeStringUtf8(final String value, int len) {
        String v = value;
        int charLen = v.length();
        // 如果jvm是9以上版本，并且字符串为Latin1的编码，长度大于5时直接copy字符串对象额value字节数组
        if (IS_BYTE_ARRAY && isLatin1(v) && charLen > 5) {
            expand(charLen);
            writeByteArray_0(StringUtil.getValue(v), 0, charLen);
            return;
        }
        // 转为utf8后最大的所需字节数
        int maxBytes = charLen * 3;
        // 如果所需最大字节数小于3k + 编码int最大字节数 则直接扩容所需最大字节数
        if (maxBytes <= 3072) {
            expand(maxBytes + 1);
            int _pos = pos;
            pos += writeChars(v, 0, charLen, _pos);
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);

        int oldPos = pos;
        int _pos   = oldPos + 1;
        expand(maxBytes);
        while (start < charLen) {
            expand(3072);
            _pos += writeChars(v, start, end, _pos);
            pos = _pos;
            start += 1024;
            end = Math.min((start + 1024), charLen);
        }
    }

    @Override
    public void writeString(final String value) {
        if (value == null) {
            writeInt32(-1);
            return;
        }
        //char[] cs = (char[])UnsafeMemory.getValue(value, StringUtil.STRING_VALUE_OFFSET);
        int charLen = value.length();
        if (charLen == 0) {
            writeInt32(0, true);
            return;
        }
        writeString0(value);
    }

    /**
     * 将不为空的字符串写入到缓存中
     * @param value
     */
    private final void writeString0(final String value) {
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
            int _pos = pos;
            pos++;
            int len = writeChars(v, 0, charLen, _pos+1);
            pos += len;
            pos += writeLenMoveBytes(bs, _pos, len);
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);

        int oldPos = pos;
        pos++;
        int _pos   = oldPos + 1;
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

    private final int writeChars(final String value, int start, int end, int pos) {
        String v = value;
        int p = pos;
        byte[] _bs = this.bs;
        for (int i=start;i<end;i++) {
            char c = v.charAt(i);
            if (c < 128) {
                _bs[p++] = (byte) c;
            } else if (c < 0x800) {
                _bs[p++] = (byte) ((0xF << 6) | (c >>> 6));
                _bs[p++] = (byte) ( 0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i+1<end
                    && Character.isLowSurrogate(value.charAt(i+1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i+1));
                _bs[p++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                _bs[p++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                _bs[p++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
                _bs[p++] = (byte) (0x80 | ( codePoint        & 0x3F));
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
    public void writeBytes(final byte[] fieldData, final byte[] value) {
        if (value == null) {
            return;
        }
        writeByteArray(fieldData, value, 0, value.length);
    }

    @Override
    public void writeBytes(final byte[] fieldData, final Byte[] value) {
        if (value == null) {
            return;
        }
        int length = value.length;
        expand(length + MAX_VARINT_SIZE * 2);
        writeFieldData(fieldData);
        writeUInt32_0(length);

        int p = pos;
        byte[] _bs = this.bs;
        for (int i=0;i<length;i++) {
            _bs[p++] = value[i].byteValue();
        }
        pos = p;
    }

    @Override
    public void writeBytes(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        int len = value.length;
        expand(len);
        System.arraycopy(value, 0, bs, pos, len);
        pos += len;
    }

    @Override
    public void writeByteArray(final byte[] fieldData, final byte[] value,
                                     int offset, int length) {
        expand(length + MAX_VARINT_SIZE * 2);
        writeFieldData(fieldData);
        writeUInt32_0(length);
        writeByteArray_0(value, offset, length);
    }

    @Override
    public void writeByteArray(final byte[] value, int offset, int length) {
        expand(length + 5);
        writeUInt32_0(length);
        System.arraycopy(value, offset, bs, pos, length);
        pos += length;
    }

    protected void writeByteArray_0(final byte[] value, int offset, int length) {
        System.arraycopy(value, offset, bs, pos, length);
        pos += length;
    }

    protected int writeLenMoveBytes(byte[] bs, int p, int len) {
        if ((len & ~0x7F) == 0) {
            bs[p] = (byte) len;
            return 0;
        } else {
            byte[] _bs = bs;
            int value = len;
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                expand(1);
                _bs = this.bs;
                System.arraycopy(_bs, p, _bs, p+1, len);
                _bs[p++] = (byte) value;
                return 1;
            } else {
                byte b2 = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    expand(2);
                    _bs = this.bs;
                    System.arraycopy(_bs, p, _bs, p+2, len);
                    _bs[p++] = b2;
                    _bs[p++] = (byte) value;
                    return 2;
                } else {
                    byte b3 = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        expand(3);
                        _bs = this.bs;
                        System.arraycopy(_bs, p, _bs, p+3, len);
                        _bs[p++] = b2;
                        _bs[p++] = b3;
                        _bs[p++] = (byte) value;
                        return 3;
                    } else {
                        byte b4 = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            expand(4);
                            _bs = this.bs;
                            System.arraycopy(_bs, p, _bs, p+4, len);
                            _bs[p++] = b2;
                            _bs[p++] = b3;
                            _bs[p++] = b4;
                            _bs[p++] = (byte) value;
                            return 4;
                        }
                    }
                }
            }
        }
        return 0;
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

    @Override
    public void writeUInt64(long value) {
        expand(MAX_VARLONG_SIZE);
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
    public void writeFixed32(int value) {
        expand(FIXED_32_SIZE);
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = (byte) ((int) (value      ) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >>  8) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 16) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 24) & 0xFF);
        pos = _pos;
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
    public void writeFixed64(long value) {
        expand(FIXED_64_SIZE);
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
    public void expand(int minLength) {
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
        wpos = 0;
    }

    @Override
    public void setWPos(int wpos) {
        this.wpos = 0;
    }

    /**
     * 写入FastBuf的下标
     */
    private int wpos = 0;
    @Override
    public int toFastBuf(FastBuf buf) {
        int len = buf.write(bs, wpos, pos);
        wpos += len;
        return len;
    }
}