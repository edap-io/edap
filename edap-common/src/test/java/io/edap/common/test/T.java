package io.edap.common.test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class T {

    public static void main(String[] args) throws NoSuchMethodException {
        Constructor cs = String.class.getDeclaredConstructor(byte[].class, byte.class);
        System.out.println(cs);
        cs.setAccessible(true);
    }
}
