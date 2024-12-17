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

public class DisruptorAcceptDispatcher implements AcceptDispatcher {

    private static final Logger LOG = LoggerManager.getLogger(DisruptorAcceptDispatcher.class);

    private RingBuffer<AcceptEvent> ringBuffer;
    public DisruptorAcceptDispatcher() {
        ringBuffer = buildRingBuffer();
    }

    @Override
    public void dispatch(SelectionKey acceptKey) {
        LOG.info("selectKey {}", l -> l.arg(acceptKey));
        SocketChannel clientChan = null;
        try {
            clientChan = ((ServerSocketChannel)acceptKey.channel()).accept();
//            clientChan.configureBlocking(false);
//            clientChan.socket().setReuseAddress(true);
            acceptKey.attachment();
            boolean published = ringBuffer.tryPublishEvent(
                    (event, sequence) -> event.setAcceptKey(acceptKey));
            System.out.println("published " + published);
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
            System.out.println("event " + event.getAcceptKey().channel() +
                    ",sequence=" + sequence + ",endOfBatch=" + endOfBatch);
        };
        Disruptor<AcceptEvent> disruptor = new Disruptor<>(
                eventFactory,
                bufferSize,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                waitStrategy);
        disruptor.handleEventsWith(handler);
        return disruptor.start();
    }
}
