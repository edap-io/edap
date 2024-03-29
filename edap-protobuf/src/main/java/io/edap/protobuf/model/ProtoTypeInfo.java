/*
 * Copyright 2022 The edap Project
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

package io.edap.protobuf.model;

import io.edap.protobuf.wire.Field;

/**
 * ProtoType的类型信息
 */
public class ProtoTypeInfo {
    /**
     * 类型的枚举类型
     */
    private Field.Type protoType;
    private ProtoTypeInfo protoTypeInfo;
    /**
     * 特殊Mesage的数据类型需要框架特殊处理的类型
     */
    private MessageInfo messageInfo;
    /**
     * 数据的基数
     */
    private Field.Cardinality cardinality;

    public Field.Type getProtoType() {
        return protoType;
    }

    public void setProtoType(Field.Type protoType) {
        this.protoType = protoType;
    }

    public Field.Cardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(Field.Cardinality cardinality) {
        this.cardinality = cardinality;
    }

    public ProtoTypeInfo getProtoTypeInfo() {
        return protoTypeInfo;
    }

    public void setProtoTypeInfo(ProtoTypeInfo protoTypeInfo) {
        this.protoTypeInfo = protoTypeInfo;
    }

    /**
     * 特殊Mesage的数据类型需要框架特殊处理的类型
     */
    public MessageInfo getMessageInfo() {
        return messageInfo;
    }

    public ProtoTypeInfo setMessageInfo(MessageInfo messageInfo) {
        this.messageInfo = messageInfo;
        return this;
    }
}
