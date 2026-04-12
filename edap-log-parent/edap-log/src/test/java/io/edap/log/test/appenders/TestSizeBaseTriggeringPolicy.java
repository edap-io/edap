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

package io.edap.log.test.appenders;

import io.edap.log.appenders.rolling.SizeBasedTriggeringPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSizeBaseTriggeringPolicy {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    public void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    public void testParseMaxFileSize() throws NoSuchFieldException, IllegalAccessException {
        SizeBasedTriggeringPolicy policy = new SizeBasedTriggeringPolicy();
        policy.setMaxFileSize(String.valueOf(100*1024*1024));
        policy.start();

        Field maxLenField = SizeBasedTriggeringPolicy.class.getDeclaredField("maxLength");
        maxLenField.setAccessible(true);

        assertEquals((long)maxLenField.get(policy), 100*1024*1024);

        policy.setMaxFileSize("2Gb");
        policy.start();
        assertEquals((long)maxLenField.get(policy), 2*1024*1024*1024L);
        assertEquals(policy.getMaxFileSize(), "2Gb");

        policy.setMaxFileSize("2g");
        policy.start();
        assertEquals((long)maxLenField.get(policy), 2*1024*1024*1024L);

        policy.setMaxFileSize("10Mb");
        policy.start();
        assertEquals((long)maxLenField.get(policy), 10*1024*1024);

        policy.setMaxFileSize("10M");
        policy.start();
        assertEquals((long)maxLenField.get(policy), 10*1024*1024);

        policy.setMaxFileSize("10Kb");
        policy.start();
        assertEquals((long)maxLenField.get(policy), 10*1024);

        policy.setMaxFileSize("10K");
        policy.start();
        assertEquals((long)maxLenField.get(policy), 10*1024);
    }

    @Test
    public void testAvalidSize() {
        String sizStr = "tmb";
        SizeBasedTriggeringPolicy policy = new SizeBasedTriggeringPolicy();
        policy.setMaxFileSize(sizStr);
        errContent.reset();
        policy.start();


        errContent.reset();
        policy.start();
        assertEquals(errContent.toString().contains("maxFileSize [" + sizStr + "] not number"), true);
        System.out.println("" + errContent.toString());

        sizStr = "2TB";
        policy.setMaxFileSize(sizStr);
        errContent.reset();
        policy.start();
        assertEquals(errContent.toString().contains("parse maxFileSize error"), true);
    }
}
