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

package io.edap.log;

import java.util.Map;

/**
 * 日志事件消息定义
 */
public class LogEvent {
    private long logTime;
    private String loggerName;
    private String threadName;
    private String format;
    private Object[] argv;
    private Throwable threw;
    private int level;
    private Map<String, Object> mdc;

    public long getLogTime() {
        return logTime;
    }

    public void setLogTime(long logTime) {
        this.logTime = logTime;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public void setLoggerName(String loggerName) {
        this.loggerName = loggerName;
    }

    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public Map<String, Object> getMdc() {
        return mdc;
    }

    public void setMdc(Map<String, Object> mdc) {
        this.mdc = mdc;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Object[] getArgv() {
        return argv;
    }

    public void setArgv(Object[] argv) {
        this.argv = argv;
    }

    public Throwable getThrew() {
        return threw;
    }

    public void setThrew(Throwable threw) {
        this.threw = threw;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }
}
