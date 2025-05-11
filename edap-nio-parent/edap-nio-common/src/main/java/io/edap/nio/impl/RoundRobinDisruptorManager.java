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
import io.edap.nio.AffinityThread;
import io.edap.nio.DisruptorManager;
import io.edap.nio.NioSession;

import java.util.concurrent.ThreadFactory;

import static io.edap.nio.util.DisruptorUtils.buildDisruptor;

/**
 * 轮训使用disruptor队列处理请求的管理器
 * @param <E> disruptor队列中事件的类型
 */
public class RoundRobinDisruptorManager<E> implements DisruptorManager<E> {

    private RingBuffer<E>[] ringBuffers;
    private int             queueSize    = 64;
    private int             queueCount;
    private int             seq;

    public RoundRobinDisruptorManager(EventFactory<E> eventFactory, EventHandler<E> handler,
                                      ThreadFactory threadFactory, int queueCount,
                                      ProducerType producerType, WaitStrategy waitStrategy) {
        Disruptor<E> disruptor;
        ringBuffers = new RingBuffer[queueCount];
        for (int i=0;i<queueCount;i++) {
            disruptor = buildDisruptor(eventFactory, handler, queueSize, threadFactory, producerType, waitStrategy);
            ringBuffers[i] = disruptor.start();
        }
        this.queueCount = queueCount;
        this.seq = 0;
    }

    /**
     * 轮训发布事件，如果轮训的队列已满则遍历所有disruptor队列，如果发布成功返回true，如果全部独队列均满则返回false。
     * @param translator 事件转换器
     * @return 成功发布返回true否则返回false
     */
    @Override
    public boolean publishEvent(AffinityThread affinityThread, EventTranslator<E> translator) {
        if (affinityThread != null && affinityThread.isAffinityThread()) {
            return affinityThreadPublish(affinityThread, translator);
        } else {
            return normalPublish(translator);
        }
    }

    private boolean normalPublish(EventTranslator<E> translator) {
        int             _seq         = seq;
        int             _queueCount  = queueCount;
        RingBuffer<E>[] _ringBuffers = ringBuffers;

        if (_seq == _queueCount) {
            _seq = 0;
        }
        _ringBuffers[_seq++].publishEvent(translator);
        this.seq = _seq;
//        boolean isPublished = _ringBuffers[_seq++].tryPublishEvent(translator);
//        if (isPublished) {
//            this.seq = _seq;
//            return isPublished;
//        }
//        for (int i=1;i<_queueCount;i++) {
//            isPublished = _ringBuffers[_seq].tryPublishEvent(translator);
//            if (_seq == _queueCount - 1) {
//                _seq = 0;
//            } else {
//                _seq++;
//            }
//            if (isPublished) {
//                this.seq = _seq;
//                return true;
//            }
//        }
//        this.seq = _seq;
        return true;
    }

    private boolean affinityThreadPublish(AffinityThread affinityThread, EventTranslator<E> translator) {
        int             _seq;
        int             _queueCount  = queueCount;
        RingBuffer<E>[] _ringBuffers = ringBuffers;
        if (affinityThread.getThreadIndex() >= 0) {
            _seq = affinityThread.getThreadIndex();
            try {
                _ringBuffers[_seq].publishEvent(translator);
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
        _ringBuffers[seq++].publishEvent(translator);

//        boolean isPublished = _ringBuffers[_seq].tryPublishEvent(translator);
//        if (isPublished) {
//            affinityThread.setThreadIndex(_seq);
//            _seq++;
//            if (_seq >= _queueCount) {
//                this.seq = 0;
//            } else {
//                this.seq = _seq;
//            }
//            return isPublished;
//        }
//        for (int i=1;i<_queueCount;i++) {
//            isPublished = _ringBuffers[_seq].tryPublishEvent(translator);
//            if (isPublished) {
//                affinityThread.setThreadIndex(_seq);
//                _seq++;
//                if (_seq >= _queueCount) {
//                    this.seq = 0;
//                } else {
//                    this.seq = _seq;
//                }
//
//                return true;
//            } else {
//                _seq++;
//            }
//        }
//        if (_seq >= _queueCount) {
//            this.seq = 0;
//        } else {
//            this.seq = _seq;
//        }
        return true;
    }
}
