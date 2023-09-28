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

import io.edap.log.*;
import io.edap.log.config.ConfigManager;
import io.edap.log.test.TestLog;
import io.edap.log.test.spi.EdapTestAdapter;
import io.edap.util.EdapTime;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSizeAndTimeRollingPolicy {

    static Logger LOG = LoggerManager.getLogger(TestLog.class);

    @Test
    public void testSizeAndTimeRollover() throws ParserConfigurationException, IOException, SAXException, InterruptedException {
        try {
            EdapTestAdapter edapTestAdapter = (EdapTestAdapter) ConfigManager.getLogAdapter();
            File f = new File("./logs/");
            if (f.exists()) {
                File[] files = f.listFiles();
                for (File child : files) {
                    child.delete();
                }
                f.delete();
            }

            if (edapTestAdapter != null) {
                edapTestAdapter.reloadConfig("/edap-log-sizeandtime-rollover-512.xml");
            }

            Appender appender = AppenderManager.instance().getAppender("rollingFile");
            System.out.println("appender=" + appender);

            long now = EdapTime.instance().currentTimeMillis();
            LogEvent logEvent = new LogEvent();
            logEvent.setLogTime(now);
            logEvent.setArgv(new Object[]{"edap", 90.0});
            logEvent.setFormat("name: {},height: {}");
            logEvent.setLevel(LogLevel.INFO);
            logEvent.setThreadName("main");
            logEvent.setLoggerName("io.edap.log.test.TestLog");
            for (int i=0;i<5;i++) {
                logEvent.setLogTime(now + (i*24*60*60*1000));
                for (int j=0;j<30;j++) {
                    appender.append(logEvent);
                }
            }

            Thread.sleep(1000);

            f = new File("./logs/");
            assertEquals(f.exists(), true);
            File[] files = f.listFiles();

            Calendar logDate = Calendar.getInstance();
            logDate.setTimeInMillis(logEvent.getLogTime());
            //logDate.add(Calendar.DAY_OF_MONTH, -1);
            List<String> logNames = new ArrayList<>();
            for (File logFile : files) {
                logNames.add(logFile.getName());
                System.out.println("name11=" + logFile.getName());
                assertEquals(logFile.length() < 512,true);
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

            assertEquals(files.length, 30);
        } finally {
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
}
