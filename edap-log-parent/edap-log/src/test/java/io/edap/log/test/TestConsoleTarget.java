/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.log.test;

import io.edap.log.io.ConsoleTarget;
import org.junit.jupiter.api.Test;

import static io.edap.log.io.ConsoleTarget.findByName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestConsoleTarget {

    @Test
    public void testFindByName() {
        String name = "System.out";
        ConsoleTarget target = findByName(name);
        assertEquals(target, ConsoleTarget.SystemOut);
        name = "System.err";
        target = findByName(name);
        assertEquals(target, ConsoleTarget.SystemErr);

        assertNull(findByName("t"));
    }

    @Test
    public void testGetterMethod() {
        String name = "System.out";
        ConsoleTarget target = findByName(name);
        assertEquals(target.getName(), "System.out");

        assertEquals(target.toString(), "System.out");
    }
}
