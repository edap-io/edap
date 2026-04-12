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

package io.edap.log.test;

import io.edap.log.LogArgsImpl;
import io.edap.log.LogLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class TestLogArgsImpl {

    @Test
    public void testLevel() {
        LogArgsImpl args = new LogArgsImpl();
        args.level(LogLevel.TRACE);
        assertEquals(args.level(), LogLevel.TRACE);

        args.level(LogLevel.DEBUG);
        assertEquals(args.level(), LogLevel.DEBUG);

        args.level(LogLevel.CONF);
        assertEquals(args.level(), LogLevel.CONF);

        args.level(LogLevel.INFO);
        assertEquals(args.level(), LogLevel.INFO);

        args.level(LogLevel.WARN);
        assertEquals(args.level(), LogLevel.WARN);

        args.level(LogLevel.ERROR);
        assertEquals(args.level(), LogLevel.ERROR);

        args.level(LogLevel.OFF);
        assertEquals(args.level(), LogLevel.OFF);
    }

    @Test
    public void testArgBoolean() throws IllegalAccessException {
        boolean flag = true;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Boolean);
        assertEquals(argv[0], true);

        flag = false;
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Boolean);
        assertEquals(argv[0], false);
    }

    @Test
    public void testArgBooleanSupplier() throws IllegalAccessException {
        final boolean flag = true;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(() -> flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Boolean);
        assertEquals(argv[0], true);

        boolean flag2 = false;
        args.reset();
        args.arg(() -> flag2);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Boolean);
        assertEquals(argv[0], false);
    }

    @Test
    public void testArgByte() throws IllegalAccessException {
        byte flag = 'a';
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Byte);
        assertEquals(argv[0], (byte)'a');

        flag = 'b';
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Byte);
        assertEquals(argv[0], (byte)'b');
    }

    @Test
    public void testArgChar() throws IllegalAccessException {
        char flag = 'c';
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Character);
        assertEquals(argv[0], 'c');

        flag = 'd';
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Character);
        assertEquals(argv[0], 'd');
    }

    @Test
    public void testArgShort() throws IllegalAccessException {
        short flag = 5;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Short);
        assertEquals(((Short)argv[0]).intValue(), 5);

        flag = 6;
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Short);
        assertEquals(((Short)argv[0]).intValue(), 6);
    }

    @Test
    public void testArgFloat() throws IllegalAccessException {
        float flag = 3.14f;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Float);
        assertEquals(argv[0], 3.14f);

        flag = 3.1415f;
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Float);
        assertEquals(argv[0], 3.1415f);
    }

    @Test
    public void testArgInt() throws IllegalAccessException {
        int flag = 7;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Integer);
        assertEquals(argv[0], 7);

        flag = 8;
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Integer);
        assertEquals(argv[0], 8);
    }

    @Test
    public void testArgIntSupplier() throws IllegalAccessException {
        final int flag = 7;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(() -> flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Integer);
        assertEquals(argv[0], 7);

        final int flag2 = 8;
        args.reset();
        args.arg(() -> flag2);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Integer);
        assertEquals(argv[0], 8);
    }

    @Test
    public void testArgLong() throws IllegalAccessException {
        long flag = 9L;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Long);
        assertEquals(argv[0], 9L);

        flag = 10L;
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Long);
        assertEquals(argv[0], 10L);
    }

    @Test
    public void testArgLongSupplier() throws IllegalAccessException {
        final long flag = 9L;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(() -> flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Long);
        assertEquals(argv[0], 9L);

        final long flag2 = 10L;
        args.reset();
        args.arg(() -> flag2);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Long);
        assertEquals(argv[0], 10L);
    }

    @Test
    public void testArgDouble() throws IllegalAccessException {
        double flag = 31.4D;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Double);
        assertEquals(argv[0], 31.4D);

        flag = 31.415D;
        args.reset();
        args.arg(flag);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Double);
        assertEquals(argv[0], 31.415D);
    }

    @Test
    public void testArgDoubleSupplier() throws IllegalAccessException {
        final double flag = 31.4D;
        LogArgsImpl args = new LogArgsImpl();
        args.arg(() -> flag);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Double);
        assertEquals(argv[0], 31.4D);

        final double flag2 = 31.415D;
        args.reset();
        args.arg(() -> flag2);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Double);
        assertEquals(argv[0], 31.415D);
    }

    @Test
    public void testArgString() throws IllegalAccessException {
        String val = "abcd,";
        LogArgsImpl args = new LogArgsImpl();
        args.arg(val);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof String);
        assertEquals(argv[0], val);

        val = "78j中文信息xixi";
        args.reset();
        args.arg(val);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof String);
        assertEquals(argv[0], val);
    }

    @Test
    public void testArgObject() {
        LogArgsImpl args = new LogArgsImpl();
        Map<String, Integer> val = new HashMap<>();
        val.put("age", 28);
        args.arg(val);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Map);
        assertEquals(((Map<String, Integer>)argv[0]).size(), 1);
        assertEquals(((Map<String, Integer>)argv[0]).get("age"), 28);

        val.clear();
        val.put("age", 17);
        val.put("height", 168);
        args.reset();
        args.arg(val);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Map);
        assertEquals(((Map<String, Integer>)argv[0]).size(), 2);
        assertEquals(((Map<String, Integer>)argv[0]).get("height"), 168);
        assertEquals(((Map<String, Integer>)argv[0]).get("age"), 17);
    }

    @Test
    public void testArgObjectSupplier() {
        LogArgsImpl args = new LogArgsImpl();
        final Map<String, Integer> val = new HashMap<>();
        val.put("age", 28);
        args.arg(() -> val);
        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Map);
        assertEquals(((Map<String, Integer>)argv[0]).size(), 1);
        assertEquals(((Map<String, Integer>)argv[0]).get("age"), 28);

        val.clear();
        val.put("age", 17);
        val.put("height", 168);
        args.reset();
        args.arg(() -> val);
        argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof Map);
        assertEquals(((Map<String, Integer>)argv[0]).size(), 2);
        assertEquals(((Map<String, Integer>)argv[0]).get("height"), 168);
        assertEquals(((Map<String, Integer>)argv[0]).get("age"), 17);
    }

    @Test
    public void testThrew() throws NoSuchFieldException, IllegalAccessException {
        Field throwableField = LogArgsImpl.class.getDeclaredField("throwable");
        throwableField.setAccessible(true);
        Throwable throwable = new Throwable("Test's throwable");
        LogArgsImpl args = new LogArgsImpl();
        args.threw(throwable);
        Throwable resultThrowable = (Throwable)throwableField.get(args);
        assertEquals(resultThrowable.getMessage(), throwable.getMessage());

        LogArgsImpl.DuplicateValueException thrown = assertThrows(LogArgsImpl.DuplicateValueException.class,
                () -> {
                    args.threw(throwable);
                });
        assertTrue(thrown.getMessage().contains("Duplicate call to threw()"));
    }

    @Test
    public void testMessage() {
        LogArgsImpl args = new LogArgsImpl();
        Object message = "io.edap.log.test.TestLogArgsImpl.testMessage info message";
        args.message(message);

        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 1);
        assertTrue(argv[0] instanceof String);
        assertEquals(argv[0], message);
    }

    @Test
    public void testFormat() throws NoSuchFieldException, IllegalAccessException {
        LogArgsImpl args = new LogArgsImpl();
        String format = "io.edap.log.test.TestLogArgsImpl.testMessage info message";
        args.format(format);

        Field formatField = LogArgsImpl.class.getDeclaredField("format");
        formatField.setAccessible(true);

        Object[] argv = args.getArgv();
        assertNotNull(argv);
        assertEquals(args.getArgc(), 0);
        assertEquals(format, formatField.get(args));

        LogArgsImpl.DuplicateValueException thrown = assertThrows(LogArgsImpl.DuplicateValueException.class,
                () -> {
                    args.format(format);
                });
        assertTrue(thrown.getMessage().contains("Duplicate call to format()"));
    }

    @Test
    public void testAppendArg() throws NoSuchFieldException, IllegalAccessException {
        Field argcField = LogArgsImpl.class.getDeclaredField("argc");
        argcField.setAccessible(true);
        LogArgsImpl args = new LogArgsImpl();
        argcField.set(args, 64);

        LogArgsImpl.TooManyArgsException thrown = assertThrows(LogArgsImpl.TooManyArgsException.class,
                () -> {
                    args.arg(1);
                });
        assertTrue(thrown.getMessage().contains("Number of args cannot exceed"));
    }
}
