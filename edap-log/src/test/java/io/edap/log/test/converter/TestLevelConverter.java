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
import io.edap.log.converter.LevelConverter;
import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import static io.edap.log.LogLevel.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestLevelConverter {

    @Test
    public void testConverte() {
        int level = TRACE;
        System.out.println("TRACE=" + (level >> 8));
        level = LogLevel.DEBUG;
        System.out.println("DEBUG=" + (level >> 8));
        level = CONF;
        System.out.println("CONF=" + (level >> 8));
        level = LogLevel.INFO;
        System.out.println("INFO=" + (level >> 8));
        level = LogLevel.WARN;
        System.out.println("WARN=" + (level >> 8));
        level = LogLevel.ERROR;
        System.out.println("ERROR=" + (level >> 8));
        level = LogLevel.OFF;
        System.out.println("OFF=" + (level >> 8));

        LevelConverter levelConverter = new LevelConverter("", " ");
        ByteArrayBuilder out = new ByteArrayBuilder();
        levelConverter.convertTo(out, TRACE);
        assertArrayEquals(out.toByteArray(), "TRACE ".getBytes());
        out.reset();
        levelConverter.convertTo(out, DEBUG);
        assertArrayEquals(out.toByteArray(), "DEBUG ".getBytes());
        out.reset();
        levelConverter.convertTo(out, CONF);
        assertArrayEquals(out.toByteArray(), " CONF ".getBytes());
        out.reset();
        levelConverter.convertTo(out, INFO);
        assertArrayEquals(out.toByteArray(), " INFO ".getBytes());
        out.reset();
        levelConverter.convertTo(out, WARN);
        assertArrayEquals(out.toByteArray(), " WARN ".getBytes());
        out.reset();
        levelConverter.convertTo(out, ERROR);
        assertArrayEquals(out.toByteArray(), "ERROR ".getBytes());
        out.reset();
        levelConverter.convertTo(out, OFF);
        assertArrayEquals(out.toByteArray(), "  OFF ".getBytes());

        out.reset();
        levelConverter = new LevelConverter("");
        levelConverter.convertTo(out, TRACE);
        assertArrayEquals(out.toByteArray(), "TRACE".getBytes());
        out.reset();
        levelConverter.convertTo(out, DEBUG);
        assertArrayEquals(out.toByteArray(), "DEBUG".getBytes());
        out.reset();
        levelConverter.convertTo(out, CONF);
        assertArrayEquals(out.toByteArray(), " CONF".getBytes());
        out.reset();
        levelConverter.convertTo(out, INFO);
        assertArrayEquals(out.toByteArray(), " INFO".getBytes());
        out.reset();
        levelConverter.convertTo(out, WARN);
        assertArrayEquals(out.toByteArray(), " WARN".getBytes());
        out.reset();
        levelConverter.convertTo(out, ERROR);
        assertArrayEquals(out.toByteArray(), "ERROR".getBytes());
        out.reset();
        levelConverter.convertTo(out, OFF);
        assertArrayEquals(out.toByteArray(), "  OFF".getBytes());

        out.reset();
        levelConverter.convertTo(out, 8);
        assertArrayEquals(out.toByteArray(), "  OFF".getBytes());

        out.reset();
        levelConverter.convertTo(out, -1);
        assertArrayEquals(out.toByteArray(), "  OFF".getBytes());
    }
}
