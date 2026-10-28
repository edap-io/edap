package io.edap.raft.benchmark;

import io.edap.concurrent.DisruptorBlockingQueue;
import io.edap.concurrent.SpinPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 队列基准用的四种实现，统一成「多个生产者 publish、一个消费者 take」的形状。
 *
 * <p>对应 edap-raft 的 {@code recvc}：NIO reactor 线程（默认 = CPU 数，本机 10）
 * 加每 peer 一条 reader 线程都是生产者，raft 状态机线程是唯一消费者。</p>
 */
public abstract class MessageBus {

    protected final int producerCount;

    protected MessageBus(int producerCount) {
        this.producerCount = producerCount;
    }

    /**
     * 生产者侧写入。{@code producerIndex} 用来在多 ring 方案里选 ring。
     */
    public abstract void publish(int producerIndex, Object msg);

    /**
     * 消费者侧读取，返回 null 表示暂时没有消息。
     */
    public abstract Object take();

    /**
     * 消费者侧「等到有消息」或超时。
     *
     * <p>带锁队列用条件变量阻塞（对齐 edap-raft 里 raft 线程 {@code Semaphore.acquire()} 的行为），
     * 无锁 ring 只能自旋 + 退避（vmsmith 的 Merger 就是这么干的）。</p>
     */
    public abstract Object takeBlocking(long timeoutMillis);

    /**
     * 启动辅助线程（vmsmith 方案的 Merger）。
     */
    public void start() {
    }

    /**
     * 停止辅助线程。
     */
    public void stop() {
    }

    /**
     * 队列满时丢弃的消息数，用来判断压测结果是不是走了「丢弃快路径」。
     */
    public long dropped() {
        return 0;
    }

    /**
     * 给每个生产者线程分配一个稳定的下标，JMH 的 {@code @Group} 拿不到线程号。
     */
    public static final class ProducerIndex {

        private final ThreadLocal<Integer> index = ThreadLocal.withInitial(() -> -1);
        private final AtomicInteger seq = new AtomicInteger();

        public int get(int producerCount) {
            int i = index.get();
            if (i < 0) {
                i = seq.getAndIncrement() % producerCount;
                index.set(i);
            }
            return i;
        }
    }

    /**
     * 无锁 ring 的「阻塞等待」只能自旋 + 退避，这是 vmsmith Merger 的做法，
     * 代价是空转时整条 CPU 都被烧掉。
     */
    protected static Object spinTake(java.util.function.Supplier<Object> poll, long timeoutMillis) {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        int idle = 0;
        while (true) {
            Object o = poll.get();
            if (o != null) {
                return o;
            }
            idle++;
            if ((idle & 0xFF) == 0 && System.nanoTime() >= deadline) {
                return null;
            }
            if (idle > 1000) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    /* ==================== 1. 现状：LinkedBlockingQueue ==================== */

    public static final class LinkedBlockingBus extends MessageBus {

        private final LinkedBlockingQueue<Object> queue;
        private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();

        public LinkedBlockingBus(int producerCount, int capacity) {
            super(producerCount);
            this.queue = new LinkedBlockingQueue<>(capacity);
        }

        @Override
        public void publish(int producerIndex, Object msg) {
            if (!queue.offer(msg)) {
                dropped.incrementAndGet();
            }
        }

        @Override
        public Object take() {
            return queue.poll();
        }

        @Override
        public long dropped() {
            return dropped.get();
        }

        @Override
        public Object takeBlocking(long timeoutMillis) {
            try {
                return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /* ==================== 2. edap-common 现成的 DisruptorBlockingQueue ==================== */

    public static final class DisruptorBus extends MessageBus {

        private final DisruptorBlockingQueue<Object> queue;
        private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();

        public DisruptorBus(int producerCount, int capacity) {
            super(producerCount);
            this.queue = new DisruptorBlockingQueue<>(capacity, SpinPolicy.WAITING);
        }

        @Override
        public void publish(int producerIndex, Object msg) {
            if (!queue.offer(msg)) {
                dropped.incrementAndGet();
            }
        }

        @Override
        public Object take() {
            return queue.poll();
        }

        @Override
        public long dropped() {
            return dropped.get();
        }

        @Override
        public Object takeBlocking(long timeoutMillis) {
            try {
                return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /* ===== 2b. 对照组：同样是 LinkedBlockingQueue，但消费侧不睡眠而是自旋 ===== */

    public static final class LinkedBlockingSpinBus extends MessageBus {

        private final LinkedBlockingQueue<Object> queue;
        private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();

        public LinkedBlockingSpinBus(int producerCount, int capacity) {
            super(producerCount);
            this.queue = new LinkedBlockingQueue<>(capacity);
        }

        @Override
        public void publish(int producerIndex, Object msg) {
            if (!queue.offer(msg)) {
                dropped.incrementAndGet();
            }
        }

        @Override
        public Object take() {
            return queue.poll();
        }

        @Override
        public Object takeBlocking(long timeoutMillis) {
            return spinTake(this::take, timeoutMillis);
        }

        @Override
        public long dropped() {
            return dropped.get();
        }
    }

    /* ========== 3. vmsmith 式两级：producer ring -> Merger -> target ring ========== */

    public static final class VmsmithBus extends MessageBus {

        private final SpscRing<Object>[] rings;
        private final SpscRing<Object> target;
        private final boolean dropOnFull;
        private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
        private Thread merger;
        private volatile boolean running = true;

        public VmsmithBus(int producerCount, int perRingCapacity, int targetCapacity,
                          boolean dropOnFull) {
            super(producerCount);
            this.dropOnFull = dropOnFull;
            this.rings = new SpscRing[producerCount];
            for (int i = 0; i < producerCount; i++) {
                rings[i] = new SpscRing<>(perRingCapacity);
            }
            this.target = new SpscRing<>(targetCapacity);
        }

        @Override
        public void publish(int producerIndex, Object msg) {
            SpscRing<Object> ring = rings[producerIndex];
            while (!ring.offer(msg)) {
                if (dropOnFull) {
                    dropped.incrementAndGet();
                    return;
                }
                // 反压：不能丢 raft 消息，等空位
                Thread.onSpinWait();
            }
        }

        @Override
        public Object take() {
            return target.poll();
        }

        @Override
        public Object takeBlocking(long timeoutMillis) {
            return spinTake(target::poll, timeoutMillis);
        }

        @Override
        public long dropped() {
            return dropped.get();
        }

        @Override
        public void start() {
            merger = new Thread(this::mergeLoop, "bench-merger");
            merger.setDaemon(true);
            merger.start();
        }

        private void mergeLoop() {
            int idle = 0;
            while (running) {
                boolean moved = false;
                for (SpscRing<Object> ring : rings) {
                    for (int n = 0; n < 64; n++) {
                        Object o = ring.poll();
                        if (o == null) {
                            break;
                        }
                        while (!target.offer(o)) {
                            Thread.onSpinWait();
                        }
                        moved = true;
                    }
                }
                if (moved) {
                    idle = 0;
                } else {
                    idle++;
                    if (idle > 1000) {
                        Thread.yield();
                    } else {
                        Thread.onSpinWait();
                    }
                }
            }
        }

        @Override
        public void stop() {
            running = false;
            if (merger != null) {
                try {
                    merger.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /* ===== 4. 去掉 Merger 的一级 fan-in：raft 线程自己轮流扫各 producer ring ===== */

    public static final class FanInBus extends MessageBus {

        private final SpscRing<Object>[] rings;
        private final boolean dropOnFull;
        private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
        private int cursor = 0;

        public FanInBus(int producerCount, int perRingCapacity, boolean dropOnFull) {
            super(producerCount);
            this.dropOnFull = dropOnFull;
            this.rings = new SpscRing[producerCount];
            for (int i = 0; i < producerCount; i++) {
                rings[i] = new SpscRing<>(perRingCapacity);
            }
        }

        @Override
        public void publish(int producerIndex, Object msg) {
            SpscRing<Object> ring = rings[producerIndex];
            while (!ring.offer(msg)) {
                if (dropOnFull) {
                    dropped.incrementAndGet();
                    return;
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public Object take() {
            for (int n = 0; n < producerCount; n++) {
                SpscRing<Object> ring = rings[cursor];
                cursor = (cursor + 1) % producerCount;
                Object o = ring.poll();
                if (o != null) {
                    return o;
                }
            }
            return null;
        }

        @Override
        public Object takeBlocking(long timeoutMillis) {
            return spinTake(this::take, timeoutMillis);
        }

        @Override
        public long dropped() {
            return dropped.get();
        }
    }

    /**
     * 构造全部方案，供基准与延迟压测共用。
     *
     * @param dropOnFull    队列满时是否丢弃消息（压满对比用 true，忠实 raft 语义用 false）
     * @param capacity      带锁队列的容量
     * @param perRingCapacity 每个 producer ring 的容量
     */
    public static List<MessageBus> allVariants(int producerCount, boolean dropOnFull,
                                                int capacity, int perRingCapacity) {
        List<MessageBus> list = new ArrayList<>();
        list.add(new LinkedBlockingBus(producerCount, capacity));
        list.add(new LinkedBlockingSpinBus(producerCount, capacity));
        list.add(new DisruptorBus(producerCount, capacity));
        list.add(new VmsmithBus(producerCount, perRingCapacity, capacity, dropOnFull));
        list.add(new FanInBus(producerCount, perRingCapacity, dropOnFull));
        return list;
    }
}
