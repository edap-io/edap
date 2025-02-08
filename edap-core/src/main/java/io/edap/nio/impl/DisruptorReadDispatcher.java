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
import io.edap.Decoder;
import io.edap.NioSession;
import io.edap.ParseResult;
import io.edap.Server;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ReadDispatcher;
import io.edap.nio.event.AcceptEvent;
import io.edap.nio.handler.AcceptEventHandler;
import io.edap.pool.MpscPool;
import io.edap.pool.Pool;
import io.edap.pool.impl.ArrayBlockingQueueMpscPool;
import io.edap.pool.impl.ThreadLocalPool;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import static io.edap.nio.AbstractAcceptor.THREAD_FACTORY;

public class DisruptorReadDispatcher implements ReadDispatcher {

    static Logger LOG = LoggerManager.getLogger(DisruptorReadDispatcher.class);

    private Pool<FastBuf> bbPool;

    private Decoder decoder;
    private Server  server;

    private RingBuffer<AcceptEvent>[] ringBuffers;


    public DisruptorReadDispatcher(Server server) {
        this.server = server;
        this.bbPool  = new ThreadLocalPool<>();
        this.decoder = server.getDecoder();
        for (int i=0;i<2) {

        }
    }

    @Override
    public void dispatch(SelectionKey readKey) {
        NioSession nioSession = (NioSession)readKey.attachment();
        FastBuf buf = bbPool.borrow();
        ParseResult pr;
        try {
            int len = nioSession.fastRead(buf);
            if (len < 0) {

            } else {
                pr = decoder.decode(buf, nioSession);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        LOG.debug("SelectionKey {}", l -> l.arg(readKey));
    }

    public RingBuffer<AcceptEvent> buildRingBuffer() {
        EventFactory<AcceptEvent> eventFactory = AcceptEvent::new;
        int bufferSize = 1024;
        WaitStrategy waitStrategy = new YieldingWaitStrategy();
        EventHandler<AcceptEvent> handler = new AcceptEventHandler(server);
        Disruptor<AcceptEvent> disruptor = new Disruptor<>(
                eventFactory,
                bufferSize,
                THREAD_FACTORY,
                ProducerType.MULTI,
                waitStrategy);
        disruptor.handleEventsWith(handler);
        return disruptor.start();
    }
}
