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
import io.edap.log.config.*;
import io.edap.log.queue.DisruptorLogEventQueue;
import io.edap.log.queue.LogEventQueue;
import io.edap.util.CollectionUtils;
import io.edap.util.EdapTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.LogQueue.instanceLogQueue;
import static io.edap.log.Logger.ROOT_LOGGER_NAME;
import static io.edap.log.consts.LogConsts.DEFAULT_EVENT_QUEUE_NAME;
import static io.edap.log.helpers.Util.parseLevel;
import static io.edap.log.helpers.Util.printError;

public class EdapLogFactory implements LoggerFactory, ConfigReload<LogConfig> {

    private final Map<String, LoggerImpl> loggerCache;
    private Map<String, LoggerConfig>     loggerConfigs;
    private Map<String, LogEventQueue>    eventQueues;
    private LogConfig                     logConfig;

    final Logger root;

    public EdapLogFactory() {
        loggerCache   = new ConcurrentHashMap<>();
        loggerConfigs = new ConcurrentHashMap<>();
        root          = new LoggerImpl(ROOT_LOGGER_NAME);
        eventQueues   = new HashMap<>();
    }
    @Override
    public Logger getLogger(final String name) {
        if (name == null || ROOT_LOGGER_NAME.equals(name)) {
            return root;
        }
        LoggerImpl logger = loggerCache.get(name);
        if (logger != null) {
            return logger;
        }
        logger = new LoggerImpl(name);
        LoggerConfig loggerConfig = setLoggerLevel(logger);
        if (loggerConfig.isAsync()) {
            String queueName = loggerConfig.getQueue();
            LogEventQueue queue = buildAndStartEventQueue(queueName);
            logger.setAsync(true);
            logger.setQueue(queue);
        }
        setLoggerAppenders(logger, loggerConfig);
        loggerCache.put(name, logger);
        return logger;
    }

    private LogEventQueue buildAndStartEventQueue(String name) {
        LogEventQueue queue = eventQueues.get(name);
        if (queue == null) {
            QueueConfigSection queueSection = logConfig.getQueueConfigSection();
            QueueConfig queueConfig = null;
            if (queueSection != null) {
                List<QueueConfig> queueCfgs = queueSection.getQueueConfigList();
                if (!CollectionUtils.isEmpty(queueCfgs)) {
                    for (QueueConfig qc : queueCfgs) {
                        if (qc.getName().equalsIgnoreCase(name)) {
                            queueConfig = qc;
                            break;
                        }
                    }
                }
            }
            if (queueConfig != null) {
                try {
                    Class cls = EdapLogFactory.class.getClassLoader().loadClass(queueConfig.getClazzName());
                    queue = (LogEventQueue) instanceLogQueue(cls, queueConfig.getArgs());
                    eventQueues.put(name, queue);
                } catch (Throwable t) {
                    printError("instance " + queueConfig.getClazzName() + " error!", t);
                }
            }
            if (queue == null) {
                queue = eventQueues.get(DEFAULT_EVENT_QUEUE_NAME);
                if (queue != null) {
                    eventQueues.put(name, queue);
                    return queue;
                }
                queue = (LogEventQueue) instanceLogQueue(DisruptorLogEventQueue.class, null);
                eventQueues.put(DEFAULT_EVENT_QUEUE_NAME, queue);
                eventQueues.put(name, queue);
            }
        }
        queue.start();
        return queue;
    }

    private void setLoggerAppenders(LoggerImpl loggerImpl, LoggerConfig loggerConfig) {
        if (loggerConfig == null || loggerConfig.getAppenderRefs().size() == 0) {
            return;
        }
        List<Appender> appenders = new ArrayList<>();
        AppenderManager appenderManager = AppenderManager.instance();
        for (String appenderName : loggerConfig.getAppenderRefs()) {
            Appender appender = appenderManager.getAppender(appenderName);
            if (appender != null && !appenders.contains(appender)) {
                appenders.add(appender);
            }
        }
        Appender[] appenderArray = appenders.toArray(new Appender[0]);
        loggerImpl.setAppenders(appenderArray);
    }

    private LoggerConfig setLoggerLevel(LoggerImpl loggerImpl) {
        LoggerConfig matchConfig = null;
        LoggerConfig rootConfig  = null;
        for (Map.Entry<String, LoggerConfig> entry : loggerConfigs.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(ROOT_LOGGER_NAME)) {
                rootConfig = entry.getValue();
            }
            if (!loggerImpl.getName().contains(entry.getKey())) {
                continue;
            }
            if (matchConfig == null) {
                matchConfig = entry.getValue();
            } else {
                if (matchConfig.getName().length() < entry.getKey().length()) {
                    matchConfig = entry.getValue();
                }
            }
        }
        if (rootConfig == null) {
            rootConfig = ConfigManager.createDefaultRootLoggerConfig();
            loggerConfigs.put(ROOT_LOGGER_NAME, rootConfig);
        }
        if (matchConfig == null) {
            matchConfig = rootConfig;
        }
        loggerImpl.level(parseLevel(matchConfig.getLevel()));
        return matchConfig;
    }

    @Override
    public void reload(LogConfig logConfig) {
        this.logConfig = logConfig;
        LoggerConfigSection logConfigSection = logConfig.getLoggerSection();
        if (logConfigSection == null || !logConfigSection.isNeedReload()) {
            return;
        }
        // 如果没有任何Logger的配置则使用根节点的默认配置
        if ((logConfigSection.getLoggerConfigs() == null
                || logConfigSection.getLoggerConfigs().size() == 0)
                && logConfigSection.getRootLoggerConfig() == null) {
            LoggerConfig config = ConfigManager.createDefaultRootLoggerConfig();
            loggerConfigs.put(ROOT_LOGGER_NAME, config);
            for (Map.Entry<String, LoggerImpl> entry : loggerCache.entrySet()) {
                LoggerImpl logger = entry.getValue();
                logger.level(parseLevel(config.getLevel()));
                setLoggerAppenders(logger, config);
            }
            logConfigSection.setNeedReload(false);
            logConfigSection.setLastReloadTime(EdapTime.instance().currentTimeMillis());
            return;
        }
        loggerConfigs.clear();
        if (logConfigSection != null && logConfigSection.getRootLoggerConfig() != null) {
            loggerConfigs.put(ROOT_LOGGER_NAME, logConfigSection.getRootLoggerConfig());
        }
        if (!CollectionUtils.isEmpty(logConfigSection.getLoggerConfigs())) {
            for (LoggerConfig config : logConfigSection.getLoggerConfigs()) {
                loggerConfigs.put(config.getName(), config);
            }
        }

        for (Map.Entry<String, LoggerImpl> entry : loggerCache.entrySet()) {
            LoggerImpl logger = entry.getValue();
            LoggerConfig loggerConfig = setLoggerLevel(logger);
            setLoggerAppenders(logger, loggerConfig);
        }
        logConfigSection.setNeedReload(false);
        logConfigSection.setLastReloadTime(EdapTime.instance().currentTimeMillis());
    }
}
