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
import io.edap.pool.Pool;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 虚拟的服务器类，每个服务监听一种协议的数据格式如果http或者socket根据实现Server的类来
 * 确认协议类型
 * @author louis
 * @date 2019-07-01 23:41
 */
public abstract class Server<T, S extends NioSession> {

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
     * 监听的服务器地址列表
     */
    private   List<Addr> addrs;
    /**
     * 该服务器所属的服务器组的实例
     */
    private ServerGroup serverGroup;

    private BufPool bufPool;

    private int backLog;

    private Decoder<T, S> decoder;

    private String name;

    private int ioThreadCount;
    private int bizThreadCount;
    /**
     * NioSession是否需要使用对象池，如果连接未短连接连接数较少建议采用池化NioSession，减少内存GC。
     */
    private boolean nioSesionPooled;

    private Pool<NioSession> nioSessionPool;

    public Server() {
        curClientCount = new AtomicInteger(0);
        addrs = new ArrayList<>();
        maxClientCount = 256000;
        backLog = 1024;
        setBufPool(new SimpleFastBufPool());
    }

    public Server name(String name) {
        this.name = name;
        return this;
    }

    public String name() {
        if (StringUtil.isEmpty(name)) {
            return this.getClass().getSimpleName();
        }
        return this.name;
    }

    public int getBackLog() {
        return backLog;
    }

    /**
     * 监听本机所有IP指定端口号
     * @param port 指定的端口号
     * @return
     */
    public Server listen(int port) {
        synchronized (this) {
            Addr existsAddr = queryAddrByPort(port);
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
            Addr existsAddr = queryAddrByPort(port);
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

    private Addr queryAddrByPort(int port) {
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

    /**
     * 该服务器所属的服务器组的实例
     */
    public ServerGroup getServerGroup() {
        return serverGroup;
    }

    public void setServerGroup(ServerGroup serverGroup) {
        this.serverGroup = serverGroup;
    }

    /**
     * NioSession是否需要使用对象池
     */
    public boolean isNioSesionPooled() {
        return nioSesionPooled;
    }

    public void setNioSesionPooled(boolean nioSesionPooled) {
        this.nioSesionPooled = nioSesionPooled;
    }

    public int getIoThreadCount() {
        return ioThreadCount;
    }

    public void setIoThreadCount(int ioThreadCount) {
        this.ioThreadCount = ioThreadCount;
    }

    public int getBizThreadCount() {
        return bizThreadCount;
    }

    public void setBizThreadCount(int bizThreadCount) {
        this.bizThreadCount = bizThreadCount;
    }

    public Pool<NioSession> getNioSessionPool() {
        return nioSessionPool;
    }

    public void setNioSessionPool(Pool<NioSession> nioSessionPool) {
        this.nioSessionPool = nioSessionPool;
    }

    public static class Addr {
        public String host;
        public int    port;
        public int    index;
        public Server server;

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(host);
            sb.append(":");
            sb.append(port);

            return sb.toString();
        }
    }
}
