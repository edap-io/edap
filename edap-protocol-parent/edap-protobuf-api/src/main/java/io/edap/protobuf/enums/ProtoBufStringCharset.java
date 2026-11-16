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
    /**
     * ★ UTF-16，<b>wire 上是「小端」字节序</b>。
     *
     * <p>JDK 的 String 内部 UTF-16 字节序 = <b>原生端序</b>
     * （{@code java.lang.StringUTF16} 的 {@code <clinit>} 调 native {@code isBigEndian()}），
     * 把内部 byte[] 原样写到 wire 上 ⇒ <b>字节序 = 写端机器的端序</b>
     * ⇒ 标记必须带上端序，读端才知道要不要翻一次。</p>
     */
    UTF16 (2),
    /**
     * ★ UTF-16，<b>wire 上是「大端」字节序</b>。
     *
     * <p>与 {@link #UTF16} 相对：大端机上的写端用它，小端机上的写端用 {@link #UTF16}
     * ⇒ 同端序时两端都零成本，跨端序时只有读端翻一次。</p>
     */
    UTF16BE(3);

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
            case 3:
                return UTF16BE;
            default:
                throw new IllegalArgumentException(
                        "no enum value WireType " + value);
        }
    }
}
