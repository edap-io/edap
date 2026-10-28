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

package io.edap.raft.quorum;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static io.edap.raft.quorum.QuorumTestSupport.alternativeMajorityCommittedIndex;
import static io.edap.raft.quorum.QuorumTestSupport.longs;
import static io.edap.raft.quorum.QuorumTestSupport.lookuper;
import static io.edap.raft.quorum.QuorumTestSupport.overlay;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * etcd {@code quorum/datadriven_test.go} 的 Java 移植。
 *
 * <p>etcd 用 datadriven 框架读取 {@code quorum/testdata/*.txt}，
 * Java 侧不引入测试数据文件，改为把每个用例直接写成断言。</p>
 */
public class QuorumDataDrivenTest {

    // Go: TestDataDriven（majority_commit.txt）
    @Test
    public void testDataDrivenMajorityCommit() {

        // committed
        checkCommitted(
                MajorityConfig.of(longs(new long[]{})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{},
                new long[]{},
                "<empty majority quorum>",
                "\u221E");

        // committed cfg=(1) idx=(_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L},
                new long[]{-1L},
                "     idx\n?      0    (id=1)\n",
                "0");

        // committed cfg=(1) idx=(12)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L},
                new long[]{12L},
                "     idx\n>     12    (id=1)\n",
                "12");

        // committed cfg=(1, 2) idx=(_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L},
                new long[]{-1L, -1L},
                "      idx\n?       0    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1, 2) idx=(12,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L},
                new long[]{12L, -1L},
                "      idx\nx>     12    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1,2) idx=(12,5)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L},
                new long[]{12L, 5L},
                "      idx\nx>     12    (id=1)\n>       5    (id=2)\n",
                "5");

        // committed cfg=(1,2,3) idx=(_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{-1L, -1L, -1L},
                "       idx\n?        0    (id=1)\n?        0    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,2,3) idx=(12,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{12L, -1L, -1L},
                "       idx\nxx>     12    (id=1)\n?        0    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,2,3) idx=(12,5,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{12L, 5L, -1L},
                "       idx\nxx>     12    (id=1)\nx>       5    (id=2)\n?        0    (id=3)\n",
                "5");

        // committed cfg=(1,2,3) idx=(12,5,6)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{12L, 5L, 6L},
                "       idx\nxx>     12    (id=1)\n>        5    (id=2)\nx>       6    (id=3)\n",
                "6");

        // committed cfg=(1,2,3) idx=(12,5,4)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{12L, 5L, 4L},
                "       idx\nxx>     12    (id=1)\nx>       5    (id=2)\n>        4    (id=3)\n",
                "5");

        // committed cfg=(1,2,3) idx=(5,5,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{5L, 5L, -1L},
                "       idx\nx>       5    (id=1)\nx>       5    (id=2)\n?        0    (id=3)\n",
                "5");

        // committed cfg=(1,2,3) idx=(5,5,12)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{5L, 5L, 12L},
                "       idx\n>        5    (id=1)\n>        5    (id=2)\nxx>     12    (id=3)\n",
                "5");

        // committed cfg=(1, 2, 3) idx=(100, 101, 103)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L},
                new long[]{100L, 101L, 103L},
                "       idx\n>      100    (id=1)\nx>     101    (id=2)\nxx>    103    (id=3)\n",
                "101");

        // committed cfg=(1, 2, 3, 4, 5) idx=(101, 104, 103, 103,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{101L, 104L, 103L, 103L, -1L},
                "         idx\nx>       101    (id=1)\nxxxx>    104    (id=2)\nxx>      103    (id=3)\nxx>      103    (id=4)\n?          0    (id=5)\n",
                "103");

        // committed cfg=(1, 2, 3, 4, 5) idx=(101, 102, 103, 103,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{101L, 102L, 103L, 103L, -1L},
                "         idx\nx>       101    (id=1)\nxx>      102    (id=2)\nxxx>     103    (id=3)\nxxx>     103    (id=4)\n?          0    (id=5)\n",
                "102");
    }

    // Go: TestDataDriven（joint_commit.txt）
    @Test
    public void testDataDrivenJointCommit() {

        // committed cfg=(1,2,3) cfgj=zero idx=(100,101,99)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{100L, 101L, 99L},
                "       idx\nx>     100    (id=1)\nxx>    101    (id=2)\n>       99    (id=3)\n",
                "100");

        // committed cfg=(1) cfgj=(2) idx=(_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, -1L},
                "      idx\n?       0    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1) cfgj=(2) idx=(100,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{100L, -1L},
                "      idx\nx>    100    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1) cfgj=(2) idx=(13, 100)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{13L, 100L},
                "      idx\n>      13    (id=1)\nx>    100    (id=2)\n",
                "13");

        // committed cfg=(1) cfgj=(1) idx=(_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{1L})),
                true,
                new long[]{1L},
                new long[]{-1L},
                "     idx\n?      0    (id=1)\n",
                "0");

        // committed cfg=(1) cfgj=(1) idx=(100)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{1L})),
                true,
                new long[]{1L},
                new long[]{100L},
                "     idx\n>    100    (id=1)\n",
                "100");

        // committed cfg=(1,3) cfgj=(2) idx=(_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 3L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 3L, 2L},
                new long[]{-1L, -1L, -1L},
                "       idx\n?        0    (id=1)\n?        0    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,3) cfgj=(2) idx=(100,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 3L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 3L, 2L},
                new long[]{100L, -1L, -1L},
                "       idx\nxx>    100    (id=1)\n?        0    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,3) cfgj=(2) idx=(100,_,50)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 3L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 3L, 2L},
                new long[]{100L, -1L, 50L},
                "       idx\nxx>    100    (id=1)\nx>      50    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,3) cfgj=(2) idx=(100,45,50)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 3L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 3L, 2L},
                new long[]{100L, 45L, 50L},
                "       idx\nxx>    100    (id=1)\nx>      50    (id=2)\n>       45    (id=3)\n",
                "45");

        // committed cfg=(1,2) cfgj=(2) idx=(_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, -1L},
                "      idx\n?       0    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2) idx=(100,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{100L, -1L},
                "      idx\nx>    100    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2) idx=(_,100)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, 100L},
                "      idx\n?       0    (id=1)\nx>    100    (id=2)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2) idx=(50,100)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{50L, 100L},
                "      idx\n>      50    (id=1)\nx>    100    (id=2)\n",
                "50");

        // committed cfg=(1,2) cfgj=(2) idx=(100,50)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{100L, 50L},
                "      idx\nx>    100    (id=1)\n>      50    (id=2)\n",
                "50");

        // committed cfg=(1,2) cfgj=(3,4) idx=(50,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{50L, -1L, -1L, -1L},
                "        idx\nxxx>     50    (id=1)\n?         0    (id=2)\n?         0    (id=3)\n?         0    (id=4)\n",
                "0");

        // committed cfg=(1,2) cfgj=(3,4) idx=(50,_,49,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{50L, -1L, 49L, -1L},
                "        idx\nxxx>     50    (id=1)\n?         0    (id=2)\nxx>      49    (id=3)\n?         0    (id=4)\n",
                "0");

        // committed cfg=(1,2) cfgj=(3,4) idx=(50,48,49,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{50L, 48L, 49L, -1L},
                "        idx\nxxx>     50    (id=1)\nx>       48    (id=2)\nxx>      49    (id=3)\n?         0    (id=4)\n",
                "0");

        // committed cfg=(1,2) cfgj=(3,4) idx=(50,48,49,47)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{50L, 48L, 49L, 47L},
                "        idx\nxxx>     50    (id=1)\nx>       48    (id=2)\nxx>      49    (id=3)\n>        47    (id=4)\n",
                "47");

        // committed cfg=(1,2) cfgj=(2,3) idx=(_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{-1L, -1L, -1L},
                "       idx\n?        0    (id=1)\n?        0    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2,3) idx=(100,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{100L, -1L, -1L},
                "       idx\nxx>    100    (id=1)\n?        0    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2,3) idx=(_,100,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{-1L, 100L, -1L},
                "       idx\n?        0    (id=1)\nxx>    100    (id=2)\n?        0    (id=3)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2,3) idx=(_,100,99)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{-1L, 100L, 99L},
                "       idx\n?        0    (id=1)\nxx>    100    (id=2)\nx>      99    (id=3)\n",
                "0");

        // committed cfg=(1,2) cfgj=(2,3) idx=(101,100,99)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{101L, 100L, 99L},
                "       idx\nxx>    101    (id=1)\nx>     100    (id=2)\n>       99    (id=3)\n",
                "99");

        // committed cfg=(1,2) cfgj=(1,2) idx=(_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, -1L},
                "      idx\n?       0    (id=1)\n?       0    (id=2)\n",
                "0");

        // committed cfg=(1,2) cfgj=(1,2) idx=(_,40)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, 40L},
                "      idx\n?       0    (id=1)\nx>     40    (id=2)\n",
                "0");

        // committed cfg=(1,2) cfgj=(1,2) idx=(41,40)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{41L, 40L},
                "      idx\nx>     41    (id=1)\n>      40    (id=2)\n",
                "40");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(_,_,_,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{-1L, -1L, -1L, -1L, -1L, -1L},
                "          idx\n?           0    (id=1)\n?           0    (id=2)\n?           0    (id=3)\n?           0    (id=4)\n?           0    (id=5)\n?           0    (id=6)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(100,_,_,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{100L, -1L, -1L, -1L, -1L, -1L},
                "          idx\nxxxxx>    100    (id=1)\n?           0    (id=2)\n?           0    (id=3)\n?           0    (id=4)\n?           0    (id=5)\n?           0    (id=6)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(100,_,_,90,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{100L, -1L, -1L, 90L, -1L, -1L},
                "          idx\nxxxxx>    100    (id=1)\n?           0    (id=2)\n?           0    (id=3)\nxxxx>      90    (id=4)\n?           0    (id=5)\n?           0    (id=6)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(100,99,_,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{100L, 99L, -1L, -1L, -1L, -1L},
                "          idx\nxxxxx>    100    (id=1)\nxxxx>      99    (id=2)\n?           0    (id=3)\n?           0    (id=4)\n?           0    (id=5)\n?           0    (id=6)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(_,99,90,97,95,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{-1L, 99L, 90L, 97L, 95L, -1L},
                "          idx\n?           0    (id=1)\nxxxxx>     99    (id=2)\nxx>        90    (id=3)\nxxxx>      97    (id=4)\nxxx>       95    (id=5)\n?           0    (id=6)\n",
                "90");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(92,99,90,97,95,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{92L, 99L, 90L, 97L, 95L, -1L},
                "          idx\nxx>        92    (id=1)\nxxxxx>     99    (id=2)\nx>         90    (id=3)\nxxxx>      97    (id=4)\nxxx>       95    (id=5)\n?           0    (id=6)\n",
                "92");

        // committed cfg=(1,2,3) cfgj=(4,5,6) idx=(92,99,90,97,95,77)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{4L, 5L, 6L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L},
                new long[]{92L, 99L, 90L, 97L, 95L, 77L},
                "          idx\nxx>        92    (id=1)\nxxxxx>     99    (id=2)\nx>         90    (id=3)\nxxxx>      97    (id=4)\nxxx>       95    (id=5)\n>          77    (id=6)\n",
                "92");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(_,_,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{-1L, -1L, -1L, -1L, -1L},
                "         idx\n?          0    (id=1)\n?          0    (id=2)\n?          0    (id=3)\n?          0    (id=4)\n?          0    (id=5)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(100,_,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{100L, -1L, -1L, -1L, -1L},
                "         idx\nxxxx>    100    (id=1)\n?          0    (id=2)\n?          0    (id=3)\n?          0    (id=4)\n?          0    (id=5)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(100,101,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{100L, 101L, -1L, -1L, -1L},
                "         idx\nxxx>     100    (id=1)\nxxxx>    101    (id=2)\n?          0    (id=3)\n?          0    (id=4)\n?          0    (id=5)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(100,101,100,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{100L, 101L, 100L, -1L, -1L},
                "         idx\nxx>      100    (id=1)\nxxxx>    101    (id=2)\nxx>      100    (id=3)\n?          0    (id=4)\n?          0    (id=5)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(_,100,_,99,98)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{-1L, 100L, -1L, 99L, 98L},
                "         idx\n?          0    (id=1)\nxxxx>    100    (id=2)\n?          0    (id=3)\nxxx>      99    (id=4)\nxx>       98    (id=5)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(_,100,99,99,98)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{-1L, 100L, 99L, 99L, 98L},
                "         idx\n?          0    (id=1)\nxxxx>    100    (id=2)\nxx>       99    (id=3)\nxx>       99    (id=4)\nx>        98    (id=5)\n",
                "98");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(1,100,99,99,98)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{1L, 100L, 99L, 99L, 98L},
                "         idx\n>          1    (id=1)\nxxxx>    100    (id=2)\nxx>       99    (id=3)\nxx>       99    (id=4)\nx>        98    (id=5)\n",
                "98");

        // committed cfg=(1,2,3) cfgj=(1,4,5) idx=(100,100,99,99,98)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 4L, 5L})),
                true,
                new long[]{1L, 2L, 3L, 4L, 5L},
                new long[]{100L, 100L, 99L, 99L, 98L},
                "         idx\nxxx>     100    (id=1)\nxxx>     100    (id=2)\nx>        99    (id=3)\nx>        99    (id=4)\n>         98    (id=5)\n",
                "99");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(_,_,_,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, -1L, -1L, -1L},
                "        idx\n?         0    (id=1)\n?         0    (id=2)\n?         0    (id=3)\n?         0    (id=4)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(_,100,99,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, 100L, 99L, -1L},
                "        idx\n?         0    (id=1)\nxxx>    100    (id=2)\nxx>      99    (id=3)\n?         0    (id=4)\n",
                "99");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(98,100,99,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{98L, 100L, 99L, -1L},
                "        idx\nx>       98    (id=1)\nxxx>    100    (id=2)\nxx>      99    (id=3)\n?         0    (id=4)\n",
                "99");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(100,100,99,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{100L, 100L, 99L, -1L},
                "        idx\nxx>     100    (id=1)\nxx>     100    (id=2)\nx>       99    (id=3)\n?         0    (id=4)\n",
                "99");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(100,100,99,98)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{100L, 100L, 99L, 98L},
                "        idx\nxx>     100    (id=1)\nxx>     100    (id=2)\nx>       99    (id=3)\n>        98    (id=4)\n",
                "99");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(100,_,_,101)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{100L, -1L, -1L, 101L},
                "        idx\nxx>     100    (id=1)\n?         0    (id=2)\n?         0    (id=3)\nxxx>    101    (id=4)\n",
                "0");

        // committed cfg=(1,2,3) cfgj=(2,3,4) idx=(100,99,_,101)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{100L, 99L, -1L, 101L},
                "        idx\nxx>     100    (id=1)\nx>       99    (id=2)\n?         0    (id=3)\nxxx>    101    (id=4)\n",
                "99");

        // committed cfg=(1,2,3) cfgj=(1,2,3) idx=(50,45,_)
        checkCommitted(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{50L, 45L, -1L},
                "       idx\nxx>     50    (id=1)\nx>      45    (id=2)\n?        0    (id=3)\n",
                "45");
    }

    // Go: TestDataDriven（majority_vote.txt）
    @Test
    public void testDataDrivenMajorityVote() {

        // vote
        checkVote(
                MajorityConfig.of(longs(new long[]{})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{},
                new long[]{},
                "VoteWon");

        // vote cfg=(1) votes=(_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L},
                new long[]{-1L},
                "VotePending");

        // vote cfg=(1) votes=(n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L},
                new long[]{0L},
                "VoteLost");

        // vote cfg=(123) votes=(y)
        checkVote(
                MajorityConfig.of(longs(new long[]{123L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{123L},
                new long[]{1L},
                "VoteWon");

        // vote cfg=(4,8) votes=(_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{4L, 8L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{4L, 8L},
                new long[]{-1L, -1L},
                "VotePending");

        // vote cfg=(4,8) votes=(n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{4L, 8L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{4L, 8L},
                new long[]{0L, -1L},
                "VoteLost");

        // vote cfg=(4,8) votes=(y,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{4L, 8L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{4L, 8L},
                new long[]{1L, -1L},
                "VotePending");

        // vote cfg=(4,8) votes=(n,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{4L, 8L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{4L, 8L},
                new long[]{0L, 1L},
                "VoteLost");

        // vote cfg=(4,8) votes=(y,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{4L, 8L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{4L, 8L},
                new long[]{1L, 1L},
                "VoteWon");

        // vote cfg=(2,4,7) votes=(_,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{-1L, -1L, -1L},
                "VotePending");

        // vote cfg=(2,4,7) votes=(n,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{0L, -1L, -1L},
                "VotePending");

        // vote cfg=(2,4,7) votes=(y,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{1L, -1L, -1L},
                "VotePending");

        // vote cfg=(2,4,7) votes=(n,n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{0L, 0L, -1L},
                "VoteLost");

        // vote cfg=(2,4,7) votes=(y,n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{1L, 0L, -1L},
                "VotePending");

        // vote cfg=(2,4,7) votes=(y,y,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{1L, 1L, -1L},
                "VoteWon");

        // vote cfg=(2,4,7) votes=(y,y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{1L, 1L, 0L},
                "VoteWon");

        // vote cfg=(2,4,7) votes=(n,y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{2L, 4L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{2L, 4L, 7L},
                new long[]{0L, 1L, 0L},
                "VoteLost");

        // vote cfg=(1,2,3,4,5,6,7) votes=(y,y,n,y,_,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L},
                new long[]{1L, 1L, 0L, 1L, -1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2,3,4,5,6,7) votes=(_,y,y,_,n,y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L},
                new long[]{-1L, 1L, 1L, -1L, 0L, 1L, 0L},
                "VotePending");

        // vote cfg=(1,2,3,4,5,6,7) votes=(y,y,n,y,_,n,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L},
                new long[]{1L, 1L, 0L, 1L, -1L, 0L, 1L},
                "VoteWon");

        // vote cfg=(1,2,3,4,5,6,7) votes=(y,y,_,n,y,n,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L},
                new long[]{1L, 1L, -1L, 0L, 1L, 0L, 0L},
                "VotePending");

        // vote cfg=(1,2,3,4,5,6,7) votes=(y,y,n,y,n,n,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L})),
                MajorityConfig.of(longs(new long[]{})),
                false,
                new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L},
                new long[]{1L, 1L, 0L, 1L, 0L, 0L, 0L},
                "VoteLost");
    }

    // Go: TestDataDriven（joint_vote.txt）
    @Test
    public void testDataDrivenJointVote() {

        // vote cfgj=zero
        checkVote(
                MajorityConfig.of(longs(new long[]{})),
                MajorityConfig.of(longs(new long[]{})),
                true,
                new long[]{},
                new long[]{},
                "VoteWon");

        // vote cfg=(1) cfgj=zero votes=(_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                true,
                new long[]{1L},
                new long[]{-1L},
                "VotePending");

        // vote cfg=(1) cfgj=zero votes=(y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                true,
                new long[]{1L},
                new long[]{1L},
                "VoteWon");

        // vote cfg=(1) cfgj=zero votes=(n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{})),
                true,
                new long[]{1L},
                new long[]{0L},
                "VoteLost");

        // vote cfg=(1) cfgj=(1) votes=(_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{1L})),
                true,
                new long[]{1L},
                new long[]{-1L},
                "VotePending");

        // vote cfg=(1) cfgj=(1) votes=(y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{1L})),
                true,
                new long[]{1L},
                new long[]{1L},
                "VoteWon");

        // vote cfg=(1) cfgj=(1) votes=(n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{1L})),
                true,
                new long[]{1L},
                new long[]{0L},
                "VoteLost");

        // vote cfg=(1) cfgj=(2) votes=(_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, -1L},
                "VotePending");

        // vote cfg=(1) cfgj=(2) votes=(y,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{1L, -1L},
                "VotePending");

        // vote cfg=(1) cfgj=(2) votes=(y,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{1L, 1L},
                "VoteWon");

        // vote cfg=(1) cfgj=(2) votes=(y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{1L, 0L},
                "VoteLost");

        // vote cfg=(1) cfgj=(2) votes=(n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{0L, -1L},
                "VoteLost");

        // vote cfg=(1) cfgj=(2) votes=(n,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{0L, 0L},
                "VoteLost");

        // vote cfg=(1) cfgj=(2) votes=(n,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L})),
                MajorityConfig.of(longs(new long[]{2L})),
                true,
                new long[]{1L, 2L},
                new long[]{0L, 1L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(3,4) votes=(_,_,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, -1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(3,4) votes=(y,_,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, -1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(3,4) votes=(y,y,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(3,4) votes=(y,y,n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 0L, -1L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(3,4) votes=(y,y,n,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 0L, 0L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(3,4) votes=(y,y,y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 1L, 0L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(3,4) votes=(y,y,y,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 1L, 1L},
                "VoteWon");

        // vote cfg=(1,2) cfgj=(2,3) votes=(_,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{-1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(2,3) votes=(_,n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{-1L, 0L, -1L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(2,3) votes=(y,y,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{1L, 1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(2,3) votes=(y,y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{1L, 1L, 0L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(2,3) votes=(y,y,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{2L, 3L})),
                true,
                new long[]{1L, 2L, 3L},
                new long[]{1L, 1L, 1L},
                "VoteWon");

        // vote cfg=(1,2) cfgj=(1,2) votes=(_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{-1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(1,2) votes=(y,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{1L, -1L},
                "VotePending");

        // vote cfg=(1,2) cfgj=(1,2) votes=(y,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{1L, 0L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(1,2) votes=(n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{0L, -1L},
                "VoteLost");

        // vote cfg=(1,2) cfgj=(1,2) votes=(n,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                MajorityConfig.of(longs(new long[]{1L, 2L})),
                true,
                new long[]{1L, 2L},
                new long[]{0L, 0L},
                "VoteLost");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(_,_,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, -1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(_,n,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, 0L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(_,n,n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, 0L, 0L, -1L},
                "VoteLost");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(_,y,y,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{-1L, 1L, 1L, -1L},
                "VoteWon");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(y,y,_,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, -1L, -1L},
                "VotePending");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(y,y,n,_)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 0L, -1L},
                "VotePending");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(y,y,n,n)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 0L, 0L},
                "VoteLost");

        // vote cfg=(1,2,3) cfgj=(2,3,4) votes=(y,y,n,y)
        checkVote(
                MajorityConfig.of(longs(new long[]{1L, 2L, 3L})),
                MajorityConfig.of(longs(new long[]{2L, 3L, 4L})),
                true,
                new long[]{1L, 2L, 3L, 4L},
                new long[]{1L, 1L, 0L, 1L},
                "VoteWon");
    }

    /**
     * 校验一次 committed 查询的结果，同时复现 datadriven 框架内部做的那些不变性检查。
     */
    private static void checkCommitted(MajorityConfig c, MajorityConfig cj, boolean joint,
                                       long[] voters, long[] idxs,
                                       String expectedDescribe, String expectedIndex) {
        MapAckIndexer l = lookuper(voters, idxs);
        if (!joint) {
            assertEquals(expectedDescribe, c.describe(l), "describe");
            assertEquals(expectedIndex, c.committedIndex(l).toString(), "committed index");
            // 另一种算法算出来的结果必须一样
            assertEquals(expectedIndex, alternativeMajorityCommittedIndex(c, l).toString(),
                    "via alternative computation");
            // 与空配置联合、与自身联合，结果都必须一样
            assertEquals(expectedIndex, JointConfig.of(c, MajorityConfig.empty()).committedIndex(l).toString(),
                    "via zero-joint quorum");
            assertEquals(expectedIndex, JointConfig.of(c, c).committedIndex(l).toString(),
                    "via self-joint quorum");
            // 把某个成员的确认位置调低，结果也不能变
            for (long id : c.ids()) {
                Index cur = l.ackedIndex(id);
                long iidx = cur == null ? 0 : cur.value();
                Index idx = c.committedIndex(l);
                if (idx.compareTo(new Index(iidx)) > 0 && iidx > 0) {
                    assertEquals(expectedIndex, c.committedIndex(overlay(c, l, id, iidx - 1)).toString(),
                            "overlaying " + id + "->" + (iidx - 1));
                    assertEquals(expectedIndex, c.committedIndex(overlay(c, l, id, 0)).toString(),
                            "overlaying " + id + "->0");
                }
            }
        } else {
            JointConfig cc = JointConfig.of(c, cj);
            assertEquals(expectedDescribe, cc.describe(l), "describe");
            assertEquals(expectedIndex, cc.committedIndex(l).toString(), "committed index");
            // 交换联合配置的两半不应影响结果
            assertEquals(expectedIndex, JointConfig.of(cj, c).committedIndex(l).toString(), "via symmetry");
        }
    }

    /**
     * 校验一次投票查询的结果。
     *
     * @param votes 1 表示赞成（y），0 表示反对（n），-1 表示没有投票
     */
    private static void checkVote(MajorityConfig c, MajorityConfig cj, boolean joint,
                                  long[] voters, long[] votes, String expectedResult) {
        Map<Long, Boolean> ballots = new TreeMap<>();
        for (int i = 0; i < voters.length; i++) {
            if (votes[i] >= 0) {
                ballots.put(voters[i], votes[i] == 1);
            }
        }
        if (!joint) {
            assertEquals(expectedResult, c.voteResult(ballots).toString(), "vote result");
        } else {
            JointConfig cc = JointConfig.of(c, cj);
            assertEquals(expectedResult, cc.voteResult(ballots).toString(), "vote result");
            // 交换联合配置的两半不应影响结果
            assertEquals(expectedResult, JointConfig.of(cj, c).voteResult(ballots).toString(), "via symmetry");
        }
    }

}
