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

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.edap.*;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ReadDispatcher;
import io.edap.nio.event.AcceptEvent;
import io.edap.nio.event.BizEvent;
import io.edap.nio.handler.AcceptEventHandler;
import io.edap.nio.handler.BizEventHandler;
import io.edap.pool.Pool;
import io.edap.pool.impl.ThreadLocalPool;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import static io.edap.nio.IoSelectorManager.BIZ_THREAD_FACTORY;

public class DisruptorReadDispatcher implements ReadDispatcher {

    static Logger LOG = LoggerManager.getLogger(DisruptorReadDispatcher.class);

    private Pool<FastBuf> bbPool;

    private Decoder decoder;
    private Server  server;

    private RingBuffer<BizEvent>[] ringBuffers;

    private volatile int queueSize = 256;

    private int seq = 0;


    public DisruptorReadDispatcher(Server server) {
        this.server      = server;
        this.bbPool      = new ThreadLocalPool<>();
        this.decoder     = server.getDecoder();
        this.ringBuffers = new RingBuffer[queueSize];
        for (int i=0;i<queueSize;i++) {
            ringBuffers[i] = buildRingBuffer();
        }
    }

    @Override
    public void dispatch(SelectionKey readKey) {
        NioSession nioSession = (NioSession)readKey.attachment();
        FastBuf buf = bbPool.borrow();
        if (buf == null) {
            buf = new FastBuf(4096);
        }
        ParseResult pr;
        try {
            int len = nioSession.fastRead(buf);
            if (len < 0) {

            } else {
                pr = decoder.decode(buf, nioSession);
                if (pr.isFinished()) {
                    int index = seq++%queueSize;
                    boolean published = ringBuffers[index].tryPublishEvent(
                            (event, sequence) -> {
                                event.setNioSession(nioSession);
                                event.setServerChannelContext(nioSession.getServerChannelContext());
                                event.setBizData(pr);
                            });
                    LOG.debug("published {}", l-> l.arg(published));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (buf != null) {
                bbPool.requite(buf);
            }
        }
        LOG.debug("SelectionKey {}", l -> l.arg(readKey));
    }

    public RingBuffer<BizEvent> buildRingBuffer() {
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
