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

import io.edap.pool.Pool;

import java.util.Objects;

/**
 * 线成内的池化接口实现，只能在单线程的情况下使用
 * @param <E>
 */
public class ThreadLocalPool<E> implements Pool<E> {

    final Object[] items;

    /** items index for next take, poll, peek or remove */
    int takeIndex;

    /** items index for next put, offer, or add */
    int putIndex;

    /** Number of elements in the queue */
    int count;

    public ThreadLocalPool() {
        this(2048);
    }

    public ThreadLocalPool(int capacity) {
        items = new Object[capacity];
    }

    @Override
    public E borrow() {
        return (count == 0) ? null : dequeue();
    }

    /**
     * Extracts element at current take position, advances, and signals.
     * Call only when holding lock.
     */
    private E dequeue() {
        // assert lock.isHeldByCurrentThread();
        // assert lock.getHoldCount() == 1;
        // assert items[takeIndex] != null;
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        E e = (E) items[takeIndex];
        items[takeIndex] = null;
        if (++takeIndex == items.length) {
            takeIndex = 0;
        }
        count--;
        return e;
    }

    @Override
    public void requite(E e) {
        Objects.requireNonNull(e);
        if (count == items.length) {
            return;
        }
        enqueue(e);
    }

    private void enqueue(E e) {
        final Object[] items = this.items;
        items[putIndex] = e;
        if (++putIndex == items.length) {
            putIndex = 0;
        }
        count++;
    }
}
