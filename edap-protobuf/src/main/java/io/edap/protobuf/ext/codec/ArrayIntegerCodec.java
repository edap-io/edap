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

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_INTEGER;

/**
 * Integer数组的编解码器，编码时按sint的方式编码，如果为null则写入4294967296L(0x80,0x80,0x80,0x80,0x10)
 * 比int最大值还大的一个值。解码时直接使用readSInt64,如果等于2147483648 则为Integer的null
 */
public class ArrayIntegerCodec implements ExtCodec<Integer[]> {

    @Override
    public Integer[] decode(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        if (len == 0) {
            return new Integer[0];
        }
        Integer[] is = new Integer[len];
        for (int i=0;i<len;i++) {
            long v = reader.readSInt64();
            if (v == 2147483648L) {
                is[i] = null;
            } else {
                is[i] = new Integer((int)v);
            }
        }
        return is;
    }

    @Override
    public void encode(ProtoBufWriter writer, Integer[] integers) throws EncodeException {

        int len = integers.length;
        if (len == 0) {
            if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
                writer.writeByte((byte)RANGE_ARRAY_INTEGER);
                writer.writeInt32(0, true);
            } else {
                writer.writeInt32(0, true);
                writer.writeByte((byte)RANGE_ARRAY_INTEGER);
            }
            return;
        }
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            writer.writeByte((byte)RANGE_ARRAY_INTEGER);
            writer.writeInt32(len);
            for (int i=0;i<len;i++) {
                if (null == integers[i]) {
                    writer.writeUInt64(4294967296L);
                } else {
                    writer.writeInt32(encodeZigZag32(integers[i]), true);
                }
            }
        } else {
            for (int i=len-1;i>=0;i--) {
                if (null == integers[i]) {
                    writer.writeUInt64(4294967296L);
                } else {
                    writer.writeInt32(encodeZigZag32(integers[i]), true);
                }
            }
            writer.writeInt32(len);
            writer.writeByte((byte)RANGE_ARRAY_INTEGER);
        }
    }
}