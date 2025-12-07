package io.edap.common.test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class T {

    public static void main(String[] args) {
        for (byte i=Byte.MIN_VALUE;i<Byte.MAX_VALUE;i++) {
            int v = (i & 0xff);
            System.out.println(v%16 + "=" + (v & 0x0f));
        }
    }
}
