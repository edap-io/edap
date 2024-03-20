import io.edap.eproto.EprotoCodecRegister;
import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.test.message.Project;

import static io.edap.eproto.EprotoWriter.encodeZigZag32;

public class T {

    public static void main(String[] args) {

        EprotoEncoder<Project> projectEnooder = EprotoCodecRegister.instance().getEncoder(Project.class);
    }
}
