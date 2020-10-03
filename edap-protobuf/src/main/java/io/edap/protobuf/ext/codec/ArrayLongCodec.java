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

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_LONG;

/**
 * long数组的编解码器
 */
public class ArrayLongCodec implements ExtCodec<long[]> {

    @Override
    public long[] decode(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        if (len == 0) {
            return new long[0];
        }
        long[] vs = new long[len];
        for (int i=0;i<len;i++) {
            vs[i] = reader.readInt64();
        }
        return vs;
    }

    @Override
    public void encode(ProtoBufWriter writer, long[] ints) throws EncodeException {
        int len = ints.length;
        if (len == 0) {
            if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
                writer.writeByte((byte)RANGE_ARRAY_LONG);
                writer.writeInt32(0, true);
            } else {
                writer.writeInt32(0, true);
                writer.writeByte((byte)RANGE_ARRAY_LONG);
            }
            return;
        }
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            writer.writeByte((byte)RANGE_ARRAY_LONG);
            writer.writeInt32(len);
            for (int i=0;i<len;i++) {
                writer.writeUInt64(ints[i]);
            }
        } else {
            for (int i=len-1;i>=0;i--) {
                writer.writeUInt64(ints[i]);
            }
            writer.writeInt32(len);
            writer.writeByte((byte)RANGE_ARRAY_LONG);
        }

    }
}
