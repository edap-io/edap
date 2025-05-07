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

package io.edap.nio;

import com.lmax.disruptor.EventTranslator;

/**
 * disruptor队列的管理器接口，负责协调消费者的事件分配和负载均衡
 * @param <E>
 */
public interface DisruptorManager<E> {
    /**
     * 使用事件转换器发送时间
     * @param translator 事件转换器
     * @return 事件是否发送成功
     */
    boolean publishEvent(AffinityThread affinityThread, EventTranslator<E> translator);
}
