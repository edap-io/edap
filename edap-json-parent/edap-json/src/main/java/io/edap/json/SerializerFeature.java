package io.edap.json;

public enum SerializerFeature {
    /**
     * long类型序列化时值添加双引号，使前端按字符串处理
     */
    LONG_TO_STRING;

    public final int mask;

    SerializerFeature() {
        this.mask = 1 << ordinal();
    }

    public int getMask() {
        return mask;
    }
}
