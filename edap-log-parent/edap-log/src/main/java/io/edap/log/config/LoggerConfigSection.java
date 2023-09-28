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

package io.edap.log.config;

import java.util.*;

public class LoggerConfigSection {
    private List<LoggerConfig> loggerConfigs;
    private boolean needReload;
    private long lastReloadTime;

    private LoggerConfig rootLoggerConfig;

    public List<LoggerConfig> getLoggerConfigs() {
        return loggerConfigs;
    }

    public void setLoggerConfigs(List<LoggerConfig> loggerConfigs) {
        this.loggerConfigs = loggerConfigs;
    }

    public boolean isNeedReload() {
        return needReload;
    }

    public void setNeedReload(boolean needReload) {
        this.needReload = needReload;
    }

    public long getLastReloadTime() {
        return lastReloadTime;
    }

    public void setLastReloadTime(long lastReloadTime) {
        this.lastReloadTime = lastReloadTime;
    }

    public LoggerConfig getRootLoggerConfig() {
        return rootLoggerConfig;
    }

    public void setRootLoggerConfig(LoggerConfig rootLoggerConfig) {
        this.rootLoggerConfig = rootLoggerConfig;
    }
}
