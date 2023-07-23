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
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;

import java.util.List;

import static io.edap.protobuf.util.ProtoUtil.computeRawVarint64Size;
import static io.edap.protobuf.wire.WireFormat.MAX_VARINT_SIZE;
import static io.edap.protobuf.wire.WireFormat.MAX_VARLONG_SIZE;
import static io.edap.util.CollectionUtils.isEmpty;

public class FastProtoBufWriter extends StandardProtoBufWriter {

    public FastProtoBufWriter(BufOut out) {
        super(out);
    }

    public void writeString9(final String value) {
        //char[] cs = (char[])UnsafeMemory.getValue(value, StringUtil.STRING_VALUE_OFFSET);
        int charLen = value.length();
        if (charLen == 0) {
            writeUInt32(0);
            return;
        }
        int start = charLen;
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

    @Override
    public void writePackedLongs(byte[] fieldData, Long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len;
        int size = values.length;
        switch (type) {
            case INT64:
            case UINT64:
                len = MAX_VARLONG_SIZE * size;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(size);
                for (int i=0;i<size;i++) {
                    writeUInt64_0(values[i]);
                }
                return;
            case SINT64:
                len = MAX_VARLONG_SIZE * size;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(size);
                for (int i=0;i<size;i++) {
                    writeUInt64_0(ProtoBufWriter.encodeZigZag64(values[i]));
                }
                return;
            case FIXED64:
            case SFIXED64:
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (int i=0;i<size;i++) {
                    writeFixed64_0(values[i]);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, List<Long> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int len;
        switch (type) {
            case INT64:
            case UINT64:
                int size = values.size();
                len = size * MAX_VARLONG_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(size);
                for (long l : values) {
                    writeUInt64_0(l);
                }
                return;
            case SINT64:
                size = values.size();
                len = size * MAX_VARLONG_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(size);
                for (long l : values) {
                    writeUInt64_0(ProtoBufWriter.encodeZigZag64(l));
                }
                return;
            case FIXED64:
            case SFIXED64:
                size = values.size();
                expand(MAX_VARINT_SIZE << 1 + size << 3);
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (long l : values) {
                    writeFixed64_0(l);
                }
            default:

        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, List<Integer> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int len;
        int size;
        switch (type) {
            case INT32:
            case UINT32:

//                len = 0;
//                for (Integer i : values) {
//                    len += computeRawVarint32Size(i);
//                }
//                expand(wbuf, MAX_VARINT_SIZE << 1 + len);
//                writeFieldData(fieldData);
//                writeUInt32_0(values.size());
//                for (Integer i : values) {
//                    writeUInt32_0(i);
//                }
//
                size = values.size();
                len = size * 5;
                expand((MAX_VARLONG_SIZE << 1) + len);
                writeFieldData(fieldData);
                writeUInt32_0(values.size());
                int i = 0;
                writeInt32_0(values.get(i++));
                if (size > 1) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 2) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 3) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 4) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 5) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 6) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 7) {
                    writeUInt32_0(values.get(i++));
                }
                if (size > 8) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 9) {
                    writeInt32_0(values.get(i++));
                }
                if (size > 10) {
                    for (i=10;i<size;i++) {
                        writeInt32_0(values.get(i));
                    }
                }
                return;
            case SINT32:
                len = values.size() * MAX_VARINT_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(values.size());
                for (Integer v : values) {
                    writeUInt32_0(ProtoBufWriter.encodeZigZag32(v));
                }
                return;
            case FIXED32:
            case SFIXED32:
                size = values.size();
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (Integer v : values) {
                    writeFixed32_0(v);
                }
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Integer[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        int len;
        switch (type) {
            case INT32:
            case UINT32:
                size = values.length;
                len = size * MAX_VARINT_SIZE;
                expand((MAX_VARINT_SIZE << 1) + len);
                writeFieldData(fieldData);
                writeUInt32_0(values.length);
                int i = 0;
                writeInt32_0(values[i++]);
                if (size > 1) {
                    writeInt32_0(values[i++]);
                }
                if (size > 2) {
                    writeInt32_0(values[i++]);
                }
                if (size > 3) {
                    writeInt32_0(values[i++]);
                }
                if (size > 4) {
                    writeInt32_0(values[i++]);
                }
                if (size > 5) {
                    writeInt32_0(values[i++]);
                }
                if (size > 6) {
                    writeInt32_0(values[i++]);
                }
                if (size > 7) {
                    writeInt32_0(values[i++]);
                }
                if (size > 8) {
                    writeInt32_0(values[i++]);
                }
                if (size > 9) {
                    writeInt32_0(values[i++]);
                }
                if (size > 10) {
                    for (i=10;i<size;i++) {
                        writeInt32_0(values[i]);
                    }
                }
                return;
            case SINT32:
                len = size * MAX_VARINT_SIZE;
                expand(MAX_VARINT_SIZE << 1 + len);
                writeFieldData(fieldData);
                writeUInt32_0(values.length);
                for (i=0;i<size;i++) {
                    writeUInt32_0(ProtoBufWriter.encodeZigZag32(values[i]));
                }
                return;
            case FIXED32:
            case SFIXED32:
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (i=0;i<size;i++) {
                    writeFixed32_0(values[i]);
                }
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, int[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        int len;
        switch (type) {
            case INT32:
            case UINT32:
                size = values.length;
                len = size * 5;
                expand((MAX_VARLONG_SIZE << 1) + len);
                writeFieldData(fieldData);
                writeInt32_0(size);
                int i = 0;
                writeInt32_0(values[i++]);
                if (size > 1) {
                    writeInt32_0(values[i++]);
                }
                if (size > 2) {
                    writeInt32_0(values[i++]);
                }
                if (size > 3) {
                    writeInt32_0(values[i++]);
                }
                if (size > 4) {
                    writeInt32_0(values[i++]);
                }
                if (size > 5) {
                    writeInt32_0(values[i++]);
                }
                if (size > 6) {
                    writeInt32_0(values[i++]);
                }
                if (size > 7) {
                    writeInt32_0(values[i++]);
                }
                if (size > 8) {
                    writeInt32_0(values[i++]);
                }
                if (size > 9) {
                    writeInt32_0(values[i++]);
                }
                if (size > 10) {
                    for (i=10;i<size;i++) {
                        writeInt32_0(values[i]);
                    }
                }
                return;
            case SINT32:
                size = values.length;
                len = size * 5;
                expand((MAX_VARLONG_SIZE << 1) + len);
                writeFieldData(fieldData);
                writeInt32_0(size);
                //writeUInt32_0(len);
                for (i=0;i<size;i++) {
                    writeUInt32_0(ProtoBufWriter.encodeZigZag32(values[i]));
                }
                return;
            case FIXED32:
            case SFIXED32:
                expand(MAX_VARINT_SIZE << 1 + size << 2);
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (i=0;i<size;i++) {
                    writeFixed32_0(values[i]);
                }
            default:
                break;
        }
    }

    @Override
    public <T> void writeMessage(T v, ProtoBufEncoder<T> codec) throws EncodeException {
        writeUInt32(WireFormat.makeTag(1, WireType.START_GROUP));
        codec.encode(this, v);
        writeUInt32(WireFormat.makeTag(1, WireType.END_GROUP));
    }

    @Override
    public <T> void writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) throws EncodeException {
        if (v == null) {
            return;
        }
        writeFieldData(fieldData);
        codec.encode(this, v);
        writeUInt32(WireFormat.makeTag(tag, WireType.END_GROUP));
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, List<T> vs, ProtoBufEncoder<T> codec) throws EncodeException {
        int size = vs.size();
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);
        codec.encode(this, vs.get(0));
        int end = WireFormat.makeTag(tag, WireType.END_GROUP);
        if (size == 1) {
            writeUInt32(end);
        } else {
            writeUInt32(end);
            for (int i=1;i<size;i++) {
                expand(MAX_VARINT_SIZE * 2);
                writeFieldData2(fieldData);
                codec.encode(this, vs.get(i));
                writeUInt32(end);
            }
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, T[] vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (vs == null) {
            return;
        }
        int end = WireFormat.makeTag(tag, WireType.END_GROUP);
        int size = vs.length;
        for (int i=0;i<size;i++) {
            //for (T v : vs) {
            T v = vs[i];
            writeMessage0(fieldData, tag, v, codec, end);
        }
    }

    public <T> void writeMessage0(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec, int end) throws EncodeException {
        writeFieldData(fieldData);
        codec.encode(this, v);
        writeUInt32(end);
    }
}
