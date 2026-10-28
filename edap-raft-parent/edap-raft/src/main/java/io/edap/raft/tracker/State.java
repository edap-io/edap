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

package io.edap.raft.tracker;

/**
 * leader 眼中某个 follower 的复制状态，等价于 etcd 的 {@code tracker.StateType}。
 *
 * <ul>
 *     <li>{@link #PROBE}：follower 的日志位置未知，每个心跳周期最多发一条探测消息，
 *     等待 follower 回 hint 来确定它的 last index；长期收不到回应的多半已经掉线。</li>
 *     <li>{@link #REPLICATE}：稳态，leader 乐观地把 next 推到已发送位置，
 *     follower 持续接收日志追加。</li>
 *     <li>{@link #SNAPSHOT}：follower 需要的日志在 leader 这边已经不存在，
 *     必须先发整包快照，等 follower 追上后回到 {@link #REPLICATE}。</li>
 * </ul>
 */
public enum State {

    /** 探测状态，follower 的 last index 未知 */
    PROBE("StateProbe"),
    /** 复制状态，follower 正在持续接收日志 */
    REPLICATE("StateReplicate"),
    /** 快照状态，需要先发送快照才能恢复复制 */
    SNAPSHOT("StateSnapshot");

    private final String label;

    State(String label) {
        this.label = label;
    }

    /**
     * 返回与 etcd 一致的状态名（StateProbe / StateReplicate / StateSnapshot），
     * 便于和上游日志、快照内容对照。
     */
    @Override
    public String toString() {
        return label;
    }
}
