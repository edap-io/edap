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
import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_BOOL_OBJ;

/**
 * Boolean数组的编解码器
 */
public class ArrayBoolObjCodec implements ExtCodec<Boolean[]> {

    static final Boolean[] EMPTY_BOOLEAN_ARRAYT = new Boolean[0];

    @Override
    public Boolean[] decode(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        if (len == 0) {
            return EMPTY_BOOLEAN_ARRAYT;
        }
        Boolean[] vs = new Boolean[len];
        for (int i=0;i<len;i++) {
            int v = reader.readSInt32();
            if (v == -1) {
                vs[i] = null;
            } else {
                vs[i] = v==1?true:false;
            }
        }
        return vs;
    }

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        for (int i=0;i<len;i++) {
            reader.readInt32();
        }
        return true;
    }

    @Override
    public void encode(ProtoBufWriter writer, Boolean[] booleans) throws EncodeException {
        int len = booleans.length;
        if (len == 0) {
            writer.writeByte((byte)RANGE_ARRAY_BOOL_OBJ);
            writer.writeInt32(0, true);
            return;
        }
        writer.writeByte((byte)RANGE_ARRAY_BOOL_OBJ);
        writer.writeInt32(len);
        for (int i=0;i<len;i++) {
            if (booleans[i] == null) {
                writer.writeUInt32(encodeZigZag32(-1));
            } else {
                writer.writeUInt32(encodeZigZag32(booleans[i] ? 1 : 0));
            }
        }
    }

}

