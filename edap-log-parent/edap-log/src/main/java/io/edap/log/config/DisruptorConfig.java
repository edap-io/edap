/*
 * Copyright 2023 The edap Project
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package io.edap.log.config;

import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;

import static io.edap.log.consts.LogConsts.DEFAULT_QUEUE_SIZE;
import static io.edap.log.consts.LogConsts.DEFAULT_WAIT_STRATEGY;

public class DisruptorConfig {
    private int capacity;
    private WaitStrategy waitStrategy;

    public int getCapacity() {
        return capacity<=0?DEFAULT_QUEUE_SIZE:capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public WaitStrategy getWaitStrategy() {
        return waitStrategy==null?DEFAULT_WAIT_STRATEGY:waitStrategy;
    }

    public void setWaitStrategy(WaitStrategy waitStrategy) {
        this.waitStrategy = waitStrategy;
    }
}
