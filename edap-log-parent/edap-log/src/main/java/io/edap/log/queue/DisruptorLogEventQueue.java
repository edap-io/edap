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

package io.edap.log.queue;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.edap.log.LogArgsImpl;
import io.edap.log.LogConfig;
import io.edap.log.LogEvent;
import io.edap.log.config.DisruptorConfig;

import static io.edap.log.helpers.Util.printError;
import static io.edap.log.util.DisruptorUtil.checkSetConfig;

public class DisruptorLogEventQueue implements LogEventQueue {

    private DisruptorConfig      config;
    private RingBuffer<LogEvent> ringBuffer;
    private Disruptor<LogEvent>  disruptor;
    private boolean              started;

    public DisruptorLogEventQueue() {
        config  = new DisruptorConfig();
        started = false;
    }

    @Override
    public void publish(LogArgsImpl args) {
        if (!started) {
            printError("DisruptorLogEventQueue not started");
            return;
        }
        ringBuffer.publishEvent(LogEventQueue::translate, args);
    }

    @Override
    public void start() {
        disruptor = new Disruptor<>(LogEvent::new, config.getCapacity(), DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI, config.getWaitStrategy());
        disruptor.handleEventsWith(LogEventQueue::handleEvent);
        ringBuffer = disruptor.start();
        started = true;
    }

    @Override
    public void stop() {
        disruptor.shutdown();
        started = false;
    }

    @Override
    public void setArg(LogConfig.ArgNode arg) throws Throwable {
        checkSetConfig(config, arg);
    }
}
