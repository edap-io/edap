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
import io.edap.nio.AcceptDispatcherFactory;
import io.edap.nio.IoSelectorManager;
import io.edap.nio.ReadDispatcherFactory;
import io.edap.nio.SelectorProvider;
import io.edap.nio.impl.FastAcceptor;
import io.edap.nio.impl.NormalAcceptor;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.enums.EventDispatchType;
import io.edap.nio.enums.ThreadType;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.CollectionUtils;
import io.edap.nio.util.ConfigUtils;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.nio.consts.EdapConsts.SERVER_GROUP_CONFIG_KEY;

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
     * run() 阶段从配置解析出的 ThreadType,post-run addServerAndBind 复用。
     * run() 之前为 null。
     */
    private ThreadType runningThreadType;
    /**
     * run() 阶段选定并保留的 Acceptor,用于 addServerAndBind post-run 路径复用
     * (run() 之前为 null)。详见 {@link #addServerAndBind}。
     */
    private Acceptor runningAcceptor;
    /**
     * run() 阶段选定的 SelectorProvider,post-run addServerAndBind 复用。
     */
    private SelectorProvider runningProvider;

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
        servers   = new ArrayList<>();
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
                server.setServerGroup(this);
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
                    server.setServerGroup(this);
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
     * 先将服务组里每个服务初始化后再启动各个服务的监听,以防端口监听后服务还未启动导致服务无法响应的问题。
     *
     * <p><b>run() 之后的 server 增量加入</b>:本方法会把选定的 {@code acceptor} / {@code provider} /
     * {@code threadType} 缓存到实例字段,供 {@link #addServerAndBind} post-run 路径复用。
     * run() 之前调 {@code addServer} 只入队,server 不 listen;run() 之后调
     * {@link #addServerAndBind} 走 {@link #bindServer} 完成 init+listen+accept。
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
        // 缓存到实例字段,供 addServerAndBind post-run 路径复用
        final Acceptor finalAcceptor = acceptor;
        final SelectorProvider finalProvider = selectorProvider;
        this.runningAcceptor        = finalAcceptor;
        this.runningProvider        = finalProvider;
        this.runningThreadType      = threadType;
        LOG.info("serverGroup {} acceptorName {} selectorProvider name {}",
                l -> l.arg(name)
                        .arg(finalAcceptor.getClass().getName())
                        .arg(finalProvider.getClass().getName()));

        for (Server s : servers) {
            bindServer(s, finalAcceptor, finalProvider, threadType);
        }
        LOG.info("{}",  l -> l.arg(providers));
    }

    /**
     * 增量加入 server:
     * <ul>
     *   <li>run() <b>之前</b> 调:仅入队,与 {@link #addServer} 等价</li>
     *   <li>run() <b>之后</b> 调:走 {@link #bindServer} 完成 init + listen + accept(用于 runtime deploy
     *       时把新 ctx 的 server bean 立刻生效)</li>
     * </ul>
     *
     * <p>幂等:同一 instance 二次入队 no-op。</p>
     */
    public ServerGroup addServerAndBind(Server server) {
        if (server == null) {
            return this;
        }
        try {
            LOCK.lock();
            if (servers.contains(server)) {
                return this;
            }
            server.setServerGroup(this);
            servers.add(server);
            if (runningAcceptor != null) {
                // run() 已执行过 — 立即 bind 这个新 server,使其开始监听
                bindServer(server, runningAcceptor, runningProvider, runningThreadType);
            }
        } finally {
            LOCK.unlock();
        }
        return this;
    }

    /**
     * 停 server 的所有 acceptor、移除引用。等价于 run() 启动循环的逆操作。
     *
     * <p>只移除 server 自己 listen 的那些 acceptor(通过 {@link ServerChannelContext#getServer}
     * 反查归属),不影响同组内其它 server 的 acceptor。</p>
     */
    public void removeServer(Server server) {
        if (server == null) {
            return;
        }
        try {
            LOCK.lock();
            java.util.Iterator<Acceptor> it = acceptors.iterator();
            while (it.hasNext()) {
                Acceptor a = it.next();
                ServerChannelContext scc = a.getServerChannelContext();
                if (scc != null && scc.getServer() == server) {
                    try {
                        a.stop();
                    } catch (Throwable t) {
                        LOG.warn("serverGroup {} stop acceptor for {} failed",
                                l -> l.arg(getName()).arg(server.name()).threw(t));
                    }
                    it.remove();
                }
            }
            servers.remove(server);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 把单个 server 的所有 listen 地址绑到本 group:init → setServerGroup/setSelectorProvider/
     * setThreadType → 为每个 addr 建 ServerChannelContext + Acceptor + listen + accept。
     * 与 run() 启动循环内的 server 处理逻辑一一对应,抽出来供 {@link #run} 与
     * {@link #addServerAndBind} 两条路径共用。
     */
    private void bindServer(Server s, Acceptor acceptor, SelectorProvider provider, ThreadType threadType) {
        s.init();
        s.setServerGroup(this);
        s.setSelectorProvider(provider);
        s.setThreadType(threadType);
        List<Server.Addr> addrs = s.getListenAddrs();
        Edap edap = getEdap();
        for (Server.Addr addr : addrs) {
            ServerChannelContext scc = new ServerChannelContext();
            Acceptor acpt;
            if (acceptor instanceof FastAcceptor) {
                acpt = new FastAcceptor();
            } else {
                acpt = new NormalAcceptor();
            }
            scc.setServer(s);
            scc.setSelectorProvider(provider);
            scc.setAcceptDispatcherFactory(s.getAcceptDispatcherFactor());
            scc.setReadDispatcherFactory(s.getReadDispatcherFactory());
            IoSelectorManager ioSelectorManager = new IoSelectorManager(s, addr);
            scc.setIoSelectorManager(ioSelectorManager);
            s.addIoSelectorManager(addr, ioSelectorManager);
            scc.setEdap(edap);
            String key = s.getServerGroup().getName() + "->" + s.name() + "->" + addr;
            scc.setMonitorIndex(edap.getMonitorIndex(key));

            acpt.setServerChannelContext(scc);
            acpt.listen(addr);

            acceptors.add(acpt);
            acpt.accept();
        }
    }



    public void stop() {
        if (CollectionUtils.isEmpty(acceptors)) {
            return;
        }
        LOG.info("server group [{}] acceptor stop...",  l -> l.arg(name));
        for (Acceptor acceptor : acceptors) {
            LOG.info("server group [{}] acceptor [{}] stop...",  l -> l.arg(name).arg(acceptor));
            acceptor.stop();
            LOG.info("server group [{}] acceptor [{}] stopped",  l -> l.arg(name).arg(acceptor));
        }
        LOG.info("server group [{}] acceptor stopped",  l -> l.arg(name));
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
