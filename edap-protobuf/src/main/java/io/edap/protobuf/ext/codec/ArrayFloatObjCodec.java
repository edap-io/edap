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

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_FLOAT_OBJ;

/**
 * Float数组的编解码器
 */
public class ArrayFloatObjCodec implements ExtCodec<Float[]> {

    @Override
    public Float[] decode(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        Float[] vs = new Float[len];
        for (int i=0;i<len;i++) {
            boolean isNull = reader.readBool();
            if (!isNull) {
                vs[i] = reader.readFloat();
            } else {
                vs[i] = null;
            }
        }
        return vs;
    }

    @Override
    public void encode(ProtoBufWriter writer, Float[] floats) throws EncodeException {
        int len = floats.length;
        if (len == 0) {
            writer.writeByte((byte)RANGE_ARRAY_FLOAT_OBJ);
            writer.writeInt32(0, true);
            return;
        }
        writer.writeByte((byte)RANGE_ARRAY_FLOAT_OBJ);
        writer.writeInt32(len);
        for (int i=0;i<len;i++) {
            writer.writeInt32(floats[i]==null?1:0, true);
            if (null != floats[i]) {
                writer.writeFixed32(Float.floatToRawIntBits(floats[i]));
            }
        }
    }
}