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

package io.edap.raft.confchange;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.tracker.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@link ConfState} 重建集群配置，等价于 etcd 的 {@code confchange.Restore}。
 *
 * <p>做法是把 {@link ConfState} 翻译成两组变更：一组先建立即将退出的旧配置，
 * 另一组在旧配置之上建立生效配置；没有旧配置时直接一条条加，有旧配置时
 * 先加旧配置再进入联合状态。</p>
 *
 * <p>举例说明（voters=(1 2 3) learners=(5) outgoing=(1 2 4 6) learners_next=(4)）：</p>
 * <pre>
 *   outgoing = add 1; add 2; add 4; add 6
 *   incoming = remove 1; remove 2; remove 4; remove 6;
 *              add 1;    add 2;    add 3;
 *              add-learner 5;
 *              add-learner 4;
 * </pre>
 * <p>先应用 outgoing 得到 {@code quorum=(1 2 4 6)}，再进入联合状态得到
 * {@code quorum=(1 2 3)&&(1 2 4 6)}，其中 4 挂在旧配置里，
 * 旧配置退出后转成 learner。</p>
 */
public final class Restore {

    private Restore() {
    }

    /**
     * 按给定的 {@link ConfState} 重建配置。
     *
     * @param changer 变更执行器，提供本节点 id 和起始配置（一般是空配置）
     * @param state   要重建的集群配置
     * @return 重建出来的配置
     */
    public static Config restore(Changer changer, ConfState state) {
        if (changer == null) {
            throw new RaftException("changer must not be null");
        }
        if (state == null) {
            throw new RaftException("conf state must not be null");
        }

        List<ConfChangeSingle> outgoing = new ArrayList<>();
        List<ConfChangeSingle> incoming = new ArrayList<>();

        // 有旧配置时，先把旧配置的成员一个个加进来
        for (Long id : state.getVotersOutgoing()) {
            add(outgoing, ConfChangeType.ADD_NODE, id);
        }
        // 旧配置的成员在生效配置里先去掉
        for (Long id : state.getVotersOutgoing()) {
            add(incoming, ConfChangeType.REMOVE_NODE, id);
        }
        // 再加入生效配置的投票成员和 learner
        for (Long id : state.getVoters()) {
            add(incoming, ConfChangeType.ADD_NODE, id);
        }
        for (Long id : state.getLearners()) {
            add(incoming, ConfChangeType.ADD_LEARNER_NODE, id);
        }
        // learnersNext 里的节点当前还是旧配置里的投票成员，同样按 learner 加入，
        // 进入联合状态时会被挂起，旧配置退出后转正
        for (Long id : state.getLearnersNext()) {
            add(incoming, ConfChangeType.ADD_LEARNER_NODE, id);
        }

        Config cfg = changer.getTrackerConfig();
        if (outgoing.isEmpty()) {
            // 没有旧配置，直接把生效配置的成员一条条加进来
            for (ConfChangeSingle cc : incoming) {
                cfg = next(changer, cfg).simple(cc);
            }
        } else {
            // ConfState 描述的是联合配置：先把旧配置加进来，再进入联合状态
            for (ConfChangeSingle cc : outgoing) {
                cfg = next(changer, cfg).simple(cc);
            }
            cfg = next(changer, cfg).enterJoint(state.isAutoLeave(), incoming);
        }
        return cfg;
    }

    /**
     * 用上一步的结果构造下一步的变更执行器，
     * 等价于 etcd 中把返回值写回 {@code chg.Tracker.Config}。
     */
    private static Changer next(Changer changer, Config cfg) {
        return new Changer(changer.getId(), cfg);
    }

    /**
     * 往变更列表里追加一条变更，id 为空时跳过。
     */
    private static void add(List<ConfChangeSingle> list, ConfChangeType type, Long id) {
        if (id != null) {
            list.add(new ConfChangeSingle(type, id));
        }
    }
}
