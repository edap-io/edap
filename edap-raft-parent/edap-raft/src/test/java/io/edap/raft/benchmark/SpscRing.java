package io.edap.raft.benchmark;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 单生产者单消费者环形队列，参照 vmsmith {@code ARCHITECTURE.md} §6.2 的描述实现。
 *
 * <p>要点：
 * <ul>
 *   <li>生产者游标与消费者游标各占一条 cache line（手工 padding），避免 false sharing</li>
 *   <li>各自缓存对方游标（{@code producerCursorCache} / {@code consumerCursorCache}），
 *       避免每次都做跨核读</li>
 *   <li>用 release/acquire 语义发布游标（等价于 vmsmith 的 {@code Unsafe.putOrderedLong}）</li>
 *   <li>容量取 2 的幂，槽位用 {@code & mask} 定位</li>
 *   <li>消费后把槽位置 null，帮助 GC</li>
 * </ul>
 *
 * <p>注意：这是纯 SPSC。多个生产者必须各用各的 ring，由 Merger 或消费者做 fan-in。
 */
public final class SpscRing<E> {

    private static final VarHandle PRODUCER_CURSOR;
    private static final VarHandle CONSUMER_CURSOR;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PRODUCER_CURSOR = lookup.findVarHandle(SpscRing.class, "producerCursor", long.class);
            CONSUMER_CURSOR = lookup.findVarHandle(SpscRing.class, "consumerCursor", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Object[] buffer;
    private final int mask;
    private final int capacity;

    /* ---------- 生产者独占的 cache line ---------- */
    /** 生产者写、消费者读（acquire） */
    private long producerCursor;
    /** 生产者缓存的消费者游标 */
    private long producerCursorCache;
    private long p1;
    private long p2;
    private long p3;
    private long p4;
    private long p5;
    private long p6;

    /* ---------- 消费者独占的 cache line ---------- */
    /** 消费者写、生产者读（acquire） */
    private long consumerCursor;
    /** 消费者缓存的生产者游标 */
    private long consumerCursorCache;
    private long c1;
    private long c2;
    private long c3;
    private long c4;
    private long c5;
    private long c6;

    public SpscRing(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of 2: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = new Object[capacity];
    }

    /**
     * 只有生产者线程可以调用。
     *
     * @return false 表示 ring 满了
     */
    public boolean offer(E e) {
        final long p = (long) PRODUCER_CURSOR.getAcquire(this);
        long c = producerCursorCache;
        if (p - c >= capacity) {
            c = (long) CONSUMER_CURSOR.getAcquire(this);
            if (p - c >= capacity) {
                return false;
            }
            producerCursorCache = c;
        }
        buffer[(int) (p & mask)] = e;
        PRODUCER_CURSOR.setRelease(this, p + 1);
        return true;
    }

    /**
     * 只有消费者线程可以调用。
     *
     * @return null 表示 ring 空了
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        final long c = (long) CONSUMER_CURSOR.getAcquire(this);
        long p = consumerCursorCache;
        if (c >= p) {
            p = (long) PRODUCER_CURSOR.getAcquire(this);
            if (c >= p) {
                return null;
            }
            consumerCursorCache = p;
        }
        final int slot = (int) (c & mask);
        final E e = (E) buffer[slot];
        buffer[slot] = null;
        CONSUMER_CURSOR.setRelease(this, c + 1);
        return e;
    }

    public int capacity() {
        return capacity;
    }
}
