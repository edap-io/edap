/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap;

import io.edap.config.EdapConfig;
import io.edap.log.Logger;
import io.edap.log.LoggerFactory;
import io.edap.log.LoggerManager;
import io.edap.nio.SelectorProvider;
import io.edap.props.Props;
import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.log.helpers.Util.printError;

/**
 * Edap容器
 * @author: louis.lu
 * @date : 2019-07-08 11:49
 */
public class Edap {

    private static final Logger log = LoggerManager.getLogger(Edap.class);

    private Map<String, ServerGroup> serverGroups;

    private static final List<SelectorProvider> SELECTOR_PROVIDERS = new CopyOnWriteArrayList<>();

    private          int                  monitIndex = 0;
    private volatile EdapState            state;
    private          Map<String, Integer> monitorIndexs = new HashMap<>();
    private final    ReentrantLock        lifecycleLock = new ReentrantLock();

    public Edap() {
        serverGroups = new HashMap<>();
        init();
        state = EdapState.NEW;
    }

    private void init() {
        ClassLoader providerClassLoader = SelectorProvider.class.getClassLoader();
        ServiceLoader<SelectorProvider> loader;
        loader = ServiceLoader.load(SelectorProvider.class, providerClassLoader);
        Iterator<SelectorProvider> iterator = loader.iterator();
        while (iterator.hasNext()) {
            SelectorProvider provider = safelyInstantiate(iterator);
            if (provider != null && !exits(provider)) {
                SELECTOR_PROVIDERS.add(provider);
            }
        }
    }

    public Props getProps() {
        // 完整配置由 EdapConfig 加载后构造；当前 stub 阶段先返回 null（容器侧 Environment 构造由 Container.start 接好）
        return new Props(new HashMap<>());
    }

    public synchronized int getMonitorIndex(String key) {
        Integer v = monitorIndexs.get(key);
        if (v == null) {
            monitorIndexs.put(key, monitIndex++);
            return monitIndex;
        } else {
            return v;
        }
    }

    public List<SelectorProvider> getSelectorProviders() {
        return Collections.unmodifiableList(SELECTOR_PROVIDERS);
    }

    private static SelectorProvider safelyInstantiate(Iterator<SelectorProvider> iterator) {
        try {
            SelectorProvider provider = iterator.next();
            return provider;
        } catch (ServiceConfigurationError e) {
            printError("A EdapLog service provider failed to instantiate:", e);
        }
        return null;
    }

    private static boolean exits(SelectorProvider provider) {
        if (SELECTOR_PROVIDERS == null || SELECTOR_PROVIDERS.size() == 0) {
            return false;
        }
        for (SelectorProvider p : SELECTOR_PROVIDERS) {
            if (p.getClass().getName().equals(provider.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    public Edap addServerGroup(ServerGroup serverGroup) {
        lifecycleLock.lock();
        try {
            serverGroup.setEdap(this);
            serverGroups.put(serverGroup.getName(), serverGroup);
            return this;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public Edap addServerGroups(Map<String, ServerGroup> serverGroups) {
        lifecycleLock.lock();
        try {
            for (ServerGroup sg : serverGroups.values()) {
                sg.setEdap(this);
            }
            this.serverGroups.putAll(serverGroups);
            return this;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ServerGroup getServerGroup(String name) {
        return serverGroups.get(name);
    }

    public Map<String, ServerGroup> getServerGroups() {
        return this.serverGroups;
    }

    public void addServer(Server server) {
        lifecycleLock.lock();
        try {
            ServerGroup sg = null;
            if (CollectionUtils.isEmpty(serverGroups)) {
                sg = new ServerGroup();
                sg.setEdap(this);
                serverGroups.put("default", sg);
            } else {
                for (Map.Entry<String, ServerGroup> entry : serverGroups.entrySet()) {
                    if (sg == null) {
                        sg = entry.getValue();
                    }
                    if ("default".equals(entry.getKey())) {
                        sg = entry.getValue();
                    }
                }
            }
            if (sg != null) {
                sg.addServer(server);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void run() throws IOException {
        lifecycleLock.lock();
        try {
            if (CollectionUtils.isEmpty(serverGroups)) {
                return;
            }

            Runtime.getRuntime().addShutdownHook(shutdownThread(this));
            serverGroups.forEach((k, v) -> {
                System.out.println("ServerGroup [" + k + "] start");
                v.run();

            });
        } finally {
            lifecycleLock.unlock();
        }
        //int read = System.in.read();
    }


    public EdapConfig getConfig() {
        return null;
    }

    private Thread shutdownThread(Edap edap) {
        return new Thread(new ShutdownRunner(edap));
    }

    public void stop() {
        EdapState saved;
        try {
            if (state == EdapState.STOPPED) {
                return;     // 幂等 no-op
            }
            if (state == EdapState.NEW) {                // 还没 start 过
                state = EdapState.STOPPED;
                return;
            }
            if (state == EdapState.STOPPING) {
                return;    // 已经在停
            }
            state.checkTransitionTo(EdapState.STOPPING);
            state = EdapState.STOPPING;
            saved = state;
        } finally {
            lifecycleLock.unlock();
        }
        doStop();   // 释放锁后再做 I/O（s.stop 可能阻塞）
    }

    private void doStop() {
        System.out.println("Edap stop...");
        log.info("Edap stop...");
        for (Map.Entry<String, ServerGroup> sgEntry : serverGroups.entrySet()) {
            System.out.println("ServerGroup [" + sgEntry.getKey() + "] stop ...");
            log.info("ServerGroup [{}] stop ...", l -> l.arg(sgEntry.getKey()));
            sgEntry.getValue().stop();
            System.out.println("ServerGroup [" + sgEntry.getKey() + "] stopped");
            log.info("ServerGroup [{}] stopped", l -> l.arg(sgEntry.getKey()));
        }
        System.out.println("Edap stopped");
        log.info("Edap stopped");
        lifecycleLock.lock();
        try {
            state = EdapState.STOPPED;
        } finally {
            lifecycleLock.unlock();
        }
    }

    class ShutdownRunner implements Runnable {

        private final Edap edap;

        public ShutdownRunner(Edap edap) {
            this.edap = edap;
        }

        @Override
        public void run() {
            edap.stop();
        }
    }
}
