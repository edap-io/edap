package io.edap.eproto.test;

public class T {
    public static void main(String[] args) {
        byte b = '\0';
        System.out.println(Integer.toBinaryString(b));

        b = '0';
        System.out.println(Integer.toBinaryString(b));

        b = (byte)0;
        System.out.println(Integer.toBinaryString(b));
    }
}
