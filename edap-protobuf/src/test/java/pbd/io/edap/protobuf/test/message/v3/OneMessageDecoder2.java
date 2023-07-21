//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbd.io.edap.protobuf.test.message.v3;

import io.edap.protobuf.AbstractDecoder;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.test.message.v3.OneMessage;
import io.edap.protobuf.test.message.v3.Proj;

public class OneMessageDecoder2 extends AbstractDecoder implements ProtoBufDecoder<OneMessage> {
    private ProtoBufDecoder<Proj> DecoderProj;

    public OneMessageDecoder2() {
        this.DecoderProj = ProtoBufCodecRegister.INSTANCE.getDecoder(Proj.class);
    }

    private OneMessage doDecode(ProtoBufReader var1, int var2) throws ProtoBufException {
        OneMessage msg = new OneMessage();
        boolean var3 = false;

        while(var3 == false) {
            int var4 = var1.readTag();
            switch (var4) {
                case 0:
                    var3 = true;
                    break;
                case 10:
                    msg.setProj((Proj)var1.readMessage(this.DecoderProj));
                    break;
                default:
                    var1.skipField(var4, var4);
            }
        }

        return msg;
    }

    public OneMessage decode(ProtoBufReader var1) throws ProtoBufException {
        return this.doDecode(var1, 0);
    }
}
