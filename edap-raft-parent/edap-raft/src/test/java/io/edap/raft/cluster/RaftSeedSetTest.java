/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft.cluster;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * seeds 的解析、展开与自排除。
 */
public class RaftSeedSetTest {

    /**
     * 普通地址原样保留，按 host:port 排序。
     */
    @Test
    void plainSeedsAreKeptAndSorted() {
        List<String> out = RaftSeedSet.expand(
                Arrays.asList("10.0.0.3:1111", "10.0.0.1:1111", "10.0.0.2:1111"),
                1111, null, 0, 256);
        assertEquals(Arrays.asList("10.0.0.1:1111", "10.0.0.2:1111", "10.0.0.3:1111"), out);
    }

    /**
     * ★ 显式地址排在通配展开之前 —— 这是「快路径」。
     *
     * <p>广播是并发扇出、先到先得：主在显式 seed 上时，
     * 探它只要几十毫秒；如果显式地址排在通配网段后面，
     * 前面两百多个不可达地址要先各超时一轮（一两秒）。</p>
     */
    @Test
    void explicitSeedsComeBeforeWildcardExpansion() {
        List<String> out = RaftSeedSet.expand(
                Arrays.asList("192.168.0.*:1111", "10.0.0.5:1111"), 1111, null, 0, 1024);
        assertEquals("10.0.0.5:1111", out.get(0), "显式地址必须排第一");
        assertEquals(255, out.size(), "254 个通配地址 + 1 个显式地址");
        assertEquals("192.168.0.1:1111", out.get(1), "通配地址排在后面，且组内有序");
    }

    /**
     * 显式地址与通配地址重合时只留一份。
     */
    @Test
    void duplicateAcrossExplicitAndWildcardIsRemoved() {
        List<String> out = RaftSeedSet.expand(
                Arrays.asList("192.168.0.5:1111", "192.168.0.*:1111"), 1111, null, 0, 1024);
        assertEquals(254, out.size());
        assertEquals(1, Collections.frequency(out, "192.168.0.5:1111"));
    }

    /**
     * 没写端口的 seed 用默认端口。
     */
    @Test
    void defaultPortIsApplied() {
        List<String> out = RaftSeedSet.expand(
                Collections.singletonList("10.0.0.1"), 1111, null, 0, 256);
        assertEquals(Collections.singletonList("10.0.0.1:1111"), out);
    }

    /**
     * ★ 通配：只支持最后一段是 {@code *}，展开 .1 ~ .254。
     */
    @Test
    void wildcardExpandsLastSegmentOnly() {
        List<String> out = RaftSeedSet.expand(
                Collections.singletonList("192.168.0.*:1111"), 1111, null, 0, 1024);
        assertEquals(254, out.size());
        assertTrue(out.contains("192.168.0.1:1111"));
        assertTrue(out.contains("192.168.0.254:1111"));
        assertTrue(out.contains("192.168.0.128:1111"));
        // 不会有 .0 和 .255
        assertFalse(out.contains("192.168.0.0:1111"));
        assertFalse(out.contains("192.168.0.255:1111"));
        // 顺序确定：所有节点看到同一个候选顺序
        assertEquals(out, RaftSeedSet.expand(
                Collections.singletonList("192.168.0.*:1111"), 1111, null, 0, 1024));
    }

    /**
     * ★ 中间段出现 {@code *} 必须报错 —— 不能猜。
     */
    @Test
    void wildcardInMiddleSegmentIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RaftSeedSet.expand(Collections.singletonList("192.168.*.1:1111"),
                        1111, null, 0, 1024));
        assertTrue(e.getMessage().contains("最后一段"), e.getMessage());
    }

    /**
     * ★ 不是四段式的通配也报错。
     */
    @Test
    void nonFourSegmentWildcardIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RaftSeedSet.expand(Collections.singletonList("10.*:1111"),
                        1111, null, 0, 1024));
    }

    /**
     * ★ 候选超限要炸，不能静默截断 —— 截断的后果是「找不到主」。
     */
    @Test
    void tooManyCandidatesFailsLoudly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RaftSeedSet.expand(Collections.singletonList("192.168.0.*:1111"),
                        1111, null, 0, 100));
        assertTrue(e.getMessage().contains("254"), e.getMessage());
        assertTrue(e.getMessage().contains("100"), e.getMessage());
    }

    /**
     * ★ 自排除：按 host:port 精确排除。
     *
     * <p>同机多容器（不同管理端口）是常见部署 ——
     * 只按 host 排除会把同伴也排掉。</p>
     */
    @Test
    void selfIsExcludedByHostAndPort() {
        List<String> out = RaftSeedSet.expand(
                Arrays.asList("127.0.0.1:1111", "127.0.0.1:1112", "127.0.0.1:1113"),
                1111, "127.0.0.1", 1112, 256);
        assertEquals(Arrays.asList("127.0.0.1:1111", "127.0.0.1:1113"), out);
    }

    /**
     * 自排除键：host 里带了端口就用它。
     */
    @Test
    void selfKeyUsesPortFromHostWhenPresent() {
        assertEquals("10.0.0.1:9999", RaftSeedSet.selfKey("10.0.0.1:9999", 1111));
        assertEquals("10.0.0.1:1111", RaftSeedSet.selfKey("10.0.0.1", 1111));
        assertEquals(null, RaftSeedSet.selfKey(null, 1111));
    }

    /**
     * 端口不是数字 ⇒ 报错。
     */
    @Test
    void nonNumericPortIsRejected() {
        try {
            RaftSeedSet.expand(Collections.singletonList("10.0.0.1:abc"), 1111, null, 0, 256);
            fail("应该抛异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("端口"), e.getMessage());
        }
    }

    /**
     * 解析：逗号分隔，去空白，去空项。
     */
    @Test
    void parseSplitsAndTrims() {
        assertEquals(Arrays.asList("a:1", "b:2"),
                RaftSeedSet.parse(" a:1 , b:2 ,, "));
        assertTrue(RaftSeedSet.parse(null).isEmpty());
    }
}
