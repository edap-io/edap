//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbdf.io.edap.protobuf.test.message.v3;

import io.edap.protobuf.AbstractDecoder;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBuf.CodecType;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.ListMessage;
import io.edap.protobuf.test.message.v3.Proj;
import java.util.ArrayList;

public class ListMessageDecoder extends AbstractDecoder implements ProtoBufDecoder<ListMessage> {
    private ProtoBufDecoder<Proj> DecoderProj;

    public ListMessageDecoder() {
        ProtoBufOption var1 = new ProtoBufOption();
        var1.setCodecType(CodecType.FAST);
        this.DecoderProj = ProtoBufCodecRegister.INSTANCE.getDecoder(Proj.class, var1);
    }

    private ListMessage doDecode(ProtoBufReader var1, int var2) throws ProtoBufException {
        ListMessage var3 = new ListMessage();
        ArrayList var4 = new ArrayList(16);
        boolean var6 = false;

        while(true) {
            while(!var6) {
                int var7 = var1.readTag();
                switch (var7) {
                    case 0:
                        var6 = true;
                        break;
                    case 11:
                        var4.add((Proj)var1.readMessage(this.DecoderProj, 12));
                        break;
                    default:
                        if (var2 > 0 && var2 == var7) {
                            var6 = true;
                        } else {
                            var1.skipField(var7, var7);
                        }
                }
            }

            var3.list = var4;
            return var3;
        }
    }

    public ListMessage decode(ProtoBufReader var1) throws ProtoBufException {
        return this.doDecode(var1, 0);
    }

    public ListMessage decode(ProtoBufReader var1, int var2) throws ProtoBufException {
        return this.doDecode(var1, var2);
    }
}
