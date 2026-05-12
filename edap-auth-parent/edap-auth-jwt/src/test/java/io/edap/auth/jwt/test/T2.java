package io.edap.auth.jwt.test;

public class T2 {

    private static final byte[] BASE64URL_BYTES = new byte[]{
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_'
    };

    private static final int[] BASE64URL_VALUES = new int[128];

    static {
        for (int i = 0; i < BASE64URL_VALUES.length; i++) {
            BASE64URL_VALUES[i] = -1;
        }
        for (int i=0;i<BASE64URL_BYTES.length;i++) {
            BASE64URL_VALUES[BASE64URL_BYTES[i]] = i;
        }
    }

    public static void main(String[] args) {
//        for (int i=0;i<BASE64URL_BYTES.length;i++) {
//            System.out.println(i + ":" + BASE64URL_BYTES[i]);
//        }i

        System.out.println("0x3f=" + Integer.parseInt("3f", 16));
        for (int i=0;i<128;i++) {
            System.out.println("[" + (char)i + "] = " + BASE64URL_VALUES[i]);
        }
    }
}
