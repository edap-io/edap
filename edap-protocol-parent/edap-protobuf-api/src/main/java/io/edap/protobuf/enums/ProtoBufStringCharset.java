/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.protobuf.enums;

import io.edap.protobuf.wire.WireType;

public enum ProtoBufStringCharset {

    UTF8  (0),
    LATIN1(1),
    UTF16 (2);

    ProtoBufStringCharset(int value) {
        this.value = value;
    }

    private final int value;

    public int getValue() {
        return value;
    }

    public static ProtoBufStringCharset fromValue(int value) {
        switch (value) {
            case 0:
                return UTF8;
            case 1:
                return LATIN1;
            case 2:
                return UTF16;
            default:
                throw new IllegalArgumentException(
                        "no enum value WireType " + value);
        }
    }
}
