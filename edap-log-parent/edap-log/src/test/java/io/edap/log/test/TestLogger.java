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

package io.edap.log.test;

import io.edap.log.*;
import io.edap.log.test.spi.TestAppender;
import io.edap.log.test.spi.TestErrorAppender;
import io.edap.util.EdapTime;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static io.edap.log.LogLevel.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLogger {

    @Test
    public void testProvider() {
        Logger logger = LoggerManager.getLogger(TestLogger.class);
        logger.info("test log value:{}", z -> z.arg(123));
    }

    @Test
    public void testTrace() throws ParseException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        String threadName = Thread.currentThread().getName();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date startDate = new Date(EdapTime.instance().currentTimeMillis());
        TestAppender testAppender = new TestAppender();
        testAppender.setLevel(TRACE);
        logger.setAppenders(new Appender[]{testAppender});
        logger.trace("trace message!");
        Date endDate = new Date(EdapTime.instance().currentTimeMillis());
        String[] logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        Date logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("TRACE", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("trace message!\n", msg.toString());

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.trace("trace message with throwable!", new Throwable("Test throwable"));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("TRACE", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals(msg.toString().contains("trace message with throwable!"), true);

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.trace("test log value:{}", z -> z.arg(123));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("TRACE", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("test log value:123\n", msg.toString());

        testAppender.reset();
        testAppender.setLevel(DEBUG);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(CONF);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(INFO);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(WARN);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(ERROR);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(OFF);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);
    }

    @Test
    public void testDebug() throws ParseException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        String threadName = Thread.currentThread().getName();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date startDate = new Date(EdapTime.instance().currentTimeMillis());
        TestAppender testAppender = new TestAppender();
        testAppender.setLevel(DEBUG);
        logger.setAppenders(new Appender[]{testAppender});
        logger.debug("trace message!");
        Date endDate = new Date(EdapTime.instance().currentTimeMillis());
        String[] logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        Date logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("DEBUG", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("trace message!\n", msg.toString());

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.debug("trace message with throwable!", new Throwable("Test throwable"));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("DEBUG", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals(msg.toString().contains("trace message with throwable!"), true);

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.debug("test log value:{}", z -> z.arg(123));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("DEBUG", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("test log value:123\n", msg.toString());

        testAppender.reset();
        testAppender.setLevel(CONF);
        logger.debug("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(INFO);
        logger.debug("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(WARN);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(ERROR);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(OFF);
        logger.trace("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);
    }

    @Test
    public void testConf() throws ParseException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        String threadName = Thread.currentThread().getName();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date startDate = new Date(EdapTime.instance().currentTimeMillis());
        TestAppender testAppender = new TestAppender();
        testAppender.setLevel(CONF);
        logger.setAppenders(new Appender[]{testAppender});
        logger.conf("trace message!");
        Date endDate = new Date(EdapTime.instance().currentTimeMillis());
        String[] logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        Date logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("CONF", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("trace message!\n", msg.toString());

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.conf("trace message with throwable!", new Throwable("Test throwable"));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date(EdapTime.instance().currentTimeMillis());
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("CONF", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals(msg.toString().contains("trace message with throwable!"), true);

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.conf("test log value:{}", z -> z.arg(123));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("CONF", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("test log value:123\n", msg.toString());

        testAppender.reset();
        testAppender.setLevel(INFO);
        logger.conf("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(WARN);
        logger.conf("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(ERROR);
        logger.conf("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(OFF);
        logger.conf("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);
    }

    @Test
    public void testInfo() throws ParseException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        String threadName = Thread.currentThread().getName();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date startDate = new Date(EdapTime.instance().currentTimeMillis());
        TestAppender testAppender = new TestAppender();
        testAppender.setLevel(INFO);
        logger.setAppenders(new Appender[]{testAppender});
        logger.info("trace message!");
        Date endDate = new Date();
        String[] logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        Date logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("INFO", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("trace message!\n", msg.toString());

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.info("trace message with throwable!", new Throwable("Test throwable"));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date(EdapTime.instance().currentTimeMillis());
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("INFO", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals(msg.toString().contains("trace message with throwable!"), true);

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.info("test log value:{}", z -> z.arg(123));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("INFO", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("test log value:123\n", msg.toString());

        testAppender.reset();
        testAppender.setLevel(WARN);
        logger.info("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(ERROR);
        logger.info("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(OFF);
        logger.info("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);
    }

    @Test
    public void testWarn() throws ParseException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        String threadName = Thread.currentThread().getName();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date startDate = new Date(EdapTime.instance().currentTimeMillis());
        TestAppender testAppender = new TestAppender();
        testAppender.setLevel(WARN);
        logger.setAppenders(new Appender[]{testAppender});
        logger.warn("trace message!");
        Date endDate = new Date(EdapTime.instance().currentTimeMillis());
        String[] logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        Date logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("WARN", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("trace message!\n", msg.toString());

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.warn("trace message with throwable!", new Throwable("Test throwable"));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("WARN", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals(msg.toString().contains("trace message with throwable!"), true);

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.warn("test log value:{}", z -> z.arg(123));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("WARN", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("test log value:123\n", msg.toString());

        testAppender.reset();
        testAppender.setLevel(ERROR);
        logger.warn("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);

        testAppender.reset();
        testAppender.setLevel(OFF);
        logger.warn("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);
    }

    @Test
    public void testError() throws ParseException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        String threadName = Thread.currentThread().getName();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date startDate = new Date(EdapTime.instance().currentTimeMillis());
        TestAppender testAppender = new TestAppender();
        testAppender.setLevel(ERROR);
        logger.setAppenders(new Appender[]{testAppender});
        logger.error("trace message!");
        Date endDate = new Date();
        String[] logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        Date logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime()
                && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("ERROR", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        StringBuilder msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("trace message!\n", msg.toString());

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.error("trace message with throwable!", new Throwable("Test throwable"));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date(EdapTime.instance().currentTimeMillis());
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("ERROR", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals(msg.toString().contains("trace message with throwable!"), true);

        startDate = new Date(EdapTime.instance().currentTimeMillis());
        testAppender.reset();
        logger.error("test log value:{}", z -> z.arg(123));
        System.out.println(new String(testAppender.toByteArray(), StandardCharsets.UTF_8));
        endDate = new Date();
        logItems = getLogItems(testAppender.toByteArray());
        assertEquals(dayFormat.format(startDate), logItems[0]);
        logTime = timeFormat.parse(logItems[0] + " " + logItems[1]);
        assertEquals(logTime.getTime() >= startDate.getTime() && logTime.getTime() <= endDate.getTime(), true);
        assertEquals("ERROR", logItems[2]);
        assertEquals(threadName, logItems[3]);
        assertEquals(logName, logItems[4]);
        msg = new StringBuilder();
        for (int i=6;i<logItems.length;i++) {
            if (i > 6) {
                msg.append((char)' ');
            }
            msg.append(logItems[i]);
        }
        assertEquals("test log value:123\n", msg.toString());

        testAppender.reset();
        testAppender.setLevel(OFF);
        logger.warn("trace message!");
        System.out.println("[" + new String(testAppender.toByteArray(), StandardCharsets.UTF_8) + "]");
        assertEquals(testAppender.toByteArray().length, 0);
    }

    @Test
    public void testLevel() {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);
        System.out.println("level=" + logger.level());
        assertEquals(0, logger.level());

        logger.level(TRACE);
        assertEquals(TRACE, logger.level());

        logger.level(DEBUG);
        assertEquals(DEBUG, logger.level());

        logger.level(CONF);
        assertEquals(CONF, logger.level());

        logger.level(INFO);
        assertEquals(INFO, logger.level());

        logger.level(WARN);
        assertEquals(WARN, logger.level());

        logger.level(ERROR);
        assertEquals(ERROR, logger.level());

        logger.level(OFF);
        assertEquals(OFF, logger.level());
    }

    @Test
    public void testGetAppenders() {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);

        logger.setAppenders(new Appender[]{new TestAppender()});
        Appender[] appenders = logger.getAppenders();
        assertNotNull(appenders);
        assertEquals(appenders.length, 1);
    }

    @Test
    public void testAppendError() throws UnsupportedEncodingException {
        String logName = TestLogger.class.getName();
        LoggerImpl logger = new LoggerImpl(logName);

        TestErrorAppender testAppender = new TestErrorAppender();
        testAppender.setLevel(TRACE);
        logger.setAppenders(new Appender[]{testAppender});

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();

        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        try {
            logger.trace("trace message!");
            System.out.println(outContent.toString("utf-8"));
            System.setOut(originalOut);
            String errorMsg = errContent.toString("utf-8");
            assertEquals(errorMsg.contains("Reported exception:")
                    && errorMsg.contains("file not exists"), true);
            System.out.println(errorMsg);
        } finally {

            System.setErr(originalErr);
        }
    }

    private String[] getLogItems(byte[] log) {
        return new String(log, StandardCharsets.UTF_8).split(" ");
    }
}
