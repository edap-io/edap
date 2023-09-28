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

package io.edap.log.test.encoder.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.converter.LineSeparatorConverter;
import io.edap.log.converter.LoggerConverter;
import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestLineSeperatorConverter {

    @Test
    public void testConvert() throws NoSuchFieldException, IllegalAccessException {
        Converter loggerConverter = new LineSeparatorConverter("%logger%n");

        ByteArrayBuilder out = new ByteArrayBuilder();
        LogEvent logEvent = new LogEvent();
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "\n".getBytes());

        loggerConverter = new LineSeparatorConverter("%n", " - [");
        out.reset();
        logEvent = new LogEvent();
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "\n - [".getBytes());

        Field winField = loggerConverter.getClass().getDeclaredField("win");
        winField.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(winField, winField.getModifiers() & ~Modifier.FINAL);
        winField.set(loggerConverter, true);
        out.reset();
        logEvent = new LogEvent();
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "\r\n - [".getBytes());
        winField.set(loggerConverter, false);
    }
}
