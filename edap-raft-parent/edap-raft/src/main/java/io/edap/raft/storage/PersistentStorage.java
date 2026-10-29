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

package io.edap.raft.storage;

import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Snapshot;

import java.util.List;

/**
 * 带写侧的 {@link Storage}：把 raft 的日志与硬状态持久化到稳定存储。
 *
 * <p>{@link Storage} 只暴露读侧（供 raft 核心恢复日志状态），写侧在这里。
 * 与 etcd raft 的 {@code Storage} 接口不同：etcd 把写侧放在应用层
 * （{@code raftStorage.SaveSnap} 等），这里是存储自己拥有写能力，
 * 框架可以在一次落盘里同时写入硬状态与整批日志，只做一次 fsync。</p>
 *
 * <h3>落盘语义</h3>
 * <ul>
 *     <li>{@link #save(HardState, List)} 是<b>唯一</b>的落盘入口：
 *         硬状态与条目在同一次写里落盘，只做一次 force。</li>
 *     <li>硬状态里的 <b>commit 是易失的</b>：实现只需在 term 或 vote 变化时
 *         落盘硬状态，commit 搭车写进去，落盘的是它的<b>陈旧下界</b>。
 *         节点重启后 commit 由 leader 的 AppendEntries 重新建立
 *         （raft 论文 §5.4.2）。这样「只有 commit 变化」的轮次不需要 fsync，
 *         而这是 Ready 周期里最大的一笔浪费。</li>
 *     <li>term 与 vote <b>必须</b>在节点回复投票请求之前落盘，
 *         否则重启后可能出现同 term 双 leader。</li>
 * </ul>
 */
public interface PersistentStorage extends Storage {

    /**
     * 一次落盘：硬状态与一批日志条目。
     *
     * <p>实现应当把两者放进<b>同一次写</b>里，只做一次 force；
     * 没有任何东西需要写时（硬状态的 term/vote 没变，也没有条目）
     * 不应该产生任何 IO。</p>
     *
     * @param st 硬状态，可以为 null 表示没有变化
     * @param entries 日志条目，可以为空
     */
    void save(HardState st, List<Entry> entries);

    /**
     * 追加日志条目。
     *
     * <p>等价于 {@code save(null, entries)}。</p>
     */
    void append(List<Entry> entries);

    /**
     * 保存硬状态。
     *
     * <p>等价于 {@code save(st, null)}。</p>
     */
    void setHardState(HardState st);

    /**
     * 用给定的快照整体覆盖当前存储内容。
     */
    void applySnapshot(Snapshot snap);

    /**
     * 丢弃 index 之前（含 index）的日志条目。
     */
    void compact(long index);

    /**
     * 在 index 处生成一份快照，返回这份新快照。
     */
    Snapshot createSnapshot(long index, ConfState cs, byte[] data);
}
