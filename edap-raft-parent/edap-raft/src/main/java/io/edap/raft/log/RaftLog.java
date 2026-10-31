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

package io.edap.raft.log;

import io.edap.raft.RaftException;
import io.edap.raft.RaftLogger;
import io.edap.raft.RaftUtil;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.storage.Storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raft 的日志视图，等价于 etcd raft 的 {@code raftLog}。
 *
 * <p>它把日志分成两半：已经落盘的部分放在 {@link Storage} 里，尚未落盘的部分
 * 放在 {@link Unstable} 里。两部分拼起来就是完整的日志，
 * {@link #firstIndex()} / {@link #lastIndex()} / {@link #term(long)} 都是
 * 先查未落盘区、查不到再查存储。</p>
 *
 * <p>另外维护两个指针：{@code committed} 是已知被多数派持久化的最大位置，
 * {@code applied} 是已经交给状态机应用的最大位置，始终满足
 * {@code applied <= committed}。</p>
 */
public class RaftLog {

    /** 已落盘的日志 */
    private final Storage storage;

    /** 尚未落盘的日志与快照 */
    private final Unstable unstable;

    /** 日志输出器 */
    private final RaftLogger log;

    /** 已知被多数派持久化的最大位置 */
    private long committed;

    /**
     * 已经交给应用层、正在应用的最大位置。
     *
     * <p>这些条目可能正在被应用，还没有到达 {@link #applied}。
     * 接受一个 Ready 时推进，{@link #appliedTo(long, long)} 时跟随 applied 前进。
     * 不变式：{@code applied <= applying <= committed}。</p>
     */
    private long applying;

    /** 已经应用到状态机的最大位置 */
    private long applied;

    /**
     * 已经从 {@link #nextCommittedEnts(boolean)} 取走、但还没有被
     * {@link #appliedTo(long, long)} 确认的条目总字节数。
     */
    private long applyingEntsSize;

    /**
     * 是否暂停应用条目。
     *
     * <p>未确认的条目字节数达到上限时暂停，等应用层确认之后再恢复。</p>
     */
    private boolean applyingEntsPaused;

    /**
     * 单次交给应用层的条目总字节上限，默认不限制。
     *
     * <p>对应 etcd 的 {@code MaxCommittedSizePerReady}。</p>
     */
    private long maxApplyingEntsSize = Long.MAX_VALUE;

    /**
     * 用给定的存储创建一个日志视图，未落盘区从存储的最后一条之后开始
     */
    public RaftLog(Storage storage) {
        this(storage, null, null);
    }

    /**
     * 用给定的存储与日志器创建一个日志视图
     */
    public RaftLog(Storage storage, RaftLogger log) {
        this(storage, null, log);
    }

    /**
     * 用给定的存储与未落盘区创建一个日志视图
     */
    public RaftLog(Storage storage, Unstable unstable) {
        this(storage, unstable, null);
    }

    /**
     * 用给定的存储、未落盘区与日志器创建一个日志视图
     *
     * @param storage 已落盘的日志存储
     * @param unstable 尚未落盘的日志与快照，null 表示新建
     * @param log      日志输出器，null 表示不记录
     */
    public RaftLog(Storage storage, Unstable unstable, RaftLogger log) {
        if (storage == null) {
            throw new RaftException("storage must not be null");
        }
        this.storage = storage;
        this.log = log;
        // committed / applying / applied 初始化成上次压缩时的位置
        this.committed = storage.firstIndex() - 1;
        this.applying = storage.firstIndex() - 1;
        this.applied = storage.firstIndex() - 1;
        this.unstable = unstable == null ? new Unstable(storage.lastIndex() + 1) : unstable;
        if (log != null) {
            this.unstable.setLogger(log);
        }
    }

    /**
     * 已落盘的日志存储
     */
    public Storage getStorage() {
        return storage;
    }

    /**
     * 尚未落盘的日志与快照
     */
    public Unstable getUnstable() {
        return unstable;
    }

    /**
     * 单次交给应用层的条目总字节上限，默认 {@link Long#MAX_VALUE}（不限制）。
     *
     * <p>对应 etcd 的 {@code MaxCommittedSizePerReady}：已经交给应用层但还没被
     * 确认的条目超过这个上限时，暂停继续交付，避免应用层的内存被撑爆。</p>
     */
    public void setMaxApplyingEntsSize(long maxApplyingEntsSize) {
        this.maxApplyingEntsSize = maxApplyingEntsSize;
    }

    /**
     * 单次交给应用层的条目总字节上限
     */
    public long getMaxApplyingEntsSize() {
        return maxApplyingEntsSize;
    }

    /**
     * 测试专用：直接设置「是否暂停应用条目」的标记。
     *
     * <p>Go 的测试直接给 {@code raftLog.applyingEntsPaused} 赋值，
     * Java 侧用这个方法达到同样的效果。</p>
     */
    void setApplyingEntsPaused(boolean applyingEntsPaused) {
        this.applyingEntsPaused = applyingEntsPaused;
    }

    /**
     * 是否暂停应用条目
     */
    public boolean isApplyingEntsPaused() {
        return applyingEntsPaused;
    }

    /**
     * 已经交给应用层、但还没有被确认的条目总字节数
     */
    public long getApplyingEntsSize() {
        return applyingEntsSize;
    }

    /**
     * 第一条日志的 index。
     *
     * <p>有未落盘快照时是快照 index + 1，否则取存储的 firstIndex。</p>
     */
    public long firstIndex() {
        if (unstable.maybeFirstIndex()) {
            return unstable.firstIndex();
        }
        return storage.firstIndex();
    }

    /**
     * 最后一条日志的 index
     */
    public long lastIndex() {
        if (unstable.maybeLastIndex()) {
            return unstable.lastIndex();
        }
        return storage.lastIndex();
    }

    /**
     * 最后一条日志的任期，等价于 etcd 的 {@code lastEntryID} 里的 term。
     *
     * <p>日志被压缩到只剩快照点时，快照点的任期仍然可查，所以这里返回的是
     * 快照点的任期而不是 0。真的查不到（比如日志为空）时返回 0。</p>
     */
    public long lastTerm() {
        return zeroTermOnOutOfBounds(lastIndex());
    }

    /**
     * index 处的任期。
     *
     * <p>先查未落盘区，再查存储。index 已被压缩掉或者还没写入时抛
     * {@link RaftException}，需要“查不到就当不匹配”的地方请用
     * {@link #matchTerm(long, long)}。</p>
     */
    public long term(long i) {
        if (unstable.maybeTerm(i)) {
            return unstable.term(i);
        }
        // 有效任期区间是 [firstIndex - 1, lastIndex]，firstIndex - 1 处的条目
        // 虽然被压缩掉了，但任期仍然保留，用于追加日志时的一致性检查
        if (i + 1 < firstIndex()) {
            throw new RaftException("requested index " + i + " is unavailable due to compaction");
        }
        if (i > lastIndex()) {
            throw new RaftException("requested entry at index " + i + " is unavailable");
        }
        Entry entry = storage.term(i);
        return entry.getTerm();
    }

    /**
     * index 处的任期是否等于 term，查不到（已压缩或不存在）返回 false
     */
    public boolean matchTerm(long index, long expectedTerm) {
        try {
            return term(index) == expectedTerm;
        } catch (RaftException e) {
            return false;
        }
    }

    /**
     * 区间 [lo, hi) 的日志条目；越界会被裁剪；空区间返回空列表
     */
    public List<Entry> entries(long lo, long hi) {
        return entries(lo, hi, Long.MAX_VALUE);
    }

    /**
     * 区间 [lo, hi) 的日志条目，总字节数不超过 maxSize。
     *
     * <p>越界会被裁剪；空区间返回空列表。只要入参非空，就至少返回一条
     * （etcd 的 {@code limitSize} 语义）。</p>
     *
     * @param lo      起始位置（含）
     * @param hi      结束位置（不含）
     * @param maxSize 条目总字节数上限
     */
    public List<Entry> entries(long lo, long hi, long maxSize) {
        long last = lastIndex();
        if (lo > last) {
            return Collections.emptyList();
        }
        long first = firstIndex();
        if (lo < first) {
            lo = first;
        }
        if (hi > last + 1) {
            hi = last + 1;
        }
        if (hi <= lo) {
            return Collections.emptyList();
        }
        return slice(lo, hi, maxSize);
    }

    /**
     * 全部日志条目
     */
    public List<Entry> allEntries() {
        return entries(firstIndex(), lastIndex() + 1);
    }

    /**
     * 提交位置能否推进到 (term, index) 这条日志，等价于 etcd 的 {@code maybeCommit}。
     *
     * <p>只有当 index 处的任期正好等于给定的任期时才推进，避免把别的任期的
     * 日志当成已提交。term 为 0 时不推进（etcd 的约定：leader 至少在第 1 个任期
     * 竞选过，term 不可能为 0）。</p>
     *
     * @param term  该日志的任期
     * @param index 该日志的 index
     * @return true 表示提交位置发生了推进
     */
    public boolean maybeCommit(long term, long index) {
        if (term != 0 && index > committed && matchTerm(index, term)) {
            commitTo(index);
            return true;
        }
        return false;
    }

    /**
     * 取 index 处的任期，越界（已被压缩或尚不存在）时返回 0。
     *
     * <p>对应 etcd 的 {@code zeroTermOnOutOfBounds(l.term(i))}：Go 里
     * {@code zeroTermOnOutOfBounds} 收的是「任期 + 错误」，而 {@code term(i)} 一次性
     * 返回这两个值，所以 Go 写成 {@code zeroTermOnOutOfBounds(term(i))} 就是
     * 「取 i 处的任期，查不到按 0 处理」。Java 没有多返回值，直接收 index。</p>
     */
    public long zeroTermOnOutOfBounds(long index) {
        try {
            return term(index);
        } catch (RaftException e) {
            return 0;
        }
    }

    /**
     * 找出「任期不超过给定任期的最大位置」，用于在复制冲突时给出回退提示。
     *
     * <p>返回值第一个元素是猜测的 index，第二个元素是该位置的任期（未知时为 0）。
     * 从 index 往前找，遇到第一个任期不大于给定任期的位置就停下；如果中途
     * 查不到任期（被压缩或还不存在），就认为可能匹配，返回该位置与 0。</p>
     *
     * @param index 从哪个位置开始找
     * @param term  给定的任期上界
     * @return 长度为 2 的数组，[0] 是 index，[1] 是任期
     */
    public long[] findConflictByTerm(long index, long term) {
        long i = index;
        while (i > 0) {
            long ourTerm;
            try {
                ourTerm = term(i);
            } catch (RaftException e) {
                // 查不到任期，无法判断是否匹配，按可能匹配处理
                return new long[]{i, 0};
            }
            if (ourTerm <= term) {
                return new long[]{i, ourTerm};
            }
            i--;
        }
        return new long[]{0, 0};
    }

    /**
     * 是否有待应用或正在应用的快照。
     *
     * <p>处于这个状态时本节点还不能竞选 leader（{@code promotable} 要求为 false）。</p>
     */
    public boolean hasNextOrInProgressSnapshot() {
        return unstable.getSnapshot() != null;
    }

    /**
     * 判断对方（日志的最后一条是 lasti / term）是否比本节点更新。
     *
     * <p>任期大的更新；任期相同时 index 大的更新；完全相同时认为对方也是最新的。</p>
     */
    public boolean isUpToDate(long lasti, long term) {
        long ourTerm = lastTerm();
        long ourIndex = lastIndex();
        return term > ourTerm || (term == ourTerm && lasti >= ourIndex);
    }

    /**
     * 把 commit 指针推进到 toCommit。commit 只增不减
     */
    public void commitTo(long toCommit) {
        if (committed < toCommit) {
            long last = lastIndex();
            if (last < toCommit) {
                throw new RaftException("tocommit(" + toCommit
                        + ") is out of range [lastIndex(" + last + ")]. Was the raft log corrupted, truncated, or lost?");
            }
            committed = toCommit;
        }
    }

    /**
     * 把 applied 指针推进到 i，并把未确认的应用字节数减掉 size。
     *
     * @param i    已经应用到状态机的位置
     * @param size 本次确认的条目编码字节数
     */
    public void appliedTo(long i, long size) {
        if (committed < i || i < applied) {
            throw new RaftException("applied(" + i + ") is out of range [prevApplied("
                    + applied + "), committed(" + committed + ")]");
        }
        applied = i;
        applying = Math.max(applying, i);
        if (applyingEntsSize > size) {
            applyingEntsSize -= size;
        } else {
            // 防御下溢
            applyingEntsSize = 0;
        }
        applyingEntsPaused = applyingEntsSize >= maxApplyingEntsSize;
    }

    /**
     * 接受一批交给应用层的条目：推进 applying 指针并累加未确认的字节数。
     *
     * @param i            这批条目的最后一个位置
     * @param size         这批条目的编码字节数
     * @param allowUnstable 是否允许应用尚未落盘的条目
     */
    public void acceptApplying(long i, long size, boolean allowUnstable) {
        if (committed < i) {
            throw new RaftException("applying(" + i + ") is out of range [prevApplying("
                    + applying + "), committed(" + committed + ")]");
        }
        applying = i;
        applyingEntsSize += size;
        // 两种情况下暂停应用：
        // 1. 未确认的字节数已经达到上限；
        // 2. 未确认的字节数还没到上限，但下一批条目会超限。
        //    这里通过比较「本次实际交付到的位置」与「没有大小限制时本应交付到的位置」
        //    来判断，如果两者不相等，说明这批条目被截断过。
        applyingEntsPaused = applyingEntsSize >= maxApplyingEntsSize
                || i < maxAppliableIndex(allowUnstable);
    }

    /**
     * 已知被多数派持久化的最大位置
     */
    public long committed() {
        return committed;
    }

    /**
     * 已经应用到状态机的最大位置
     */
    public long applied() {
        return applied;
    }

    /**
     * 已经交给应用层、正在应用的最大位置
     */
    public long applying() {
        return applying;
    }

    /**
     * 标记 index 处的条目（任期为 term）已经落盘
     */
    public void stableTo(long i, long term) {
        unstable.stableTo(i, term);
    }

    /**
     * 标记 index 处的快照已经落盘
     */
    public void stableSnapTo(long i) {
        unstable.stableSnapTo(i);
    }

    /**
     * 标记未落盘区已经开始落盘，之后的 Ready 不再重复返回这些条目
     */
    public void acceptUnstable() {
        unstable.acceptInProgress();
    }

    /**
     * 丢弃 index 之后的未落盘日志。
     *
     * <p>只能截断尚未提交的日志，index 小于等于 committed 时抛
     * {@link RaftException}。</p>
     */
    public void truncateSuffix(long i) {
        if (i <= committed) {
            throw new RaftException("truncateSuffix is an illegal operation [index("
                    + i + ") <= committed(" + committed + ")]");
        }
        unstable.truncateSuffix(i);
    }

    /**
     * 把日志回退到 index：丢弃 index 之后的未落盘日志，并把 commit/applied
     * 指针移到 index。用于冲突回退或从快照重建内存日志状态。
     */
    public void reset(long i) {
        long last = lastIndex();
        if (i > last) {
            throw new RaftException("reset(" + i + ") is out of range [lastIndex(" + last + ")]");
        }
        unstable.truncateSuffix(i);
        committed = i;
        applied = Math.min(applied, i);
        applying = Math.min(applying, i);
    }

    /**
     * 最近一次快照：优先取未落盘快照，没有则取存储里的快照
     */
    public Snapshot snapshot() {
        Snapshot snap = unstable.getSnapshot();
        if (snap != null) {
            return snap;
        }
        return storage.snapshot();
    }

    /**
     * 用给定的快照恢复日志：commit 指针跳到快照点，未落盘区整体重置
     */
    public void restore(Snapshot s) {
        if (s == null) {
            throw new RaftException("snapshot must not be null");
        }
        if (log != null) {
            log.info("log [%s] starts to restore snapshot [index: %d, term: %d]",
                    this, s.getLastIncludedIndex(), s.getLastIncludedTerm());
        }
        committed = s.getLastIncludedIndex();
        unstable.restore(s);
    }

    /**
     * 全部未落盘条目（包含正在落盘的那部分）
     */
    public List<Entry> unstableEntries() {
        return unstable.getEntries();
    }

    /**
     * 还没有进入落盘流程的未落盘条目，也就是 Ready 里需要写盘的那部分
     */
    public List<Entry> nextUnstableEnts() {
        return unstable.nextEntries();
    }

    /**
     * 是否有待落盘的日志条目
     */
    public boolean hasNextUnstableEnts() {
        return unstable.hasNextEntries();
    }

    /**
     * 未落盘区里是否有内容（有条目，或者有正在落盘的条目）。
     *
     * <p>与 {@link #hasNextUnstableEnts()} 的区别是：这个方法把「正在落盘」
     * 的条目也算进来。</p>
     */
    public boolean hasNextOrInProgressUnstableEnts() {
        return !unstable.getEntries().isEmpty();
    }

    /**
     * 待落盘的快照，没有或者正在落盘返回 null
     */
    public Snapshot nextUnstableSnapshot() {
        return unstable.nextSnapshot();
    }

    /**
     * 是否有待落盘的快照
     */
    public boolean hasNextUnstableSnapshot() {
        return unstable.nextSnapshot() != null;
    }

    /**
     * 可以应用的最大提交位置。
     *
     * <p>allowUnstable 为 true 时，未落盘的条目也可以应用；为 false 时，
     * 只应用已经确定在本地稳定存储里的条目。</p>
     */
    public long maxAppliableIndex(boolean allowUnstable) {
        long hi = committed;
        if (!allowUnstable) {
            hi = Math.min(hi, unstable.getOffset() - 1);
        }
        return hi;
    }

    /**
     * 取出可以交给应用层执行的已提交条目。
     *
     * <p>条目在本地还没落盘时也可能已经提交，因此 allowUnstable 为 true 时
     * 未落盘的条目也会被返回；为 false 时只返回确定在本地稳定存储里的条目。</p>
     *
     * @param allowUnstable 是否允许应用尚未落盘的条目
     */
    public List<Entry> nextCommittedEnts(boolean allowUnstable) {
        if (applyingEntsPaused) {
            // 未确认的条目字节数达到上限，暂停应用
            return Collections.emptyList();
        }
        if (hasNextOrInProgressSnapshot()) {
            // 有快照要应用时不再同时返回已提交条目
            return Collections.emptyList();
        }
        long lo = applying + 1;
        long hi = maxAppliableIndex(allowUnstable) + 1;
        if (lo >= hi) {
            // 没有可应用的条目
            return Collections.emptyList();
        }
        long maxSize = maxApplyingEntsSize - applyingEntsSize;
        if (maxSize <= 0) {
            throw new RaftException("applying entry size (" + maxApplyingEntsSize + "-"
                    + applyingEntsSize + ")=" + maxSize + " not positive");
        }
        return slice(lo, hi, maxSize);
    }

    /**
     * 是否有可以交给应用层执行的已提交条目。
     *
     * <p>这是 {@link #nextCommittedEnts(boolean)} 的快速检查，不会真的切片。</p>
     */
    public boolean hasNextCommittedEnts(boolean allowUnstable) {
        if (applyingEntsPaused) {
            return false;
        }
        if (hasNextOrInProgressSnapshot()) {
            return false;
        }
        long lo = applying + 1;
        long hi = maxAppliableIndex(allowUnstable) + 1;
        return lo < hi;
    }

    /**
     * 分页扫描区间 [lo, hi) 的日志条目。
     *
     * <p>每页最多 pageSize 字节，通过回调逐页交给调用方，避免一次性把
     * 整个区间读进内存。回调返回 false 表示提前结束扫描。</p>
     *
     * @param lo       起始位置（含）
     * @param hi       结束位置（不含）
     * @param pageSize 每页的字节上限
     * @param consumer 每页的消费者，返回 false 表示停止扫描
     */
    public void scan(long lo, long hi, long pageSize, PageConsumer consumer) {
        while (lo < hi) {
            List<Entry> ents = slice(lo, hi, pageSize);
            if (ents.isEmpty()) {
                throw new RaftException("got 0 entries in [" + lo + ", " + hi + ")");
            }
            if (!consumer.accept(ents)) {
                return;
            }
            lo += ents.size();
        }
    }

    /**
     * 分页扫描时每页的消费者，返回 false 表示停止扫描。
     */
    public interface PageConsumer {

        /**
         * 处理一页日志条目
         *
         * @param ents 这一页的条目
         * @return false 表示提前结束扫描
         */
        boolean accept(List<Entry> ents);
    }

    /**
     * 找到与给定日志冲突的第一条条目的 index。
     *
     * <p>冲突的定义是 index 相同但任期不同。没有冲突时，如果给定日志完全被现有
     * 日志覆盖，返回 0；否则返回第一条新条目的 index。</p>
     */
    public long findConflict(List<Entry> ents) {
        if (ents == null) {
            return 0;
        }
        for (Entry entry : ents) {
            if (!matchTerm(entry.getIndex(), entry.getTerm())) {
                if (entry.getIndex() <= lastIndex()) {
                    if (log != null) {
                        log.info("found conflict at index %d [existing term: %d, conflicting term: %d]",
                                entry.getIndex(), zeroTermOnOutOfBounds(entry.getIndex()), entry.getTerm());
                    }
                }
                return entry.getIndex();
            }
        }
        return 0;
    }

    /**
     * 尝试把 leader 的日志追加进来。
     *
     * <p>切片的前置条目与本地日志不匹配时返回 -1；匹配时把冲突之后的日志截断重写，
     * 返回追加后最后一条日志的 index。leaderCommit 只会被推进到不超过本次追加的
     * 最后一条。</p>
     */
    public long maybeAppend(LogSlice a, long leaderCommit) {
        if (!matchTerm(a.getPrev().getIndex(), a.getPrev().getTerm())) {
            return -1;
        }
        List<Entry> entries = a.getEntries();
        long lastNewIndex = a.getPrev().getIndex() + entries.size();
        long conflict = findConflict(entries);
        if (conflict != 0) {
            if (conflict <= committed) {
                throw new RaftException("entry " + conflict
                        + " conflict with committed entry [committed(" + committed + ")]");
            }
            long offset = a.getPrev().getIndex() + 1;
            if (conflict - offset > entries.size()) {
                throw new RaftException("index, " + (conflict - offset)
                        + ", is out of range [" + entries.size() + "]");
            }
            append(entries.subList((int) (conflict - offset), entries.size()));
        }
        commitTo(Math.min(leaderCommit, lastNewIndex));
        return lastNewIndex;
    }

    /**
     * 追加日志条目到未落盘区，返回追加后的最后一条日志的 index
     */
    public long append(List<Entry> ents) {
        if (ents == null || ents.isEmpty()) {
            return lastIndex();
        }
        long after = ents.get(0).getIndex() - 1;
        if (after < committed) {
            throw new RaftException("after(" + after + ") is out of range [committed(" + committed + ")]");
        }
        unstable.truncateAndAppend(ents);
        return lastIndex();
    }

    /**
     * 取出区间 [lo, hi) 的日志条目，跨越存储与未落盘区时把两段拼起来。
     *
     * <p>总字节数不超过 maxSize。</p>
     */
    List<Entry> slice(long lo, long hi, long maxSize) {
        mustCheckOutOfBounds(lo, hi);
        if (lo == hi) {
            return Collections.emptyList();
        }
        if (lo >= unstable.getOffset()) {
            // 整段都在未落盘区
            return limitSize(unstable.entriesIn(lo, hi), maxSize);
        }
        long cut = Math.min(hi, unstable.getOffset());
        List<Entry> ents = storage.entries(lo, cut, maxSize);
        if (hi <= unstable.getOffset()) {
            return ents;
        }
        // 快速路径：存储返回的条目数少于请求的数量，说明存储侧已经按大小截断过了
        if (ents.size() < cut - lo) {
            return ents;
        }
        // 慢速路径：算出实际占用，再决定未落盘区还能放多少
        long size = RaftCodec.entriesSize(ents);
        if (size >= maxSize) {
            return ents;
        }
        List<Entry> unstableEnts = limitSize(unstable.entriesIn(unstable.getOffset(), hi), maxSize - size);
        // 未落盘区的总大小只有在一批只有一条时才可能超出剩余额度，超出就丢弃这一条
        if (unstableEnts.size() == 1 && size + RaftCodec.entriesSize(unstableEnts) > maxSize) {
            return ents;
        }
        List<Entry> merged = new ArrayList<>(ents);
        merged.addAll(unstableEnts);
        return merged;
    }

    /**
     * 检查切片区间是否越界，等价于 etcd 的 {@code mustCheckOutOfBounds}。
     *
     * <p>不变式：{@code firstIndex <= lo <= hi <= firstIndex + len(entries)}。
     * lo 小于 firstIndex 说明日志已被压缩掉；hi 超过末尾属于调用方 bug。</p>
     *
     * @throws RaftException 区间非法或者已被压缩时抛出
     */
    void mustCheckOutOfBounds(long lo, long hi) {
        if (lo > hi) {
            throw new RaftException("invalid slice " + lo + " > " + hi);
        }
        long first = firstIndex();
        if (lo < first) {
            throw new RaftException("requested index " + lo + " is unavailable due to compaction");
        }
        long length = lastIndex() + 1 - first;
        if (hi > first + length) {
            throw new RaftException("slice[" + lo + "," + hi + ") out of bound ["
                    + first + "," + lastIndex() + "]");
        }
    }

    /**
     * 截取最长的前缀，使总字节数不超过 maxSize。
     *
     * <p>只要入参非空，就至少返回一条（etcd 的 limitSize 语义）。</p>
     */
    private List<Entry> limitSize(List<Entry> ents, long maxSize) {
        return RaftUtil.limitSize(ents, maxSize);
    }

    /**
     * 与 etcd 一致的可读描述。
     *
     * <p>格式是 {@code committed=…, applied=…, applying=…, unstable.offset=…,
     * unstable.offsetInProgress=…, len(unstable.Entries)=…}，
     * etcd 的日志里会直接打印这个字符串，这里保持一致。</p>
     */
    @Override
    public String toString() {
        return "committed=" + committed + ", applied=" + applied + ", applying=" + applying
                + ", unstable.offset=" + unstable.getOffset()
                + ", unstable.offsetInProgress=" + unstable.getOffsetInProgress()
                + ", len(unstable.Entries)=" + unstable.getEntries().size();
    }
}
