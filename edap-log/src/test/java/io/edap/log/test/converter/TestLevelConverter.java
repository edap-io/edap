/*
 * Copyright 2023 The edap Project
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

package io.edap.log.test.converter;

import io.edap.log.LogLevel;
import org.junit.jupiter.api.Test;

public class TestLevelConverter {

    @Test
    public void testConverte() {
        int level = LogLevel.TRACE;
        System.out.println("TRACE=" + (level >> 8));
        level = LogLevel.DEBUG;
        System.out.println("DEBUG=" + (level >> 8));
        level = LogLevel.CONF;
        System.out.println("CONF=" + (level >> 8));
        level = LogLevel.INFO;
        System.out.println("INFO=" + (level >> 8));
        level = LogLevel.WARN;
        System.out.println("WARN=" + (level >> 8));
        level = LogLevel.ERROR;
        System.out.println("ERROR=" + (level >> 8));
        level = LogLevel.OFF;
        System.out.println("OFF=" + (level >> 8));
    }
}
