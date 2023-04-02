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

import io.edap.log.appenders.FileAppender;
import io.edap.log.appenders.rolling.RollingPolicy;
import io.edap.log.config.AppenderConfig;
import io.edap.log.config.AppenderConfigSection;
import io.edap.log.helps.LogEncoderRegister;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.helpers.Util.printError;

public class AppenderManager {

    private final Map<String, Appender> appenderMap;

    private static final Appender DEFAULT_CONSOLE_APPENDER;

    static {
        DEFAULT_CONSOLE_APPENDER = new Appender() {

            @Override
            public void start() {

            }

            @Override
            public void stop() {

            }

            @Override
            public boolean isStarted() {
                return true;
            }

            private String name = "console";
            @Override
            public void append(LogEvent logEvent) throws IOException {

            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public void setName(String name) {
                this.name = name;
            }

            @Override
            public LogWriter getLogoutStream() {
                return null;
            }
        };
    }

    private AppenderManager() {
        appenderMap = new ConcurrentHashMap<>();
    }

    public Appender createAppender(AppenderConfig appenderConfig) {
        Appender appender = null;
        String clazzName = appenderConfig.getClazzName();
        try {
            List<LogConfig.ArgNode> args = appenderConfig.getArgs();
            appender = (Appender)Class.forName(clazzName).newInstance();
            Encoder encoder = null;
            String name = appenderConfig.getName();
            if (StringUtil.isEmpty(name)) {
                throw new RuntimeException("appender's name cann't null");
            }
            appender.setName(name);
            String file = null;
            String prudentStr = null;
            String immediateFlushStr = null;
            RollingPolicy rollingPolicy = null;
            if (!CollectionUtils.isEmpty(args)) {
                for (LogConfig.ArgNode argNode : args) {
                    if ("encoder".equals(argNode.getName())) {
                        encoder = getEncoder(argNode);
                    } else if ("file".equals(argNode.getName())) {
                        file = argNode.getValue();
                    } else if ("prudent".equals(argNode.getName())) {
                        prudentStr = argNode.getValue();
                    } else if ("immediateFlush".equals(argNode.getName())) {
                        immediateFlushStr = argNode.getValue();
                    } else if ("rollingPolicy".equals(argNode.getName())) {
                        rollingPolicy = createRollingPolicy(argNode);
                    }
                }
            }
            if (encoder != null) {
                Method method = getMethod(appender, "encoder", Encoder.class);
                if (method != null) {
                    method.invoke(appender, encoder);
                }
            }
            if (!StringUtil.isEmpty(file)) {
                Method method = getMethod(appender, "file", String.class);
                if (method != null) {
                    method.invoke(appender, file);
                }
            }
            if (!StringUtil.isEmpty(prudentStr)) {
                try {
                    boolean prudent = Boolean.parseBoolean(prudentStr);
                    Method method = getMethod(appender, "prudent", boolean.class);
                    if (method != null) {
                        method.invoke(appender, prudent);
                    }
                } catch (Throwable t) {
                    printError("parse prudent error", t);
                }
            }
            if (!StringUtil.isEmpty(immediateFlushStr)) {
                try {
                    boolean immediate = Boolean.parseBoolean(immediateFlushStr);
                    Method method = getMethod(appender, "immediateFlush", boolean.class);
                    if (method != null) {
                        method.invoke(appender, immediate);
                    }
                } catch (Throwable t) {
                    printError("parse immediateFlush error", t);
                }
            }
            if (rollingPolicy != null) {
                rollingPolicy.start();
                rollingPolicy.setParent((FileAppender) appender);
                Method method = getMethod(appender, "rollingPolicy", RollingPolicy.class);
                if (method != null) {
                    method.invoke(appender, rollingPolicy);
                }
            }
            appender.start();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        return appender;
    }

    private RollingPolicy createRollingPolicy(LogConfig.ArgNode argNode) {
        String clsName = argNode.getAttributes().get("class");
        if (StringUtil.isEmpty(clsName)) {
            return null;
        }
        try {
            Class cls = Class.forName(clsName);
            RollingPolicy rollingPolicy = (RollingPolicy) cls.newInstance();
            if (argNode.getChilds() != null) {
                for (LogConfig.ArgNode node : argNode.getChilds()) {
                    String fieldName = node.getName();
                    Method method = getMethod(rollingPolicy, fieldName, String.class);
                    if (method == null) {
                        continue;
                    }
                    method.invoke(rollingPolicy, node.getValue());
                }
            }
            return rollingPolicy;
        } catch (Throwable t) {
            printError("createRollingPolicy error", t);
        }
        return null;
    }

    private Method getMethod(Object obj, String name, Class type) {
        try {
            Method method = obj.getClass().getMethod(
                    "set" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1),
                    type);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            printError("getMethod " + name + " error", t);
        }
        return null;
    }

    private Encoder getEncoder(LogConfig.ArgNode encoderNode) {
        if (encoderNode == null) {
            return null;
        }
        List<LogConfig.ArgNode> childNodes = encoderNode.getChilds();
        if (CollectionUtils.isEmpty(childNodes)) {
            return null;
        }
        String pattern = null;
        for (LogConfig.ArgNode node : childNodes) {
            if ("pattern".equals(node.getName())) {
                pattern = node.getValue();
            }
        }
        if (StringUtil.isEmpty(pattern)) {
            return null;
        }
        return LogEncoderRegister.instance().getEncoder(pattern);
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

    public void reloadConfig(AppenderConfigSection appenderSection) {
        List<AppenderConfig> appenderConfigList = appenderSection.getAppenderConfigs();
        if (CollectionUtils.isEmpty(appenderConfigList)) {
            return;
        }
        synchronized (appenderSection) {
            List<String> allAppenderNames = new ArrayList<>();
            for (String key : appenderMap.keySet()) {
                allAppenderNames.add(key);
            }
            for (AppenderConfig appenderConfig : appenderConfigList) {
                Appender appender = AppenderManager.instance().createAppender(appenderConfig);
                if (appender == null) {
                    continue;
                }
                String name = appender.getName();
                appenderMap.put(name, appender);
                allAppenderNames.remove(name);
            }
            // 清除没有使用的appender实例
            if (!CollectionUtils.isEmpty(allAppenderNames)) {
                for (String key : allAppenderNames) {
                    appenderMap.remove(key);
                }
            }
        }
    }

    private static class SingletonHolder {
        private static final AppenderManager INSTANCE = new AppenderManager();
    }
}
