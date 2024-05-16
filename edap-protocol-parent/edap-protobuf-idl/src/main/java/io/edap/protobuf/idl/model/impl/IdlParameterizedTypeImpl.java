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
import io.edap.protobuf.idl.model.IdlParameterizedType;
import io.edap.util.CollectionUtils;

public class IdlParameterizedTypeImpl implements IdlParameterizedType {

    private IdlJavaType rawType;
    private IdlJavaType[] actualTypeArgs;

    public IdlParameterizedTypeImpl(IdlJavaType rawType, IdlJavaType[] actualTypeArgs) {
        this.rawType = rawType;
        this.actualTypeArgs = actualTypeArgs;
    }

    @Override
    public String binaryName() {
        return rawType.binaryName();
    }

    @Override
    public String canonicalName() {
        return rawType.canonicalName();
    }

    @Override
    public IdlJavaType rawType() {
        return rawType;
    }

    @Override
    public IdlJavaType[] ActualTypeArgs() {
        return actualTypeArgs;
    }

    @Override
    public String toString() {
        StringBuilder type = new StringBuilder();
        String binaryName = rawType.binaryName();
        type.append(binaryName, 0, binaryName.length()-1).append('<');
        if (CollectionUtils.isEmpty(actualTypeArgs)) {
            type.append('?');
        } else {
            for (IdlJavaType argType : actualTypeArgs) {
                if (argType instanceof IdlJavaClass) {
                    type.append(argType.binaryName());
                } else {
                    type.append(argType.toString());
                }
            }
        }
        type.append(">;");
        return type.toString();
    }
}
