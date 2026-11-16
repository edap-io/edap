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

package io.edap.pool.impl;

import io.edap.pool.MpscPool;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 用 {@link ArrayBlockingQueue} 实现的<b>线程安全</b>对象池。
 *
 * <p>★ 什么时候必须用它：写缓冲的「借出」和「归还」可能发生在<b>不同线程</b>上 ——
 * HTTP/2 的帧既可能由 IO 线程产出（请求处理完立即回响应），
 * 也可能由业务线程产出（服务端流式在别的线程推消息）。
 * {@link ThreadLocalPool} 是无锁的数组实现，多线程借还会互相踩
 * （{@code takeIndex}/{@code putIndex}/{@code count} 都不是原子的），
 * 这种场景必须用带锁的实现。</p>
 *
 * <p>归还时池满则丢弃对象（不阻塞、不抛异常）—— 池是缓存不是资源，
 * 丢了重新分配即可，阻塞反而会把 IO 线程挂住。</p>
 */
public class ArrayBlockingQueueMpscPool<E> implements MpscPool<E> {

    private final ArrayBlockingQueue<E> queue;

    public ArrayBlockingQueueMpscPool() {
        this(1024);
    }

    public ArrayBlockingQueueMpscPool(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public E borrow() {
        return queue.poll();
    }

    @Override
    public void requite(E e) {
        if (e == null) {
            return;
        }
        queue.offer(e);
    }
}
