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
import io.edap.raft.tracker.Config;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * etcd {@code confchange-datadriven_test.go} 的 Java 实现。
 *
 * <p>etcd 用 datadriven 框架读取 {@code confchange/testdata/*.txt}，
 * Java 侧不引入测试数据文件，改为把每个用例直接写成断言。</p>
 *
 * <p>etcd 的测试数据文件在这里取不到（离线环境），因此用例是按照
 * {@code confchange.Changer} 的语义逐条推导出来的，覆盖如下行为：</p>
 * <ul>
 *     <li>简单协议下投票成员一次只能变一个（{@code symdiff <= 1}）；</li>
 *     <li>投票成员被全部移除时报错；</li>
 *     <li>联合配置的进入与退出，以及进入联合配置后不能再做简单变更；</li>
 *     <li>联合配置期间把投票成员降级成 learner 时先挂起（learners_next），
 *         退出联合配置后才转正；</li>
 *     <li>进入联合配置时 autoLeave 标志的设置。</li>
 * </ul>
 *
 * <p>与 etcd 的差异：etcd 的用例还会输出各节点的复制进度
 * （{@code tracker.ProgressMap}），Java 侧的 {@link Changer} 只维护配置，
 * 不维护每个节点的复制进度，因此这里只比较配置部分。</p>
 */
public class ConfChangeDataDrivenTest {

    /**
     * Go: TestConfChangeDataDriven
     */
    @Test
    // Go: TestConfChangeDataDriven
    void testConfChangeDataDriven() {
        testSimple();
        testJoint();
        testJointLearner();
        testJointAutoLeave();
        testErrors();
    }

    /**
     * 简单协议下的成员变更。
     */
    private static void testSimple() {
        Harness h = new Harness();
        // 加第一个投票成员
        h.check("simple", "v1", "voters=(1)");
        // 再加一个投票成员
        h.check("simple", "v2", "voters=(1 2)");
        // 加一个 learner
        h.check("simple", "l3", "voters=(1 2) learners=(3)");
        // 移除一个投票成员
        h.check("simple", "r1", "voters=(2) learners=(3)");
        // 把刚被移除的节点重新加回来，它同时不再是 learner
        h.check("simple", "v1", "voters=(1 2) learners=(3)");
        // 把 learner 提升为投票成员
        h.check("simple", "v3", "voters=(1 2 3)");
        // 更新节点信息不影响配置
        h.check("simple", "u4", "voters=(1 2 3)");
        // nodeId 为 0 的变更会被忽略
        h.check("simple", "v0", "voters=(1 2 3)");
        // 一次改了两个投票成员，必须先进入联合配置
        h.check("simple", "r1 r2", "more than one voter changed without entering joint config");
        // 失败的命令不会改变配置
        h.check("simple", "v1", "voters=(1 2 3)");
        // 一次移除所有投票成员
        h.check("simple", "r1 r2 r3", "removed all voters");
        // 移除一个投票成员
        h.check("simple", "r3", "voters=(1 2)");
    }

    /**
     * 联合配置的进入与退出。
     */
    private static void testJoint() {
        Harness h = new Harness();
        h.check("simple", "v1", "voters=(1)");
        h.check("simple", "v2", "voters=(1 2)");
        // 进入联合配置：旧配置 (1 2) 被保留下来
        h.check("enter-joint", "v3", "voters=(1 2 3)&&(1 2)");
        // 已经是联合配置，不能再进入一次
        h.check("enter-joint", "v4", "config is already joint");
        // 联合配置下不能做简单变更
        h.check("simple", "v4", "can't apply simple config change in joint config");
        // 退出联合配置，旧配置被丢弃
        h.check("leave-joint", "", "voters=(1 2 3)");
        // 非联合配置不能退出
        h.check("leave-joint", "", "can't leave a non-joint config");
    }

    /**
     * 联合配置期间把投票成员降级成 learner。
     */
    private static void testJointLearner() {
        Harness h = new Harness();
        h.check("simple", "v1", "voters=(1)");
        h.check("simple", "v2", "voters=(1 2)");
        // 1 还在旧配置里当投票成员，只能先挂起
        h.check("enter-joint", "l1", "voters=(2)&&(1 2) learners_next=(1)");
        // 退出联合配置后，挂起的 learner 转正
        h.check("leave-joint", "", "voters=(2) learners=(1)");
    }

    /**
     * 进入联合配置时打开 autoLeave。
     */
    private static void testJointAutoLeave() {
        Harness h = new Harness();
        h.check("simple", "v1", "voters=(1)");
        h.check("enter-joint", "v2", true, "voters=(1 2)&&(1) autoleave");
        // 退出联合配置后 autoLeave 标志被清掉
        h.check("leave-joint", "", "voters=(1 2)");
    }

    /**
     * 各种非法输入与非法状态。
     */
    private static void testErrors() {
        Harness h = new Harness();
        // 空配置下一次性加两个投票成员
        h.check("simple", "v1 v2", "more than one voter changed without entering joint config");
        // 空配置不能进入联合状态
        h.check("enter-joint", "v1", "can't make a zero-voter config joint");
        // 非联合配置不能退出
        h.check("leave-joint", "", "can't leave a non-joint config");
        // 未知的变更前缀
        h.check("simple", "x1", "unknown input: x1");
        // 缺少节点 id
        h.check("simple", "v", "unknown token v");
        // 节点 id 不是一个数字
        h.check("simple", "vabc", "strconv.ParseUint: parsing \"abc\": invalid syntax");
        // leave-joint 不接受任何输入
        h.check("leave-joint", "v1", "this command takes no input");
    }

    /**
     * 等价于 etcd 的 datadriven 用例执行器：维护一份当前配置，
     * 每条命令作用在它上面，把结果打印出来。
     */
    private static final class Harness {

        /** 当前配置，初始是空配置 */
        private Config cfg = new Config();

        /**
         * 执行一条 {@code simple} 命令。
         */
        void check(String cmd, String input, String expected) {
            assertEquals(expected, run(cmd, input, false), cmd + " " + input);
        }

        /**
         * 执行一条 {@code enter-joint} 命令。
         */
        void check(String cmd, String input, boolean autoLeave, String expected) {
            assertEquals(expected, run(cmd, input, autoLeave), cmd + " " + input);
        }

        /**
         * 执行一条命令，返回它的输出。
         *
         * <p>命令执行成功时返回配置的可读描述，失败时返回错误信息；
         * 失败的命令不会改变当前配置。</p>
         */
        private String run(String cmd, String input, boolean autoLeave) {
            List<ConfChangeSingle> ccs;
            try {
                ccs = parse(input);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            try {
                if ("simple".equals(cmd)) {
                    cfg = new Changer(0, cfg).simple(ccs.toArray(new ConfChangeSingle[0]));
                } else if ("enter-joint".equals(cmd)) {
                    cfg = new Changer(0, cfg).enterJoint(autoLeave, ccs);
                } else if ("leave-joint".equals(cmd)) {
                    if (!ccs.isEmpty()) {
                        return "this command takes no input";
                    }
                    cfg = new Changer(0, cfg).leaveJoint();
                } else {
                    return "unknown command";
                }
            } catch (RaftException e) {
                return e.getMessage();
            }
            return cfg.toString();
        }

        /**
         * 把一行输入解析成变更列表。
         *
         * <p>每个 token 的第一个字符表示变更类型：{@code v} 加投票成员、
         * {@code l} 加 learner、{@code r} 移除、{@code u} 更新；
         * 剩下的部分是节点 id。</p>
         */
        private static List<ConfChangeSingle> parse(String input) {
            List<ConfChangeSingle> ccs = new ArrayList<>();
            if (input == null) {
                return ccs;
            }
            for (String tok : input.trim().split(" ")) {
                if (tok.isEmpty()) {
                    continue;
                }
                if (tok.length() < 2) {
                    throw new IllegalArgumentException("unknown token " + tok);
                }
                ConfChangeType type;
                switch (tok.charAt(0)) {
                    case 'v':
                        type = ConfChangeType.ADD_NODE;
                        break;
                    case 'l':
                        type = ConfChangeType.ADD_LEARNER_NODE;
                        break;
                    case 'r':
                        type = ConfChangeType.REMOVE_NODE;
                        break;
                    case 'u':
                        type = ConfChangeType.UPDATE_NODE;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown input: " + tok);
                }
                String id = tok.substring(1);
                long nodeId;
                try {
                    nodeId = Long.parseLong(id);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "strconv.ParseUint: parsing \"" + id + "\": invalid syntax");
                }
                ccs.add(new ConfChangeSingle(type, nodeId));
            }
            return ccs;
        }
    }
}
