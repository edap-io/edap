import io.edap.eproto.EprotoCodecRegister;
import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.EprotoWriter;
import io.edap.eproto.test.message.AllType;
import io.edap.eproto.writer.ByteArrayWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.internal.ProtoBufOut;

public class T {

    public static void main(String[] args) {

        EprotoEncoder<AllType> projectEnooder = EprotoCodecRegister.instance().getEncoder(AllType.class);

        long v = (1L << 35);
        System.out.println("v=" + v);
        System.out.println("v=" + Long.toBinaryString(v));
        System.out.println("v=" + Long.toBinaryString(v-1));

        BufOut out    = new ProtoBufOut();
        EprotoWriter writer = new ByteArrayWriter(out);
        writer.reset();
        writer.writeLong(34359738368L);
        System.out.println("size1:" + writer.size());
        writer.reset();
        writer.writeLong(34359738367L);
        System.out.println("size2:" + writer.size());
        System.out.println("size2:" + Long.toHexString(34359738367L - 1));
        System.out.println("0x7=" + Long.parseLong("7F", 16));
        System.out.println("34359738367L:" + (34359738367L & ~0x7fffffffFL));
        System.out.println("34359738366L:" + (34359738366L & ~0x7fffffffFL));
        System.out.println("34359738368L:" + (34359738368L & ~0x7fffffffFL));
    }
}
