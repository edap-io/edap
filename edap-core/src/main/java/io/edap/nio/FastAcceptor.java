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

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.edap.Acceptor;
import io.edap.Server;

import java.nio.channels.Selector;
import java.util.List;
import java.util.concurrent.ThreadFactory;

public class FastAcceptor extends AbstractAcceptor {

    @Override
    public boolean isEnable(SelectorProvider selectorProvider) {
        if (selector == null) {
            try {
                selector = selectorProvider.getSelect();
                return true;
            } catch (Throwable t) {
                throw new RuntimeException("SelectorProvider getSelector error", t);
            }
        }
        return false;
    }

    @Override
    public void accept() {

    }

    @Override
    public void addAddrs(List<Server.Addr> addrs) {

    }

    @Override
    public void stop() {

    }
}
