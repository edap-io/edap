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

import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.tracker.Config;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * etcd {@code confchange-quick_test.go} 的 Java 实现。
 *
 * <p>Go 用 {@code testing/quick} 做随机属性测试，Java 侧改成固定种子的伪随机，
 * 保证每次跑出来的用例完全一样，失败时也能复现。</p>
 *
 * <p>属性是：同一组变更，用简单协议（一条一条改）与用联合协议
 * （先进联合状态再退出）做出来的最终配置必须相同。</p>
 *
 * <p>Java 侧的 {@link Changer} 只维护配置，不维护每个节点的复制进度
 * （etcd 的 ProgressMap），所以这里只比较配置部分。</p>
 */
public class ConfChangeQuickTest {

    /** Go: quick.Config{MaxCount: 1000} */
    private static final int MAX_COUNT = 1000;

    /** 固定随机种子，保证用例可复现 */
    private static final long SEED = 20231117L;

    /**
     * Go: TestConfChangeQuick
     */
    @Test
    // Go: TestConfChangeQuick
    void testConfChangeQuick() {
        Random rnd = new Random(SEED);
        for (int i = 0; i < MAX_COUNT; i++) {
            List<ConfChangeSingle> setup = genInitialChanges(rnd);
            List<ConfChangeSingle> ccs = genConfChanges(rnd);

            Config simple = runWithSimple(setup, ccs);
            Config joint = runWithJoint(setup, ccs);
            assertEquals(simple, joint, "case " + i + ": setup=" + describe(setup)
                    + " changes=" + describe(ccs));
        }
    }

    /**
     * Go: runWithJoint
     *
     * <p>先进联合状态，再退出联合状态。</p>
     */
    private static Config runWithJoint(List<ConfChangeSingle> setup, List<ConfChangeSingle> ccs) {
        Changer c = new Changer(0, new Config());
        for (ConfChangeSingle cc : setup) {
            c = new Changer(c.getId(), c.simple(cc));
        }

        Config cfg = c.enterJoint(false, ccs);
        // autoLeave 打开再来一次，结果必须一样，只是 autoLeave 这个标志不同
        Config cfg2a = c.enterJoint(true, ccs);
        cfg2a.autoLeave = false;
        assertEquals(cfg, cfg2a, "joint config with and without autoLeave");

        // 退出联合状态
        Config cfg2b = new Changer(c.getId(), cfg).leaveJoint();
        // 回到主干（autoLeave=false）再退一次，结果必须一样
        Config cfg3 = new Changer(c.getId(), cfg).leaveJoint();
        assertEquals(cfg3, cfg2b, "leave joint");

        return cfg3;
    }

    /**
     * Go: runWithSimple
     *
     * <p>一条一条地做简单变更。</p>
     */
    private static Config runWithSimple(List<ConfChangeSingle> setup, List<ConfChangeSingle> ccs) {
        Changer c = new Changer(0, new Config());
        for (ConfChangeSingle cc : setup) {
            c = new Changer(c.getId(), c.simple(cc));
        }
        for (ConfChangeSingle cc : ccs) {
            c = new Changer(c.getId(), c.simple(cc));
        }
        return c.getTrackerConfig();
    }

    /**
     * Go: confChanges.Generate
     *
     * <p>随机生成 1~9 条变更，节点 id 落在 2~10（避开 1，1 是特殊节点）。</p>
     */
    private static List<ConfChangeSingle> genConfChanges(Random rnd) {
        int n = 1 + rnd.nextInt(9);
        List<ConfChangeSingle> ccs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long id = 1 + (1 + rnd.nextInt(9));
            ConfChangeType type = ConfChangeType.values()[rnd.nextInt(ConfChangeType.values().length)];
            ccs.add(new ConfChangeSingle(type, id));
        }
        return ccs;
    }

    /**
     * Go: initialChanges.Generate
     *
     * <p>节点 1 一定在里面（它永远是投票成员），其余是 1~5 条随机 id 的加节点。</p>
     */
    private static List<ConfChangeSingle> genInitialChanges(Random rnd) {
        int n = 1 + rnd.nextInt(5);
        List<ConfChangeSingle> ccs = new ArrayList<>(n + 1);
        ccs.add(new ConfChangeSingle(ConfChangeType.ADD_NODE, 1));
        for (int i = 0; i < n; i++) {
            long id = 1 + rnd.nextInt(5);
            ccs.add(new ConfChangeSingle(ConfChangeType.ADD_NODE, id));
        }
        return ccs;
    }

    /**
     * Go: confchange.Describe
     */
    private static String describe(List<ConfChangeSingle> ccs) {
        StringBuilder buf = new StringBuilder();
        for (ConfChangeSingle cc : ccs) {
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(cc.getType()).append('(').append(cc.getNodeId()).append(')');
        }
        return buf.toString();
    }
}
