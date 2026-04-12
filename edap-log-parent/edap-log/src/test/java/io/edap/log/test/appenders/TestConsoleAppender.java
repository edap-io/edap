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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.spi.EdapTestAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestConsoleAppender {

    static Logger LOG = LoggerManager.getLogger(TestConsoleAppender.class);

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
    public void testLogger() throws ParserConfigurationException, IOException, SAXException {
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter) ConfigManager.getLogAdapter();
        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-console.xml");
        }

        outContent.reset();
        errContent.reset();
        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));

        String log = outContent.toString("utf-8");
        assertEquals(log.substring(23), " INFO  [main] [i.e.l.t.a.TestConsoleAppender] [] []  - name: edap,height: 90.0 \n");
        assertEquals(errContent.toString().length(), 0);

        if (edapTestAdapter != null) {
            edapTestAdapter.reloadConfig("/edap-log-console-system-err.xml");
        }

        outContent.reset();
        errContent.reset();
        LOG.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));

        log = errContent.toString("utf-8");
        assertEquals(log.substring(23), " INFO  [main] [i.e.l.t.a.TestConsoleAppender] [] []  - name: edap,height: 90.0 \n");
        assertEquals(outContent.toString().length(), 0);
    }
}
