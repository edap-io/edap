package io.edap.protobuf.test.message.v3;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;

public class SkipSrc {

    @ProtoField(tag = 19, type = Field.Type.STRING)
    public String field19;

    @ProtoField(tag = 20, type = Field.Type.MESSAGE)
    private SkipSrcInner skipInner;

    @ProtoField(tag = 17, type = Field.Type.OBJECT)
    private Object fieldObj;

    public SkipSrcInner getSkipInner() {
        return skipInner;
    }

    public void setSkipInner(SkipSrcInner skipInner) {
        this.skipInner = skipInner;
    }

    public Object getFieldObj() {
        return fieldObj;
    }

    public void setFieldObj(Object fieldObj) {
        this.fieldObj = fieldObj;
    }
}
