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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.spi.EdapTestAdapter;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestAsyncLogger {

    @Test
    public void testDefaultConfig() throws IOException, ParserConfigurationException, SAXException {
        System.setProperty("edaplog.configurationFile", "edap-log-async-logger-basefile.xml");
        EdapTestAdapter edapTestAdapter = (EdapTestAdapter) ConfigManager.getLogAdapter();
        edapTestAdapter.reloadConfig("/edap-log-async-logger-basefile.xml");
        Logger logger = LoggerManager.getLogger(TestAsyncLogger.class);
        String    format     = new String("name {}, age: {}");
        logger.info(format, l -> l.arg("louis").arg(28));

        Logger queue1Logger = LoggerManager.getLogger("queue1");
        queue1Logger.info(format, l -> l.arg("china").arg(5000));

        edapTestAdapter.reloadConfig("/edap-log-async-logger-hasqueue.xml");
        queue1Logger = LoggerManager.getLogger("queue1-1");
        queue1Logger.info(format, l -> l.arg("china").arg(5000));


        Logger queue2Logger = LoggerManager.getLogger("queue2-1");
        queue2Logger.info(format, l -> l.arg("china").arg(5000));

        Logger queue22Logger = LoggerManager.getLogger("queue2-2");
        queue22Logger.info(format, l -> l.arg("china").arg(5000));

        assertNotNull(logger);
//        System.in.read();
    }
}
