package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.ext.ExtCodec;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.Map;

import static io.edap.protobuf.ext.AnyCodec.RANGE_MAP;

public class MapCodec implements ExtCodec<Map<Object, Object>> {
    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        reader.readString();
        int size = reader.readInt32();
        for (int i=0;i<size;i++) {
            reader.readObject();
            reader.readObject();
        }
        return true;
    }

    @Override
    public Map decode(ProtoBufReader reader) throws ProtoException {
        String typeName = reader.readString();
        if ("java.util.Collections$EmptyMap".equals(typeName)) {
            return Collections.EMPTY_MAP;
        }
        int size = reader.readInt32();
        Map<Object, Object> map = makeMap(typeName);
        for (int i=0;i<size;i++) {
            map.put(reader.readObject(), reader.readObject());
        }
        return map;
    }

    @Override
    public void encode(ProtoBufWriter writer, Map<Object, Object> map) throws EncodeException {
        int size = map.size();
        writer.writeByte((byte)RANGE_MAP);
        writer.writeString(map.getClass().getName());
        writer.writeInt32(size, true);
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            AnyCodec.encode(writer, entry.getKey());
            AnyCodec.encode(writer, entry.getValue());
        }
    }

    private Map<Object, Object> makeMap(String typeName) {
        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(typeName);
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (int i=0;i<constructors.length;i++) {
                Constructor constructor = constructors[i];
                if (constructor.getParameterCount() == 0) {
                    return (Map<Object, Object>) constructor.newInstance();
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Cann't instance " + typeName);
        }

        throw new RuntimeException(typeName + " has't default Constructor");
    }
}
