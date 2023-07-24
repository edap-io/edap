//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbef.io.edap.protobuf.test.message.v3;

import io.edap.protobuf.*;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.ListInt32;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.Field.Type;
import io.edap.protobuf.wire.Syntax;

public class ListInt32Encoder2 extends AbstractEncoder implements ProtoBufEncoder<ListInt32> {
    private static final byte[] tag1;

    static {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        tag1 = ProtoUtil.buildFieldData(1, Type.INT32, Cardinality.REPEATED, Syntax.PROTO_3, option);
    }

    public ListInt32Encoder2() {
    }

    public void encode(ProtoBufWriter var1, ListInt32 var2) throws EncodeException {
        try {
            var1.writePackedInts(tag1, var2.list, Type.INT32);
        } catch (Exception var4) {
            throw new EncodeException(var4);
        }
    }
}
