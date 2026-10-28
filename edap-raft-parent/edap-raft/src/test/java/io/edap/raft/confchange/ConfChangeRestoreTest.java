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

import io.edap.raft.protocol.ConfState;
import io.edap.raft.tracker.Config;
import io.edap.raft.tracker.ProgressTracker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * etcd {@code confchange-restore_test.go} 的 Java 实现。
 *
 * <p>属性是：任意一份 {@link ConfState} 都能用 {@link Restore} 重建出配置，
 * 重建出来的配置再转回 {@link ConfState} 必须与原来的一份（成员集合相同）。</p>
 *
 * <p>Go 用 {@code testing/quick} 做随机属性测试，Java 侧改成固定种子的伪随机，
 * 保证每次跑出来的用例完全一样。</p>
 */
public class ConfChangeRestoreTest {

    /** Go: quick.Config{MaxCount: 1000} */
    private static final int MAX_COUNT = 1000;

    /** 固定随机种子，保证用例可复现 */
    private static final long SEED = 20231117L;

    /**
     * Go: TestRestore
     */
    @Test
    // Go: TestRestore
    void testRestore() {
        // 单元用例
        ConfState[] unitCases = {
                // 空配置
                new ConfState(Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false),
                // 只有投票成员
                new ConfState(ids(1, 2, 3), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false),
                // 投票成员 + learner
                new ConfState(ids(1, 2, 3), ids(4, 5, 6),
                        Collections.emptyList(), Collections.emptyList(), false),
                // 联合配置：旧配置里有 1、2、4、6，新配置里有 1、2、3，
                // 4 退下来之后要变成 learner
                new ConfState(ids(1, 2, 3), ids(5), ids(1, 2, 4, 6), ids(4), false),
        };
        for (int i = 0; i < unitCases.length; i++) {
            checkRestore(unitCases[i], "unit case " + i);
        }

        // 随机用例
        Random rnd = new Random(SEED);
        for (int i = 0; i < MAX_COUNT; i++) {
            checkRestore(genRandomConfState(rnd), "random case " + i);
        }
    }

    /**
     * Go: TestRestore 里的 f 函数
     */
    private static void checkRestore(ConfState cs, String message) {
        Changer chg = new Changer(20, new Config());
        Config cfg = Restore.restore(chg, cs);

        ConfState cs2 = confStateOf(cfg);
        // 比较前先把成员列表排序，Go 那边也是这么做的
        assertEquals(sorted(cs), sorted(cs2), message);
    }

    /**
     * 把配置转回 ConfState，等价于 Go 的 {@code tracker.ConfState()}。
     */
    private static ConfState confStateOf(Config cfg) {
        ProgressTracker tracker = ProgressTracker.makeProgressTracker(0, 0);
        tracker.config = cfg;
        return tracker.confState();
    }

    /**
     * Go: rndConfChange.Generate
     *
     * <p>随机生成一份合法的 ConfState：投票成员、learner、被移除的投票成员
     * 三者互不重叠；learnersNext 是被移除投票成员的子集（它们退下来后变成 learner）。</p>
     */
    private static ConfState genRandomConfState(Random rnd) {
        int nVoters = 1 + rnd.nextInt(5);
        int nLearners = rnd.nextInt(5);
        int nRemovedVoters = rnd.nextInt(3);

        // Go 用 rand.Perm 生成一批互不相同的 id，再统一加一
        List<Long> all = perm(rnd, 2 * (nVoters + nLearners + nRemovedVoters));

        List<Long> voters = new ArrayList<>(all.subList(0, nVoters));
        List<Long> learners = new ArrayList<>(all.subList(nVoters, nVoters + nLearners));
        List<Long> removed = new ArrayList<>(all.subList(nVoters + nLearners,
                nVoters + nLearners + nRemovedVoters));

        // 旧配置里保留多少个投票成员
        int nOutgoingRetainedVoters = rnd.nextInt(nVoters + 1);
        List<Long> votersOutgoing = new ArrayList<>();
        if (nOutgoingRetainedVoters > 0 || nRemovedVoters > 0) {
            votersOutgoing.addAll(voters.subList(0, nOutgoingRetainedVoters));
            votersOutgoing.addAll(removed);
        }

        // 只有被移除的投票成员才可能变成 learner
        List<Long> learnersNext = new ArrayList<>();
        if (nRemovedVoters > 0) {
            int nLearnersNext = rnd.nextInt(nRemovedVoters + 1);
            learnersNext.addAll(removed.subList(0, nLearnersNext));
        }

        boolean autoLeave = !votersOutgoing.isEmpty() && rnd.nextInt(2) == 1;
        return new ConfState(voters, learners, votersOutgoing, learnersNext, autoLeave);
    }

    /**
     * 生成 1~n 的一个随机排列，等价于 Go 的 {@code rand.Perm} 再统一加一。
     */
    private static List<Long> perm(Random rnd, int n) {
        List<Long> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add((long) i);
        }
        // Fisher-Yates 洗牌
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            Long tmp = ids.get(i);
            ids.set(i, ids.get(j));
            ids.set(j, tmp);
        }
        // Go 的 conv：id 加一，0 变成 1
        for (int i = 0; i < n; i++) {
            ids.set(i, ids.get(i) + 1);
        }
        return ids;
    }

    /**
     * 把 ConfState 的各个成员列表排序后重新拼一份，便于比较。
     */
    private static ConfState sorted(ConfState cs) {
        List<Long> voters = new ArrayList<>(cs.getVoters());
        List<Long> learners = new ArrayList<>(cs.getLearners());
        List<Long> votersOutgoing = new ArrayList<>(cs.getVotersOutgoing());
        List<Long> learnersNext = new ArrayList<>(cs.getLearnersNext());
        Collections.sort(voters);
        Collections.sort(learners);
        Collections.sort(votersOutgoing);
        Collections.sort(learnersNext);
        return new ConfState(voters, learners, votersOutgoing, learnersNext, cs.isAutoLeave());
    }

    private static List<Long> ids(long... ids) {
        List<Long> list = new ArrayList<>(ids.length);
        for (long id : ids) {
            list.add(id);
        }
        return list;
    }
}
