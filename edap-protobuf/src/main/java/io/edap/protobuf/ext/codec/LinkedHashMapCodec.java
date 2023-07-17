package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.ExtCodec;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.edap.protobuf.ext.AnyCodec.RANGE_LINKED_HASHMAP;

public class LinkedHashMapCodec implements ExtCodec<LinkedHashMap<Object, Object>>  {
    @Override
    public LinkedHashMap decode(ProtoBufReader reader) throws ProtoBufException {
        int len = reader.readInt32();
        LinkedHashMap map = new LinkedHashMap();
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
    public void encode(ProtoBufWriter writer, LinkedHashMap<Object, Object> map) throws EncodeException {
        writer.writeByte((byte)(RANGE_LINKED_HASHMAP));
        for (Map.Entry entry : map.entrySet()) {
            writer.writeObject(entry.getKey());
            writer.writeObject(entry.getValue());
        }
    }
}
