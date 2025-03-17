package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_SHORT;

public class ShortCodec implements ExtCodec<Short> {

    @Override
    public Short decode(ProtoBufReader reader) throws ProtoException {
        return (short)reader.readUInt32();
    }

    @Override
    public boolean skip(ProtoBufReader reader) throws ProtoException {
        reader.readUInt32();
        return true;
    }

    @Override
    public void encode(ProtoBufWriter writer, Short v) throws EncodeException {
        writer.writeByte((byte)RANGE_SHORT);
        writer.writeUInt32(v);
    }
}
