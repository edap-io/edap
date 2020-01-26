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

package io.edap.protobuf.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

public class PbField {

    private String name;
    private Type genericType;
    private Class<?> type;

    public static PbField from(Field field) {
        PbField pbField = new PbField();
        pbField.setGenericType(field.getGenericType());
        pbField.setName(field.getName());
        pbField.setType(field.getType());
        return pbField;
    }

    public Type getGenericType() {
        return genericType;
    }

    public PbField setGenericType(Type genericType) {
        this.genericType = genericType;
        return this;
    }

    public Class<?> getType() {
        return type;
    }

    public PbField setType(Class<?> type) {
        this.type = type;
        return this;
    }

    public String getName() {
        return name;
    }

    public PbField setName(String name) {
        this.name = name;
        return this;
    }
}
