/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.log.queue;

import io.edap.log.Appender;
import io.edap.log.LogArgsImpl;
import io.edap.log.LogEvent;
import io.edap.log.LogQueue;

import java.io.IOException;

import static io.edap.log.LoggerImpl.LOG_TIME;
import static io.edap.log.helpers.Util.printError;

/**
 * 异步处理日志的队列
 */
public interface LogEventQueue extends LogQueue<LogArgsImpl, LogEvent> {

    static void handleEvent(LogEvent event, long sequence, boolean endOfBatch) {
        Appender[] appenders = event.getAppenders();
        if (appenders.length <= 0) {
            return;
        }
        for (Appender appender : appenders) {
            try {
                appender.append(event);
            } catch (IOException e) {
                printError(e.getMessage(), e);
            }
        }
    }

    static void translate(LogEvent event, long sequence, LogArgsImpl logArgs) {
        translateLogEvent(event, logArgs);
        event.setAppenders(logArgs.getAppenders());
    }

    static void translateLogEvent(LogEvent event, LogArgsImpl logArgs) {
        event.setLogTime(LOG_TIME.currentTimeMillis());
        event.setFormat(logArgs.getFormat());
        event.setThrew(logArgs.getThrowable());
        Object[] argv;
        if (logArgs.getThrowable() != null) {
            argv = new Object[logArgs.getArgc() + 1];
            argv[logArgs.getArgc()]  = logArgs.getThrowable();
        } else {
            argv = new Object[logArgs.getArgc()];
        }
        System.arraycopy(logArgs.getArgv(), 0, argv, 0, logArgs.getArgc());
        event.setArgv(argv);
        event.setLevel(logArgs.level());
        event.setLoggerName(logArgs.getLoggerName());
        event.setThreadName(Thread.currentThread().getName());
    }
}
