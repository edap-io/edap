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
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ProtoReader;
import io.edap.protobuf.ProtoWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAY_OBJECT;

public class ArrayObjectCodec implements ExtCodec<Object[]> {

    private final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    @Override
    public Object[] decode(ProtoReader reader) throws ProtoException {
        int len = reader.readInt32();
        if (len == 0) {
            return EMPTY_OBJECT_ARRAY;
        }
        Object[] vs = new Object[len];
        for (int i=0;i<len;i++) {
            vs[i] = reader.readObject();
        }
        return vs;
    }

    @Override
    public boolean skip(ProtoReader reader) throws ProtoException {
        int len = reader.readInt32();
        for (int i=0;i<len;i++) {
            reader.readObject();
        }
        return true;
    }

    @Override
    public void encode(ProtoWriter writer, Object[] vs) throws EncodeException {
        int len = vs.length;
        if (len == 0) {
            writer.writeByte((byte)RANGE_ARRAY_OBJECT);
            writer.writeInt32(0, true);
            return;
        }
        writer.writeByte((byte)RANGE_ARRAY_OBJECT);
        writer.writeInt32(len);
        for (int i=0;i<len;i++) {
            writer.writeObject(vs[i]);
        }
    }
}
