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

package io.edap.log.test.spi;

import io.edap.log.Appender;
import io.edap.log.LogEvent;
import io.edap.log.LogWriter;
import io.edap.log.converter.CacheDateFormatterConverter;
import io.edap.log.helps.ByteArrayBuilder;

import java.io.IOException;
import java.util.List;

import static io.edap.log.LogLevel.*;
import static io.edap.log.helps.MessageFormatter.formatTo;

public class TestAppender implements Appender {

    private final ByteArrayBuilder byteArrayBuilder = new ByteArrayBuilder();

    private CacheDateFormatterConverter dateConverter = new CacheDateFormatterConverter("yyyy-MM-dd HH:mm:ss.SSS ");

    private int level;

    @Override
    public void append(LogEvent logEvent) throws IOException {
        if (logEvent.getLevel() < level) {
            return;
        }
        dateConverter.convertTo(byteArrayBuilder, logEvent);
        byteArrayBuilder.append(levelLabel(logEvent.getLevel())).append((byte)' ');
        byteArrayBuilder.append(logEvent.getThreadName()).append((byte)' ');
        byteArrayBuilder.append(logEvent.getLoggerName()).append((byte)' ', (byte)'-', (byte)' ');
        formatTo(byteArrayBuilder, logEvent.getFormat(), logEvent.getArgv());
        byteArrayBuilder.append((byte)'\n');
    }

    @Override
    public void batchAppend(List<LogEvent> logEvents) throws IOException {

    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public void setName(String name) {

    }

    @Override
    public LogWriter getLogoutStream() {
        return null;
    }

    private byte[] levelLabel(int level) {
        switch (level) {
            case 0x100:
                return TRACE_BYTES;
            case 0x200:
                return DEBUG_BYTES;
            case 0x300:
                return CONF_BYTES;
            case 0x400:
                return INFO_BYTES;
            case 0x500:
                return WARN_BYTES;
            case 0x600:
                return ERROR_BYTES;
            case 0x700:
                return OFF_BYTES;
            default:
                return OFF_BYTES;
        }
    }

    public byte[] toByteArray() {
        return byteArrayBuilder.toByteArray();
    }

    public void reset() {
        byteArrayBuilder.reset();
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isStarted() {
        return false;
    }
}
