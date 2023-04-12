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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * @author louis
 * @date 2019-07-06 16:43
 */
public abstract class BasePoolEntry implements ConcurrentPool.PoolEntry {

    /**
     * 原则操作AioSession对象池状态的更新器
     */
    private static final AtomicIntegerFieldUpdater<BasePoolEntry> POOL_STATE_UPDATER;

    static {
        POOL_STATE_UPDATER = AtomicIntegerFieldUpdater
                .newUpdater(BasePoolEntry.class, "poolState");
    }

    /**
     * 在对象池内的状态值
     */
    private volatile int poolState = 0;
    /**
     * AioSession在池中的序列号
     */
    private volatile int seq;

    /**
     * 对比并设置状态，如果对象当前状态与我们预期的状态一致，则将对象设置为新的状态，
     * 如果对象当前状态与我们预期状态不一致或者设置为新状态失败则返回false，否则返回true
     *
     * @param expectState 预期的对象状态
     * @param newState    需要设置的新状态
     * @return 是否与预期一致并设置新状态
     */
    public boolean compareAndSet(int expectState, int newState) {
        return POOL_STATE_UPDATER.compareAndSet(this, expectState, newState);
    }

    /**
     * 强制更新为新的状态值
     *
     * @param newState
     */
    public void setState(int newState) {
        POOL_STATE_UPDATER.set(this, newState);
    }

    /**
     * 获取对象当前的状态
     *
     * @return
     */
    @Override
    public int getState() {
        return POOL_STATE_UPDATER.get(this);
    }

    /**
     * 设置该元素在池内的序列号，按添加到列表的下标来计算
     *
     * @param seq 需要设置的序号
     */
    @Override
    public void setSeq(int seq) {
        this.seq = seq;
    }

    /**
     * 获取该元素在池内的序列号
     *
     * @return 序号
     */
    @Override
    public int getSeq() {
        return this.seq;
    }
}
