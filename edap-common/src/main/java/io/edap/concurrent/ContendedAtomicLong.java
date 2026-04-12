package io.edap.concurrent;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 避免伪共享的一个数据结构
 */
public class ContendedAtomicLong {

    /**
     * 缓存行的数据大小
     */
    static final int CACHE_LINE = Integer.getInteger("Intel.CacheLineSize", 64); // bytes

    /**
     * 缓存行包含长整形的个数
     */
    private static final int CACHE_LINE_LONGS = CACHE_LINE/Long.BYTES;

    private final AtomicLongArray contendedArray;

    ContendedAtomicLong(final long init)
    {
        contendedArray = new AtomicLongArray(2*CACHE_LINE_LONGS);

        set(init);
    }

    void set(final long l) {
        contendedArray.set(CACHE_LINE_LONGS, l);
    }

    long get() {
        return contendedArray.get(CACHE_LINE_LONGS);
    }

    public String toString() {
        return Long.toString(get());
    }

    public boolean compareAndSet(final long expect, final long l) {
        return contendedArray.compareAndSet(CACHE_LINE_LONGS, expect, l);
    }
}
