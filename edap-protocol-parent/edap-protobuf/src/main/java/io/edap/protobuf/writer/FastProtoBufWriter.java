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
import io.edap.protobuf.MapEntryEncoder;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import io.edap.util.UnsafeUtil;

import java.util.List;
import java.util.Map;

import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.CollectionUtils.isEmpty;
import static io.edap.util.StringUtil.*;
import static io.edap.util.StringUtil.getCharValue;
import static io.edap.util.UnsafeUtil.copyUtf16le;

public class FastProtoBufWriter extends StandardProtoBufWriter {

    static int START_TAG = WireFormat.makeTag(1, WireType.START_GROUP);

    static int END_TAG = WireFormat.makeTag(1, WireType.END_GROUP);

    public static final byte LATIN1_BYTE  = 0;
    public static final byte UTF16LE_BYTE = 1;
    public static final byte UTF8_BYTE    = 2;

    public FastProtoBufWriter(BufOut out) {
        super(out);
    }

    @Override
    public <T> void writeMessage(T v, ProtoBufEncoder<T> codec) throws EncodeException {
        writeInt32(START_TAG);
        codec.encode(this, v);
        writeInt32(END_TAG);
    }

    @Override
    public <K, V> void writeMap(byte[] fieldData, int tag, Map<K, V> map, MapEntryEncoder<K, V> mapEncoder) throws EncodeException {
        if (CollectionUtils.isEmpty(map)) {
            return;
        }
        for (Map.Entry<K, V> entry : map.entrySet()) {
            writeInt32(WireFormat.makeTag(tag, WireType.START_GROUP));
            mapEncoder.encode(this, entry);
            writeInt32(WireFormat.makeTag(tag, WireType.END_GROUP));
        }
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
    protected final void writeString0(final String value) {
        String v = value;
        // 如果jvm是9以上版本，并且字符串为Latin1的编码，长度大于5时直接copy字符串对象额value字节数组
        if (IS_BYTE_ARRAY) {
            byte[] data = StringUtil.getValue(v);
            int length = data.length;
            expand(length + 6);
            writeUInt32_0(length + 1);
            int _pos = pos;
            if (isLatin1(v)) {
                bs[_pos++] = LATIN1_BYTE;
            } else {
                bs[_pos++] = UTF16LE_BYTE;
            }
            System.arraycopy(data, 0, bs, _pos, length);
            pos = _pos + length;
        } else {
            int charLen = v.length();
            expand(charLen * 2 + 6);
            int _pos = pos;
            bs[_pos++] = UTF16LE_BYTE;
            copyUtf16le((char[]) UnsafeUtil.getValue(value, VALUE_FIELD_OFFSET), 0, bs, _pos, charLen);
            pos += charLen * 2+1;
        }
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
        for (int i=0;i<size;i++) {
            expand(MAX_VARINT_SIZE);
            writeFieldData(fieldData);
            codec.encode(this, vs.get(i));
            writeUInt32(WireFormat.makeTag(tag, WireType.END_GROUP));
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

    @Override
    public void writeObject(byte[] fieldData, Object v, ProtoBufOption option) throws EncodeException {
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);
        AnyCodec.encode(this, v, option);
    }

}
