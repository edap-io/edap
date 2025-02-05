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
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.edap.NioSession;
import io.edap.ServerChannelContext;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.AcceptDispatcher;
import io.edap.nio.event.AcceptEvent;
import io.edap.util.FastList;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;

import static io.edap.nio.AbstractAcceptor.THREAD_FACTORY;

public class DisruptorAcceptDispatcher implements AcceptDispatcher {

    private static final Logger LOG = LoggerManager.getLogger(DisruptorAcceptDispatcher.class);

    private RingBuffer<AcceptEvent> ringBuffer;
    public DisruptorAcceptDispatcher() {
        ringBuffer = buildRingBuffer();
    }

    @Override
    public void dispatch(SelectionKey acceptKey) {
        LOG.info("selectKey {}", l -> l.arg(acceptKey));
        SocketChannel clientChan;
        try {
            clientChan = ((ServerSocketChannel)acceptKey.channel()).accept();
            boolean published = ringBuffer.tryPublishEvent(
                    (event, sequence) -> event.setChannel(clientChan)
                            .setServerChannelCtx((ServerChannelContext) acceptKey.attachment()));
            LOG.debug("published {}", l-> l.arg(published));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private RingBuffer<AcceptEvent> getRingBuffer(){
        return null;
    }

    public RingBuffer<AcceptEvent> buildRingBuffer() {
        EventFactory<AcceptEvent> eventFactory = AcceptEvent::new;
        int bufferSize = 1024;
        WaitStrategy waitStrategy = new YieldingWaitStrategy();
        EventHandler<AcceptEvent> handler = (event, sequence, endOfBatch) -> {
            LOG.debug("event:{}, sequence={}, endOfBatch={}",
                    l -> l.arg(event.getChannel()).arg(sequence).arg(endOfBatch));
            ServerChannelContext scc = event.getServerChannelCtx();
            SocketChannel sc = event.getChannel();
            sc.configureBlocking(false);
            NioSession nioSession = event.getServerChannelCtx().getServer().createNioSession();
            nioSession.setSocketChannel(sc);
            scc.getIoSelectorManager().registerNioSession(nioSession);
        };
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
