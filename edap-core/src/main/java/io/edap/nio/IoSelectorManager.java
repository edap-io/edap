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

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.edap.NioSession;
import io.edap.Server;
import io.edap.ServerChannelContext;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.event.BizEvent;
import io.edap.nio.handler.BizEventHandler;
import io.edap.util.EventHandleThreadFactory;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.atomic.AtomicInteger;

import static io.edap.util.SystemUtil.getCpuCount;

public class IoSelectorManager {

    static Logger LOG = LoggerManager.getLogger(IoSelectorManager.class);

    private Server                server;
    private SelectorProvider      selectorProvider;
    private ReadDispatcherFactory readDispatcherFactory;
    private IoWorker[]            ioWorkers;
    private int                   ioThreadCount;
    private int                   bizThreadCount;
    private volatile int ioWorkerIndex;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    public static final EventHandleThreadFactory BIZ_THREAD_FACTORY;

    static {
        BIZ_THREAD_FACTORY = new EventHandleThreadFactory("edap-biz-handle");
    }

    public IoSelectorManager(ServerChannelContext scc) {
        this.server                = scc.getServer();
        this.selectorProvider      = scc.getSelectorProvider();
        this.readDispatcherFactory = scc.getReadDispatcherFactory();
        if (scc.getServer().getIoThreadCount() < 1) {
            ioThreadCount = getCpuCount();
        }
        if (bizThreadCount < 1) {
            bizThreadCount = 256;
        }

        RingBuffer<BizEvent>[] ringBuffers = new RingBuffer[bizThreadCount];
        for (int i=0;i<bizThreadCount;i++) {
            ringBuffers[i] = buildRingBuffer();
        }

        ioWorkers = new IoWorker[ioThreadCount];
        for (int i=0;i<ioThreadCount;i++) {
            IoWorker ioWorker = new IoWorker();
            EventDispatcherSet eventDispatcherSet;
            Selector selector;
            try {
                EdapSelectorInfo info = selectorProvider.openSelector(
                        readDispatcherFactory.createReadDispatcher(scc.getServer(), ringBuffers));
                selector = info.getSelector();
                eventDispatcherSet = info.getEventDispatcherSet();
                ioWorker.selector = selector;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Thread runningThread = new Thread(() -> {
                    while (ioWorker.running) {
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
            runningThread.setName("edap-io-select-" + THREAD_SEQ.addAndGet(1));
            runningThread.setDaemon(true);
            ioWorker.ioThread = runningThread;
            ioWorkers[i] = ioWorker;
        }
    }

    public void registerNioSession(NioSession nioSession) {
        LOG.debug("registerNioSession {}", l -> l.arg(nioSession));
        SelectionKey key;
        try {
            IoWorker ioWorker = ioWorkers[ioWorkerIndex++];
            if (ioWorkerIndex == ioWorkers.length) {
                ioWorkerIndex = 0;
            }
            key = nioSession.getSocketChannel().register(ioWorker.selector, SelectionKey.OP_READ, nioSession);
            if (!ioWorker.running) {
                ioWorker.running = true;
                ioWorker.ioThread.start();
            }
        } catch (ClosedChannelException e) {
            throw new RuntimeException(e);
        }
    }

    class IoWorker {
        private Selector selector;
        private boolean running;
        private Thread ioThread;
    }

    private RingBuffer<BizEvent> buildRingBuffer() {
        EventFactory<BizEvent> eventFactory = BizEvent::new;
        int bufferSize = 1024;
        WaitStrategy waitStrategy = new BlockingWaitStrategy();
        EventHandler<BizEvent> handler = new BizEventHandler(server);
        Disruptor<BizEvent> disruptor = new Disruptor<>(
                eventFactory,
                bufferSize,
                BIZ_THREAD_FACTORY,
                ProducerType.MULTI,
                waitStrategy);
        disruptor.handleEventsWith(handler);
        return disruptor.start();
    }
}
