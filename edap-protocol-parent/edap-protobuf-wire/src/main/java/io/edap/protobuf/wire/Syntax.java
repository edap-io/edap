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

package io.edap.protobuf.wire;

/**
 * protocol buffer的协议版本
 */
public enum Syntax {

    PROTO_2("proto2"),
    PROTO_3("proto3");

    private final String value;

    Syntax(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Syntax fromValue(String value) {
        switch (value) {
            case "proto2":
                return Syntax.PROTO_2;
            case "proto3":
                return Syntax.PROTO_3;
            default:
                throw new IllegalArgumentException(
                        "no enum value Syntax " + value);
        }
    }
}