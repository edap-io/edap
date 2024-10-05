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
import io.edap.nio.SelectorProvider;
import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.edap.log.helpers.Util.printError;

/**
 * Edap容器
 * @author: louis.lu
 * @date : 2019-07-08 11:49
 */
public class Edap {

    private Map<String, ServerGroup> serverGroups;

    private static final List<SelectorProvider> SELECTOR_PROVIDERS = new CopyOnWriteArrayList<>();

    public Edap() {
        serverGroups = new HashMap<>();
        init();
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
        serverGroup.setEdap(this);
        serverGroups.put(serverGroup.getName(), serverGroup);
        return this;
    }

    public Edap addServerGroups(Map<String, ServerGroup> serverGroups) {
        for (ServerGroup sg : serverGroups.values()) {
            sg.setEdap(this);
        }
        this.serverGroups.putAll(serverGroups);
        return this;
    }

    public ServerGroup getServerGroup(String name) {
        return serverGroups.get(name);
    }

    public Map<String, ServerGroup> getServerGroups() {
        return this.serverGroups;
    }

    public void addServer(Server server) {
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
    }

    public void run() throws IOException {
        if (CollectionUtils.isEmpty(serverGroups)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(shutdownThread(serverGroups));
        serverGroups.forEach((k, v) -> {
            System.out.println("ServerGroup [" + k + "] start");
            v.run();

        });

        //int read = System.in.read();
    }

    public EdapConfig getConfig() {
        return null;
    }

    private Thread shutdownThread(Map<String, ServerGroup> serverGroups) {
        return new Thread(new ShutdownRunner(serverGroups));
    }

    class ShutdownRunner implements Runnable {

        private final Map<String, ServerGroup> serverGroups;

        public ShutdownRunner(Map<String, ServerGroup> serverGroups) {
            this.serverGroups = serverGroups;
        }

        @Override
        public void run() {
            for (Map.Entry<String, ServerGroup> sgEntry : serverGroups.entrySet()) {
                System.out.println("ServerGroup [" + sgEntry.getKey() + "] stop ...");
                sgEntry.getValue().stop();
                System.out.println("ServerGroup [" + sgEntry.getKey() + "] stopped");
            }
        }
    }
}
