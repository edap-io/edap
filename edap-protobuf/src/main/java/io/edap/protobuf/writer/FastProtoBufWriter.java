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
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;

import java.util.List;

import static io.edap.protobuf.wire.WireFormat.MAX_VARINT_SIZE;

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
    public <T> void writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) {
        if (v == null) {
            return;
        }
        writeUInt32(WireFormat.makeTag(tag, WireType.START_GROUP));
        codec.encode(this, v);
        writeUInt32(WireFormat.makeTag(tag, WireType.END_GROUP));
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, List<T> vs, ProtoBufEncoder<T> codec) {
        int size = vs.size();
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);
        codec.encode(this, vs.get(0));
        if (size == 1) {
            expand(MAX_VARINT_SIZE);
            writeFieldData(fieldData);
        } else {
            for (int i=1;i<size;i++) {
                expand(MAX_VARINT_SIZE * 2);
                writeFieldData2(fieldData);
                codec.encode(this, vs.get(i));
            }
            expand(MAX_VARINT_SIZE);
            writeFieldData(fieldData);
        }
    }
}
