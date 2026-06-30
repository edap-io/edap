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

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 分布式ID生成器,采用改进后的雪花算法，第一位为正数标记位 + 40位距离2020-10-10 00:00:00日期的时间戳，+ 10为workId + 13位序列号
 */
public class IdGenerator {

    /**
     * ID生成器开始的时间戳，为了减少字节占用，时间戳开始的从"2020-10-10 00:00:00GMT"开始的时间戳
     */
    public static long START_TIMESTAMP;

    static {
        Calendar cal = Calendar.getInstance();
        cal.set(2025, 1, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        START_TIMESTAMP = cal.getTimeInMillis();
    }

    /**
     * 时间戳左移的位数，左移的位数为最后序列号的位数加workId所用的位数
     */
    private final    int               timestampLeftShift;
    /**
     * workId的值，为移位后的值
     */
    private final    long              workIdValue;
    /**
     * 节点的workId的值，为计算前的原始值
     */
    private final    int               workId;
    /**
     * workId的掩码
     */
    private final    long              workIdMask;
    /**
     * 最后的序列数字所占的位数
     */
    private final    int               seqBits;
    /**
     * 随后序列值的掩码
     */
    private final    long              sequenceMask;
    /**
     * workId所使用的位数
     */
    private final    int               workIdBits;
    /**
     * 当前时间戳,对应的id的值，简化计算优化性能
     */
    private volatile long              curMillisValue;
    /**
     * 最后的时间戳
     */
    private          long              lastTimestamp;
    /**
     * 当前的时间戳
     */
    private          long              curTimestamp;
    /**
     * 最后的序列值，毫秒内自增，不同的毫秒时归0后自增
     */
    private          long              seq = 0;
    /**
     * Edap的时间实例，优化取当前时间戳的逻辑，提供并发性能
     */
    private static   EdapTime          EDAP_TIME = EdapTime.instance();
    /**
     * 防止时间回拨的秒数，每秒会保留1000毫秒的记录默认保留10秒的历史记录，当回拨大于10秒时抛异常。
     */
    private final    int               historyMills;

    private final    RingArray<IdInfo> idInfoHistory;

    public IdGenerator(WorkIdProvider workIdProvider) {
        this(12, 10, workIdProvider.getWordId(), 10);
    }

    public IdGenerator(int workId) {
        this(12, 10, workId, 10);
    }

    public IdGenerator(int seqBits, int workIdBits, WorkIdProvider workIdProvider) {
        this(seqBits, workIdBits, workIdProvider.getWordId(), 10);
    }

    public IdGenerator(int seqBits, int workIdBits, WorkIdProvider workIdProvider, int historySecond) {
        this(seqBits, workIdBits, workIdProvider.getWordId(), historySecond);
    }

    public IdGenerator(int seqBits, int workIdBits, int workId) {
        this(seqBits, workIdBits, workId, 10);
    }

    public IdGenerator(int seqBits, int workIdBits, int workId, int historySecond) {
        this.workId             = workId;
        this.workIdBits         = workIdBits;
        this.workIdMask         = -1L ^ (-1L << (workIdBits + seqBits));
        this.workIdValue        = (workId << seqBits) & workIdMask;
        this.seqBits            = seqBits;
        this.sequenceMask       = -1L ^ (-1L << seqBits);
        this.timestampLeftShift = seqBits + workIdBits;
        this.historyMills       = historySecond * 1000;
        this.idInfoHistory      = new RingArray<>(IdInfo::new, historyMills);

        EDAP_TIME.addCallback(this::setCurTimeMillis, 0, 1, TimeUnit.MILLISECONDS);
        setCurTimeMillis(EDAP_TIME.currentTimeMillis());
    }

    private synchronized void setCurTimeMillis(long timeMillis) {
        this.curTimestamp = timeMillis;
        if (timeMillis > lastTimestamp) {
            idInfoHistory.put(idInfo -> {
				idInfo.seq       = seq;
				idInfo.workId    = workId;
				idInfo.timestamp = lastTimestamp;
			});
            lastTimestamp = timeMillis;
            seq = 0;
        }
        curMillisValue = ((timeMillis - START_TIMESTAMP) << timestampLeftShift);
    }

    public int getSeqBits() {
        return seqBits;
    }

    /**
     * 批量获取分布ID
     * @param count
     * @return
     */
    public synchronized long[] getIds(int count) {
        long[] vs = new long[count];
        for (int i=0;i<count;i++) {
            vs[i] = getId0();
        }
        return vs;
    }

    private long getId0() {
        long cur = curTimestamp;
        if (cur < lastTimestamp) {
            System.out.println("cur=" + cur + ",curTimestamp=" + curTimestamp);
            return getTimeRewindId(cur, lastTimestamp);
        }
        seq = (seq + 1) & sequenceMask;
        if (seq == 0) {
            getNextMillis();
        }

        return curMillisValue | workIdValue | seq;
    }

    private long getTimeRewindId(long cur, long lastTimestamp) {
        RingArray<IdInfo> _history = idInfoHistory;
        int interval = (int)(lastTimestamp - cur);
        if (interval > _history.size()) {
            throw new RuntimeException("getTimeRewindId idInfoHistory.size(): " + _history.size());
        }
        int index = _history.size() - interval;
        IdInfo idInfo = _history.get(index++);
        if (idInfo.timestamp == cur) {
            idInfo.seq = idInfo.seq + 1;
            return idInfo.seq;
        } else {
            while (idInfo.timestamp != cur && index < _history.size()) {
                idInfo = _history.get(index++);
            }
            if (idInfo.timestamp == cur) {
                idInfo.seq = idInfo.seq + 1;
                return idInfo.seq;
            } else {
                throw new RuntimeException("getTimeRewindId error");
            }
        }
    }

    public synchronized long getId() {
        return getId0();
    }

    public int getWorkId() {
        return workId;
    }

    private long getNextMillis() {
        long c = EDAP_TIME.currentTimeMillis();
        while (c <= curTimestamp) {
            try {
                TimeUnit.NANOSECONDS.sleep(10);
            } catch (InterruptedException e) {

            }
            c = EDAP_TIME.currentTimeMillis();
        }
        setCurTimeMillis(c);
        return c;
    }

    public IdInfo idInfo(long traceId) {
        long ts     = traceId >> (seqBits + workIdBits);
        long workId = (traceId & workIdMask) >> seqBits;

        IdInfo info = new IdInfo();
        info.setTimestamp(ts + START_TIMESTAMP);
        info.setWorkId((int)workId);
        info.setSeq((int)(traceId & sequenceMask));
        return info;
    }

    public static class IdInfo {
        private long timestamp;
        private int  workId;
        private long seq;

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public int getWorkId() {
            return workId;
        }

        public void setWorkId(int workId) {
            this.workId = workId;
        }

        public long getSeq() {
            return seq;
        }

        public void setSeq(int seq) {
            this.seq = seq;
        }
    }

    /**
     * workId的提供器，负责获取雪花算法wordId的实现
     * @author : luysh@yonyou.com
     * @date : 2020/11/26
     */
    @FunctionalInterface
    public interface WorkIdProvider {
        /**
         * 产生集群内唯一workId，获取的workId为在集群内唯一，并且没有被其他节点使用的0-1024的值
         * @return
         */
        int getWordId();
    }
}
