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

import io.edap.log.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.Logger.ROOT_LOGGER_NAME;

public class EdapLogFactory implements LoggerFactory, ConfigReload {

    private Map<String, Logger> loggerCache;

    final Logger root;

    public EdapLogFactory() {
        loggerCache = new ConcurrentHashMap<>();
        root = new LoggerImpl(ROOT_LOGGER_NAME);
    }
    @Override
    public Logger getLogger(final String name) {
        if (name == null || ROOT_LOGGER_NAME.equals(name)) {
            return root;
        }
        Logger logger = loggerCache.get(name);
        if (logger != null) {
            return logger;
        }
        logger = new LoggerImpl(name);
        loggerCache.put(name, logger);
        return logger;
    }

    @Override
    public void reload(LogConfig logConfig) {

    }
}
