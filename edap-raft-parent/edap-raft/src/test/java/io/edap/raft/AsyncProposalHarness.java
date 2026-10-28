package io.edap.raft;

import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 量「提案异步化」能把吞吐推到什么位置，以及在途窗口深度的影响。
 *
 * <p>不改任何生产代码：{@code Node.onMessage()} 对 {@code MSG_PROP} 的处理
 * 就是「入队就走、不等结果」，正好是异步提案的 publish 侧。
 * 同步提案 {@code Node.propose()} 会阻塞到状态机 step 完这条提案，
 * 差别在于有没有人在等 {@code CompletableFuture.result}。</p>
 *
 * <p>四种模式：</p>
 * <ul>
 *   <li>{@code sync} —— 阻塞到状态机 step 完，不等提交</li>
 *   <li>{@code sync-commit} —— 阻塞到状态机 step 完，再等提交（真实客户端语义）</li>
 *   <li>{@code async} —— 入队就走，完全不等（流水线填充的上限）</li>
 *   <li>{@code async-commit} —— 入队就走，但等自己的提案提交；
 *       第 5 个参数是每客户端允许的未确认提案条数（<b>在途窗口</b>），
 *       窗口=1 时退化成同步语义</li>
 * </ul>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.AsyncProposalHarness \
 *      [客户端数] [时长秒] [fsync毫秒] [sync|sync-commit|async|async-commit] [每客户端在途上限]
 * </pre>
 */
public final class AsyncProposalHarness {

    /** 载荷：4 字节客户端 id + 8 字节序号 */
    private static final int PAYLOAD_LEN = 12;

    /** 统计状态机收到的提案数 */
    static final class CountingRaft extends Raft {

        final AtomicInteger propSteps = new AtomicInteger();

        CountingRaft(RaftConfig cfg) {
            super(cfg);
        }

        @Override
        public boolean step(Message m) {
            if (m.getType() == MessageType.MSG_PROP) {
                propSteps.incrementAndGet();
            }
            return super.step(m);
        }
    }

    public static void main(String[] args) throws Exception {
        int clients = args.length > 0 ? Integer.parseInt(args[0]) : 16;
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        long fsyncMillis = args.length > 2 ? Long.parseLong(args[2]) : 0;
        String mode = args.length > 3 ? args[3] : "sync";
        int maxInflight = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        MemoryStorage storage = new MemoryStorage(new ConfState(
                Collections.singletonList(1L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
        RaftConfig cfg = new RaftConfig();
        cfg.id = 1;
        cfg.electionTick = 10;
        cfg.heartbeatTick = 1;
        cfg.storage = storage;
        cfg.maxSizePerMsg = RaftConfig.NO_LIMIT;
        cfg.maxInflightMsgs = 256;

        CountingRaft raft = new CountingRaft(cfg);
        Node node = new Node(new RawNode(raft));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger fsyncs = new AtomicInteger();
        AtomicLong entries = new AtomicLong();
        AtomicLong applied = new AtomicLong();
        // 每个客户端已提交的提案数
        AtomicLong[] committed = new AtomicLong[clients];
        Object[] commitLocks = new Object[clients];
        for (int i = 0; i < clients; i++) {
            committed[i] = new AtomicLong();
            commitLocks[i] = new Object();
        }

        // 应用层：取 Ready → 落盘 → 应用已提交条目 → advance
        Thread app = new Thread(() -> {
            while (!stop.get()) {
                Ready rd;
                try {
                    rd = node.ready(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (rd == null) {
                    continue;
                }
                if (!rd.getEntries().isEmpty()) {
                    fsyncs.incrementAndGet();
                    entries.addAndGet(rd.getEntries().size());
                    storage.append(rd.getEntries());
                    if (fsyncMillis > 0) {
                        try {
                            Thread.sleep(fsyncMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                boolean[] touched = null;
                for (Entry e : rd.getCommittedEntries()) {
                    byte[] data = e.getData();
                    if (data != null && data.length == PAYLOAD_LEN) {
                        int cid = ByteBuffer.wrap(data).getInt();
                        if (cid >= 0 && cid < committed.length) {
                            if (touched == null) {
                                touched = new boolean[committed.length];
                            }
                            touched[cid] = true;
                            committed[cid].incrementAndGet();
                        }
                    }
                }
                if (touched != null) {
                    for (int cid = 0; cid < touched.length; cid++) {
                        if (touched[cid]) {
                            synchronized (commitLocks[cid]) {
                                commitLocks[cid].notifyAll();
                            }
                        }
                    }
                }
                applied.addAndGet(rd.getCommittedEntries().size());
                node.advance();
            }
        }, "raft-app");
        app.setDaemon(true);
        app.start();

        node.campaign();
        long deadline = System.currentTimeMillis() + 5000;
        while (raft.getLead() == Raft.NONE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (raft.getLead() == Raft.NONE) {
            throw new IllegalStateException("node is not leader");
        }

        boolean fireAndForget = "async".equals(mode);
        boolean waitCommit = mode.endsWith("-commit");

        // 端到端延迟：提案从发布到被观察到提交的耗时
        long[] latencies = new long[200_000];
        AtomicInteger latN = new AtomicInteger();

        AtomicLong published = new AtomicLong();
        CountDownLatch done = new CountDownLatch(clients);
        for (int i = 0; i < clients; i++) {
            final int cid = i;
            Thread t = new Thread(() -> {
                try {
                    long seq = 0;      // 已发布的条数
                    long acked = 0;    // 已提交（按序）的条数
                    // 每个在途提案的发布时间（环形缓冲，槽位 = 序号 % 窗口）
                    long[] pubAt = new long[Math.max(1, maxInflight)];
                    while (!stop.get()) {
                        // 把在途窗口填满：流水线深度就是异步提案的收益来源。
                        // 纯 fire-and-forget 模式不等提交，在途窗口没有意义，
                        // 直接不限量地发。
                        int window = waitCommit ? maxInflight : Integer.MAX_VALUE;
                        while (seq - acked < window && !stop.get()) {
                            seq++;
                            byte[] data = payload(cid, seq);
                            if (waitCommit) {
                                pubAt[(int) (seq % pubAt.length)] = System.nanoTime();
                            }
                            if (fireAndForget) {
                                // 异步：入队就走，不等状态机
                                node.onMessage(new Message().setType(MessageType.MSG_PROP)
                                        .setEntries(Collections.singletonList(
                                                new Entry(0, 0, EntryType.NORMAL, data))));
                            } else {
                                // 同步：阻塞到状态机 step 完
                                node.propose(data);
                            }
                            published.incrementAndGet();
                        }
                        if (seq == acked) {
                            continue;
                        }
                        if (waitCommit) {
                            // 等最老的一条提交
                            if (!awaitCommit(cid, acked + 1, committed, commitLocks, stop)) {
                                return;
                            }
                            long now = committed[cid].get();
                            // 记录这一批里每一条的端到端延迟
                            for (long k = acked + 1; k <= now && k > 0; k++) {
                                int kk = latN.getAndIncrement();
                                if (kk < latencies.length) {
                                    latencies[kk] = now == 0 ? 0 : System.nanoTime() - pubAt[(int) (k % pubAt.length)];
                                }
                            }
                            acked = now;
                        }
                    }
                } catch (RaftException e) {
                    return;
                } finally {
                    done.countDown();
                }
            }, "producer-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(durationSec * 1000L);
        stop.set(true);
        done.await(10, TimeUnit.SECONDS);
        node.stop();

        double sec = durationSec;
        long committedTotal = 0;
        for (AtomicLong c : committed) {
            committedTotal += c.get();
        }
        System.out.printf(
                "mode=%-13s clients=%-3d fsync=%dms  状态机step %9.0f/s  提交 %9.0f/s  落盘 %7.0f/s  %8.1f 条/次落盘",
                mode, clients, fsyncMillis,
                raft.propSteps.get() / sec,
                committedTotal / sec,
                fsyncs.get() / sec,
                fsyncs.get() == 0 ? 0 : (double) entries.get() / fsyncs.get());
        if (latN.get() > 0) {
            long[] sorted = java.util.Arrays.copyOf(latencies, Math.min(latN.get(), latencies.length));
            java.util.Arrays.sort(sorted);
            System.out.printf("  端到端延迟(发布→提交) p50=%.2fms p99=%.2fms 均值=%.2fms%n",
                    pct(sorted, 50) / 1e6, pct(sorted, 99) / 1e6,
                    mean(sorted) / 1e6);
        }
        System.out.println();
    }

    /** 取分位数（输入已排序） */
    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static double mean(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    private static boolean awaitCommit(int cid, long seq, AtomicLong[] committed,
            Object[] commitLocks, AtomicBoolean stop) {
        synchronized (commitLocks[cid]) {
            while (!stop.get() && committed[cid].get() < seq) {
                try {
                    commitLocks[cid].wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private static byte[] payload(int clientId, long seq) {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putInt(clientId);
        buf.putLong(seq);
        return buf.array();
    }
}
