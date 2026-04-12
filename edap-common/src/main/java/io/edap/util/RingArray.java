/*
 * Copyright 2020 The edap Project
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

package io.edap.util;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 循环使用的数组结构，里面的元素会循环使用。通常用于记录时间顺序记录的结构
 */
public class RingArray<E> {
	/**
	 * 数组元素个数
	 */
	private       int      size;
	/**
	 * 容器容量
	 */
	private final int      cap;
	/**
	 * 队列头部的下标
	 */
	private       int      headIndex;
	/**
	 * 队列尾部的下标
	 */
	private       int      tailIndex;
	/**
	 * 容器元素数组
	 */
	private final Object[] values;
	/**
	 * 数组大小变动的锁
	 */
	private final Lock     lock;

	public RingArray(EventFactory<E> eventFactory, int size) {
		this.cap = size;
		Object[] vs = new Object[size];
		for (int i=0;i<size;i++) {
			vs[i] = eventFactory.createEvent();
		}
		this.values = vs;
		lock = new ReentrantLock();
	}

	public E get(int i) {
		if (i > size - 1) {
			throw new IndexOutOfBoundsException("RingArray size is " + size);
		}
		if (headIndex + i > cap - 1) {
			return (E)values[headIndex+i-cap];
		} else {
			return (E)values[headIndex+i];
		}
	}

	public int size() {
		return size;
	}

	public void put(ItemTranslator<E> transfer) {
		lock.lock();
		try {
			int _tailIndex = tailIndex;
			int _cap       = cap;
			if (_tailIndex >= _cap) {
				_tailIndex = 0;
			}
			transfer.translateTo((E) values[_tailIndex]);
			if (size < _cap) {
				size++;
			} else {
				if (headIndex < _cap - 1) {
					headIndex++;
				} else {
					headIndex = 0;
				}
			}
			_tailIndex++;
			tailIndex = _tailIndex;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * 元素转换器，为了方便容器内对象的复用，put元素时不构建新的Item对象，而是由转换器将外部的变量转换到内部复用
	 * 对象的属性中。减少JVM的gc的次数。
	 * @param <T>
	 */
	@FunctionalInterface
	public interface ItemTranslator<T> {
		void translateTo(T t);
	}

	@FunctionalInterface
	public interface EventFactory<E> {
		E createEvent();
	}
}
