package io.edap.raft.benchmark;

import io.edap.raft.RawNode;
import io.edap.raft.RaftConfig;
import io.edap.raft.Ready;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.storage.MemoryStorage;

import java.util.Collections;
import java.util.List;

/**
 * 量「raft 核心 + Ready 组装 + advance」这条纯内存路径的吞吐天花板。
 *
 * <p>这里完全绕开了 {@code Node}：没有队列、没有线程切换、没有
 * {@code wakeup} 信号，单线程直接驱动 {@link RawNode}，就像 etcd 的
 * {@code raftNode.start()} 那样：</p>
 *
 * <pre>
 *     propose xN  →  ready()  →  storage.append(entries)  →  advance()
 * </pre>
 *
 * <p>它给出的是「驱动链路上层」的天花板：{@code Node} 那一层再多花的时间
 * （队列、线程切换、Ready 重复组装）都是从这个数字里扣掉的。</p>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.benchmark.RaftCoreThroughputHarness [每批条数] [时长秒] [fsync毫秒] [载荷字节]
 * </pre>
 */
public final class RaftCoreThroughputHarness {

    public static void main(String[] args) throws Exception {
        int batch = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        long fsyncMillis = args.length > 2 ? Long.parseLong(args[2]) : 0;
        int payloadSize = args.length > 3 ? Integer.parseInt(args[3]) : 64;

        System.out.printf("batch=%d  fsync=%dms  payload=%dB  duration=%ds%n",
                batch, fsyncMillis, payloadSize, durationSec);

        List<Long> voters = Collections.singletonList(1L);
        MemoryStorage storage = new MemoryStorage(new ConfState(voters, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
        RaftConfig cfg = new RaftConfig();
        cfg.id = 1;
        cfg.electionTick = 10;
        cfg.heartbeatTick = 1;
        cfg.storage = storage;
        cfg.maxSizePerMsg = RaftConfig.NO_LIMIT;
        cfg.maxInflightMsgs = 256;

        RawNode rn = new RawNode(cfg);
        rn.campaign();
        // 竞选的自我投票要等落盘确认后才会生效（与 etcd 一样，投票响应在
        // messagesAfterAppend 里），所以先走一轮 Ready/advance 把票投出去。
        Ready vote = rn.ready();
        if (!vote.getEntries().isEmpty()) {
            storage.append(vote.getEntries());
        }
        if (vote.getHardState() != null) {
            storage.setHardState(vote.getHardState());
        }
        rn.advance(vote);
        if (rn.getRaft().getLead() != 1L) {
            throw new IllegalStateException("node is not leader");
        }

        byte[] data = new byte[payloadSize];
        long proposed = 0;
        long dropped = 0;
        long readys = 0;
        long committed = 0;
        long entriesTotal = 0;

        long deadline = System.nanoTime() + durationSec * 1_000_000_000L;
        long lastTick = System.nanoTime();
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < batch; i++) {
                if (rn.propose(data)) {
                    proposed++;
                } else {
                    dropped++;
                }
            }
            Ready rd = rn.ready();
            readys++;
            if (!rd.getEntries().isEmpty()) {
                entriesTotal += rd.getEntries().size();
                storage.append(rd.getEntries());
            }
            committed += rd.getCommittedEntries().size();
            rn.advance(rd);

            if (fsyncMillis > 0) {
                try {
                    Thread.sleep(fsyncMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 定期 tick，避免 leader 因为没推进逻辑时钟而误判
            long now = System.nanoTime();
            if (now - lastTick > 50_000_000L) {
                rn.tick();
                lastTick = now;
            }
        }

        double sec = durationSec;
        System.out.printf("  提案 %.0f/s  丢弃 %.0f/s  Ready %.0f/s  提交 %.0f/s  平均 %.1f 条/Ready%n",
                proposed / sec, dropped / sec, readys / sec, committed / sec,
                readys == 0 ? 0 : (double) entriesTotal / readys);
    }
}
