package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ProtoReader;
import io.edap.protobuf.ProtoWriter;
import io.edap.protobuf.ext.ExtCodec;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.edap.protobuf.ext.AnyCodec.RANGE_LINKED_HASHMAP;
import static io.edap.protobuf.ext.AnyCodec.RANGE_NULL;

public class LinkedHashMapCodec implements ExtCodec<LinkedHashMap<Object, Object>>  {
    @Override
    public LinkedHashMap decode(ProtoReader reader) throws ProtoException {
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
    public boolean skip(ProtoReader reader) throws ProtoException {
        int len = reader.readInt32();
        for (int i=0;i<len;i++) {
            reader.readObject();
            reader.readObject();
        }
        return true;
    }

    @Override
    public void encode(ProtoWriter writer, LinkedHashMap<Object, Object> map) throws EncodeException {
        writer.writeByte((byte)(RANGE_LINKED_HASHMAP));
        writer.writeInt32(map.size(), true);
        for (Map.Entry entry : map.entrySet()) {
            writer.writeObject(entry.getKey());
            writer.writeObject(entry.getValue());
        }
    }
}
