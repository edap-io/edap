/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.nio;

import io.edap.NioSession;
import io.edap.Server;
import io.edap.ServerChannelContext;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.EventHandleThreadFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

public class IoSelectorManager {

    static Logger LOG = LoggerManager.getLogger(IoSelectorManager.class);

    private Server               server;
    private SelectorProvider     selectorProvider;
    private ServerChannelContext serverChannelContext;
    private ReadDispatcher       readDispatcher;
    private Selector             selector;
    private Thread               runningThread;
    private volatile boolean     running;

    public static final EventHandleThreadFactory BIZ_THREAD_FACTORY;

    static {
        BIZ_THREAD_FACTORY = new EventHandleThreadFactory("edap-biz-handle");
    }

    public IoSelectorManager(ServerChannelContext scc) {
        this.serverChannelContext = scc;
        selectorProvider = scc.getSelectorProvider();
        readDispatcher   = scc.getReadDispatcher();
        EventDispatcherSet eventDispatcherSet;
        try {
            EdapSelectorInfo info = selectorProvider.openSelector(readDispatcher);
            selector = info.getSelector();
            eventDispatcherSet = info.getEventDispatcherSet();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        runningThread = new Thread(() -> {
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
        runningThread.setName("edap-io-select");
        runningThread.setDaemon(true);
    }

    public void registerNioSession(NioSession nioSession) {
        LOG.debug("registerNioSession {}", l -> l.arg(nioSession));
        SelectionKey key;
        try {
            key = nioSession.getSocketChannel().register(selector, SelectionKey.OP_READ, nioSession);
            if (!running) {
                running = true;
                runningThread.start();
            }
        } catch (ClosedChannelException e) {
            throw new RuntimeException(e);
        }
    }
}
