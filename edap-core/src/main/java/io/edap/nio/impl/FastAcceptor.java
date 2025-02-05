/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.nio.impl;

import io.edap.Server;
import io.edap.ServerChannelContext;
import io.edap.nio.AbstractAcceptor;
import io.edap.nio.EdapSelectorInfo;
import io.edap.nio.EventDispatcherSet;
import io.edap.nio.IoSelectorManager;
import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;

import static java.nio.channels.SelectionKey.OP_ACCEPT;

public class FastAcceptor extends AbstractAcceptor {

    private List<ServerSocketChannel> serverSocketChannelList;

    private Selector selector;

    private Thread runningThread;

    private volatile boolean running;

    @Override
    public void accept() {
        EventDispatcherSet eventDispatcherSet;
        try {
            EdapSelectorInfo info = selectorProvider.openSelector(acceptDispatcher);
            selector = info.getSelector();
            eventDispatcherSet = info.getEventDispatcherSet();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            serverSocketChannelList = new ArrayList<>();
            for (Server.Addr addr : addrs) {
                ServerSocketChannel ssc = bind(serverGroup, addr);
                serverSocketChannelList.add(ssc);
                ssc.register(selector, OP_ACCEPT, serverChannelContext);
            }
        } catch (ClosedChannelException e) {
            throw new RuntimeException(e);
        }

        runningThread = new Thread(() -> {
                running = true;
                while (running) {
                    try {
                        eventDispatcherSet.reset();
                        int count = selector.select();
                        if (count > 0) {
                            LOG.info("selector.select() count: {}", l -> l.arg(count));
                        }
                    } catch (IOException e) {
                        LOG.warn("selector.select() error", e);
                    }
                }
            }
        );
        runningThread.setName("edap-accept-select");
        runningThread.start();
    }

    @Override
    public void stop() {
        runningThread.interrupt();
        running = false;
        LOG.info("select thread stopped!");
        if (!CollectionUtils.isEmpty(serverSocketChannelList)) {
            for (ServerSocketChannel ssc : serverSocketChannelList) {
                try {
                    ssc.close();
                    LOG.info("ServerSocketChannel {} closed", l -> l.arg(ssc));
                } catch (IOException e) {
                    LOG.error("ServerSocketChannel {} close error", l -> l.arg(ssc).arg(e));
                }
            }
        }

        if (selector != null) {
            try {
                selector.close();
                LOG.info("Selector {} closed!", l -> l.arg(selector));
            } catch (IOException e) {
                LOG.error("Selector {} close error", l -> l.arg(selector).arg(e));
            }
        }

    }
}
