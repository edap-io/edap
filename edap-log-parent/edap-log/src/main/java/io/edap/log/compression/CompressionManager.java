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

package io.edap.log.compression;

import io.edap.log.LogCompression;

import java.util.Iterator;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.helpers.Util.printError;

public class CompressionManager {

    private Map<String, LogCompression> compressionMap = new ConcurrentHashMap<>();

    private CompressionManager() {
        try {
            ClassLoader managerClassLoader = CompressionManager.class.getClassLoader();
            ServiceLoader<LogCompression> loader;
            loader = ServiceLoader.load(LogCompression.class, managerClassLoader);
            Iterator<LogCompression> iterator = loader.iterator();
            while (iterator.hasNext()) {
                LogCompression compression = safelyInstantiate(iterator);
                if (compression != null) {
                    compressionMap.put(compression.getSuffix(), compression);
                }
            }
        } catch (Throwable t) {
            printError("Load LogCompression SPI eror!", t);
        }
        compressionMap.put("gz", new GzCompression());
        compressionMap.put("zip", new ZipCompression());
    }

    public Map<String, LogCompression> getCompressionMap() {
        return compressionMap;
    }

    private static LogCompression safelyInstantiate(Iterator<LogCompression> iterator) {
        try {
            LogCompression compression = iterator.next();
            return compression;
        } catch (ServiceConfigurationError e) {
            printError("A Edap LogCompression failed to instantiate:", e);
        }
        return null;
    }


    public static CompressionManager getInstance() {
        return SingletonHolder.INSTANCE;
    }


    private static class SingletonHolder {
        private static final CompressionManager INSTANCE = new CompressionManager();
    }
}
