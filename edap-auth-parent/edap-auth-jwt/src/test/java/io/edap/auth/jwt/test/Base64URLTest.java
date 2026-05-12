package io.edap.auth.jwt.test;

import io.edap.auth.jwt.utils.Base64URL;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Base64URLTest {

    @Test
    public void testEncode() {

        Base64.Encoder encoder = Base64.getUrlEncoder();
        Random random = new Random();
        byte[] bytes = new byte[4];
        int count = 0;
        for (int i=0;i<256;i++) {
            bytes[0] = (byte)i;
            for (int j=0;j<256;j++) {
                bytes[1] = (byte)j;
                for (int k=0;k<256;k++) {
                    bytes[2] = (byte)k;
                    bytes[3] = (byte)random.nextInt(Byte.MAX_VALUE);
                    count++;
                    String base64 = encoder.encodeToString(bytes);
                    int lastDeng = base64.indexOf('=');
                    if (lastDeng != -1) {
                        base64 = base64.substring(0, lastDeng);
                    }
                    assertEquals(base64, Base64URL.encode(bytes));
                    //System.out.println(base64);
                }
            }
        }

        bytes = new byte[5];
        count = 0;
        for (int i=0;i<256;i++) {
            bytes[0] = (byte)i;
            for (int j=0;j<256;j++) {
                bytes[1] = (byte)j;
                for (int k=0;k<256;k++) {
                    bytes[2] = (byte)k;
                    bytes[3] = (byte)random.nextInt(Byte.MAX_VALUE);
                    bytes[4] = (byte)random.nextInt(Byte.MAX_VALUE);
                    count++;
                    String base64 = encoder.encodeToString(bytes);
                    int lastDeng = base64.indexOf('=');
                    if (lastDeng != -1) {
                        base64 = base64.substring(0, lastDeng);
                    }
                    assertEquals(base64, Base64URL.encode(bytes));
                    //System.out.println(base64);
                }
            }
        }
        System.out.println("count = " + count);
    }

    @Test
    public void testEncode2() {
        byte[] bytes = new byte[3];
        Base64.Encoder encoder = Base64.getUrlEncoder();
        long start = System.nanoTime();

        for (int i=0;i<256;i++) {
            bytes[0] = (byte)i;
            for (int j=0;j<256;j++) {
                bytes[1] = (byte)j;
                for (int k=0;k<256;k++) {
                    bytes[2] = (byte)k;
                    Base64URL.encode(bytes);
                }
            }
        }
        long time1 = (System.nanoTime() - start);
        System.out.println("time1 = " + time1);
        start = System.nanoTime();
        for (int i=0;i<256;i++) {
            bytes[0] = (byte)i;
            for (int j=0;j<256;j++) {
                bytes[1] = (byte)j;
                for (int k=0;k<256;k++) {
                    bytes[2] = (byte)k;
                    encoder.encodeToString(bytes);
                    //System.out.println(base64);
                }
            }
        }
        long time2 = (System.nanoTime() - start);
        System.out.println("time2 = " + time2);

        start = System.nanoTime();

        for (int i=0;i<256;i++) {
            bytes[0] = (byte)i;
            for (int j=0;j<256;j++) {
                bytes[1] = (byte)j;
                for (int k=0;k<256;k++) {
                    bytes[2] = (byte)k;
                    Base64URL.encode(bytes);
                }
            }
        }
        time1 = (System.nanoTime() - start);
        System.out.println("time1 = " + time1);
        start = System.nanoTime();
        for (int i=0;i<256;i++) {
            bytes[0] = (byte)i;
            for (int j=0;j<256;j++) {
                bytes[1] = (byte)j;
                for (int k=0;k<256;k++) {
                    bytes[2] = (byte)k;
                    encoder.encodeToString(bytes);
                    //System.out.println(base64);
                }
            }
        }
        time2 = (System.nanoTime() - start);
        System.out.println("time2 = " + time2);
    }
}
