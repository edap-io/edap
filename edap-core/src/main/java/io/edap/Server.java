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
import io.edap.pool.ConcurrentPool;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.ThreadUtil;

import static io.edap.pool.ConcurrentPool.PoolStateListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虚拟的服务器类，每个服务监听一种协议的数据格式如果http或者socket根据实现Server的类来
 * 确认协议类型
 * @author louis
 * @date 2019-07-01 23:41
 */
public abstract class Server<T, S extends NioSession> implements PoolStateListener {

    Logger log = LoggerManager.getLogger(this.getClass());
    /**
     * 服务器允许最大连接数
     */
    protected volatile int        maxClientCount;
    /**
     * 当前连接到服务器的客户端的个数
     */
    protected AtomicInteger curClientCount;
    /**
     * NioSession的会话池
     */
    private ConcurrentPool<NioSession> nioSessionPool;
    /**
     * 监听的服务器地址列表
     */
    private   List<Addr> addrs;
    /**
     * 负责向NioSession连接池添加NioSession的线程池
     */
    private ThreadPoolExecutor addExecutor;

    private BufPool bufPool;

    private int backLog;

    private Decoder<T, S> decoder;

    public Server() {
        curClientCount = new AtomicInteger(0);
        addrs = new ArrayList<>();
        maxClientCount = 256000;
        nioSessionPool = new ConcurrentPool<>(this);
        backLog = 1024;
        addExecutor    = ThreadUtil.createThreadPoolExecutor(256,
                "AioSessionAdder", null,
                new ThreadPoolExecutor.DiscardPolicy());
        setBufPool(new SimpleFastBufPool());
    }

    public int getBackLog() {
        return backLog;
    }

    public ConcurrentPool<NioSession> getNioSessionPool() {
        return nioSessionPool;
    }

    /**
     * 监听本机所有IP指定端口号
     * @param port 指定的端口号
     * @return
     */
    public Server listen(int port) {
        synchronized (this) {
            Addr existsAddr = getAddrByPort(port);
            //如果该端口号没有被设置为监听
            if (existsAddr == null) {
                Addr addr = new Addr();
                addr.host = "*";
                addr.port = port;
                addr.server = this;
                addrs.add(addr);
                return this;
            }
            //如果端口被监听但不是监听所有IP则替换为该端口的所有IP
            if (!existsAddr.host.equals("*")) {
                Addr addr = new Addr();
                addr.host = "*";
                addr.port = port;
                addr.server = this;
                addrs.set(existsAddr.index, addr);
            }
        }
        return this;
    }

    /**
     * 监听指定主机和端口号
     * @param host 主机名或者IP地址
     * @param port 监听的端口号
     * @return
     */
    public Server listen(String host, int port) {
        synchronized (this) {
            Addr existsAddr = getAddrByPort(port);
            //如果该端口号没有被设置为监听
            if (existsAddr == null) {
                Addr addr = new Addr();
                addr.host = host;
                addr.port = port;
                addr.server = this;
                addrs.add(addr);
                return this;
            }
            //如果该端口被监听而且不是监听的所有IP的添加该IP以及端口的监听
            if (!existsAddr.host.equals("*") && existsAddr.host.equals(host)) {
                Addr addr = new Addr();
                addr.host = host;
                addr.port = port;
                addr.server = this;
                addrs.add(addr);
            }
        }
        return this;
    }

    public List<Addr> getListenAddrs() {
        return Collections.unmodifiableList(addrs);
    }

    /**
     * 设置该服务的可同时连接的客户的连接数
     * @param maxClientCount
     * @return
     */
    public Server maxClientCount(int maxClientCount) {
        this.maxClientCount = maxClientCount;
        return this;
    }

    /**
     * 获取当前连接到该服务器的客户端数量
     * @return
     */
    public int getCurClientCount() {
        return curClientCount.get();
    }

    /**
     * 获取该服务器允许创建最大的连接数
     * @return
     */
    public int maxClientCount() {
        return maxClientCount;
    }

    /**
     * 需要应用服务器实现的创建NioSession的函数，NioSession的管理由框架来实现
     * @return
     */
    public abstract NioSession createNioSession();

    @Override
    public void addPoolItem(int waiting) {
        addExecutor.submit(new NioSessionCreator(waiting));

    }

    private Addr getAddrByPort(int port) {
        for (int i=0;i<addrs.size();i++) {
            Addr addr = addrs.get(i);
            if (port == addr.port) {
                addr.index = i;
                return addr;
            }
        }
        return null;
    }

    /**
     * 启动监听前需要进行的操作
     */
    public void init() {
        List<NioSession> sessions = new ArrayList<>();
        for (int i=0;i<256;i++) {
            sessions.add(createNioSession());
        }
        nioSessionPool.add(sessions);
    }

    public BufPool getBufPool() {
        return bufPool;
    }

    public void setBufPool(BufPool bufPool) {
        this.bufPool = bufPool;
    }

    public Decoder<T, S> getDecoder() {
        return decoder;
    }

    public void setDecoder(Decoder<T, S> decoder) {
        this.decoder = decoder;
    }

    public static class Addr {
        public String host;
        public int port;
        public int index;
        public Server server;

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(host);
            sb.append(":");
            sb.append(port);

            return sb.toString();
        }
    }

    /**
     * NioSession添加器，负责根据对象池所需的对象数以及Server允许最大的对象数来向池中
     * 添加NioSession对象
     */
    private final class NioSessionCreator implements Callable<Boolean> {

        private int waiting;

        NioSessionCreator(int waiting) {
            this.waiting = waiting;
        }

        @Override
        public Boolean call() throws Exception {
            int count = maxClientCount - nioSessionPool.getCount();
//            log.warn("client count is max! aioSessionPool.getCount()={} maxClients={}",
//                        nioSessionPool.getCount(), maxClientCount);
            if (count <= 0) {
//                log.warn("client count is max! aioSessionPool.getCount()={} maxClients={}",
//                        nioSessionPool.getCount(), maxClientCount);
                return Boolean.FALSE;
            }
            if (waiting > count) {
                waiting = count;
            }
            List<NioSession> sessions = new ArrayList<>(waiting);
            while (sessions.size() < waiting) {
                NioSession nioSession = createNioSession();
                if (nioSession != null) {
                    sessions.add(nioSession);
                }
            }
            //log.info("Add AioSession count [{}]", sessions.size());
            nioSessionPool.add(sessions);
            return Boolean.TRUE;
        }
    }
}
