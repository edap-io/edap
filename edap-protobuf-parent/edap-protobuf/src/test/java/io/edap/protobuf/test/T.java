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

        String s = "{\"gtx_id\":\"20240229134046@6c191449-e030-44a9-9a12-c82210d6496e\",\"message\":\"java.lang.RuntimeException: java.lang.RuntimeException: com.yonyou.cloud.yts.YtsTransactionException: iuap-ymsc-yts->yts-mdd-stock调用后异常\"}";

    }
}
