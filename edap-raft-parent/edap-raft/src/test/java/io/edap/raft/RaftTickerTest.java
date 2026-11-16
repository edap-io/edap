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

package io.edap.raft;

import io.edap.raft.storage.MemoryStorage;
import io.edap.util.timing.TimerHandle;
import io.edap.util.timing.WheelTimerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Node#startTicker} —— tick 驱动接入平台时间轮。
 *
 * <p>raft 的超时全靠 tick 推进：没有网络消息、没有提案的时候，
 * 选举超时与心跳间隔都只能由 tick 驱动。这里验证"用平台时间轮驱动 tick
 * 之后，节点能自己选出主"——即 ticker 真的把 tick 送到了状态机线程。</p>
 */
public class RaftTickerTest {

    private WheelTimerService timerService;

    @AfterEach
    public void tearDown() {
        if (timerService != null) {
            timerService.close();
        }
    }

    @Test
    public void tickerDrivesElection() throws Exception {
        timerService = WheelTimerService.create();

        MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1L);
        RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
        Node node = Node.restartNode(cfg);

        AtomicBoolean stop = new AtomicBoolean();
        // 应用层：取 Ready → 落盘 → advance。
        // 单节点集群里，候选者给自己的投票要等落盘之后才会被释放，
        // 没有这一层就选不出主。
        Thread app = new Thread(() -> {
            while (!stop.get()) {
                Ready rd;
                try {
                    rd = node.ready(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (rd == null) {
                    continue;
                }
                if (!rd.getEntries().isEmpty()) {
                    storage.append(rd.getEntries());
                }
                node.advance();
            }
        }, "ticker-app");
        app.setDaemon(true);
        app.start();

        try {
            node.startTicker(timerService, 20);

            long deadline = System.currentTimeMillis() + 5000;
            while (node.getRawNode().getRaft().getLead() == Raft.NONE
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertNotEquals(Raft.NONE, node.getRawNode().getRaft().getLead(),
                    "平台时间轮驱动的 tick 必须能把选举超时推起来");
        } finally {
            stop.set(true);
            node.stop();
        }
    }

    @Test
    public void stopCancelsTicker() throws Exception {
        timerService = WheelTimerService.create();

        MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1L);
        RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
        Node node = Node.restartNode(cfg);
        try {
            TimerHandle h = node.startTicker(timerService, 20);
            assertTrue(timerService.pendingCount() > 0, "ticker 任务应挂在定时服务上");

            node.stop();
            assertTrue(h.isCancelled(), "节点停了，tick 驱动必须被撤掉");
        } finally {
            node.stop();
        }
    }

    @Test
    public void startTickerRejectsBadArguments() throws Exception {
        timerService = WheelTimerService.create();
        MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1L);
        RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
        Node node = Node.restartNode(cfg);
        try {
            assertThrows(NullPointerException.class, () -> node.startTicker(null, 10));
            assertThrows(IllegalArgumentException.class, () -> node.startTicker(timerService, 0));
        } finally {
            node.stop();
        }
    }
}
