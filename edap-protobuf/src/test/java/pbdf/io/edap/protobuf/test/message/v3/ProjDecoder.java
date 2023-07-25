//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbdf.io.edap.protobuf.test.message.v3;

import io.edap.protobuf.AbstractDecoder;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.test.message.v3.Proj;

public class ProjDecoder extends AbstractDecoder implements ProtoBufDecoder<Proj> {
    public ProjDecoder() {
    }

    private Proj doDecode(ProtoBufReader var1, int var2) throws ProtoBufException {
        Proj var3 = new Proj();
        boolean var4 = false;

        while(true) {
            while(!var4) {
                int var5 = var1.readTag();
                switch (var5) {
                    case 0:
                        var4 = true;
                        break;
                    case 8:
                        var3.setId(var1.readInt64());
                        break;
                    case 23:
                        var3.setName(var1.readString());
                        break;
                    case 31:
                        var3.setRepoPath(var1.readString());
                        break;
                    default:
                        if (var2 > 0 && var2 == var5) {
                            var4 = true;
                        } else {
                            var1.skipField(var5, var5);
                        }
                }
            }

            if (var3.getName() == null) {
                var3.setName("");
            }

            if (var3.getRepoPath() == null) {
                var3.setRepoPath("");
            }

            return var3;
        }
    }

    public Proj decode(ProtoBufReader var1) throws ProtoBufException {
        return this.doDecode(var1, 0);
    }

    public Proj decode(ProtoBufReader var1, int var2) throws ProtoBufException {
        return this.doDecode(var1, var2);
    }
}
