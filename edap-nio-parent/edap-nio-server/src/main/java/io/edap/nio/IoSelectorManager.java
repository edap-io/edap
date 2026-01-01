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
import com.lmax.disruptor.dsl.ProducerType;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.event.BizEvent;
import io.edap.nio.handler.BizEventHandler;
import io.edap.nio.impl.RoundRobinDisruptorManager;
import io.edap.nio.util.EventHandleThreadFactory;
import io.edap.util.SystemUtil;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class IoSelectorManager {

    static Logger LOG = LoggerManager.getLogger(IoSelectorManager.class);

    private Server                server;
    private SelectorProvider      selectorProvider;
    private ReadDispatcherFactory dispatcherFactory;
    private IoWorker[]            ioWorkers;
    private int                   ioThreadCount;
    private volatile int          ioWorkerIndex;
    private Server.Addr           addr;
    private static Lock           lock = new ReentrantLock();

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    public static final EventHandleThreadFactory BIZ_THREAD_FACTORY;

    private long seq = 0;

    static {
        BIZ_THREAD_FACTORY = new EventHandleThreadFactory("e-biz-h");
    }

    public IoSelectorManager(Server server, Server.Addr addr) {
        this.server            = server;
        this.addr              = addr;
        this.selectorProvider  = server.getSelectorProvider();
        this.dispatcherFactory = server.getReadDispatcherFactory();
        if (server.getIoThreadCount() < 1) {
            ioThreadCount = SystemUtil.getCpuCount();
        }
		//ioThreadCount += 2;

        ioWorkers = new IoWorker[ioThreadCount];
        for (int i=0;i<ioThreadCount;i++) {
            IoWorker ioWorker = new IoWorker();
            EventDispatcherSet         eventDispatcherSet;
            Selector                   selector;
            ReadDispatcher             readDispatcher;
            EdapSelectorInfo           info;
            DisruptorManager<BizEvent> disruptorManager = null;
            try {
                //disruptorManager   = createDisruptorManager();
                readDispatcher     = dispatcherFactory.createReadDispatcher(server, disruptorManager);
                info               = selectorProvider.openSelector(readDispatcher);
                selector           = info.getSelector();
                eventDispatcherSet = info.getEventDispatcherSet();
                ioWorker.selector  = selector;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Thread runningThread = new Thread(() -> {
                    while (ioWorker.running) {
                        try {
                            eventDispatcherSet.reset();
                            int count = selector.select(50);
                            if (count > 0) {
                                LOG.info("selector.select() count: {}", l -> l.arg(count));
                            }
                        } catch (Throwable e) {
                            LOG.warn("selector.select() error", e);
                        }
                    }
                }
            );
            runningThread.setName("e-ioe-s-" + THREAD_SEQ.addAndGet(1));
            runningThread.setDaemon(true);
            ioWorker.ioThread = runningThread;
            ioWorkers[i]      = ioWorker;
        }
    }

    public int getClientCount() {
        int total = 0;
        for (int i=0;i<ioThreadCount;i++) {
            total += ioWorkers[i].selector.keys().size();
        }
        return total;
    }

    public List<IoWorkerInfo> getWorkerInfoList() {
        List<IoWorkerInfo> infos = new ArrayList<>();
        for (int i=0;i<ioThreadCount;i++) {
            IoWorkerInfo ioWorkerInfo = new IoWorkerInfo();
            ioWorkerInfo.clientCount  = ioWorkers[i].selector.keys().size();
            ioWorkerInfo.workerStatus = ioWorkers[i].running?1:0;
            ioWorkerInfo.threadName   = ioWorkers[i].ioThread.getName();
            infos.add(ioWorkerInfo);
        }

        return infos;
    }

//    private DisruptorManager<BizEvent> createDisruptorManager() {
//        DisruptorManager<BizEvent> manager = new RoundRobinDisruptorManager<>(
//                BizEvent::new, new BizEventHandler(server), BIZ_THREAD_FACTORY, 32,
//                ProducerType.SINGLE, new BlockingWaitStrategy()
//        );
//
//        return manager;
//    }

    public void registerNioSession(NioServerSession nioSession) {
        LOG.debug("registerNioSession {}", l -> l.arg(nioSession));
        SelectionKey key;
        LOG.info("seq=" + seq++);
        lock.lock();
        IoWorker ioWorker;
        try {
            ioWorker = ioWorkers[ioWorkerIndex++];
            if (ioWorkerIndex == ioWorkers.length) {
                ioWorkerIndex = 0;
            }
            if (!ioWorker.running) {
                ioWorker.running = true;
                ioWorker.ioThread.start();
            }
        } finally {
            lock.unlock();
        }
        try {
			nioSession.setSelector(ioWorker.selector);
            key = nioSession.getSocketChannel().register(ioWorker.selector, SelectionKey.OP_READ, nioSession);
        } catch (ClosedChannelException e) {
            throw new RuntimeException(e);
        }
    }

    class IoWorker {
        private Selector selector;
        private boolean  running;
        private Thread   ioThread;
    }

    public class IoWorkerInfo {
        private int clientCount;
        private int workerStatus;
        private String threadName;


        public int getClientCount() {
            return clientCount;
        }

        public void setClientCount(int clientCount) {
            this.clientCount = clientCount;
        }

        public int getWorkerStatus() {
            return workerStatus;
        }

        public void setWorkerStatus(int workerStatus) {
            this.workerStatus = workerStatus;
        }

        public String getThreadName() {
            return threadName;
        }

        public void setThreadName(String threadName) {
            this.threadName = threadName;
        }
    }

}
