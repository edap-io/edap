package io.edap.protobuf;

/**
     * ProtoBuf的编解码器类型，标准的和google官方的兼容。Fast则才有优化的编解码方式。
     */
    public enum CodecType {
        STANDARD,
        FAST;
    }