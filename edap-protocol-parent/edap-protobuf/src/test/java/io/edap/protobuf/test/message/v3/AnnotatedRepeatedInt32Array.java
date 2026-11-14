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

/**
 * 故意不写 {@code cardinality} 的 repeated int32（<b>数组</b>形态，不是 List）。
 *
 * @see AnnotatedRepeatedInt32
 */
public class AnnotatedRepeatedInt32Array {

    @ProtoField(tag = 1, type = Field.Type.INT32)
    public int[] value;
}
