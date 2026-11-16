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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发启动时的确定性仲裁：(host, port) 最小者自举。
 */
public class RaftBootstrapArbiterTest {

    /**
     * 没有同伴 ⇒ 自举。
     */
    @Test
    void noPeerMeansBootstrap() {
        assertTrue(RaftBootstrapArbiter.shouldBootstrap("10.0.0.1", 1111, Collections.emptyList()));
        assertTrue(RaftBootstrapArbiter.shouldBootstrap("10.0.0.1", 1111, null));
    }

    /**
     * 同伴里有地址更小的 ⇒ 不自举，等他。
     */
    @Test
    void smallerPeerMeansWait() {
        assertFalse(RaftBootstrapArbiter.shouldBootstrap("10.0.0.2", 1111,
                Collections.singletonList("10.0.0.1:1111")));
    }

    /**
     * 同伴全都更大 ⇒ 自举。
     */
    @Test
    void allPeersBiggerMeansBootstrap() {
        assertTrue(RaftBootstrapArbiter.shouldBootstrap("10.0.0.1", 1111,
                Arrays.asList("10.0.0.2:1111", "10.0.0.3:1111")));
    }

    /**
     * 同 host 时比端口。
     */
    @Test
    void sameHostComparesByPort() {
        assertFalse(RaftBootstrapArbiter.shouldBootstrap("127.0.0.1", 1112,
                Collections.singletonList("127.0.0.1:1111")));
        assertTrue(RaftBootstrapArbiter.shouldBootstrap("127.0.0.1", 1111,
                Collections.singletonList("127.0.0.1:1112")));
    }

    /**
     * 比较函数：先 host 后 port。
     */
    @Test
    void compareOrdersByHostThenPort() {
        assertTrue(RaftBootstrapArbiter.compare("10.0.0.1:1111", "10.0.0.2:1111") < 0);
        assertTrue(RaftBootstrapArbiter.compare("10.0.0.1:1112", "10.0.0.1:1111") > 0);
        assertEquals(0, RaftBootstrapArbiter.compare("10.0.0.1:1111", "10.0.0.1:1111"));
    }

    /**
     * 地址拆解。
     */
    @Test
    void addressPartsAreExtracted() {
        assertEquals("10.0.0.1", RaftBootstrapArbiter.hostOf("10.0.0.1:1111"));
        assertEquals(1111, RaftBootstrapArbiter.portOf("10.0.0.1:1111"));
        assertEquals("", RaftBootstrapArbiter.hostOf(null));
        assertEquals(0, RaftBootstrapArbiter.portOf("10.0.0.1"));
    }
}
