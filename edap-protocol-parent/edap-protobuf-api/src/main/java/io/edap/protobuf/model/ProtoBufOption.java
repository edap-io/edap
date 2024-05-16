package io.edap.protobuf.model;


import io.edap.protobuf.CodecType;

public class ProtoBufOption {

    private CodecType codecType;

    public CodecType getCodecType() {
        return codecType;
    }

    public void setCodecType(CodecType codecType) {
        this.codecType = codecType;
    }
}
