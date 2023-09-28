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

import io.edap.log.appenders.rolling.SizeAndTimeBasedFNATP;
import io.edap.log.appenders.rolling.SizeAndTimeBasedRollingPolicy;
import io.edap.log.appenders.rolling.TimeBasedRollingPolicy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static io.edap.util.ClazzUtil.getDeclaredField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestSizeAndTimeBasedFNATP {

    @Test
    public void testScanAndGetSeq() throws NoSuchFieldException, IllegalAccessException, IOException {

        try {
            File file = new File("./logs/");
            if (!file.exists()) {
                file.mkdirs();
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String dateStr = dateFormat.format(new Date());
            for (int i = 0; i < 12; i++) {
                File f = new File("./logs/edap-sizeandtime-rollover-" + dateStr + "-" + i + ".log");
                if (!f.exists()) {
                    f.createNewFile();
                }
            }

            SizeAndTimeBasedRollingPolicy policy = new SizeAndTimeBasedRollingPolicy();
            policy.setFileNamePattern("./logs/edap-sizeandtime-rollover-%d{yyyy-MM-dd}-%i.log");

            policy.start();

            Field triggerField = getDeclaredField(SizeAndTimeBasedRollingPolicy.class,
                    "timeBasedFileNamingAndTriggeringPolicy");
            triggerField.setAccessible(true);

            Field seqField = SizeAndTimeBasedFNATP.class.getDeclaredField("currentSeq");
            seqField.setAccessible(true);

            int seq = (int) seqField.get((SizeAndTimeBasedFNATP) triggerField.get(policy));
            assertEquals(seq, 11);


        } finally {
            File file = new File("./logs/");
            if (!file.exists()) {
                file.mkdirs();
            }
            File[] childs = file.listFiles();
            for (File f : childs) {
                f.delete();
            }
        }


    }

    @Test
    public void testGetExpireNames() {
        try {
            File file = new File("./logs/");
            if (!file.exists()) {
                file.mkdirs();
            }
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -4);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String dateStr = dateFormat.format(cal.getTime());
            for (int i = 0; i < 12; i++) {
                File f = new File("./logs/edap-sizeandtime-rollover-" + dateStr + "-" + i + ".log");
                if (!f.exists()) {
                    f.createNewFile();
                }
            }

            SizeAndTimeBasedRollingPolicy policy = new SizeAndTimeBasedRollingPolicy();
            policy.setFileNamePattern("./logs/edap-sizeandtime-rollover-%d{yyyy-MM-dd}-%i.log");

            policy.start();

            Field triggerField = getDeclaredField(SizeAndTimeBasedRollingPolicy.class,
                    "timeBasedFileNamingAndTriggeringPolicy");
            triggerField.setAccessible(true);

            Field seqField = SizeAndTimeBasedFNATP.class.getDeclaredField("currentSeq");
            seqField.setAccessible(true);

            SizeAndTimeBasedFNATP astbFNATP = (SizeAndTimeBasedFNATP) triggerField.get(policy);

            //System.out.println("seq=" + (int)seqField.get(stbFNATP));
            System.out.println("seq=" + (int) seqField.get(astbFNATP));

            List<String> needDeleteNames = astbFNATP.getExpireNames(3);

            System.out.println("needDeleteNames=" + needDeleteNames);
            assertNotNull(needDeleteNames);
            assertEquals(needDeleteNames.size(), 12);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            File file = new File("./logs/");
            if (!file.exists()) {
                file.mkdirs();
            }
            File[] childs = file.listFiles();
            for (File f : childs) {
                f.delete();
            }
        }
    }
}
