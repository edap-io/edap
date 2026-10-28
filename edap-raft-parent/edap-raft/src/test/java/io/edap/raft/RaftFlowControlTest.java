/*
 * Copyright 2023 The edap Project
 *
 * The NetPy Project licenses this file to you under the Apache License,
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

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;
import io.edap.raft.tracker.Progress;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static io.edap.raft.RaftTestSupport.newTestMemoryStorage;
import static io.edap.raft.RaftTestSupport.newTestRaft;
import static io.edap.raft.RaftTestSupport.readMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code raft_flow_control_test.go} 的 Java 实现。
 *
 * <p>覆盖 leader 向 follower 发送追加消息时的「在途窗口」流控：</p>
 * <ul>
 *     <li>窗口没满时一直发，满了就停下来</li>
 *     <li>确认消息能把窗口往前推，过时的确认不起作用</li>
 *     <li>窗口满时收到心跳响应会腾出一个位置</li>
 * </ul>
 */
public class RaftFlowControlTest {

    /**
     * 造一条只有负载、没有 index/term 的提案条目（index/term 由 leader 赋值）。
     */
    private static List<Entry> someData() {
        return Collections.singletonList(
                new Entry(0, 0, EntryType.NORMAL, "somedata".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Go: TestMsgAppFlowControlFull
     */
    @Test
    void testMsgAppFlowControlFull() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft r = newTestRaft(1, 5, 1, storage);
        r.becomeCandidate();
        r.becomeLeader();

        Progress pr2 = r.getTracker().getProgress().get(2);
        // 强制把进度切到复制状态
        pr2.becomeReplicate();

        // 把在途窗口填满
        for (int i = 0; i < r.getTracker().maxInflight; i++) {
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP).setEntries(someData()));
            List<Message> ms = readMessages(r);
            assertEquals(1, ms.size(), "expect one message");
            assertEquals(MessageType.MSG_APP, ms.get(0).getType());
        }

        // 窗口满了，暂停发送
        assertTrue(pr2.isPaused());

        // 窗口没腾出位置之前，一条也发不出去
        for (int i = 0; i < 10; i++) {
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP).setEntries(someData()));
            List<Message> ms = readMessages(r);
            assertTrue(ms.isEmpty(), "expect no message, but got " + ms);
        }
    }

    /**
     * Go: TestMsgAppFlowControlMoveForward
     */
    @Test
    void testMsgAppFlowControlMoveForward() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft r = newTestRaft(1, 5, 1, storage);
        r.becomeCandidate();
        r.becomeLeader();

        Progress pr2 = r.getTracker().getProgress().get(2);
        // 强制把进度切到复制状态
        pr2.becomeReplicate();

        // 把在途窗口填满
        for (int i = 0; i < r.getTracker().maxInflight; i++) {
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP).setEntries(someData()));
            readMessages(r);
        }

        // index 1 是空操作，2 是刚发出的第一条提案，所以从 2 开始
        for (int tt = 2; tt < r.getTracker().maxInflight; tt++) {
            // 把窗口往前推
            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP).setIndex(tt));
            readMessages(r);

            // 再填一次窗口
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP).setEntries(someData()));
            List<Message> ms = readMessages(r);
            assertEquals(1, ms.size(), "expect one message");
            assertEquals(MessageType.MSG_APP, ms.get(0).getType());

            // 过时的确认消息不该影响窗口
            assertTrue(pr2.isPaused());
            for (int i = 0; i < tt; i++) {
                r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP).setIndex(i));
                assertTrue(pr2.isPaused());
            }
        }
    }

    /**
     * Go: TestMsgAppFlowControlRecvHeartbeat
     */
    @Test
    void testMsgAppFlowControlRecvHeartbeat() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft r = newTestRaft(1, 5, 1, storage);
        r.becomeCandidate();
        r.becomeLeader();

        Progress pr2 = r.getTracker().getProgress().get(2);
        // 强制把进度切到复制状态
        pr2.becomeReplicate();

        // 把在途窗口填满
        for (int i = 0; i < r.getTracker().maxInflight; i++) {
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP).setEntries(someData()));
            readMessages(r);
        }

        for (int tt = 1; tt < 5; tt++) {
            // 每收到一次心跳响应，就腾出一个位置
            for (int i = 0; i < tt; i++) {
                assertTrue(pr2.isPaused());
                // 先解除暂停，发一条空的 MsgApp，然后再暂停
                r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_HEARTBEAT_RESP));
                List<Message> ms = readMessages(r);
                assertEquals(1, ms.size(), "expect one message");
                assertEquals(MessageType.MSG_APP, ms.get(0).getType());
                assertTrue(ms.get(0).getEntries() == null || ms.get(0).getEntries().isEmpty(),
                        "expect empty entries");
            }

            // 没有心跳就发不出追加消息
            for (int i = 0; i < 10; i++) {
                assertTrue(pr2.isPaused());
                r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP).setEntries(someData()));
                List<Message> ms = readMessages(r);
                assertTrue(ms.isEmpty(), "expect no message, but got " + ms);
            }

            // 清掉所有待发消息
            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_HEARTBEAT_RESP));
            readMessages(r);
        }
    }
}
