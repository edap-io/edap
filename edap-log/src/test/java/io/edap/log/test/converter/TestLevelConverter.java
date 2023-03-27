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

import io.edap.log.LogEvent;
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

        LevelConverter levelConverter = new LevelConverter("%5level", " ");
        ByteArrayBuilder out = new ByteArrayBuilder();
        LogEvent logEvent = new LogEvent();
        logEvent.setLevel(TRACE);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "TRACE ".getBytes());
        out.reset();
        logEvent.setLevel(DEBUG);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "DEBUG ".getBytes());
        out.reset();
        logEvent.setLevel(CONF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), " CONF ".getBytes());
        out.reset();
        logEvent.setLevel(INFO);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), " INFO ".getBytes());
        out.reset();
        logEvent.setLevel(WARN);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), " WARN ".getBytes());
        out.reset();
        logEvent.setLevel(ERROR);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "ERROR ".getBytes());
        out.reset();
        logEvent.setLevel(OFF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "  OFF ".getBytes());

        levelConverter = new LevelConverter("%-5level", " ");
        out = new ByteArrayBuilder();
        logEvent = new LogEvent();
        logEvent.setLevel(TRACE);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "TRACE ".getBytes());
        out.reset();
        logEvent.setLevel(DEBUG);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "DEBUG ".getBytes());
        out.reset();
        logEvent.setLevel(CONF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "CONF  ".getBytes());
        out.reset();
        logEvent.setLevel(INFO);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "INFO  ".getBytes());
        out.reset();
        logEvent.setLevel(WARN);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "WARN  ".getBytes());
        out.reset();
        logEvent.setLevel(ERROR);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "ERROR ".getBytes());
        out.reset();
        logEvent.setLevel(OFF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "OFF   ".getBytes());

        levelConverter = new LevelConverter("%-5p", " ");
        out = new ByteArrayBuilder();
        logEvent = new LogEvent();
        logEvent.setLevel(TRACE);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "TRACE ".getBytes());
        out.reset();
        logEvent.setLevel(DEBUG);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "DEBUG ".getBytes());
        out.reset();
        logEvent.setLevel(CONF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "CONF  ".getBytes());
        out.reset();
        logEvent.setLevel(INFO);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "INFO  ".getBytes());
        out.reset();
        logEvent.setLevel(WARN);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "WARN  ".getBytes());
        out.reset();
        logEvent.setLevel(ERROR);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "ERROR ".getBytes());
        out.reset();
        logEvent.setLevel(OFF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "OFF   ".getBytes());

        out.reset();
        levelConverter = new LevelConverter("");
        logEvent.setLevel(TRACE);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "TRACE".getBytes());
        out.reset();
        logEvent.setLevel(DEBUG);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "DEBUG".getBytes());
        out.reset();
        logEvent.setLevel(CONF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "CONF".getBytes());
        out.reset();
        logEvent.setLevel(INFO);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "INFO".getBytes());
        out.reset();
        logEvent.setLevel(WARN);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "WARN".getBytes());
        out.reset();
        logEvent.setLevel(ERROR);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "ERROR".getBytes());
        out.reset();
        logEvent.setLevel(OFF);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "OFF".getBytes());


        levelConverter = new LevelConverter("%-ilevel");

        levelConverter = new LevelConverter("%tlevel");

        out.reset();
        logEvent.setLevel(8 << 8);
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "OFF".getBytes());

        out.reset();
        logEvent.setLevel((-1 << 8));
        levelConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "OFF".getBytes());
    }
}
