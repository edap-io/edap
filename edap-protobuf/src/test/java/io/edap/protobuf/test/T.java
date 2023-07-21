package io.edap.protobuf.test;

import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;

import static io.edap.protobuf.wire.WireFormat.makeTag;

public class T {
    public static void main(String[] args) {
        System.out.println(Integer.parseInt("b", 16));
        System.out.println(Integer.parseInt("c", 16));
        System.out.println(makeTag(1, WireType.START_GROUP));
        System.out.println(makeTag(1, WireType.END_GROUP));
    }
}
