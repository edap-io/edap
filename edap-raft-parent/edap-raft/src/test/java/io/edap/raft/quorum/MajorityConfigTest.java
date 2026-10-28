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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * etcd {@code quorum/majority_test.go} 与 {@code quorum/quick_test.go} 的 Java 移植。
 */
public class MajorityConfigTest {

    /**
     * Go: TestDescribe
     */
    @Test
    public void testDescribe() {
        // 用例 1：每个投票节点的提交位置各不相同
        MajorityConfig c = MajorityConfig.of(Arrays.asList(1L, 2L, 3L));
        Map<Long, Index> acked = new TreeMap<>();
        acked.put(1L, new Index(101));
        acked.put(2L, new Index(102));
        acked.put(3L, new Index(103));
        assertEquals("       idx\n"
                        + ">      101    (id=1)\n"
                        + "x>     102    (id=2)\n"
                        + "xx>    103    (id=3)\n",
                c.describe(new MapAckIndexer(acked)));

        // 用例 2：所有投票节点的提交位置相同
        acked = new TreeMap<>();
        acked.put(1L, new Index(100));
        acked.put(2L, new Index(100));
        acked.put(3L, new Index(100));
        assertEquals("       idx\n"
                        + ">      100    (id=1)\n"
                        + ">      100    (id=2)\n"
                        + ">      100    (id=3)\n",
                c.describe(new MapAckIndexer(acked)));

        // 用例 3：部分投票节点的提交位置相同
        acked = new TreeMap<>();
        acked.put(1L, new Index(100));
        acked.put(2L, new Index(102));
        acked.put(3L, new Index(102));
        assertEquals("       idx\n"
                        + ">      100    (id=1)\n"
                        + "x>     102    (id=2)\n"
                        + "x>     102    (id=3)\n",
                c.describe(new MapAckIndexer(acked)));

        // 用例 4：部分投票节点还没有提交位置
        acked = new TreeMap<>();
        acked.put(1L, new Index(101));
        acked.put(3L, new Index(103));
        assertEquals("       idx\n"
                        + "x>     101    (id=1)\n"
                        + "?        0    (id=2)\n"
                        + "xx>    103    (id=3)\n",
                c.describe(new MapAckIndexer(acked)));
    }

    /**
     * Go: TestQuick
     *
     * <p>etcd 用 testing/quick 随机生成成员集合与确认位置，
     * 断言 {@code CommittedIndex} 的主实现与「笨」实现结果一致。
     * Java 侧用固定种子的 {@link Random} 复现同样的性质检查。</p>
     */
    @Test
    public void testQuick() {
        final int maxCount = 50000;
        Random rand = new Random(0x5eed);
        for (int i = 0; i < maxCount; i++) {
            List<Long> ids = randomIds(rand);
            Map<Long, Index> l = randomIdxMap(rand);
            MajorityConfig c = MajorityConfig.of(ids);
            MapAckIndexer indexer = new MapAckIndexer(l);
            assertEquals(QuorumTestSupport.alternativeMajorityCommittedIndex(c, indexer),
                    c.committedIndex(indexer),
                    "case #" + i + " cfg=" + c + " l=" + l);
        }
    }

    /**
     * Go: smallRandIdxMap 里的 id 生成部分
     *
     * <p>取 {@code rand.Intn(10)} 个不重复的 id，取值范围是 {@code [0, 2n)}。</p>
     */
    private static List<Long> randomIds(Random rand) {
        int n = rand.nextInt(10);
        List<Long> perm = new ArrayList<>(2 * n);
        for (int i = 0; i < 2 * n; i++) {
            perm.add((long) i);
        }
        Collections.shuffle(perm, rand);
        return perm.subList(0, n);
    }

    /**
     * Go: smallRandIdxMap
     *
     * <p>生成一份「节点 id -> 提交位置」的映射，提交位置的取值范围是 {@code [0, n)}。</p>
     */
    private static Map<Long, Index> randomIdxMap(Random rand) {
        int n = rand.nextInt(10);
        List<Long> perm = new ArrayList<>(2 * n);
        for (int i = 0; i < 2 * n; i++) {
            perm.add((long) i);
        }
        Collections.shuffle(perm, rand);
        Map<Long, Index> m = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            m.put(perm.get(i), new Index(rand.nextInt(n)));
        }
        return m;
    }
}
