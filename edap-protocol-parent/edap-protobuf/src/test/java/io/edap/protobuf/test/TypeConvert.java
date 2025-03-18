package io.edap.protobuf.test;

public class TypeConvert {

    public static void main(String[] args) {
        int v = 3;
        Byte byteObj      = Byte.valueOf((byte)v);
        Character charObj = Character.valueOf((char)v);
        Short shorObj     = Short.valueOf((short)v);
    }
}
