/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.util;

import io.edap.config.EdapConfig;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.edap.util.AsmUtil.isPojo;

public class ConfigUtils {

    private static Logger LOG = LoggerManager.getLogger(ConfigUtils.class);

    private ConfigUtils() {}

    public static Object getConfigValue(String key, EdapConfig config) {
        return getConfigValue(key, config, null);
    }

    public static Object getConfigValue(String key, EdapConfig config, Object defaultValue) {
        if (key == null) {
            return defaultValue;
        }
        List<String> keys = parseKey(key);
        Object v;
        if (config == null) {
            v = parseEnvValue(keys);
        } else {
            v = parseConfigValue(keys, config);
        }
        return v == null?defaultValue:v;
    }

    private static List<String> parseKey(String key) {
        List<String> keys = new ArrayList<>();
        int start = 0;
        int dotIndex = key.indexOf('.', start);
        while (dotIndex != -1) {
            if (dotIndex > start) {
                keys.add(key.substring(start, dotIndex));
            }
            start = dotIndex + 1;
            dotIndex = key.indexOf('.', start);
        }
        if (key.length() > start || key.isEmpty()) {
            keys.add(key.substring(start));
        }
        return keys;
    }

    private static Object parseConfigValue(List<String> keys, EdapConfig config) {
        if (config == null || keys.isEmpty()) {
            return null;
        }
        int pathIndex = 0;
        String key = keys.get(pathIndex);
        Object v = parseBeanValue(key, config);
        while (pathIndex != keys.size() - 1) {
            if (v instanceof Map<?, ?>) {
                pathIndex++;
                key = keys.get(pathIndex);
                v = ((Map<?, ?>)v).get(key);
            } else if (isPojo(v.getClass())) {
                pathIndex++;
                key = keys.get(pathIndex);
                v = parseBeanValue(key, v);
            } else {
                StringBuilder path = new StringBuilder();
                for (int i=0;i<=pathIndex;i++) {
                    if (i > 0) {
                        path.append(',');
                    }
                    path.append(keys.get(i));
                }
                Object fv = v;
                LOG.warn("config [{}] can't parse value value type is [{}]", l -> l.arg(path).arg(fv.getClass()));
                return null;
            }

        }
        return v;
    }

    private static Object parseBeanValue(String key, Object bean) {
        if (bean == null) {
            return null;
        }
        Object v;
        try {
            Field field = bean.getClass().getDeclaredField(key);
            if (!Modifier.isPublic(field.getModifiers())) {
                field.setAccessible(true);
            }
            v = field.get(bean);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.warn("bean [{}] hasn't {} field", l -> l.arg(bean.getClass()).arg(key).arg(e));
            v = null;
        }

        return v;
    }

    private static Object parseEnvValue(List<String> keys) {
        StringBuilder keyBuilder = new StringBuilder();
        for (String k : keys) {
            if (!keyBuilder.isEmpty()) {
                keyBuilder.append(".");
            }
            keyBuilder.append(k);
        }
        String key = keyBuilder.toString();
        String v = System.getProperty(key);
        if (v == null) {
            v = System.getenv(key);
        }
        return v;
    }
}
