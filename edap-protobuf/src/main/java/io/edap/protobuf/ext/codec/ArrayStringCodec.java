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

package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_STRING;

/**
 * 字符串数组的编解码器，字符串编码为数组长度 + 长度+utf8编码字节数组，-1表示为null,
 */
public class ArrayStringCodec implements ExtCodec<String[]> {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    @Override
    public String[] decode(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        if (len == 0) {
            return EMPTY_STRING_ARRAY;
        }
        String[] vs = new String[len];
        for (int i=0;i<len;i++) {
            vs[i] = reader.readString();
        }
        return vs;
    }

    @Override
    public void encode(ProtoBufWriter writer, String[] ss) throws EncodeException {
        int len = ss.length;
        if (len == 0) {
            if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
                writer.writeByte((byte)RANGE_ARRAY_STRING);
                writer.writeInt32(0, true);
            } else {
                writer.writeInt32(0, true);
                writer.writeByte((byte)RANGE_ARRAY_STRING);
            }
            return;
        }
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            writer.writeByte((byte)RANGE_ARRAY_STRING);
            writer.writeInt32(len);
            for (int i=0;i<len;i++) {
                if (null == ss[i]) {
                    writer.writeInt32(-1);
                } else {
                    writer.writeString(ss[i]);
                }
            }
        } else {
            for (int i=len-1;i>=0;i--) {
                if (null == ss[i]) {
                    writer.writeInt32(-1);
                } else {
                    writer.writeString(ss[i]);
                }
            }
            writer.writeInt32(len);
            writer.writeByte((byte)RANGE_ARRAY_STRING);
        }
    }
}
