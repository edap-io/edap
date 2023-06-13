/*
 * Copyright 2022 The edap Project
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

package io.edap.log.api.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static io.edap.log.helpers.Util.printError;
import static io.edap.log.helpers.Util.printMsg;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestUtil {

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
    public void testPrintError() {
        String msg = "Print error message.";
        printError(msg);
        assertEquals("EdapLog: " + msg + "\n", errContent.toString());

    }

    @Test
    public void testPrintErrorTwoArg() {
        String msg = "Print error message.";
        try {
            float result = 1/0;
        } catch (Throwable t) {
            printError(msg, t);
        }
        String e = errContent.toString();
        String[] lines = e.split("\n");
        assertEquals(lines[0], msg);
        assertEquals(lines[1], "Reported exception:");
        assertEquals(lines[2], "java.lang.ArithmeticException: / by zero");
    }

    @Test
    public void testPrintMsg() {
        String msg = "Print info message.";
        printMsg(msg);
        assertEquals("Print info message.\n", outContent.toString());

    }
}
