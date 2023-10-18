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

import java.util.List;

/**
 * edap日志appender配置段的定义，包括是否有配置更新以及最后被加载时间
 */
public class AppenderConfigSection {
    private List<AppenderConfig> appenderConfigs;
    private boolean needReload;
    private long lastReloadTime;

    public List<AppenderConfig> getAppenderConfigs() {
        return appenderConfigs;
    }

    public void setAppenderConfigs(List<AppenderConfig> appenderConfigs) {
        this.appenderConfigs = appenderConfigs;
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
}