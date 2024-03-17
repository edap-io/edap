import static io.edap.eproto.EprotoWriter.encodeZigZag32;

public class T {

    public static void main(String[] args) {

        System.out.println(encodeZigZag32(-1));
        int v = 5 + (9 << 2);
        System.out.println("v=" + v);
    }
}
