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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code tracker/progress_test.go} 的 Java 移植。
 *
 * <p>Go 的测试直接给 {@code Progress} 结构体赋值，Java 侧用 setter
 * 达到同样的效果（{@code State} 通过包内可见的 {@code setState} 设置）。</p>
 */
public class ProgressTest {

    /**
     * Go: TestProgressString
     */
    @Test
    public void testProgressString() {
        Inflights ins = new Inflights(1, 0);
        ins.add(123, 1);
        Progress pr = new Progress(1, 1, 2, ins);
        pr.setState(State.SNAPSHOT);
        pr.setPendingSnapshot(123);
        pr.setMsgAppFlowPaused(true);
        pr.setLearner(true);

        assertEquals("StateSnapshot match=1 next=2 learner paused pendingSnap=123 inactive inflight=1[full]",
                pr.toString());
    }

    /**
     * Go: TestProgressIsPaused
     */
    @Test
    public void testProgressIsPaused() {
        assertPaused(State.PROBE, false, false);
        assertPaused(State.PROBE, true, true);
        assertPaused(State.REPLICATE, false, false);
        assertPaused(State.REPLICATE, true, true);
        assertPaused(State.SNAPSHOT, false, true);
        assertPaused(State.SNAPSHOT, true, true);
    }

    private static void assertPaused(State state, boolean paused, boolean expected) {
        Progress p = new Progress(1, 0, 0, new Inflights(256, 0));
        p.setState(state);
        p.setMsgAppFlowPaused(paused);
        assertEquals(expected, p.isPaused(), "state=" + state + " paused=" + paused);
    }

    /**
     * Go: TestProgressResume
     *
     * <p>{@code MaybeUpdate} 与 {@code MaybeDecrTo} 都要复位 {@code MsgAppFlowPaused}。</p>
     */
    @Test
    public void testProgressResume() {
        Progress p = new Progress(1, 0, 2);
        p.setMsgAppFlowPaused(true);
        assertTrue(p.maybeDecrTo(1, 1));
        assertFalse(p.isMsgAppFlowPaused());

        p.setMsgAppFlowPaused(true);
        assertTrue(p.maybeUpdate(2));
        assertFalse(p.isMsgAppFlowPaused());
    }

    /**
     * Go: TestProgressBecomeProbe
     */
    @Test
    public void testProgressBecomeProbe() {
        // 普通状态：next 重置为 match+1
        Progress p = new Progress(1, 1, 5);
        p.setState(State.REPLICATE);
        assertBecomeProbe(p, 2);

        // 快照发送成功：从 pendingSnapshot+1 继续探测
        p = new Progress(1, 1, 5);
        p.setState(State.SNAPSHOT);
        p.setPendingSnapshot(10);
        assertBecomeProbe(p, 11);

        // 快照发送失败：仍从 match+1 开始
        p = new Progress(1, 1, 5);
        p.setState(State.SNAPSHOT);
        p.setPendingSnapshot(0);
        assertBecomeProbe(p, 2);
    }

    private static void assertBecomeProbe(Progress p, long expectedNext) {
        p.becomeProbe();
        assertEquals(State.PROBE, p.getState());
        assertEquals(1, p.getMatch());
        assertEquals(expectedNext, p.getNext());
    }

    /**
     * Go: TestProgressBecomeReplicate
     */
    @Test
    public void testProgressBecomeReplicate() {
        Progress p = new Progress(1, 1, 5);
        p.setState(State.PROBE);
        p.becomeReplicate();
        assertEquals(State.REPLICATE, p.getState());
        assertEquals(1, p.getMatch());
        assertEquals(p.getMatch() + 1, p.getNext());
    }

    /**
     * Go: TestProgressBecomeSnapshot
     */
    @Test
    public void testProgressBecomeSnapshot() {
        Progress p = new Progress(1, 1, 5);
        p.setState(State.PROBE);
        p.becomeSnapshot(10);
        assertEquals(State.SNAPSHOT, p.getState());
        assertEquals(1, p.getMatch());
        assertEquals(10, p.getPendingSnapshot());
    }

    /**
     * Go: TestProgressUpdate
     */
    @Test
    public void testProgressUpdate() {
        final long prevM = 3;
        final long prevN = 5;
        // do not decrease match, next
        assertUpdate(prevM, prevN, prevM - 1, prevM, prevN, false);
        // do not decrease next
        assertUpdate(prevM, prevN, prevM, prevM, prevN, false);
        // increase match, do not decrease next
        assertUpdate(prevM, prevN, prevM + 1, prevM + 1, prevN, true);
        // increase match, next
        assertUpdate(prevM, prevN, prevM + 2, prevM + 2, prevN + 1, true);
    }

    private static void assertUpdate(long prevM, long prevN, long update,
                                     long expectedMatch, long expectedNext, boolean expectedOk) {
        Progress p = new Progress(1, prevM, prevN);
        assertEquals(expectedOk, p.maybeUpdate(update), "update=" + update);
        assertEquals(expectedMatch, p.getMatch(), "match");
        assertEquals(expectedNext, p.getNext(), "next");
    }

    /**
     * Go: TestProgressMaybeDecr
     */
    @Test
    public void testProgressMaybeDecr() {
        // state replicate and rejected is not greater than match
        assertMaybeDecr(State.REPLICATE, 5, 10, 5, 5, false, 10);
        // state replicate and rejected is not greater than match
        assertMaybeDecr(State.REPLICATE, 5, 10, 4, 4, false, 10);
        // state replicate and rejected is greater than match
        // directly decrease to match+1
        assertMaybeDecr(State.REPLICATE, 5, 10, 9, 9, true, 6);
        // next-1 != rejected is always false
        assertMaybeDecr(State.PROBE, 0, 0, 0, 0, false, 0);
        // next-1 != rejected is always false
        assertMaybeDecr(State.PROBE, 0, 10, 5, 5, false, 10);
        // next>1 = decremented by 1
        assertMaybeDecr(State.PROBE, 0, 10, 9, 9, true, 9);
        // next>1 = decremented by 1
        assertMaybeDecr(State.PROBE, 0, 2, 1, 1, true, 1);
        // next<=1 = reset to 1
        assertMaybeDecr(State.PROBE, 0, 1, 0, 0, true, 1);
        // decrease to min(rejected, last+1)
        assertMaybeDecr(State.PROBE, 0, 10, 9, 2, true, 3);
        // rejected < 1, reset to 1
        assertMaybeDecr(State.PROBE, 0, 10, 9, 0, true, 1);
    }

    private static void assertMaybeDecr(State state, long m, long n, long rejected, long last,
                                        boolean expectedOk, long expectedNext) {
        Progress p = new Progress(1, m, n);
        p.setState(state);
        assertEquals(expectedOk, p.maybeDecrTo(rejected, last),
                "state=" + state + " rejected=" + rejected + " last=" + last);
        assertEquals(m, p.getMatch(), "match");
        assertEquals(expectedNext, p.getNext(), "next");
    }
}
