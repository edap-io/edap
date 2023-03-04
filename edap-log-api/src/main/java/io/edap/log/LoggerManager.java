/*
 * Copyright 2021 The edap Project
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

import io.edap.log.spi.LoggerServiceProvider;
import io.edap.log.spi.NopLoggerFactory;
import io.edap.log.helpers.Util;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.edap.log.helpers.Util.printError;

/**
 * 日志管理器
 * @author louis
 */
public class LoggerManager {

    private static final List<LoggerServiceProvider> SPI_PROVIDERS = new CopyOnWriteArrayList<>();
    private static Boolean INITED = null;
    private static String SPI_PROVIDER_NAME = null;

    public static final Logger NOP_LOGGER = new NopLogger();

    private static LoggerFactory LOGGER_FACTORY = null;

    private LoggerManager() {}

    public static Logger getLogger(Class clazz) {
        return getLogger(clazz.getName());
    }

    public static Logger getLogger(String name) {
        if (LOGGER_FACTORY != null) {
            return LOGGER_FACTORY.getLogger(name);
        }
        if (INITED == null) {
            init();
        }
        if (LOGGER_FACTORY != null) {
            return LOGGER_FACTORY.getLogger(name);
        }
        return NOP_LOGGER;
    }

    public static void setSpiProviderName(String name, boolean useDefault) {
        SPI_PROVIDER_NAME = name;
        if (SPI_PROVIDERS != null && SPI_PROVIDERS.size() > 0) {
            for (LoggerServiceProvider lsp : SPI_PROVIDERS) {
                if (lsp.getClass().getName().equals(name)) {
                    LOGGER_FACTORY = lsp.getLoggerFactory();
                    return;
                }
            }
            if (useDefault) {
                if (LOGGER_FACTORY == null) {
                    LOGGER_FACTORY = SPI_PROVIDERS.get(0).getLoggerFactory();
                }
            }
        }
    }

    public static void setSpiProviderName(String name) {
        setSpiProviderName(name, true);
    }

    private static String getSysSpiProviderName() {
        String spiProvider = System.getProperty("edap_log_spi_provider");
        if (spiProvider != null && spiProvider.trim().length() > 0) {
            return spiProvider;
        }
        return System.getenv("edap_log_spi_provider");
    }

    public static String getSpiProviderName() {
        return SPI_PROVIDER_NAME;
    }

    private synchronized static void init() {
        if (INITED != null) {
            return;
        }
        ClassLoader managerClassLoader = LoggerManager.class.getClassLoader();
        ServiceLoader<LoggerServiceProvider> loader;
        loader = ServiceLoader.load(LoggerServiceProvider.class, managerClassLoader);
        Iterator<LoggerServiceProvider> iterator = loader.iterator();
        while (iterator.hasNext()) {
            LoggerServiceProvider provider = safelyInstantiate(iterator);
            if (provider != null) {
                SPI_PROVIDERS.add(provider);
            }
        }
        if (SPI_PROVIDERS != null && SPI_PROVIDERS.size() > 0) {
            setSpiProviderName(getSysSpiProviderName(), true);
            INITED = true;
        }
        if (LOGGER_FACTORY == null) {
            Util.printError("Can't found LoggerServiceProvider");
            LOGGER_FACTORY = new NopLoggerFactory();
            Util.printError("Use NopLoggerFactory");
            INITED = true;
        }
    }

    private static LoggerServiceProvider safelyInstantiate(Iterator<LoggerServiceProvider> iterator) {
        try {
            LoggerServiceProvider provider = iterator.next();
            return provider;
        } catch (ServiceConfigurationError e) {
            printError("A EdapLog service provider failed to instantiate:", e);
        }
        return null;
    }
}
