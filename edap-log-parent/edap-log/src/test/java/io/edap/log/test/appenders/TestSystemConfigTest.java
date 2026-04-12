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

import io.edap.log.EdapLogContext;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.io.File;
import java.io.IOException;

import static io.edap.log.test.TestLog.readFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestSystemConfigTest {

    Logger logger;

    @BeforeEach
    public void setup() {
        File f = new File("./logs/");
        if (f.exists()) {
            File[] files = f.listFiles();
            for (File child : files) {
                child.delete();
            }
            f.delete();
        }
        System.setProperty("edaplog.configurationFile", "edap-log-file.xml");
    }

    @Test
    public void testSystemConfigPath() throws IOException {
        try {
            EdapLogContext.instance().reload();
            logger = LoggerManager.getLogger(TestSystemConfigTest.class);
            logger.info("name: {},height: {}", l -> l.arg("edap").arg(90.0));
            File f = new File("./logs/edap.log");
            String log = readFile(f);
            assertNotNull(log);
            assertEquals(log.substring(23), " INFO  [main] [i.e.l.t.a.TestSystemConfigTest] [] []  -" +
                    " name: edap,height: 90.0 \n");
        } finally {
            tearDown();
            EdapLogContext.instance().reload();
            File f = new File("./logs/");
            if (f.exists()) {
                File[] files = f.listFiles();
                for (File child : files) {
                    child.delete();
                }
                f.delete();
            }
        }
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty("edaplog.configurationFile");
    }

}
