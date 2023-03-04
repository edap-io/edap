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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AppenderManager {

    private final Map<String, Appender> appenderMap;

    private static final Appender DEFAULT_CONSOLE_APPENDER;

    static {
        DEFAULT_CONSOLE_APPENDER = new Appender() {
            @Override
            public void append(LogEvent logEvent) throws IOException {

            }
        };
    }

    private AppenderManager() {
        appenderMap = new ConcurrentHashMap<>();
    }

    public Appender getAppender(String name) {
        Appender appender = appenderMap.get(name);
        if (appender == null) {
            return DEFAULT_CONSOLE_APPENDER;
        }
        return appender;
    }

    public static final AppenderManager instance() {
        return AppenderManager.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final AppenderManager INSTANCE = new AppenderManager();
    }
}
