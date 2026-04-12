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

package io.edap.protobuf.idl.model.impl;

import io.edap.protobuf.idl.model.IdlJavaType;

public class IdlJavaClass implements IdlJavaType {
    private final String binaryName;
    private final String canonicalName;

    public IdlJavaClass(String binaryName, String canonicalName) {
        this.binaryName = binaryName;
        this.canonicalName = canonicalName;
    }

    @Override
    public String binaryName() {
        return binaryName;
    }

    @Override
    public String canonicalName() {
        return canonicalName;
    }

    public boolean isArray() {
        return canonicalName.startsWith("[");
    }

    public IdlJavaClass getComponentType() {
        if (!isArray()) {
            return null;
        }
        return new IdlJavaClass(binaryName.substring(1),
                canonicalName.substring(1));
    }

    @Override
    public String toString() {
        return canonicalName;
    }
}
