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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.protobuf.ext.AnyCodec.*;

public class ConcurrentHashMapCodec implements ExtCodec<ConcurrentHashMap<Object, Object>> {

    @Override
    public ConcurrentHashMap decode(ProtoBufReader reader) throws ProtoException {
        //String mapName = reader.readString();
        int len = reader.readInt32();
        ConcurrentHashMap map = new ConcurrentHashMap(len);
        for (int i=0;i<len;i++) {
            map.put(reader.readObject(), reader.readObject());
        }
        return map;
    }

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        int len = reader.readInt32();
        for (int i=0;i<len;i++) {
            reader.readObject();
            reader.readObject();
        }
        return true;
    }

    @Override
    public void encode(ProtoBufWriter writer, ConcurrentHashMap<Object, Object> map) throws EncodeException {
        writer.writeByte((byte)RANGE_CONCURRENT_HASHMAP);
        writer.writeInt32(map.size(), true);
        for (Map.Entry entry : map.entrySet()) {
            writeMapEntry(writer, entry);
        }
    }

    private void writeMapEntry(ProtoBufWriter writer, Map.Entry entry) throws EncodeException {
        writer.writeObject(entry.getKey());
        writer.writeObject(entry.getValue());
    }
}
