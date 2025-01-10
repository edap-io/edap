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
import io.edap.log.appenders.rolling.TriggeringPolicy;
import io.edap.log.config.AppenderConfig;
import io.edap.log.config.AppenderConfigSection;
import io.edap.log.config.QueueConfig;
import io.edap.log.config.QueueConfigSection;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.LogEncoderRegister;
import io.edap.log.queue.DisruptorLogDataQueue;
import io.edap.log.queue.LogDataQueue;
import io.edap.log.spi.EdapLogFactory;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.LogQueue.instanceLogQueue;
import static io.edap.log.consts.LogConsts.DEFAULT_DATA_LOG_QUEUE_NAME;
import static io.edap.log.consts.LogConsts.DEFAULT_EVENT_QUEUE_NAME;
import static io.edap.log.helpers.Util.printError;
import static io.edap.util.ClazzUtil.getClassMethods;

public class AppenderManager {

    private final Map<String, Appender> appenderMap;

    private static final Appender DEFAULT_CONSOLE_APPENDER;

    private Map<String, LogDataQueue>    logDataQueues;

    static {
        DEFAULT_CONSOLE_APPENDER = buildNopAppender();
    }

    private AppenderManager() {
        appenderMap   = new ConcurrentHashMap<>();
        logDataQueues = new ConcurrentHashMap<>();
    }

    public Appender createAppender(AppenderConfig appenderConfig, QueueConfigSection queueConfigSection) {
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
            String           file              = null;
            String           prudentStr        = null;
            String           immediateFlushStr = null;
            RollingPolicy    rollingPolicy     = null;
            TriggeringPolicy triggeringPolicy  = null;
            String           queueName         = null;
            boolean          async             = false;
            String target = null;
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
                    } else if ("triggeringPolicy".equals(argNode.getName())) {
                        triggeringPolicy = createTriggeringPolicy(argNode);
                    } else if ("target".equals(argNode.getName())) {
                        target = argNode.getValue();
                    } else if ("async".equals(argNode.getName())) {
                        queueName = argNode.getValue();
                        if (StringUtil.isEmpty(queueName) && !CollectionUtils.isEmpty(argNode.getAttributes())
                                && argNode.getAttributes().containsKey("queue")) {
                            queueName = argNode.getAttributes().get("queue");
                        }
                        async = true;
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
            if (triggeringPolicy != null) {
                if (appender instanceof FileAppender) {
                    triggeringPolicy.setFileAppender((FileAppender) appender);
                }
                triggeringPolicy.start();
                Method method = getMethod(appender, "triggeringPolicy", TriggeringPolicy.class);
                if (method != null) {
                    method.invoke(appender, triggeringPolicy);
                }
            }
            if (target != null) {
                Method method = getMethod(appender, "target", String.class);
                if (method != null) {
                    method.invoke(appender, target);
                }
            }
            appender.setAsync(async);
            if (async) {
                if (StringUtil.isEmpty(queueName)) {
                    queueName = DEFAULT_DATA_LOG_QUEUE_NAME;
                }
                if (queueConfigSection == null) {
                    queueConfigSection = new QueueConfigSection();
                    queueConfigSection.setQueueConfigList(new ArrayList<>());
                }
                LogDataQueue queue = buildEventQueue(queueName, queueConfigSection.getQueueConfigList());
                appender.setAsyncQueue(queue);
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

    private LogDataQueue buildEventQueue(String name, List<QueueConfig> queueConfigs) {
        LogDataQueue queue = logDataQueues.get(name);
        if (queue == null) {
            QueueConfig queueConfig = null;
            if (!CollectionUtils.isEmpty(queueConfigs)) {
                for (QueueConfig qc : queueConfigs) {
                    if (qc.getName().equalsIgnoreCase(name)) {
                        queueConfig = qc;
                        break;
                    }
                }
            }
            if (queueConfig != null) {
                try {
                    Class cls = EdapLogFactory.class.getClassLoader().loadClass(queueConfig.getClazzName());
                    queue = (LogDataQueue)instanceLogQueue(cls, queueConfig.getArgs());
                    logDataQueues.put(name, queue);
                } catch (Throwable t) {
                    printError("instance " + queueConfig.getClazzName() + " error!", t);
                }
            }
            if (queue == null) {
                queue = logDataQueues.get(DEFAULT_EVENT_QUEUE_NAME);
                if (queue != null) {
                    logDataQueues.put(name, queue);
                    return queue;
                }
                queue = (LogDataQueue) instanceLogQueue(DisruptorLogDataQueue.class, null);
                logDataQueues.put(DEFAULT_EVENT_QUEUE_NAME, queue);
                logDataQueues.put(name, queue);
            }
        }
        return queue;
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
                    setObjectField(rollingPolicy, fieldName, node.getValue());
                }
            }
            return rollingPolicy;
        } catch (Throwable t) {
            printError("createRollingPolicy error", t);
        }
        return null;
    }

    private TriggeringPolicy createTriggeringPolicy(LogConfig.ArgNode argNode) {
        String clsName = argNode.getAttributes().get("class");
        if (StringUtil.isEmpty(clsName)) {
            return null;
        }
        try {
            Class cls = Class.forName(clsName);
            TriggeringPolicy rollingPolicy = (TriggeringPolicy) cls.newInstance();
            if (argNode.getChilds() != null) {
                for (LogConfig.ArgNode node : argNode.getChilds()) {
                    String fieldName = node.getName();
                    setObjectField(rollingPolicy, fieldName, node.getValue());
                }
            }
            return rollingPolicy;
        } catch (Throwable t) {
            printError("createRollingPolicy error", t);
        }
        return null;
    }

    private void setObjectField(Object obj, String name, String value) throws InvocationTargetException, IllegalAccessException {
        List<Method> methods = getClassMethods(obj.getClass());
        if (methods == null) {
            return;
        }
        String setName = "set" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);
        for (Method m : methods) {
            String methodName = m.getName();
            if (methodName.equals(setName) && m.getParameterTypes().length == 1
                    && Modifier.isPublic(m.getModifiers())) {
                Class type = m.getParameterTypes()[0];
                m.setAccessible(true);
                switch (type.getName()) {
                    case "java.lang.String":
                        m.invoke(obj, value);
                        break;
                    case "int":
                        m.invoke(obj, Integer.parseInt(value));
                        break;
                    case "java.lang.Integer":
                        m.invoke(obj, Integer.valueOf(value));
                        break;
                    case "boolean":
                        m.invoke(obj, Boolean.parseBoolean(value));
                        break;
                    case "java.lang.Boolean":
                        m.invoke(obj, Boolean.valueOf(value));
                        break;
                    default:

                }
            }
        }
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

    public void reloadConfig(LogConfig logConfig) {
        AppenderConfigSection appenderSection = logConfig.getAppenderSection();
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
                Appender appender = AppenderManager.instance()
                        .createAppender(appenderConfig, logConfig.getQueueConfigSection());
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

    private static Appender buildNopAppender () {
        return new Appender() {

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
            public void batchAppend(List<LogEvent> logEvents) throws IOException {

            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public void setAsync(boolean async) {

            }

            @Override
            public void setAsyncQueue(LogDataQueue logDataQueue) {

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
}
