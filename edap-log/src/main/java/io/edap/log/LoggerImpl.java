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

package io.edap.log;

import io.edap.log.helps.LogTime;

import java.util.function.Consumer;

import static io.edap.log.LogLevel.*;

public class LoggerImpl implements Logger {

    private int level;
    private String name;

    private Appender[] appenders;

    private static LogTime LOG_TIME = LogTime.instance();

    private final ThreadLocal<LogArgsImpl> threadLocalArgs = ThreadLocal.withInitial(LogArgsImpl::new);

    public LoggerImpl(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public void trace(Object message) {
        log(TRACE, message);
    }

    @Override
    public void trace(String msg, Throwable cause) {
        log(TRACE, msg, cause);
    }

    @Override
    public void trace(String format, Consumer<LogArgs> logArgsConsumer) {
        log(TRACE, format, logArgsConsumer);
    }

    @Override
    public void debug(Object message) {
        log(DEBUG, message);
    }

    @Override
    public void debug(String msg, Throwable cause) {
        log(DEBUG, msg, cause);
    }

    @Override
    public void debug(String format, Consumer<LogArgs> logArgsConsumer) {
        log(DEBUG, format, logArgsConsumer);
    }

    @Override
    public void conf(Object message) {
        log(CONF, message);
    }

    @Override
    public void conf(String msg, Throwable cause) {
        log(CONF, msg, cause);
    }

    @Override
    public void conf(String format, Consumer<LogArgs> logArgsConsumer) {
        log(CONF, format, logArgsConsumer);
    }

    @Override
    public void info(Object message) {
        log(INFO, message);
    }

    @Override
    public void info(String msg, Throwable cause) {
        log(INFO, msg, cause);
    }

    @Override
    public void info(String format, Consumer<LogArgs> logArgsConsumer) {
        log(INFO, format, logArgsConsumer);
    }

    @Override
    public void warn(Object message) {
        log(WARN, message);
    }

    @Override
    public void warn(String msg, Throwable cause) {
        log(WARN, msg, cause);
    }

    @Override
    public void warn(String format, Consumer<LogArgs> logArgsConsumer) {
        log(WARN, format, logArgsConsumer);
    }

    @Override
    public void error(Object message) {
        log(ERROR, message);
    }

    @Override
    public void error(String msg, Throwable cause) {
        log(ERROR, msg, cause);
    }

    @Override
    public void error(String format, Consumer<LogArgs> logArgsConsumer) {
        log(ERROR, format, logArgsConsumer);
    }


    private void flush(LogArgsImpl logArgs) {
        LogEvent logEvent = new LogEvent();
        logEvent.setLogTime(LOG_TIME.getLogTime());
        logEvent.setFormat(logArgs.getFormat());
        logEvent.setThrew(logArgs.getThrowable());
        Object[] argv = new Object[logArgs.getArgc()];
        System.arraycopy(logArgs.getArgv(), 0, argv, 0, logArgs.getArgc());
        logEvent.setArgv(argv);
        logEvent.setLevel(logArgs.level());
        logArgs.reset();
    }

    @Override
    public int level() {
        return level;
    }

    @Override
    public boolean isEnabled(int level) {
        return level >= this.level;
    }

    @Override
    public void level(int level) {
        this.level = level;
    }

    private void log(int level, Object message) {
        LogArgsImpl logArgs = threadLocalArgs.get();
        logArgs.level(level).message(message);
        flush(logArgs);
    }

    private void log(int level, String msg, Throwable cause) {
        if (isEnabled(level)) {
            LogArgsImpl logArgs = threadLocalArgs.get();
            logArgs.level(level).message(msg).threw(cause);
            flush(logArgs);
        }
    }

    private void log(int level, String format, Consumer<LogArgs> logArgsConsumer) {
        if (isEnabled(level)) {
            LogArgsImpl logArgs = threadLocalArgs.get();
            logArgs.level(level).format(format);
            logArgsConsumer.accept(logArgs);
            flush(logArgs);
        }
    }

    public Appender[] getAppenders() {
        return appenders;
    }

    public void setAppenders(Appender[] appenders) {
        this.appenders = appenders;
    }
}