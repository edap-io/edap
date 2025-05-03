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

package io.edap.nio.util;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.ThreadFactory;

public class DisruptorUtils {

    public static <E> Disruptor<E> buildDisruptor(EventFactory<E> eventFactory, EventHandler<E> handler,
                                                  int size, ThreadFactory eventHandleThreadFactory,
                                                  ProducerType producerType, WaitStrategy waitStrategy) {
        Disruptor<E> disruptor;
        disruptor = new Disruptor<>(eventFactory, size, eventHandleThreadFactory, producerType, waitStrategy);
        disruptor.handleEventsWith(handler);
        return disruptor;
    }
}
