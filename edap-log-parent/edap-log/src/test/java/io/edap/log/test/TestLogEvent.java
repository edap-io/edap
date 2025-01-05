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

package io.edap.log.test;

import io.edap.log.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static io.edap.log.LogLevel.WARN;
import static org.junit.jupiter.api.Assertions.*;

public class TestLogEvent {

    @Test
    public void testLogEvent() {
        LogEvent event = new LogEvent();

        Object[]  argv       = new Object[]{1, 2D, "edap-log"};
        long      time       = System.currentTimeMillis();
        String    format     = new String("name {}, age: {}");
        int       level      = WARN;
        String    loggerName = TestLogEvent.class.getName();
        int       nano       = new Random().nextInt();
        String    threadName = Thread.currentThread().getName();
        Throwable threw      = new RuntimeException("edap logEvent error");

        Map<String, Object> mdc = new HashMap<>();
        mdc.put("traceId", UUID.randomUUID().toString());


        event.setArgv(argv);
        event.setLogTime(time);
        event.setFormat(format);
        event.setLevel(level);
        event.setMdc(mdc);
        event.setLoggerName(loggerName);
        event.setNanoSeconds(nano);
        event.setThreadName(threadName);
        event.setThrew(threw);
        event.setCallerData(threw.getStackTrace());

        assertEquals(event.getArgv().length, argv.length);
        assertArrayEquals(event.getArgv(), argv);
        assertEquals(event.getLogTime(), time);
        assertEquals(event.getFormat(), format);
        assertEquals(event.getLevel(), level);
        assertEquals(event.getLoggerName(), loggerName);
        assertEquals(event.getNanoSeconds(), nano);
        assertEquals(event.getThreadName(), threadName);
        assertEquals(event.getThrew().getClass().getName(), threw.getClass().getName());
        assertEquals(event.getThrew().getMessage(), threw.getMessage());
        assertArrayEquals(event.getCallerData(), threw.getStackTrace());


        assertEquals(event.getMdc().size(), mdc.size());
        for (Map.Entry<String, Object> entry : event.getMdc().entrySet()) {
            String key = entry.getKey();
            assertTrue(mdc.containsKey(key));
            assertEquals(entry.getValue(), mdc.get(key));
        }


    }
}
