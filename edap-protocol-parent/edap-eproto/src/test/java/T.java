import io.edap.eproto.EprotoCodecRegister;
import io.edap.eproto.EprotoEncoder;
import io.edap.eproto.EprotoWriter;
import io.edap.eproto.test.message.AllType;
import io.edap.eproto.writer.ByteArrayWriter;
import io.edap.io.BufOut;
import io.edap.protobuf.internal.ProtoBufOut;

import java.util.Random;

import static io.edap.eproto.test.TestUtils.randomLatin1;
import static io.edap.eproto.test.TestUtils.randomUtf8;

public class T {

    public static void main(String[] args) {

        System.out.println("3 << 2=" + (3 << 2));

        int value = 1078529622;
        System.out.println( (byte) ((value      ) & 0xFF));
        System.out.println((byte) ((value >>  8) & 0xFF));
        System.out.println((byte) ((value >> 16) & 0xFF));
        System.out.println((byte) ((value >> 24) & 0xFF));

        System.out.println("Float.floatToRawIntBits(value)=" + Float.floatToRawIntBits(3.1415f));

        for (int i=0;i<5;i++) {
            System.out.println(randomLatin1(50));
        }

        for (int i=0;i<5;i++) {
            System.out.println(randomUtf8(50));
        }
    }

    public static String randomStr(int count) {
        int max = Byte.MAX_VALUE;
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<count;i++) {
            String s;
            while (true) {
                try {
                    s = new String(new byte[]{(byte)random.nextInt(max), (byte)random.nextInt(max)}, "utf-8");
                    break;
                } catch (Exception e) {

                }
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
