/*
 * Copyright 2023 The edap Project
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package io.edap.log.api.test;

import org.junit.jupiter.api.Test;

import static io.edap.log.LogLevel.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestLogLevel {

    @Test
    public void testLevelBytes() {
        assertArrayEquals(OFF_BYTES, new byte[]{'O', 'F', 'F'});
        assertArrayEquals(ERROR_BYTES, new byte[]{'E', 'R', 'R', 'O', 'R'});
        assertArrayEquals(WARN_BYTES,  new byte[]{'W', 'A', 'R', 'N'});
        assertArrayEquals(INFO_BYTES,  new byte[]{'I', 'N', 'F', 'O'});
        assertArrayEquals(CONF_BYTES,  new byte[]{'C', 'O', 'N', 'F'});
        assertArrayEquals(DEBUG_BYTES, new byte[]{'D', 'E', 'B', 'U', 'G'});
        assertArrayEquals(TRACE_BYTES, new byte[]{'T', 'R', 'A', 'C', 'E'});

    }
}
