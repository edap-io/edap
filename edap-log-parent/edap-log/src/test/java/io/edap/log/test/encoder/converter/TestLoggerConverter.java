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

import io.edap.log.LogEvent;
import io.edap.log.converter.LoggerConverter;
import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import static io.edap.log.LogLevel.TRACE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestLoggerConverter {

    @Test
    public void testConvert() {
        LoggerConverter loggerConverter = new LoggerConverter("%logger{36}");

        ByteArrayBuilder out = new ByteArrayBuilder();
        LogEvent logEvent = new LogEvent();
        logEvent.setLoggerName("org.junit.platform.engine.support.hierarchical.NodeTestTask");
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "o.j.p.e.s.hierarchical.NodeTestTask".getBytes());

        out.reset();
        logEvent = new LogEvent();
        logEvent.setLoggerName("org.junit.platform.engine.support.hierarchical.NodeTestTask");
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "o.j.p.e.s.hierarchical.NodeTestTask".getBytes());

        out.reset();
        loggerConverter = new LoggerConverter("%logger{36}", " [");
        logEvent = new LogEvent();
        logEvent.setLoggerName("org.junit.platform.engine.support.hierarchical.NodeTestTask");
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "o.j.p.e.s.hierarchical.NodeTestTask [".getBytes());

        out.reset();
        loggerConverter = new LoggerConverter("logger{36}", " [");
        logEvent = new LogEvent();
        logEvent.setLoggerName("org.junit.platform.engine.support.hierarchical.NodeTestTask");
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "o.j.p.e.s.hierarchical.NodeTestTask [".getBytes());

        out.reset();
        loggerConverter = new LoggerConverter("logger{vs}", " [");
        logEvent = new LogEvent();
        logEvent.setLoggerName("org.junit.platform.engine.support.hierarchical.NodeTestTask");
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "org.junit.platform.engine.support.hierarchical.NodeTestTask [".getBytes());
    }
}
