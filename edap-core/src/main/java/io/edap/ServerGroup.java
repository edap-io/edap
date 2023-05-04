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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.NormalAcceptor;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

    private ExecutorService workerService;

    static {
        LOCK = new ReentrantLock();
    }

    public ThreadType getThreadType() {
        return threadType;
    }

    public void setThreadType(ThreadType threadType) {
        this.threadType = threadType;
    }

    public synchronized ExecutorService getWorkerService() {
        if (workerService == null) {
            int corePoolSize = 16;
            int maxPoolSize = 256;
            int keepAliveTime = 5;
            int queueSize = 65536;
//            BlockingQueue<Runnable> queue = new MPMCBlockingQueue<>(queueSize);
//            workerService = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime,
//                    TimeUnit.MINUTES, queue, r -> new EdapThread());

            BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueSize);
            workerService = new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime,
                    TimeUnit.MINUTES, queue);
        }
        return workerService;
    }

    public void setWorkerService(ExecutorService workerService) {
        this.workerService = workerService;
    }

    public enum ThreadType {
        /**
         * 标准的reaction的模式
         */
        REACTOR,
        /**
         * edap的线程模型，selector遍历的线程按协议解码完成后将业务处理的任务直接提交到线程执行
         * 使用反射的模式替换selectedKeys和publicSelectedKeys在添加到这两个数据结构前直接解码
         * 根据协议生成业务对象后直接将业务处理的任务添加到线程池，减少遍历selector.selectedKeys()的过程
         */
        EDAP
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

    public void run() {
        String sThreadType = System.getProperty("edap.thread.type");
        if (!StringUtil.isEmpty(sThreadType)) {
            try {
                threadType = ThreadType.valueOf(sThreadType.toUpperCase(Locale.ENGLISH));
            } catch (Exception e) {

            }
        }
        LOG.info("ServerGroup [{}]'s thread type: {}", l -> l.arg(getName()).arg(threadType));

        directRun();
    }

    /**
     * 采用读写线程直接处理业务逻辑的直接处理方式，适用于业务处理响应很快的场景，因为该方式当前线程处理业务时
     * 会堵塞其他由该线程监听的其他channel的操作
     */
    private void directRun() {
        if (CollectionUtils.isEmpty(servers)) {
            return;
        }

        servers.forEach(s -> {
            NormalAcceptor acpt = new NormalAcceptor();
            acpt.addAddrs(s.getListenAddrs());
            acpt.setServer(s);
            s.init();
            acceptors.add(acpt);
        });

        if (CollectionUtils.isEmpty(acceptors)) {
            return;
        }
        acceptors.forEach(e -> e.accept());
    }
}
