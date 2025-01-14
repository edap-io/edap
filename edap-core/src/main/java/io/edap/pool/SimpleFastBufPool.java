/*
 * Copyright 2023 The edap Project
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

package io.edap.pool;

import io.edap.BufPool;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.ThreadUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SimpleFastBufPool implements BufPool, ConcurrentPool.PoolStateListener {

    static Logger LOG = LoggerManager.getLogger(SimpleFastBufPool.class);

    ConcurrentPool<FastBuf> pool = new ConcurrentPool(this);
    /**
     * 默认FastBuf的容量
     */
    private final int bufCapacity;

    private ThreadPoolExecutor addExecutor;

    /**
     * 默认总内存大小为512MB
     */
    private long totalMemory = 8192 * 1024 * 1024L;

    public SimpleFastBufPool() {
        this(512);
    }

    public SimpleFastBufPool(int bufCapacity) {
        this.bufCapacity = bufCapacity;
        this.addExecutor = ThreadUtil.createThreadPoolExecutor(256,
                "FastBufAdder", null,
                new ThreadPoolExecutor.DiscardPolicy());
        addPoolItem(100);
    }

    @Override
    public FastBuf borrow(int capacity) {
        try {
            return pool.borrow(10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            LOG.warn("Get FastBuf error!", ie);
        }
        return null;
    }

    @Override
    public void requite(FastBuf fastBuf) {
        fastBuf.clear(true);
        pool.requite(fastBuf);
    }

    @Override
    public void addPoolItem(int waiting) {
        addExecutor.submit(new FastBufCreator(waiting));
    }

    /**
     * 默认总内存大小为512MB
     */
    public long getTotalMemory() {
        return totalMemory;
    }

    public void setTotalMemory(long totalMemory) {
        this.totalMemory = totalMemory;
    }

    private final class FastBufCreator implements Callable<Boolean> {

        private int waiting;
        FastBufCreator(int waiting) {
            this.waiting = waiting;
        }

        @Override
        public Boolean call() throws Exception {
            long total = pool.getCount() * bufCapacity + waiting * bufCapacity;
            //LOG.warn("total={},maxSize={}", total, getTotalMemory());
            if (total >= getTotalMemory()) {
                waiting = (int)(getTotalMemory() - pool.getCount()*bufCapacity)/bufCapacity;
            }
            if (waiting <= 0) {
                return Boolean.FALSE;
            }
            List<FastBuf> bufs = new ArrayList<>(waiting);
            for (int i=0;i<waiting;i++) {
                bufs.add(new FastBuf(bufCapacity));
            }
            pool.add(bufs);
            return Boolean.TRUE;
        }
    }
}
