import io.edap.eproto.EprotoCodecRegister;
import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.test.message.AllType;
import io.edap.eproto.test.message.Project;

import static io.edap.eproto.EprotoWriter.encodeZigZag32;

public class T {

    public static void main(String[] args) {

        EprotoEncoder<AllType> projectEnooder = EprotoCodecRegister.instance().getEncoder(AllType.class);
    }
}
