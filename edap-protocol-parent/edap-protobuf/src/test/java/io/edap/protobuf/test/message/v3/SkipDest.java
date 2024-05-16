package io.edap.protobuf.test.message.v3;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;

/**
 * 测试跳过属性目标message
 */
public class SkipDest {

    @ProtoField(tag = 19, type = Field.Type.STRING)
    public String field19;
}
