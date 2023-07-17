//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbe.io.edap.protobuf.test.message.v3;

import io.edap.protobuf.*;
import io.edap.protobuf.test.message.v3.OneMessage;
import io.edap.protobuf.test.message.v3.Proj;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.Field.Type;

public class OneMessageEncoder2 extends AbstractEncoder implements ProtoBufEncoder<OneMessage> {
    private static final byte[] tag1;
    private ProtoBufEncoder<Proj> encoderProj;

    static {
        tag1 = ProtoUtil.buildFieldData(1, Type.MESSAGE, Cardinality.OPTIONAL);
    }

    public OneMessageEncoder2() {
    }

    private ProtoBufEncoder<Proj> getEncoderProj() {
        if (encoderProj == null) {
            encoderProj = ProtoBufCodecRegister.INSTANCE.getEncoder(Proj.class, this.getProtoBufOption());
        }
        return encoderProj;
    }

    public void encode(ProtoBufWriter var1, OneMessage var2) throws EncodeException {
        try {
            var1.writeMessage(tag1, 1, var2.getProj(), this.getEncoderProj());
        } catch (Exception var4) {
            throw new EncodeException(var4);
        }
    }
}
