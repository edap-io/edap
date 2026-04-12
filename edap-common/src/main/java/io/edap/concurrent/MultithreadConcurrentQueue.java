package io.edap.concurrent;

import java.util.concurrent.atomic.LongAdder;

public class MultithreadConcurrentQueue<E> implements ConcurrentQueue<E> {

    /**
     * ring结构的数组大小，该数值为不小于指定队列大小的2的n次方的整数
     */
    protected final int size;

    /**
     * 针对ring大小的掩码
     */
    final long mask;

    /**
     * 队尾的序列号
     */
    final LongAdder tail = new LongAdder();

    /**
     * 队尾的游标，负责队尾的序列化切换
     */
    final ContendedAtomicLong tailCursor = new ContendedAtomicLong(0L);

    // use the value in the L1 cache rather than reading from memory when possible
    long p1, p2, p3, p4, p5, p6, p7;
    long tailCache = 0L;
    long a1, a2, a3, a4, a5, a6, a7, a8;

    /**
     * 保存队列元素的数组
     */
    final E[] buffer;

    long r1, r2, r3, r4, r5, r6, r7;
    long headCache = 0L;
    long c1, c2, c3, c4, c5, c6, c7, c8;

    /**
     * 队首的序列号
     */
    final LongAdder head =  new LongAdder();

    /**
     * 队首的游标，负责无锁的进行队首的序列号切换
     */
    final ContendedAtomicLong headCursor = new ContendedAtomicLong(0L);


    public MultithreadConcurrentQueue(final int capacity) {
        size = Capacity.getCapacity(capacity);
        mask = size - 1L;
        buffer = (E[])new Object[size];
    }


    @Override
    public boolean offer(E e) {
        int spin  = 0;
        for (;;) {
            final long tailSeq = tail.sum();
            final long queueStart = tailSeq - size;

            // 判断队列是否已满
            if (headCache > queueStart || (headCache = head.sum()) > queueStart) {
                if (tailCursor.compareAndSet(tailSeq, tailSeq + 1L)) {
                    try {
                        final int tailSlot = (int)(tailSeq&mask);
                        buffer[tailSlot] = e;

                        return true;
                    } finally {
                        tail.increment();
                    }
                } // 如果序列号已经被其他线程占用则重试
            } else { // 队列已满
                return false;
            }

            spin = Condition.progressiveYield(spin);
        }
    }

    @Override
    public E poll() {
        int spin = 0;

        for (;;) {
            final long headSeq = head.sum();

            // 判断队列中是否有元素
            if (tailCache > headSeq || (tailCache = tail.sum()) > headSeq) {
                // 判断队首的序列号是否正常自增
                if (headCursor.compareAndSet(headSeq, headSeq + 1L)) {
                    try {
                        final int pollSlot = (int)(headSeq & mask);
                        final E   pollObj  = (E)buffer[pollSlot];

                        buffer[pollSlot] = null;

                        return pollObj;
                    } finally {
                        head.increment();
                    }
                } // 如果队首的序号被其他线程获取则该线程重试
            } else {  // 队列中没有元素
                return null;
            }

            spin = Condition.progressiveYield(spin);
        }
    }

    @Override
    public E peek() {
        return buffer[(int)(head.sum() & mask)];
    }

    @Override
    public int size() {
        // 队列元素的个数
        return (int)Math.max(tail.sum() - head.sum(), 0);
    }

    @Override
    public int capacity() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return tail.sum() == head.sum();
    }

    @Override
    public boolean contains(Object o) {
        for (int i=0;i<size();i++) {
            final int slot = (int)((head.sum()+i)& mask);
            if (buffer[slot] != null && buffer[slot].equals(o)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int remove(E[] e) {
        final int maxElements = e.length;
        int spin = 0;
        for (;;) {
            final long pollPos = head.sum();
            final int nToRead = Math.min((int)(tail.sum() - pollPos), maxElements);
            if (nToRead > 0) {
                for (int i=0;i<nToRead;i++) {
                    final int pollSlot = (int)((pollPos+i) & mask);
                    e[i] = buffer[pollSlot];
                }

                if (headCursor.compareAndSet(pollPos, pollPos + nToRead)) {
                    head.add(nToRead);
                    return nToRead;
                }
            } else {
                return 0;
            }
            spin = Condition.progressiveYield(spin);
        }
    }

    @Override
    public void clear() {
        int spin = 0;
        for (;;) {
            final long headSeq = head.sum();
            if (headCursor.compareAndSet(headSeq, headSeq + 1)) {
                for (;;) {
                    final long tailSeq = tail.sum();
                    if (tailCursor.compareAndSet(tailSeq, tailSeq + 1)) {
                         for (int i=0;i<buffer.length;i++) {
                             buffer[i] = null;
                         }

                         tail.increment();
                         head.add(tailSeq-headSeq + 1);
                         headCursor.set(tailSeq + 1);

                         return;
                    }
                    spin = Condition.progressiveYield(spin);
                }
            }

            spin = Condition.progressiveYield(spin);
        }
    }
}
