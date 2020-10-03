package io.edap.protobuf.util;

import io.edap.protobuf.ProtoBuf;

import java.util.Comparator;

/**
 * Protobuf属性的信息排序的排序器
 * @author : luysh@yonyou.com
 * @date : 2020/5/10
 */
public class ProtoTagComparator implements Comparator<ProtoBuf.ProtoFieldInfo> {
    @Override
    public int compare(ProtoBuf.ProtoFieldInfo f1, ProtoBuf.ProtoFieldInfo f2) {
        if (f1.protoField.tag() > f2.protoField.tag()) {
            return 1;
        } else if (f1.protoField.tag() < f2.protoField.tag()) {
            return -1;
        }
        return 0;
    }
}
