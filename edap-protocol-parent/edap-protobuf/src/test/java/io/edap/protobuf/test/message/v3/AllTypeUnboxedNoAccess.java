/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.test.message.v3;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;

import java.util.Map;

/**
 * 支持所有的类型没有装箱的类型，没有public的get和set方法
 */
public class AllTypeUnboxedNoAccess {

    @ProtoField(tag = 1, type = Field.Type.BOOL)
    private boolean field1;
    @ProtoField(tag = 2, type = Field.Type.BYTES)
    private byte[] field2;
    @ProtoField(tag = 3, type = Field.Type.DOUBLE)
    private double field3;
    @ProtoField(tag = 4, type = Field.Type.ENUM)
    private Corpus field4;
    @ProtoField(tag = 5, type = Field.Type.FIXED32)
    private int field5;
    @ProtoField(tag = 6, type = Field.Type.FIXED64)
    private long field6;
    @ProtoField(tag = 7, type = Field.Type.FLOAT)
    private float field7;
    @ProtoField(tag = 8, type = Field.Type.INT32)
    private int field8;
    @ProtoField(tag = 9, type = Field.Type.INT64)
    private long    field9;
    @ProtoField(tag = 10, type = Field.Type.MAP)
    private Map<String, Project> field10;
    @ProtoField(tag = 11, type = Field.Type.MESSAGE)
    private Proj field11;
    @ProtoField(tag = 12, type = Field.Type.SFIXED32)
    private int field12;
    @ProtoField(tag = 13, type = Field.Type.SFIXED64)
    private long field13;
    @ProtoField(tag = 14, type = Field.Type.SINT32)
    private int field14;
    @ProtoField(tag = 15, type = Field.Type.SINT64)
    private long field15;
    @ProtoField(tag = 16, type = Field.Type.STRING)
    private String field16;
    @ProtoField(tag = 17, type = Field.Type.UINT32)
    private int field17;
    @ProtoField(tag = 18, type = Field.Type.UINT64)
    private long field18;

}
