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
import io.edap.log.config.LoggerConfigSection;

import java.util.List;
import java.util.Map;

public class LogConfig {

    public static class ArgNode {
        private String name;
        private Map<String, String> attributes;
        private List<ArgNode> childs;

        private String value;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }

        public List<ArgNode> getChilds() {
            return childs;
        }

        public void setChilds(List<ArgNode> childs) {
            this.childs = childs;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    private LoggerConfigSection loggerSection;
    private AppenderConfigSection appenderSection;

    public AppenderConfigSection getAppenderSection() {
        return appenderSection;
    }

    public void setAppenderSection(AppenderConfigSection appenderSection) {
        this.appenderSection = appenderSection;
    }

    public LoggerConfigSection getLoggerSection() {
        return loggerSection;
    }

    public void setLoggerSection(LoggerConfigSection loggerSection) {
        this.loggerSection = loggerSection;
    }
}
