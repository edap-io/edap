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

import io.edap.io.BufOut;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;
import io.edap.util.UnsafeUtil;

import java.util.List;

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ProtoBufWriter.encodeZigZag64;
import static io.edap.protobuf.util.ProtoUtil.moveForwardBytes;
import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.CollectionUtils.isEmpty;


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
    public WriteOrder getWriteOrder() {
        return WriteOrder.SEQUENTIAL;
    }

    public int getPos() {
        return pos;
    }

    @Override
    public BufOut getBufOut() {
        return out;
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
        if (value >= 0) {
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
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(value);
    }

    @Override
    public final void writeInt32(int value) {
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
        if (value >= 0) {
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
    public void writeSInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeSInt32(final byte[] fieldData, final int value) {
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        expand(fieldData.length + FIXED_32_SIZE);
        writeFieldData(fieldData);
        writeFixed32_0(value);
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final int value) {
        expand(fieldData.length + FIXED_32_SIZE);
        writeFieldData(fieldData);
        writeFixed32_0(value);
    }

    @Override
    public void writeSFixed32(final byte[] fieldData, final Integer value) {
        if (value == null) {
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
        if (value == null) {
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
        if (value == null) {
            return;
        }
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt64_0(value);
    }

    @Override
    public void writeUInt64(final byte[] fieldData, final long value) {
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt64_0(value);
    }

    @Override
    public final void writeSInt64(final byte[] fieldData, final Long value) {
        if (value == null) {
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
    public void writeFixed64(final byte[] fieldData, final Long value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, value.longValue());
    }

    @Override
    public void writeFixed64(final byte[] fieldData, final long value) {
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
    public void writeString(final byte[] fieldData, final String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        expand(fieldData.length);
        writeFieldData(fieldData);
        writeString(value);
    }

    @Override
    public void writeStringUtf8(final String value, int len) {
        if (value.length() > 10) {
            writeString2(value);
            return;
        }
        if (len < 0) {
            len = computeUTF8Size(value, 0, value.length());
        }
        expand(len);
        int p = pos;
        byte[] bs = this.bs;
        int charLen = value.length();
        for (int i=0;i<charLen;i++) {
            char c = value.charAt(i);
            if (c < 128) {
                bs[p++] = (byte) c;
            } else if (c < 0x800) {
                bs[p++] = (byte) ((0xF << 6) | (c >>> 6));
                bs[p++] = (byte) (0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i+1<charLen
                    && Character.isLowSurrogate(value.charAt(i+1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i+1));
                bs[p++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                bs[p++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                bs[p++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
                bs[p++] = (byte) (0x80 | ( codePoint        & 0x3F));
                i++;
            } else {
                bs[p++] = (byte) ((0xF << 5) | (c >>> 12));
                bs[p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                bs[p++] = (byte) (0x80 | (0x3F & c));
            }
        }
        pos = p;
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
        int start = pos + 5;
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
        int len = start - oldPos - 5;
        pos = oldPos;
        writeUInt32_0(len);
        moveForwardBytes(bs, oldPos + 5, len, oldPos + 5 - pos);
        pos += len;
    }

    public final void writeString2(final String value) {
        //char[] cs = (char[])UnsafeMemory.getValue(value, StringUtil.STRING_VALUE_OFFSET);
        int charLen = value.length();
        /**/
        expand(charLen * 3);
        byte[] buf = bs;
        /**/
        //byte[] buf = new byte[charLen*4];
        int start = pos;
        int i = 0;
//        int p = charLen/10;
//        for (int j=0;j<p;j++) {
//            char c0 = value.charAt(i++);
//            if (c0 < 128) {
//                buf[start++] = (byte) c0;
//            } else {
//                i--;
//                break;
//            }
//            char c1 = value.charAt(i++);
//            if (c1 < 128) {
//                buf[start++] = (byte) c1;
//            } else {
//                i--;
//                break;
//            }
//            char c2 = value.charAt(i++);
//            if (c2 < 128) {
//                buf[start++] = (byte) c2;
//            } else {
//                i--;
//                break;
//            }
//            char c3 = value.charAt(i++);
//            if (c3 < 128) {
//                buf[start++] = (byte) c3;
//            } else {
//                i--;
//                break;
//            }
//            char c4 = value.charAt(i++);
//            if (c4 < 128) {
//                buf[start++] = (byte) c4;
//            } else {
//                i--;
//                break;
//            }
//            char c5 = value.charAt(i++);
//            if (c5 < 128) {
//                buf[start++] = (byte) c5;
//            } else {
//                i--;
//                break;
//            }
//            char c6 = value.charAt(i++);
//            if (c6 < 128) {
//                buf[start++] = (byte) c6;
//            } else {
//                i--;
//                break;
//            }
//            char c7 = value.charAt(i++);
//            if (c7 < 128) {
//                buf[start++] = (byte) c7;
//            } else {
//                i--;
//                break;
//            }
//            char c8 = value.charAt(i++);
//            if (c8 < 128) {
//                buf[start++] = (byte) c8;
//            } else {
//                i--;
//                break;
//            }
//            char c9 = value.charAt(i++);
//            if (c9 < 128) {
//                buf[start++] = (byte) c9;
//            } else {
//                i--;
//                break;
//            }
//        }

        for (;i<charLen;i++) {
            char c = value.charAt(i);
            if (c < 128) {
                buf[start++] = (byte) c;
            } else {
                break;
            }
        }
        for (; i < charLen; i++) {
            char c = value.charAt(i);
            if (c < 128) {
                buf[start++] = (byte) c;
            } else if (c < 0x800) {
                buf[start++] = (byte) ((0xF << 6) | (c >>> 6));
                buf[start++] = (byte) (0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i + 1 < charLen
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i + 1));
                buf[start++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                buf[start++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                buf[start++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                buf[start++] = (byte) (0x80 | (codePoint & 0x3F));
                i++;
            } else {
                buf[start++] = (byte) ((0xF << 5) | (c >>> 12));
                buf[start++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                buf[start++] = (byte) (0x80 | (0x3F & c));
            }
        }
        //int j = wbuf.start;
        //UnsafeUtil.copyMemory(buf, 0, bs, pos, start);
        //System.arraycopy(buf, 0, wbuf.bs, wbuf.start, start);
        //for (int i=0;i<start;i++){
        //    bs[j++] = buf[i];
        //}
        //wbuf.start = j;
        pos = start;
    }

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
        if (charLen > 10) {
            writeString0(value);
            return;
        }
        /*
        byte[] buf = LOCAL_TMP_BYTE_ARRAY.get();
        if (buf.length < charLen * 4) {
            buf = new byte[charLen * 4];
            LOCAL_TMP_BYTE_ARRAY.set(buf);
        }
        int start = 0;
        for (int i=0;i<charLen;i++) {
            char c = value.charAt(i);
            if (c < 128) {
               buf[start++] = (byte) c;
            } else if (c < 0x800) {
                buf[start++] = (byte) ((0xF << 6) | (c >>> 6));
                buf[start++] = (byte) (0x80 | (0x3F & c));
            } else if (c < Character.MIN_SURROGATE || Character.MAX_SURROGATE < c) {
                buf[start++] = (byte) ((0xF << 5) | (c >>> 12));
                buf[start++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                buf[start++] = (byte) (0x80 | (0x3F & c));
            } else {
                buf[start++] = (byte) (0xF0 | ((c >> 18) & 0x07));
                buf[start++] = (byte) (0x80 | ((c >> 12) & 0x3F));
                buf[start++] = (byte) (0x80 | ((c >>  6) & 0x3F));
                buf[start++] = (byte) (0x80 | (        c & 0x3F));
            }
        }
        */
        int start = computeUTF8Size(value, 0, charLen);
        //int start = charLen;
        /**/
        expand(start + MAX_VARINT_SIZE);
        writeUInt32(start);

        int p = pos;
        byte[] bs = this.bs;
        for (int i=0;i<charLen;i++) {
            char c = value.charAt(i);
            if (c < 128) {
                bs[p++] = (byte) c;
            } else if (c < 0x800) {
                bs[p++] = (byte) ((0xF << 6) | (c >>> 6));
                bs[p++] = (byte) (0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i+1<charLen
                    && Character.isLowSurrogate(value.charAt(i+1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i+1));
                bs[p++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                bs[p++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                bs[p++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
                bs[p++] = (byte) (0x80 | ( codePoint        & 0x3F));
                i++;
            } else {
                bs[p++] = (byte) ((0xF << 5) | (c >>> 12));
                bs[p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                bs[p++] = (byte) (0x80 | (0x3F & c));
            }
        }
        pos = p;
    }

    public final void writeString0(final String value) {
        //char[] cs = (char[])UnsafeMemory.getValue(value, StringUtil.STRING_VALUE_OFFSET);
        int charLen = value.length();
        if (charLen == 0) {
            writeUInt32(0);
            return;
        }
        /**/
        byte[] buf = LOCAL_TMP_BYTE_ARRAY.get();
        if (buf.length < charLen * 4) {
            buf = new byte[charLen * 4];
            LOCAL_TMP_BYTE_ARRAY.set(buf);
        }
        /**/
        //byte[] buf = new byte[charLen*4];
        int start = 0;
        for (int i=0;i<charLen;i++) {
            char c = value.charAt(i);
            if (c < 128) {
                buf[start++] = (byte) c;
            } else if (c < 0x800) {
                buf[start++] = (byte) ((0xF << 6) | (c >>> 6));
                buf[start++] = (byte) (0x80 | (0x3F & c));
            } else if (Character.isHighSurrogate(c) && i+1<charLen
                    && Character.isLowSurrogate(value.charAt(i+1))) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i+1));
                buf[start++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
                buf[start++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                buf[start++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
                buf[start++] = (byte) (0x80 | ( codePoint        & 0x3F));
                i++;
            } else {
                buf[start++] = (byte) ((0xF << 5) | (c >>> 12));
                buf[start++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                buf[start++] = (byte) (0x80 | (0x3F & c));
            }
        }
        writeUInt32(start);
        expand(start);
        //int j = wbuf.start;
        UnsafeUtil.copyMemory(buf, 0, bs, pos, start);
        //System.arraycopy(buf, 0, wbuf.bs, wbuf.start, start);
        //for (int i=0;i<start;i++){
        //    bs[j++] = buf[i];
        //}
        //wbuf.start = j;
        pos += start;
    }

    @Override
    public void writeBytes(final byte[] fieldData, final byte[] value) {
        if (value == null) {
            return;
        }
        writeByteArray(fieldData, value, 0, value.length);
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
        while (true) {
            if ((value & ~0x7FL) == 0) {
                bs[pos++] = (byte) value;
                return;
            } else {
                bs[pos++] = ((byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    protected void writeUInt64_0(long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                bs[pos++] = (byte) value;
                return;
            } else {
                bs[pos++] = ((byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    @Override
    public void writeFixed32(int value) {
        expand(FIXED_32_SIZE);
        bs[pos++] = (byte) ((int) (value      ) & 0xFF);
        bs[pos++] = (byte) ((int) (value >>  8) & 0xFF);
        bs[pos++] = (byte) ((int) (value >> 16) & 0xFF);
        bs[pos++] = (byte) ((int) (value >> 24) & 0xFF);
    }

    protected void writeFixed32_0(int value) {
        bs[pos++] = (byte) ((int) (value      ) & 0xFF);
        bs[pos++] = (byte) ((int) (value >>  8) & 0xFF);
        bs[pos++] = (byte) ((int) (value >> 16) & 0xFF);
        bs[pos++] = (byte) ((int) (value >> 24) & 0xFF);
    }

    @Override
    public void writeFixed64(long value) {
        expand(FIXED_64_SIZE);
        bs[pos++] = ((byte) ((int) (value      ) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >>  8) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 16) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 24) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 32) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 40) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 48) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 56) & 0xFF));
    }

    protected void writeFixed64_0(long value) {
        bs[pos++] = ((byte) ((int) (value      ) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >>  8) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 16) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 24) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 32) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 40) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 48) & 0xFF));
        bs[pos++] = ((byte) ((int) (value >> 56) & 0xFF));
        wbuf.start += 8;
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
}