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

import io.edap.log.config.AppenderConfigSection;
import io.edap.log.config.LoggerConfig;

import java.util.List;

public class LogConfig {
    private List<LoggerConfig> loggerSection;
    private AppenderConfigSection appenderSection;

    public List<LoggerConfig> getLoggerSection() {
        return loggerSection;
    }

    public void setLoggerSection(List<LoggerConfig> loggerSection) {
        this.loggerSection = loggerSection;
    }

    public AppenderConfigSection getAppenderSection() {
        return appenderSection;
    }

    public void setAppenderSection(AppenderConfigSection appenderSection) {
        this.appenderSection = appenderSection;
    }
}
