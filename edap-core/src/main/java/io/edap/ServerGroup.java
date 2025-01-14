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
import io.edap.log.LoggerManager;
import io.edap.nio.AcceptDispatcher;
import io.edap.nio.impl.DisruptorAcceptDispatcher;
import io.edap.nio.impl.FastAcceptor;
import io.edap.nio.impl.NormalAcceptor;
import io.edap.nio.SelectorProvider;
import io.edap.nio.enums.EventDispatchType;
import io.edap.nio.enums.ThreadType;
import io.edap.nio.impl.ThreadPoolAcceptDispatcher;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.CollectionUtils;
import io.edap.util.ConfigUtils;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.consts.EdapConsts.SERVER_GROUP_CONFIG_KEY;

/**
 * 定义服务器组的数据结构
 * @author: louis.lu
 * @date : 2019-07-08 16:30
 */
public class ServerGroup {

    static Logger LOG = LoggerManager.getLogger(ServerGroup.class);

    /**
     * 服务器组的名称
     */
    private String       name;
    /**
     * 服务器组包含的服务器列表
     */
    private List<Server> servers;

    private Edap edap;
    /**
     * 该服务器组拥有的处理网络连接的处理器列表
     */
    private List<Acceptor> acceptors;

    /**
     * 操作添加删除服务器时的锁
     */
    private static Lock LOCK;
    /**
     * 处理IO的线程数，默认为CPU核数
     */
    private int reactorCount;

    private BufPool bufPool;
    /**
     * 线程模型
     */
    private ThreadType threadType = ThreadType.REACTOR;
    /**
     * 运行的线程模型
     */
    private ThreadType runningThreadType;

    static {
        LOCK = new ReentrantLock();
    }

    public ThreadType getThreadType() {
        return threadType;
    }

    public void setThreadType(ThreadType threadType) {
        this.threadType = threadType;
    }

    public ServerGroup() {
        servers = new ArrayList<>();
        acceptors = new ArrayList<>();
    }

    public ServerGroup setEdap(Edap edap) {
        this.edap = edap;
        return this;
    }

    public Edap getEdap() {
        return this.edap;
    }

    public ServerGroup setBufPool(BufPool bufPool) {
        this.bufPool = bufPool;
        return this;
    }

    public BufPool getBufPool() {
        synchronized (this) {
            if (bufPool == null) {
                bufPool = new SimpleFastBufPool();
            }
        }
        return this.bufPool;
    }

    public ServerGroup addServer(Server server) {
        if (server == null) {
            return this;
        }
        try {
            LOCK.lock();
            if (!servers.contains(server)) {
                servers.add(server);
            }
        } finally {
            LOCK.unlock();
        }

        return this;
    }

    public ServerGroup addServers(Collection<? extends Server> servers) {
        if (CollectionUtils.isEmpty(servers)) {
            return this;
        }
        try {
            LOCK.lock();
            for (Server server : servers) {
                if (!this.servers.contains(servers)) {
                    this.servers.add(server);
                }
            }
        } finally {
            LOCK.unlock();
        }
        return this;
    }

    public List<Server> getServers() {
        return this.servers;
    }

    public String getName() {
        return name;
    }

    public ServerGroup setName(String name) {
        this.name = name;
        return this;
    }

    public ServerGroup setReactorCount(int reactorCount) {
        this.reactorCount = reactorCount;
        return this;
    }

    public int getReactorCount() {
        return this.reactorCount;
    }

    /**
     * 先将服务组里每个服务初始化后再启动各个服务的监听，以防端口监听后服务还未启动导致服务无法响应的问题。
     */
    public void run() {
        EventDispatchType eventDispatchType = parseEventDispatchType(edap.getConfig());
        ThreadType        threadType        = parseThreadType(edap.getConfig());
        List<SelectorProvider> providers = edap.getSelectorProviders();
        if (CollectionUtils.isEmpty(providers)) {
            LOG.error("SelectorProvider is null");
            System.exit(1);
        }
        Acceptor acceptor;
        SelectorProvider selectorProvider;
        if (eventDispatchType == EventDispatchType.KEY_SET) {
            acceptor = new FastAcceptor();
            selectorProvider = getSelectorProvider(acceptor);
            if (selectorProvider == null) {
                LOG.warn("serverGroup {} set eventDispatchType is {} but not SelectorProvider enabled!",
                        l -> l.arg(getName()).arg(eventDispatchType));
                acceptor = new NormalAcceptor();
                selectorProvider = getSelectorProvider(acceptor);
            }
        } else {
            acceptor = new NormalAcceptor();
            selectorProvider = getSelectorProvider(acceptor);
        }
        if (selectorProvider == null) {
            System.err.println("No selectorProvider enabled");
            System.exit(1);
        }
        Acceptor fAcceptor = acceptor;
        SelectorProvider provider = selectorProvider;
        LOG.info("serverGroup {} acceptorName {} selectorProvider name {}",
                l -> l.arg(fAcceptor.getClass().getName()).arg(provider.getClass().getName()));

        AcceptDispatcher dispatcher;
        if (threadType == ThreadType.EDAP) {
            dispatcher = new DisruptorAcceptDispatcher();
        } else {
            dispatcher = new ThreadPoolAcceptDispatcher();
        }
        for (Server s : servers) {
            s.init();
            List<Server.Addr> addrs = s.getListenAddrs();
            Acceptor acpt;
            if (fAcceptor instanceof FastAcceptor) {
                acpt = new FastAcceptor();
                acpt.setSelectorProvider(provider);
            } else {
                acpt = new NormalAcceptor();
                acpt.setSelectorProvider(provider);
            }
            acpt.setServerGroup(this);
            acpt.setEventDispatcher(dispatcher);
            acpt.setServer(s);
            acpt.addAddrs(addrs);
            acpt.accept();
        }
        LOG.info("{}",  l -> l.arg(providers));
    }



    public void stop() {

    }

    private SelectorProvider getSelectorProvider(Acceptor acceptor) {
        for (SelectorProvider provider : edap.getSelectorProviders()) {
            if (acceptor.isEnable(provider)) {
                return provider;
            }
        }

        return null;
    }

    private EventDispatchType parseEventDispatchType(EdapConfig config) {
        String dispatchTypeKey = SERVER_GROUP_CONFIG_KEY + "." + this.getName() + ".eventDispatchType";
        Object eventDispatchValue = ConfigUtils.getConfigValue(dispatchTypeKey, edap.getConfig(),
                "KEY_SET");
        EventDispatchType eventDispatchType;
        try {
            eventDispatchType = EventDispatchType.valueOf(((String)eventDispatchValue).toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            LOG.warn("EventDispatchType parse error", e);
            eventDispatchType = EventDispatchType.KEY_SET;
        }

        return eventDispatchType;
    }

    private ThreadType parseThreadType(EdapConfig config) {
        String threadTypeKey   = SERVER_GROUP_CONFIG_KEY + "." + this.getName() + ".threadType";
        Object threadTypeValue = ConfigUtils.getConfigValue(threadTypeKey, edap.getConfig(),
                "EDAP");
        ThreadType threadType;
        try {
            threadType = ThreadType.valueOf(((String)threadTypeValue).toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            LOG.warn("EventDispatchType parse error", e);
            threadType = ThreadType.EDAP;
        }

        return threadType;
    }
}
