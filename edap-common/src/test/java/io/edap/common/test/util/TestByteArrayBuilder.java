/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.common.test.util;

import io.edap.util.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestByteArrayBuilder {

    @Test
    public void testAppendBoolean() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        assertEquals(bab.length(), 0);
        assertEquals(bab.remain(), 3);
        bab.append(true);
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "true".getBytes());

        bab = new ByteArrayBuilder(3);
        bab.append(false);
        assertEquals(bab.length(), 5);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "false".getBytes());

    }

    @Test
    public void testAppendBooleanObj() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        Boolean bool = null;
        assertEquals(bab.length(), 0);
        assertEquals(bab.remain(), 3);
        bab.append(bool);
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "null".getBytes());

        bab = new ByteArrayBuilder(3);
        bool = true;
        assertEquals(bab.length(), 0);
        assertEquals(bab.remain(), 3);
        bab.append(bool);
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "true".getBytes());

        bool = false;
        bab = new ByteArrayBuilder(3);
        bab.append(bool);
        assertEquals(bab.length(), 5);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "false".getBytes());

    }

    @Test
    public void testAppendOneByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        bab.append((byte)'a');
        assertEquals(bab.length(), 1);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "a".getBytes());
    }

    @Test
    public void testAppendTwoByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        bab.append((byte)'a', (byte)'b');
        assertEquals(bab.length(), 2);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "ab".getBytes());

        bab = new ByteArrayBuilder(3);
        bab.append((byte)'a', (byte)'b');
        bab.append((byte)'c', (byte)'d');
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 2);
        assertArrayEquals(bab.toByteArray(), "abcd".getBytes());
    }

    @Test
    public void testUncheckAppendTwoByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(3);
        bab.uncheckAppend((byte)'a', (byte)'b');
        assertEquals(bab.length(), 2);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "ab".getBytes());

        ArrayIndexOutOfBoundsException ex = assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> {
                    ByteArrayBuilder bab2 = new ByteArrayBuilder(3);
                    bab2.uncheckAppend((byte) 'a', (byte) 'b');
                    bab2.uncheckAppend((byte) 'c', (byte) 'd');
                }
        );
        assertTrue(ex.getMessage().contains("Index 3 out of bounds for length 3"));
    }

    @Test
    public void testAppendThreeByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(2);
        bab.append((byte)'a', (byte)'b', (byte)'c');
        assertEquals(bab.length(), 3);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "abc".getBytes());

        bab = new ByteArrayBuilder(3);
        bab.append((byte)'a', (byte)'b');
        bab.append((byte)'c', (byte)'d', (byte)'e');
        assertEquals(bab.length(), 5);
        assertEquals(bab.remain(), 1);
        assertArrayEquals(bab.toByteArray(), "abcde".getBytes());
    }

    @Test
    public void testAppendFourByte() {
        ByteArrayBuilder bab = new ByteArrayBuilder(2);
        bab.append((byte)'a', (byte)'b', (byte)'c', (byte)'d');
        assertEquals(bab.length(), 4);
        assertEquals(bab.remain(), 0);
        assertArrayEquals(bab.toByteArray(), "abcd".getBytes());

        bab = new ByteArrayBuilder(5);
        bab.append((byte)'a', (byte)'b', (byte)'c', (byte)'d');
        bab.append((byte)'e', (byte)'f');
        assertEquals(bab.length(), 6);
        assertEquals(bab.remain(), 4);
        assertArrayEquals(bab.toByteArray(), "abcdef".getBytes());
    }

}
