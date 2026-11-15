/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.protobuf.benchmark;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field.Type;

import java.util.List;
import java.util.Map;

/**
 * 嵌套消息容器 —— 一次覆盖四条路径：
 * 单个内嵌消息、repeated 内嵌消息、packed int 列表、map&lt;string,string&gt;、bytes。
 *
 * <p>没有用到的字段保持 {@code null}（编码器对 null 字段直接跳过，解码器也不会给它建对象），
 * 这样每个负载只包含它真正要打的那条路径。</p>
 */
public class BenchNest {

    @ProtoField(tag = 1, type = Type.MESSAGE)
    private BenchInner inner;

    @ProtoField(tag = 2, type = Type.MESSAGE)
    private List<BenchInner> inners;

    @ProtoField(tag = 3, type = Type.INT32)
    private List<Integer> nums;

    @ProtoField(tag = 4, type = Type.MESSAGE)
    private Map<String, String> attrs;

    @ProtoField(tag = 5, type = Type.BYTES)
    private byte[] blob;

    public BenchInner getInner() {
        return inner;
    }

    public void setInner(BenchInner inner) {
        this.inner = inner;
    }

    public List<BenchInner> getInners() {
        return inners;
    }

    public void setInners(List<BenchInner> inners) {
        this.inners = inners;
    }

    public List<Integer> getNums() {
        return nums;
    }

    public void setNums(List<Integer> nums) {
        this.nums = nums;
    }

    public Map<String, String> getAttrs() {
        return attrs;
    }

    public void setAttrs(Map<String, String> attrs) {
        this.attrs = attrs;
    }

    public byte[] getBlob() {
        return blob;
    }

    public void setBlob(byte[] blob) {
        this.blob = blob;
    }
}
