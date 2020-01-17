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

package io.edap.protobuf.wire.test;

import io.edap.protobuf.wire.Option;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试Option类的操作逻辑
 */
public class TestOption {

    @ParameterizedTest
    @ValueSource(strings = {
            "true",
            "t",
            "True",
            "TRUE",
            "T"
    })
    void testOptionTrue(String boolStr) {
        boolean b = Option.getBoolean(boolStr);
        assertTrue(b);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "false",
            "f",
            "FALSE",
            "False",
            "F",
            "",
            "fasdf"
    })
    void testOptionFalse(String boolStr) {
        boolean b = Option.getBoolean(boolStr);
        assertFalse(b);
    }
}
