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

import io.edap.log.Logger;
import io.edap.log.LoggerImpl;
import io.edap.log.config.LoggerConfig;
import io.edap.log.config.LoggerConfigSection;
import io.edap.log.spi.EdapLogFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static io.edap.log.LogLevel.INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestEdapLogFactory {

    @Test
    public void testGetLogger() {
        EdapLogFactory logFactory = new EdapLogFactory();
        Logger logger = logFactory.getLogger("ROOT");
        assertEquals(logger instanceof LoggerImpl, true);
        LoggerImpl loggerImpl = (LoggerImpl)logger;
        assertEquals(loggerImpl.getName(), "ROOT");

        logger = logFactory.getLogger(TestEdapLogFactory.class.getName());
        assertEquals(logger instanceof LoggerImpl, true);
        loggerImpl = (LoggerImpl)logger;
        assertEquals(loggerImpl.getName(), TestEdapLogFactory.class.getName());

        logger = logFactory.getLogger(TestEdapLogFactory.class.getName());
        assertEquals(logger instanceof LoggerImpl, true);
        loggerImpl = (LoggerImpl)logger;
        assertEquals(loggerImpl.getName(), TestEdapLogFactory.class.getName());

    }

    @Test
    public void testConfigReload() {
        EdapLogFactory logFactory = new EdapLogFactory();
        Logger logger = logFactory.getLogger(TestEdapLogFactory.class.getName());
        assertEquals(logger instanceof LoggerImpl, true);
        LoggerImpl loggerImpl = (LoggerImpl)logger;
        System.out.println(loggerImpl.level());
        assertEquals(loggerImpl.level(), INFO);

        LoggerConfigSection loggerConfigSection = new LoggerConfigSection();
        loggerConfigSection.setNeedReload(true);
        logFactory.reload(loggerConfigSection);

        loggerConfigSection = new LoggerConfigSection();
        loggerConfigSection.setNeedReload(true);
        loggerConfigSection.setLoggerConfigs(new ArrayList<>());
        logFactory.reload(loggerConfigSection);

        loggerConfigSection = new LoggerConfigSection();
        loggerConfigSection.setNeedReload(true);
        LoggerConfig loggerConfig = new LoggerConfig();
        loggerConfig.setName("io.edap.log.test");
        loggerConfig.setLevel("trace");
        loggerConfig.setAppenderRefs(Arrays.asList("console"));
        loggerConfigSection.setLoggerConfigs(Arrays.asList(loggerConfig));
        logFactory.reload(loggerConfigSection);
        System.out.println("logger.level=" + logger.level());



        loggerConfigSection = new LoggerConfigSection();
        loggerConfigSection.setNeedReload(true);
        loggerConfig = new LoggerConfig();
        loggerConfig.setName("io.edap.log");
        loggerConfig.setLevel("debug");
        loggerConfig.setAppenderRefs(Arrays.asList("console"));

        LoggerConfig testConfig = new LoggerConfig();
        testConfig.setName("io.edap.log.test");
        testConfig.setLevel("trace");
        testConfig.setAppenderRefs(Arrays.asList("console"));

        loggerConfigSection.setLoggerConfigs(Arrays.asList(loggerConfig, testConfig));
        logFactory.reload(loggerConfigSection);
        System.out.println("logger.level=" + logger.level());
    }
}
