/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.util;

import java.io.Serializable;
import java.util.*;

/**
 * @author louis
 * @date 2019-07-06 16:37
 */
public class FastList<T> implements List<T>, RandomAccess, Serializable {


    private static final long serialVersionUID = -4710864221337830300L;
    /**
     * 保存对象的数组
     */
    private Object[] value;
    /**
     * 当前List的元素个数
     */
    private int size;

    public FastList() {
        this.value = new Object[32];
    }

    public FastList(int capacity) {
        this.value = new Object[capacity];
    }

    /**
     * 添加一个元素的到List中
     * @param e 需要添加的元素
     * @return 是否添加成功
     */
    @Override
    public boolean add(T e) {
        if (size < value.length) {
            value[size++] = e;
        } else {
            //如果容量不够则按2倍进行扩容
            final int oldCapacity = value.length;
            final int newCapacity = oldCapacity << 1;
            @SuppressWarnings("unchecked")
            final Object[] newValue = new Object[newCapacity];
            System.arraycopy(value, 0, newValue, 0, oldCapacity);
            newValue[size++] = e;
            value = newValue;
        }

        return true;
    }

    /**
     * 根据指定的下标获取List的元素
     * @param index 元素的下标
     * @return 返回该下标的元素，或者是一个越界的异常
     */
    @Override
    public T get(int index) {
        return (T)value[index];
    }

    /**
     * 移除List中最后一个元素，不进行越界检查所以如果对空List进行移除则会报越界的异常
     *
     * @return 返回被移除的最后元素
     */
    public T removeLast() {
        T e = (T)value[--size];
        value[size] = null;
        return e;
    }

    /**
     * 移除制定的元素，判断是否相等只是对比地址是否相等，不会对值进行匹配。
     * @param e 需要删除的原色
     * @return 是否删除成功，元素在List中不存在则返回失败
     */
    @Override
    public boolean remove(Object e) {
        for (int index = size - 1;index >= 0;index--) {
            if (e == value[index]) {
                final int numMoved = size - index - 1;
                if (numMoved > 0) {
                    System.arraycopy(value, index + 1, value, index, numMoved);
                }
                value[--size] = null;

                return true;
            }
        }

        return false;
    }

    /**
     * 清除List的对象
     */
    @Override
    public void clear() {
        for (int i=0;i<size;i++) {
            value[i] = null;
        }

        size = 0;
    }

    /**
     * 返回List中对象的个数
     * @return
     */
    @Override
    public int size() {
        return size;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 为指定下标设置新的元素
     * @param index 需要设置的下标
     * @param e 需要设置的元素
     * @return 返回该下标原有的元素
     */
    @Override
    public T set(int index, T e) {
        T old = (T)value[index];
        value[index] = e;
        return old;
    }

    /** {@inheritDoc} */
    @Override
    public T remove(int index) {
        if (size == 0) {
            return null;
        }

        final T old = (T)value[index];

        final int numMoved = size - index - 1;
        if (numMoved > 0) {
            System.arraycopy(value, index + 1, value, index, numMoved);
        }

        value[--size] = null;

        return old;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {

            private int index;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public T next() {
                if (index < size) {
                    return (T)value[index++];
                }

                throw new NoSuchElementException("No more elements in FastList");
            }
        };
    }

    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }



    @Override
    public Object[] toArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T1> T1[] toArray(T1[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
        if (o == null) {
            for (int i = 0; i < size; i++) {
                if (value[i] == null) {
                    return i;
                }
            }
        } else {
            for (int i = 0; i < size; i++) {
                if (o.equals(value[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<T> listIterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException();
    }
}
