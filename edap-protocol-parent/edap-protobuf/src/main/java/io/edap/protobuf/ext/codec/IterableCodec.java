package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.ExtCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static io.edap.protobuf.ext.AnyCodec.RANGE_ITERABLE;

public class IterableCodec<T extends Iterable> implements ExtCodec<T> {
    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        String type = reader.readString();
        int size = reader.readInt32();
        List list = new ArrayList<>(size);
        for (int i=0;i<size;i++) {
            reader.readObject();
        }
        return true;
    }

    @Override
    public T decode(ProtoBufReader reader) throws ProtoException {
        String type = reader.readString();
        int size = reader.readInt32();
        List list = new ArrayList<>(size);
        for (int i=0;i<size;i++) {
            list.add(reader.readObject());
        }
        return (T)list;
    }

    @Override
    public void encode(ProtoBufWriter writer, T iterable) throws EncodeException {
        writer.writeByte((byte)RANGE_ITERABLE);
        writer.writeString(iterable.getClass().getName());
        if (iterable instanceof Collection) {
            Collection coll = (Collection)iterable;
            writer.writeInt32(coll.size(), true);
            Iterator itr = coll.iterator();
            while (itr.hasNext()) {
                writer.writeObject(itr.next());
            }
        } else {
            Iterable itr = (Iterable)iterable;
            writer.writeObjects(itr.iterator());
        }
    }
}
