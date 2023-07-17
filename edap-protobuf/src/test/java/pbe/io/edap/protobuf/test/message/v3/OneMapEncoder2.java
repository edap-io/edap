//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbe.io.edap.protobuf.test.message.v3;

import io.edap.protobuf.AbstractEncoder;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.mapencoder.MapEncoder_539ff74a030cda56cde7c7e4bfabf767;
import io.edap.protobuf.test.message.v3.OneMap;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.Field.Type;
import java.util.Iterator;
import java.util.Map;

public class OneMapEncoder2 extends AbstractEncoder implements ProtoBufEncoder<OneMap> {
    private static final byte[] tag1;
    private ProtoBufEncoder<Project> encoderProject;
    private ProtoBufEncoder<MapEncoder_539ff74a030cda56cde7c7e4bfabf767> encoderMapMapEncoder_539ff74a030cda56cde7c7e4bfabf767;

    static {
        tag1 = ProtoUtil.buildFieldData(1, Type.MAP, Cardinality.OPTIONAL);
    }

    public OneMapEncoder2() {
        // $FF: Couldn't be decompiled
    }

    private ProtoBufEncoder<Project> getEncoderProject() {
        if (this.encoderProject == null) {
            this.encoderProject = ProtoBufCodecRegister.INSTANCE.getEncoder(Project.class, this.getProtoBufOption());
        }

        return this.encoderProject;
    }

    public void encode(ProtoBufWriter var1, OneMap var2) throws EncodeException {
        try {
            this.writeMap_0(var1, 1, var2.getValue());
        } catch (Exception var4) {
            throw new EncodeException(var4);
        }
    }

    private void writeMap_0(ProtoBufWriter var1, int var2, Map var3) throws EncodeException {
        if (var3 != null && !var3.isEmpty()) {
            MapEncoder_539ff74a030cda56cde7c7e4bfabf767 var4 = new MapEncoder_539ff74a030cda56cde7c7e4bfabf767();
            ProtoBufEncoder var5 = ProtoBufCodecRegister.INSTANCE.getEncoder(MapEncoder_539ff74a030cda56cde7c7e4bfabf767.class, this.getProtoBufOption());
            Iterator var6 = var3.entrySet().iterator();

            while(var6.hasNext()) {
                Map.Entry var7 = (Map.Entry)var6.next();
                var4.key = (String)var7.getKey();
                var4.value = (Project)var7.getValue();
                var1.writeMessage(tag1, var2, var4, var5);
            }

        }
    }
}
