/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.ExtCodec;

import java.util.HashSet;

import static io.edap.protobuf.ext.AnyCodec.RANGE_HASH_SET;

public class HashSetCodec implements ExtCodec<HashSet<Object>> {
    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        int len = reader.readInt32();
        for (int i=0;i<len;i++) {
            reader.readObject();
        }
        return true;
    }

    @Override
    public HashSet<Object> decode(ProtoBufReader reader) throws ProtoException {
        int len = reader.readInt32();
        HashSet set = new HashSet();
        for (int i=0;i<len;i++) {
            set.add(reader.readObject());
        }

        return set;
    }

    @Override
    public void encode(ProtoBufWriter writer, HashSet<Object> objects) throws EncodeException {
        writer.writeByte((byte)RANGE_HASH_SET);
        writer.writeInt32(objects.size(), true);
        for (Object o : objects) {
            writer.writeObject(o);
        }
    }
}
