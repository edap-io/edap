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
 * 纯字符串消息 —— 打字符串编解码路径，不掺杂别的字段。
 */
public class BenchStr {

    @ProtoField(tag = 1, type = Type.STRING)
    private String s;

    public String getS() {
        return s;
    }

    public void setS(String s) {
        this.s = s;
    }
}
