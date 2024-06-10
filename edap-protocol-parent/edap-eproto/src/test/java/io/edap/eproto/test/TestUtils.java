package io.edap.eproto.test;

import java.util.Random;

public class TestUtils {

    public static String randomLatin1(int count) {
        int max = 128;
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

    public static String randomUtf8(int count) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        int max = Character.MAX_VALUE;
        for (int i=0;i<count;i++) {
            String s;
            char c;
            while (true) {
                c = (char)random.nextInt(max);
                if (c < 128) {

                } else if (c < 0x800) {

                } else if (c >= '\ud800' && c <= '\udfff') {
                } else {
                    break;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
