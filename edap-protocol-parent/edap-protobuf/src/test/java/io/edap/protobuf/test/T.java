package io.edap.protobuf.test;

import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;

import static io.edap.protobuf.wire.WireFormat.makeTag;

public class T {
    public static void main(String[] args) {
        System.out.println(Integer.parseInt("b", 16));
        System.out.println(Integer.parseInt("c", 16));
        System.out.println(makeTag(10, WireType.START_GROUP));
        System.out.println(makeTag(1, WireType.OBJECT));
        System.out.println(Integer.toHexString(makeTag(1, WireType.OBJECT)));

        System.out.println(Integer.parseInt("800", 16));
        System.out.println(Integer.toHexString(2047));

        for (int i=0;i<256;i++) {
            System.out.println((i < 0x80) + "\t" + ((i & ~0x7F) == 0));
        }


    }
}
