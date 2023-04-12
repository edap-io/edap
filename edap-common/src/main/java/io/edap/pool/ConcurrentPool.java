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

package io.edap.pool;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.CollectionUtils;
import io.edap.util.EdapTime;
import io.edap.util.FastGenericList;
import io.edap.util.FastList;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.edap.pool.ConcurrentPool.PoolEntry.*;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

/**
 * 利用ThreadLocal的变量来避免线程竞争的线程安全对象池的实现，基本参考了 <strong>HikariCP</strong>
 * 的对象池实现逻辑
 * @author louis
 * @date 2019-07-06 16:32
 */
public class ConcurrentPool<T extends ConcurrentPool.PoolEntry> implements AutoCloseable {

    private static final Logger LOG = LoggerManager.getLogger(ConcurrentPool.class);

    private static final EdapTime TIME = EdapTime.instance();

    /**
     * 保存池化对象对象的共享列表，多线程共享
     */
    private final CopyOnWriteArrayList<T> sharedList;
    /**
     * 是否使用软引用的标记
     */
    private final boolean weakThreadLocals;

    /**
     * ThreadLocal变量保存当前线程持有的对象列表
     */
    private final ThreadLocal<List<Object>> threadList;

    private final PoolStateListener listener;

    private final AtomicInteger waiters;

    private volatile boolean closed;

    private final SynchronousQueue<T> handoffQueue;


    /**
     * 如果想让对象作为池化进行管理则需要实现的接口
     */
    public interface PoolEntry {
        /**
         * 对象闲置
         */
        int STATE_NOT_IN_USE = 0;
        /**
         * 对象正在被使用
         */
        int STATE_IN_USE     = 1;
        /**
         * 对象已经被移除
         */
        int STATE_REMOVED    = 2;
        /**
         * 对象为备用
         */
        int STATE_RESERVED   = 3;

        /**
         * 对比并设置状态，如果对象当前状态与我们预期的状态一致，则将对象设置为新的状态，
         * 如果对象当前状态与我们预期状态不一致或者设置为新状态失败则返回false，否则返回true
         * @param expectState 预期的对象状态
         * @param newState 需要设置的新状态
         * @return 是否与预期一致并设置新状态
         */
        boolean compareAndSet(int expectState, int newState);

        /**
         * 强制更新为新的状态值
         * @param newState
         */
        void setState(int newState);

        /**
         * 获取对象当前的状态
         * @return
         */
        int getState();

        /**
         * 设置该元素在池内的序列号，按添加到列表的下标来计算
         * @param seq 需要设置的序号
         */
        void setSeq(int seq);

        /**
         * 获取该元素在池内的序列号
         * @return 序号
         */
        int getSeq();

        /**
         * 清理相关资源可以让对象进行复用
         */
        void reset();
    }

    /**
     * 对象池状态监听器
     */
    public interface PoolStateListener {

        /**
         * 当有线程等待时，根据等待线程数来添加相应的对象。实现该操作的类需要采用其他的线程池
         * 来进行操作，否则会堵塞当前的线程导致线程挂起。
         * @param waiting 等待的线程数
         */
        void addPoolItem(int waiting);
    }

    public ConcurrentPool(final PoolStateListener listener) {
        this.listener = listener;
        this.weakThreadLocals = useWeakThreadLocals();
        System.out.println("weakThreadLocals=" + weakThreadLocals);

        this.handoffQueue = new SynchronousQueue<>(true);
        this.waiters = new AtomicInteger();
        this.sharedList = new CopyOnWriteArrayList<>();
        if (weakThreadLocals) {
            this.threadList = ThreadLocal.withInitial(() -> new FastList<>(16));
        } else {
            this.threadList = ThreadLocal.withInitial(() -> new FastGenericList<>(PoolEntry.class, 16));
        }
    }

    /**
     * 从池中获取一个闲置的对象，如果超时则抛出超时的错误。首先尝试从当前线程列表中获取，
     * 如果当前线程列表中没有则尝试从共享的列表中获取，如果还没有则尝试在等待队列中获取，
     * 如果超时时间内没有获取到则返回null，如果有中断错误则抛出中断错误
     * @param timeout 超时时间
     * @param timeUnit 超时时间的时间单位
     * @return 如果在超时时间内能够获取到对象，则返回闲置的对象，否则返回null或者超时异常
     * @throws InterruptedException
     */
    public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException {
        //检查当前线程的列表中是否有闲置的对象
        final List<Object> list = threadList.get();
        for (int i = list.size() - 1; i >= 0; i--) {
            final Object item = list.remove(i);

            @SuppressWarnings("unchecked")
            final T entry = weakThreadLocals ? ((WeakReference<T>)item).get() : (T)item;
            if (entry != null && entry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                return entry;
            }
        }

        //如果当前线程列表中没有闲置对象则扫描共享列表，然后从等待队列里获取
        final int waiting = waiters.incrementAndGet();
        LOG.info("sharedList={}", l -> l.arg(sharedList::size));
        try {
            for (T entry : sharedList) {
                if (entry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                    if (waiting > 1) {
                        listener.addPoolItem(waiting - 1);
                    }
                    return entry;
                }
            }

            listener.addPoolItem(waiting);

            timeout = timeUnit.toNanos(timeout);
            long finalTimeout = timeout;
            LOG.info("timeout1={}", l -> l.arg(finalTimeout));
            do {
                final long start = TIME.nanoTime();
                final T entry = handoffQueue.poll(timeout, NANOSECONDS);
                LOG.info("T={}", l -> l.arg(entry));
                if (entry == null || entry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                    return entry;
                }

                timeout -= (TIME.nanoTime() - start);
                long finalTimeout1 = timeout;
                LOG.info("timeout2={}", l -> l.arg(finalTimeout1));
            } while (timeout > 10_000);

            return null;
        } finally {
            waiters.decrementAndGet();
        }
    }

    /**
     * 向池中归还一个对象
     * @param entry 需要向池中归还的对象
     */
    public void requite(final T entry) {
        entry.setState(STATE_NOT_IN_USE);

        for (int i = 0; waiters.get() > 0; i++) {
            if (entry.getState() != STATE_NOT_IN_USE || handoffQueue.offer(entry)) {
                return;
            } else if ((i & 0xff) == 0xff) { //每遍历255则休眠10微秒
                parkNanos(MICROSECONDS.toNanos(10));
            } else {
                //yield();
            }
        }

        final List<Object> list = threadList.get();
        if (list.size() < 50) {
            list.add(weakThreadLocals ? new WeakReference<>(entry) : entry);
        }
    }

    /**
     * 向池中添加一个对象
     * @param entries 需要添加的对象
     */
    public synchronized void add(final List<T> entries) {
        if (closed) {
            LOG.info("ConcurrentBag has been closed, ignoring add()");
            throw new IllegalStateException("ConcurrentPool has been closed, ignoring add()");
        }
        if (CollectionUtils.isEmpty(entries)) {
            LOG.info("entries is empty, ignoring add()");
            return;
        }
        int size = sharedList.size();
        for (T e : entries) {
            e.setSeq(size++);
        }
        sharedList.addAll(entries);
        for (T entry : entries) {
            while (waiters.get() > 0 && entry.getState() == STATE_NOT_IN_USE
                    && !handoffQueue.offer(entry)) {
                //yield();
            }
        }
    }

    /**
     * 从池中移除一个对象
     * @param entry 需要移除的对象
     * @return
     */
    public boolean remove(final T entry) {
        if (!entry.compareAndSet(STATE_IN_USE, STATE_REMOVED)
                && !entry.compareAndSet(STATE_RESERVED, STATE_REMOVED)
                && !closed) {
            LOG.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", l -> l.arg(entry));
            return false;
        }

        final boolean removed = sharedList.remove(entry);
        if (!removed && !closed) {
            LOG.warn("Attempt to remove an object from the bag that does not exist: {}", l -> l.arg(entry));
        }

        return removed;
    }

    /**
     * 返回池内指定状态的元素列表
     * @param state 指定的状态
     * @return 符合指定状态的元素列表
     */
    public List<T> values(final int state) {
        final List<T> list = sharedList.stream()
                .filter(e -> e.getState() == state)
                .collect(Collectors.toList());
        Collections.reverse(list);
        return list;
    }

    /**
     * 返回池内所有的元素列表
     * @return 返回元素列表
     */
    public List<T> values() {
        return (List<T>) sharedList.clone();
    }

    /**
     * 将可用的元素置为备用状态
     * @param entry 需要置为备用状态的元素
     * @return 返回是否成功
     */
    public boolean reserve(final T entry) {
        return entry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
    }

    /**
     * 将备用状态的元素置为可用状态
     * @param entry 需要解除备用状态的元素
     */
    public void unreserve(final T entry) {
        if (entry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
            while (waiters.get() > 0 && !handoffQueue.offer(entry)) {
                //yield();
            }
        } else {
            LOG.warn("Attempt to relinquish an object to the bag that was not reserved: {}",l -> l.arg(entry));
        }
    }

    /**
     * 正在等待的线程数
     * @return
     */
    public int getWaitingThreadCount() {
        return waiters.get();
    }

    /**
     * 获取指定状态的元素个数
     * @param state 指定的状态值
     * @return 该状态的个数
     */
    public int getCount(final int state) {
        int count = 0;
        for (PoolEntry e : sharedList) {
            if (e.getState() == state) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取当前对象池中所有对象的个数
     * @return 返回池内对象个数
     */
    public int getCount() {
        return sharedList.size();
    }

    /**
     * 获取各种状态以及池总元素数以及等待的线程数
     * @return 各个状态的数量
     */
    public int[] getStateCounts() {
        final int[] states = new int[6];
        for (PoolEntry e : sharedList) {
            ++states[e.getState()];
        }
        states[4] = sharedList.size();
        states[5] = waiters.get();

        return states;
    }

    /**
     * 判断是否使用软引用的方式，如果系统配置"io.edap.pool.useWeakReferences"为true则
     * 返回true，如果设置的为false，但是当前的classloader不是SystemClassLoader则也使用
     * 软引用的方式。如果有安全的异常也使用软引用的方式。
     * @return 是否使用软引用的方式
     */
    private boolean useWeakThreadLocals() {
        try {
            if (System.getProperty("io.edap.pool.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
                return Boolean.getBoolean("io.edap.pool.useWeakReferences");
            }

            return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
        } catch (SecurityException se) {
            return true;
        }
    }


    @Override
    public void close() throws Exception {
        closed = true;
    }
}
