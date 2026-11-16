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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提案回执表：requestId → future，以及幂等去重。
 */
public class RaftProposalTrackerTest {

    /**
     * 登记 → apply → 回执完成。
     */
    @Test
    void registerThenComplete() throws Exception {
        RaftProposalTracker tracker = new RaftProposalTracker();
        RaftProposalTracker.Registration reg = tracker.register(7L);
        assertFalse(reg.isDuplicate());
        byte[] payload = "hi".getBytes(StandardCharsets.UTF_8);
        assertTrue(tracker.complete(7L, payload));
        assertArrayEquals(payload, reg.getFuture().get(1, TimeUnit.SECONDS));
    }

    /**
     * ★ 同一个 requestId 重试：第二次登记直接判定为重复，
     * 并且不会再次应用（complete 返回 false）。
     */
    @Test
    void duplicateRequestIdIsAppliedOnce() {
        RaftProposalTracker tracker = new RaftProposalTracker();
        tracker.register(7L);
        assertTrue(tracker.complete(7L, new byte[]{1}));

        RaftProposalTracker.Registration again = tracker.register(7L);
        assertTrue(again.isDuplicate());
        assertTrue(again.getFuture().isDone());
        // 再来一次 apply 也算重复
        assertFalse(tracker.complete(7L, new byte[]{1}));
    }

    /**
     * 在途的同一个 requestId 复用同一个 future（不会被覆盖丢掉）。
     */
    @Test
    void concurrentSameRequestIdSharesFuture() {
        RaftProposalTracker tracker = new RaftProposalTracker();
        RaftProposalTracker.Registration a = tracker.register(7L);
        RaftProposalTracker.Registration b = tracker.register(7L);
        assertFalse(b.isDuplicate());
        assertEquals(a.getFuture(), b.getFuture());
        assertEquals(1, tracker.pendingCount());
    }

    /**
     * 失败：future 以异常结束。
     */
    @Test
    void failureCompletesExceptionally() {
        RaftProposalTracker tracker = new RaftProposalTracker();
        RaftProposalTracker.Registration reg = tracker.register(7L);
        tracker.fail(7L, new RuntimeException("boom"));
        assertTrue(reg.getFuture().isCompletedExceptionally());
        assertEquals(0, tracker.pendingCount());
    }

    /**
     * 没有 requestId 的条目当作普通条目放行。
     */
    @Test
    void zeroRequestIdIsPassedThrough() {
        RaftProposalTracker tracker = new RaftProposalTracker();
        assertTrue(tracker.complete(0L, new byte[]{1}));
    }
}
