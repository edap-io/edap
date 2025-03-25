package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.ExtCodec;

import java.lang.reflect.Constructor;
import java.util.List;

import static io.edap.protobuf.ext.AnyCodec.*;

public class ListCodec implements ExtCodec<List>  {

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        reader.readString();
        int size = reader.readInt32();
        for (int i=0;i<size;i++) {
            reader.readObject();
        }
        return false;
    }

    @Override
    public List decode(ProtoBufReader reader) throws ProtoException {
        List list = makeList(reader.readString());
        int size = reader.readInt32();
        for (int i=0;i<size;i++) {
            list.add(reader.readObject());
        }
        return list;
    }

    @Override
    public void encode(ProtoBufWriter writer, List list) throws EncodeException {
        int len = list.size();
        writer.writeByte((byte)RANGE_LIST);
        writer.writeString(list.getClass().getName());
        writer.writeInt32(len, true);
        for (int i=0;i<len;i++) {
            writer.writeObject(list.get(i));
        }
    }

    private List makeList(String typeName) {
        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(typeName);
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (int i=0;i<constructors.length;i++) {
                Constructor constructor = constructors[i];
                if (constructor.getParameterCount() == 0) {
                    return (List)constructor.newInstance();
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Cann't instance " + typeName);
        }

        throw new RuntimeException(typeName + " has't default Constructor");
    }

}
