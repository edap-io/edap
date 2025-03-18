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

public class MapEntryDecoder_2 implements MapEntryDecoder<Long, Project> {
    private static final ProtoBufOption PROTO_BUF_OPTION = new ProtoBufOption();
    private ProtoBufDecoder<Project> valueDecoder;

    public MapEntryDecoder_2() {
    }

    public void decode(ProtoBufReader var1, Map<Long, Project> var2) throws ProtoException {
        var1.readUInt32();
        int var3 = var1.readTag();
        Long var4;
        if (WireFormat.getTagFieldNumber(var3) == 1) {
            var4 = var1.readInt64();
            var1.readTag();
        } else {
            var4 = 0L;
        }

        var2.put(var4, (Project)var1.readMessage(this.getValueDecoder()));
    }

    private ProtoBufDecoder<Project> getValueDecoder() {
        if (this.valueDecoder == null) {
            this.valueDecoder = ProtoBufCodecRegister.INSTANCE.getDecoder(Project.class, PROTO_BUF_OPTION);
        }

        return this.valueDecoder;
    }
}
