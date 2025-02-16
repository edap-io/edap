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

package io.edap.nio;

import io.edap.Acceptor;
import io.edap.Server;
import io.edap.ServerChannelContext;
import io.edap.ServerGroup;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.CollectionUtils;
import io.edap.util.EventHandleThreadFactory;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractAcceptor implements Acceptor {

    protected static final Logger LOG = LoggerManager.getLogger(AbstractAcceptor.class);

    protected Server server;

    protected List<Server.Addr> addrs;

    protected SelectorProvider selectorProvider;

    protected AcceptDispatcherFactory acceptDispatcherFactory;

    protected ServerGroup serverGroup;

    protected ServerChannelContext serverChannelContext;

    public static final EventHandleThreadFactory ACCEPT_THREAD_FACTORY;

    static {
        ACCEPT_THREAD_FACTORY = new EventHandleThreadFactory("edap-Accept-handle");
    }

    @Override
    public void addAddrs(List<Server.Addr> addrs) {
        if (CollectionUtils.isEmpty(addrs)) {
            return;
        }
        if (this.addrs == null) {
            this.addrs = new ArrayList<>();
        }
        for (Server.Addr addr : addrs) {
            if (!this.addrs.contains(addr)) {
                this.addrs.add(addr);
            }
        }
    }


    public void setAcceptDispatcherFactory(AcceptDispatcherFactory dispatcherFactory) {
        this.acceptDispatcherFactory = dispatcherFactory;
    }


    public AcceptDispatcherFactory getAcceptDispatcherFactory() {
        return acceptDispatcherFactory;
    }

    /**
     * 停止接收新连接进入
     */
    @Override
    public void stop() {

    }

    protected ServerSocketChannel bind(ServerGroup serverGroup, Server.Addr addr) {
        InetSocketAddress address;
        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();

            if (StringUtil.isEmpty(addr.host) || "*".equals(addr.host)) {
                address = new InetSocketAddress(addr.port);
            } else {
                address = new InetSocketAddress(addr.host, addr.port);
            }
            ssc.configureBlocking(false);
            ssc.socket().setReceiveBufferSize(16 * 1024);
            ssc.bind(address, Math.max(addr.server.getBackLog(), 10));

            LOG.info("serverGroup {} listen:{}", l -> l.arg(serverGroup.getName()).arg(addr));
            return ssc;
        } catch (IOException e) {
            throw new RuntimeException(addr + " bind error", e);
        }
    }

    @Override
    public String toString() {
        StringBuilder id = new StringBuilder();
        id.append(server.name()).append("::");
        if (!CollectionUtils.isEmpty(addrs)) {
            int i = 0;
            for (Server.Addr addr : addrs) {
                if (i > 0) {
                    id.append("/");
                }
                id.append(addr.host).append(':').append(addr.port);
                i++;
            }
        }

        return id.toString();
    }

    @Override
    public ServerChannelContext getServerChannelContext() {
        return serverChannelContext;
    }

    @Override
    public void setServerChannelContext(ServerChannelContext serverChannelContext) {
        this.serverChannelContext    = serverChannelContext;
        this.selectorProvider        = serverChannelContext.getSelectorProvider();
        this.serverGroup             = serverChannelContext.getServer().getServerGroup();
        this.acceptDispatcherFactory = serverChannelContext.getAcceptDispatcherFactory();
        this.server                  = serverChannelContext.getServer();
    }
}
