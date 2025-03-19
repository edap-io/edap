//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package io.edap.protobuf.mapdecoder;

import io.edap.protobuf.MapEntryDecoder;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.protobuf.wire.WireFormat;

import java.util.Map;

import static io.edap.protobuf.wire.WireFormat.getTagFieldNumber;

public class MapEntryDecoder_2 implements MapEntryDecoder<String, Byte> {
    private static final ProtoBufOption PROTO_BUF_OPTION = new ProtoBufOption();
    private ProtoBufDecoder<Project> valueDecoder;

    public MapEntryDecoder_2() {
    }

    public void decode(ProtoBufReader reader, Map<String, Byte> var2) throws ProtoException {
        int len = reader.readUInt32();
        int oldPos = reader.getPos();
        int var3 = WireFormat.getTagFieldNumber(reader.readTag());
        String var4;
        if (var3 == 1) {
            var4 = reader.readString();
        } else {
            var4 = "";
        }
        if (var3 == 2) {
            var2.put(var4, (byte)reader.readInt32());
        } else if (reader.getPos() - oldPos < len) {
            reader.readTag();
            var2.put(var4, (byte)reader.readInt32());
        } else {
            var2.put(var4, (byte)0);
        }
    }

    private ProtoBufDecoder<Project> getValueDecoder() {
        if (this.valueDecoder == null) {
            this.valueDecoder = ProtoBufCodecRegister.INSTANCE.getDecoder(Project.class, PROTO_BUF_OPTION);
        }

        return this.valueDecoder;
    }
}
