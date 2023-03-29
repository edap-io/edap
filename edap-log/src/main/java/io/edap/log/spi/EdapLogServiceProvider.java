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

import io.edap.log.EdapLogContext;
import io.edap.log.LoggerFactory;
import io.edap.log.config.ConfigManager;

import static io.edap.log.helpers.Util.printMsg;

public class EdapLogServiceProvider implements LoggerServiceProvider {

    EdapLogFactory edapLogFactory;

    public EdapLogServiceProvider() {
        ConfigManager configManager = new ConfigManager();
        configManager.loadConfig();
        edapLogFactory = new EdapLogFactory();
        EdapLogContext.instance().setEdapLogFactory(edapLogFactory);
    }

    @Override
    public LoggerFactory getLoggerFactory() {
        printMsg("EdapLogServiceProvider...");
        return edapLogFactory;
    }
}
