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

/**
 * 内嵌消息 —— 一个字符串 + 一个 int。
 *
 * <p>内层字符串的长度决定嵌套载荷落在长度前缀 varint 的哪一档：
 * &lt;128B ⇒ 前缀 1 字节；128..16383B ⇒ 前缀 2 字节；&gt;16383B ⇒ 前缀 3 字节。</p>
 */
public class BenchInner {

    @ProtoField(tag = 1, type = Type.STRING)
    private String s;

    @ProtoField(tag = 2, type = Type.INT32)
    private int v;

    public String getS() {
        return s;
    }

    public void setS(String s) {
        this.s = s;
    }

    public int getV() {
        return v;
    }

    public void setV(int v) {
        this.v = v;
    }
}
