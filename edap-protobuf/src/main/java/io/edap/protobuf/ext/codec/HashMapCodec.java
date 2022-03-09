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

import java.util.HashMap;
import java.util.Map;

import static io.edap.protobuf.ext.AnyCodec.RANGE_HASHMAP_END;
import static io.edap.protobuf.ext.AnyCodec.RANGE_HASHMAP_START;

/**
 * Map的编解码器
 */
public class HashMapCodec implements ExtCodec<HashMap<Object, Object>> {

    private Integer size;

    public HashMapCodec() {

    }

    public HashMapCodec(Integer size) {
        this.size = size;
    }

    @Override
    public HashMap decode(ProtoBufReader reader) throws ProtoBufException {
        //String mapName = reader.readString();
        HashMap map = null;
        int len;
        if (null == size) {
            len =reader.readInt32();
        } else {
            len = size.intValue();
        }
        map = new HashMap(len);
        for (int i=0;i<len;i++) {
            //reader.readInt32();
            Object key = reader.readObject();
            //reader.readInt32();
            Object val = reader.readObject();
            map.put(key, val);
        }
        return map;
    }

    @Override
    public void encode(ProtoBufWriter writer, HashMap<Object, Object> map) throws EncodeException {
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            if (map.size() > RANGE_HASHMAP_END - RANGE_HASHMAP_START) {
                writer.writeByte((byte)RANGE_HASHMAP_END);
                writer.writeInt32(map.size(), true);
            } else {
                writer.writeByte((byte)(RANGE_HASHMAP_START + map.size()));
            }
            for (Map.Entry entry : map.entrySet()) {
                writeMapEntry(writer, entry, writer.getWriteOrder());
            }
        } else {
            for (Map.Entry entry : map.entrySet()) {
                writeMapEntry(writer, entry, writer.getWriteOrder());
            }
            if (map.size() > RANGE_HASHMAP_END - RANGE_HASHMAP_START) {
                writer.writeInt32(map.size(), true);
                writer.writeByte((byte)RANGE_HASHMAP_END);
            } else {
                writer.writeByte((byte)(RANGE_HASHMAP_START + map.size()));
            }
        }
    }

    private void writeMapEntry(ProtoBufWriter writer, Map.Entry entry, ProtoBufWriter.WriteOrder writeOrder) throws EncodeException {
        if (writeOrder == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            writer.writeObject(entry.getKey());
            writer.writeObject(entry.getValue());
        } else {
            writer.writeObject(entry.getValue());
            writer.writeObject(entry.getKey());
        }
    }
}
