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

import org.junit.jupiter.api.Test;

import io.edap.raft.storage.MemoryStorage;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 选举超时只靠 tick 推进时的行为。
 *
 * <p>tick 是节点唯一的「时间来源」：没有网络消息、没有提案的时候，
 * 选举超时、心跳间隔全靠它。</p>
 *
 * <p>修 {@link Node#tick()} 之前，tick 只是塞进队列，
 * 状态机线程要等应用层下一次调用 {@code ready()} 才顺带被叫醒
 * （最多一个 100ms 的轮询切片延迟）；修完之后 tick 直接唤醒状态机线程。</p>
 */
public class NodeTickTest {

    /**
     * 只打 tick，不发任何网络消息，也不主动 campaign，节点应当自己选出主。
     */
    @Test
    public void electionTimeoutFiresFromTicksAlone() throws Exception {
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
        }, "tick-app");
        app.setDaemon(true);
        app.start();

        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (node.getRawNode().getRaft().getLead() == Raft.NONE
                    && System.currentTimeMillis() < deadline) {
                node.tick();
                Thread.sleep(20);
            }
            assertNotEquals(Raft.NONE, node.getRawNode().getRaft().getLead(),
                    "election timeout must fire from ticks alone: "
                            + "tick() has to wake the state machine thread up");
        } finally {
            stop.set(true);
            node.stop();
        }
    }
}
