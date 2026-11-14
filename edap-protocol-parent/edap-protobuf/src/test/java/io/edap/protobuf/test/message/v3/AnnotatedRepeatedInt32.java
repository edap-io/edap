/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.edap.protobuf.test.message.v3;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;

import java.util.List;

/**
 * <b>故意不写 {@code cardinality}</b> 的 repeated int32 —— 复现 {@code JavaBuilder} 生成的 POJO 形状。
 *
 * <p>{@code JavaBuilder} 生成 {@code @ProtoField} 时从来��输出 {@code cardinality}
 * （见 {@code edap-protobuf-wire/.../builder/JavaBuilder.java}），
 * 而 {@code ProtoField.cardinality()} 的默认值是 {@code OPTIONAL}
 * ⇒ 运行时会按「单个 varint」而不是「packed」来算 tag ⇒ wire 格式与 protobuf 不一致。</p>
 */
public class AnnotatedRepeatedInt32 {

    @ProtoField(tag = 1, type = Field.Type.INT32)
    public List<Integer> value;
}
