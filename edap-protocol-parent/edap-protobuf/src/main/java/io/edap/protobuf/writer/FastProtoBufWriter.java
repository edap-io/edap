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
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import io.edap.util.UnsafeUtil;

import java.util.List;
import java.util.Map;

import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.StringUtil.*;
import static io.edap.util.UnsafeUtil.copyUtf16le;

public class FastProtoBufWriter extends StandardProtoBufWriter {

    static int START_TAG = WireFormat.makeTag(1, WireType.START_GROUP);

    static int END_TAG = WireFormat.makeTag(1, WireType.END_GROUP);

    public static final byte LATIN1_BYTE  = 0;
    public static final byte UTF16LE_BYTE = 1;
    public static final byte UTF8_BYTE    = 2;
    /**
     * ★ UTF-16 大端。
     *
     * <p><b>UTF16 标记携带的是「发送方的字节序」，不是「协议规定的字节序」</b>：
     * JDK 的 String 内部 UTF-16 字节序 = <b>原生端序</b>（{@code StringUTF16} 的 {@code <clinit>}
     * 调 native {@code isBigEndian()}），本类把 String 的内部 byte[] 原样写出去
     * ⇒ 写出去的字节序 = 本机端序 ⇒ 读端必须知道对方的端序才能正确还原。
     * ⇒ 写端按本机端序选标记（<b>写端一次字节序转换都不做</b>），
     * 读端只在「对方端序 ≠ 自己端序」时才翻一次。</p>
     */
    public static final byte UTF16BE_BYTE = 3;

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
        // ★ UTF16 串写出去的字节 = String 内部 byte[] 原样拷贝 ⇒ 字节序 = 本机端序
        //   ⇒ 标记必须带上「本机端序」，读端才知道要不要翻（见 UTF16BE_BYTE 的注释）。
        //   写端不翻字节：跨端序的转换全部由读端承担 ⇒ 同端序时两端都零成本。
        if (IS_BYTE_ARRAY) {
            byte[] data = StringUtil.getValue(v);
            int length = data.length;
            expand(length + 6);
            writeUInt32_0(length + 1);
            int _pos = pos;
            if (isLatin1(v)) {
                bs[_pos++] = LATIN1_BYTE;
            } else {
                bs[_pos++] = NATIVE_LITTLE_ENDIAN ? UTF16LE_BYTE : UTF16BE_BYTE;
            }
            System.arraycopy(data, 0, bs, _pos, length);
            pos = _pos + length;
        } else {
            int charLen = v.length();
            expand(charLen * 2 + 6);
            int _pos = pos;
            bs[_pos++] = NATIVE_LITTLE_ENDIAN ? UTF16LE_BYTE : UTF16BE_BYTE;
            // copyUtf16le 其实是「整块拷贝 char[] 的原生字节」（名字有误导），字节序同样是本机端序
            copyUtf16le((char[]) UnsafeUtil.getValue(value, VALUE_FIELD_OFFSET), 0, bs, _pos, charLen);
            pos += charLen * 2+1;
        }
    }

    @Override
    public <T> void writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) throws EncodeException {
        if (v == null) {
            return;
        }
        // ★ writeFieldData 是裸写（不检查容量）⇒ 必须先预留 fieldData 的长度，否则缓冲写满时越界
        expand(fieldData.length);
        writeFieldData(fieldData);
        codec.encode(this, v);
        writeUInt32(WireFormat.makeTag(tag, WireType.END_GROUP));
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, List<T> vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (vs == null) {
            return;
        }
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
        // ★ writeFieldData 是裸写（不检查容量）⇒ 必须先预留 fieldData 的长度，否则缓冲写满时越界
        expand(fieldData.length);
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
