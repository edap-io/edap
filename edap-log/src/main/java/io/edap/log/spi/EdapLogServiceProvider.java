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

package io.edap.log.spi;

import io.edap.log.AppenderManager;
import io.edap.log.EdapLogContext;
import io.edap.log.LogConfig;
import io.edap.log.LoggerFactory;
import io.edap.log.config.AppenderConfigSection;
import io.edap.log.config.ConfigManager;
import io.edap.log.config.LoggerConfigSection;

import static io.edap.log.helpers.Util.printMsg;

public class EdapLogServiceProvider implements LoggerServiceProvider {

    EdapLogFactory edapLogFactory;

    static Boolean isSetLogFactory = false;

    public EdapLogServiceProvider() {
        ConfigManager configManager = new ConfigManager();
        LogConfig logConfig = configManager.loadConfig();
        synchronized (isSetLogFactory) {
            if (!isSetLogFactory) {
                if (logConfig != null && logConfig.getAppenderSection() != null) {
                    AppenderConfigSection appenderConfigSection = logConfig.getAppenderSection();
                    appenderConfigSection.setNeedReload(true);
                    AppenderManager.instance().reloadConfig(appenderConfigSection);
                }
                edapLogFactory = new EdapLogFactory();
                if (logConfig != null && logConfig.getLoggerSection() != null) {
                    LoggerConfigSection loggerConfigSection = logConfig.getLoggerSection();
                    loggerConfigSection.setNeedReload(true);
                    edapLogFactory.reload(loggerConfigSection);
                }
                EdapLogContext.instance().setEdapLogFactory(edapLogFactory);
                isSetLogFactory = true;
            }
        }
    }

    @Override
    public LoggerFactory getLoggerFactory() {
        printMsg("EdapLogServiceProvider...");
        return edapLogFactory;
    }
}
