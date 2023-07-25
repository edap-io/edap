//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbdf.io.edap.protobuf.test.message.ext;

import io.edap.protobuf.AbstractDecoder;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.test.message.ext.FieldObject;

public class FieldObjectDecoder extends AbstractDecoder implements ProtoBufDecoder<FieldObject> {
    public FieldObjectDecoder() {
    }

    private FieldObject doDecode(ProtoBufReader var1, int var2) throws ProtoBufException {
        FieldObject var3 = new FieldObject();
        boolean var4 = false;

        while(true) {
            while(!var4) {
                int var5 = var1.readTag();
                switch (var5) {
                    case 0:
                        var4 = true;
                        break;
                    case 14:
                        var3.setObj((Object)var1.readObject());
                        break;
                    default:
                        if (var2 > 0 && var2 == var5) {
                            var4 = true;
                        } else {
                            var1.skipField(var5, var5);
                        }
                }
            }

            return var3;
        }
    }

    public FieldObject decode(ProtoBufReader var1) throws ProtoBufException {
        return this.doDecode(var1, 0);
    }

    public FieldObject decode(ProtoBufReader var1, int var2) throws ProtoBufException {
        return this.doDecode(var1, var2);
    }
}
