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

package io.edap.raft.protocol;

import io.edap.raft.RaftException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 线格式编解码的单测。
 *
 * <p>线格式是手写的大端字节拼装（没有用 protobuf），字段一多就容易错位，
 * 所以这里逐个字段做往返比对，并对帧头、长度计算做精确断言。</p>
 */
class RaftCodecTest {

    @Test
    @DisplayName("帧头：长度是大端，类型字节紧跟其后")
    void frameHeader() {
        Message msg = new Message().setType(MessageType.MSG_VOTE).setTerm(7);
        byte[] frame = RaftCodec.encode(msg);

        int bodyLength = RaftCodec.frameLength(frame, 0);
        assertEquals(frame.length - RaftCodec.HEADER_SIZE, bodyLength, "帧长字段应等于消息体长度");
        // 大端：长度最高的字节在最前
        assertEquals((byte) ((bodyLength >>> 24) & 0xFF), frame[0]);
        assertEquals((byte) (bodyLength & 0xFF), frame[3]);
        assertEquals(MessageType.MSG_VOTE.getCode(), frame[RaftCodec.LENGTH_FIELD_SIZE]);
    }

    @Test
    @DisplayName("标量字段往返")
    void scalarRoundTrip() {
        Message msg = new Message()
                .setType(MessageType.MSG_APP)
                .setTo(2)
                .setFrom(1)
                .setTerm(3)
                .setLogTerm(4)
                .setIndex(5)
                .setCommit(6)
                .setVote(7)
                .setReject(true)
                .setRejectHint(8);

        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);
        assertEquals(msg, decoded);
        assertEquals(2L, decoded.getTo());
        assertEquals(1L, decoded.getFrom());
        assertEquals(3L, decoded.getTerm());
        assertEquals(4L, decoded.getLogTerm());
        assertEquals(5L, decoded.getIndex());
        assertEquals(6L, decoded.getCommit());
        assertEquals(7L, decoded.getVote());
        assertEquals(true, decoded.getReject());
        assertEquals(8L, decoded.getRejectHint());
    }

    @Test
    @DisplayName("日志条目往返")
    void entriesRoundTrip() {
        Entry e1 = new Entry(1, 1, EntryType.NORMAL, "hello".getBytes(StandardCharsets.UTF_8));
        Entry e2 = new Entry(2, 1, EntryType.CONFIG_CHANGE, new byte[]{1, 2, 3});
        Entry e3 = new Entry(3, 2, EntryType.CONFIG_CHANGE_V2, new byte[0]);
        List<Entry> entries = Arrays.asList(e1, e2, e3);

        Message msg = new Message().setType(MessageType.MSG_APP).setEntries(entries);
        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);

        assertEquals(3, decoded.getEntries().size());
        assertEquals(e1, decoded.getEntries().get(0));
        assertEquals(e2, decoded.getEntries().get(1));
        assertEquals(e3, decoded.getEntries().get(2));
    }

    @Test
    @DisplayName("条目长度计算与实际编码一致")
    void entrySize() {
        Entry entry = new Entry(1, 2, EntryType.NORMAL, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(8 + 8 + 1 + 4 + 3, RaftCodec.entrySize(entry));

        // 空数据也要占掉长度字段
        assertEquals(8 + 8 + 1 + 4, RaftCodec.entrySize(new Entry(1, 1, EntryType.NORMAL, new byte[0])));

        List<Entry> entries = Arrays.asList(entry, entry);
        assertEquals(2L * RaftCodec.entrySize(entry), RaftCodec.entriesSize(entries));
        assertEquals(0L, RaftCodec.entriesSize(Collections.emptyList()));
        assertEquals(0L, RaftCodec.entriesSize(null));
    }

    @Test
    @DisplayName("带请求 id 的条目往返")
    void requestIdRoundTrip() {
        Entry withId = new Entry(1, 1, EntryType.NORMAL, new byte[]{9, 8, 7}, 0x0000_0002_0000_0001L);
        Entry withoutId = new Entry(2, 1, EntryType.NORMAL, new byte[]{6, 5});

        Message msg = new Message().setType(MessageType.MSG_APP)
                .setEntries(Arrays.asList(withId, withoutId));
        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);

        assertEquals(2, decoded.getEntries().size());
        assertEquals(withId, decoded.getEntries().get(0));
        assertEquals(0x0000_0002_0000_0001L, decoded.getEntries().get(0).getRequestId());
        assertEquals(withoutId, decoded.getEntries().get(1));
        assertEquals(0L, decoded.getEntries().get(1).getRequestId());
    }

    @Test
    @DisplayName("请求 id 是条件编码：不带请求 id 的条目字节数不变")
    void requestIdIsConditionallyEncoded() {
        Entry withoutId = new Entry(1, 1, EntryType.NORMAL, new byte[]{1, 2, 3});
        Entry withId = new Entry(1, 1, EntryType.NORMAL, new byte[]{1, 2, 3}, 42L);

        // 带请求 id 的条目多 8 字节
        assertEquals(RaftCodec.entrySize(withoutId) + 8, RaftCodec.entrySize(withId));

        // 整条消息只在这 8 个字节上有差别 —— 不带请求 id 的条目编码长度不变，
        // 流控的分批边界（maxSizePerMsg）不受影响
        byte[] without = RaftCodec.encode(new Message().setType(MessageType.MSG_APP)
                .setEntries(Collections.singletonList(withoutId)));
        byte[] with = RaftCodec.encode(new Message().setType(MessageType.MSG_APP)
                .setEntries(Collections.singletonList(withId)));
        assertEquals(without.length + 8, with.length);
    }

    @Test
    @DisplayName("快照与成员配置往返")
    void snapshotRoundTrip() {
        ConfState cs = new ConfState(
                Arrays.asList(1L, 2L, 3L),
                Collections.singletonList(5L),
                Arrays.asList(1L, 4L),
                Collections.singletonList(4L),
                true);
        Snapshot snapshot = new Snapshot("state".getBytes(StandardCharsets.UTF_8),
                new SnapshotMetadata(cs, 11, 12));

        Message msg = new Message().setType(MessageType.MSG_SNAP).setSnapshot(snapshot);
        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);

        Snapshot got = decoded.getSnapshot();
        assertNotNull(got);
        assertArrayEquals("state".getBytes(StandardCharsets.UTF_8), got.getData());
        assertEquals(11L, got.getLastIncludedIndex());
        assertEquals(12L, got.getLastIncludedTerm());
        assertEquals(cs, got.getConfState());
        assertEquals(Arrays.asList(1L, 2L, 3L), got.getConfState().getVoters());
        assertEquals(Collections.singletonList(5L), got.getConfState().getLearners());
        assertEquals(Arrays.asList(1L, 4L), got.getConfState().getVotersOutgoing());
        assertEquals(Collections.singletonList(4L), got.getConfState().getLearnersNext());
        assertEquals(true, got.getConfState().isAutoLeave());
    }

    @Test
    @DisplayName("快照里没有成员配置时解出来也是空的")
    void snapshotWithoutConfState() {
        Snapshot snapshot = new Snapshot(new byte[0], new SnapshotMetadata(null, 3, 4));
        Message msg = new Message().setType(MessageType.MSG_SNAP).setSnapshot(snapshot);

        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);
        assertNotNull(decoded.getSnapshot());
        assertNull(decoded.getSnapshot().getConfState());
        assertEquals(3L, decoded.getSnapshot().getLastIncludedIndex());
        assertEquals(4L, decoded.getSnapshot().getLastIncludedTerm());
    }

    @Test
    @DisplayName("附加数据往返")
    void contextRoundTrip() {
        byte[] context = "campaign-transfer".getBytes(StandardCharsets.UTF_8);
        Message msg = new Message().setType(MessageType.MSG_TIMEOUT_NOW).setContext(context);

        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);
        assertArrayEquals(context, decoded.getContext());
    }

    @Test
    @DisplayName("读索引的响应列表往返")
    void responsesRoundTrip() {
        Message resp1 = new Message().setType(MessageType.MSG_READ_INDEX_RESP).setIndex(9);
        Message resp2 = new Message().setType(MessageType.MSG_READ_INDEX_RESP).setIndex(10).setReject(true);
        Message msg = new Message().setType(MessageType.MSG_READ_INDEX)
                .setResponses(Arrays.asList(resp1, resp2));

        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);
        assertNotNull(decoded.getResponses());
        assertEquals(2, decoded.getResponses().size());
        assertEquals(resp1, decoded.getResponses().get(0));
        assertEquals(resp2, decoded.getResponses().get(1));
    }

    @Test
    @DisplayName("空的消息体也能往返")
    void emptyMessage() {
        Message msg = new Message().setType(MessageType.MSG_HEARTBEAT);
        Message decoded = RaftCodec.decodeFrame(RaftCodec.encode(msg), 0);
        assertEquals(msg, decoded);
        assertNull(decoded.getEntries());
        assertNull(decoded.getSnapshot());
        assertNull(decoded.getResponses());
    }

    @Test
    @DisplayName("帧没收全时解析要报错")
    void incompleteFrame() {
        byte[] frame = RaftCodec.encode(new Message().setType(MessageType.MSG_APP).setTerm(1));

        assertEquals(-1, RaftCodec.frameLength(new byte[]{0, 0}, 0), "不足 4 字节时长度未知");
        byte[] truncated = Arrays.copyOf(frame, frame.length - 1);
        assertThrows(RaftException.class, () -> RaftCodec.decodeFrame(truncated, 0),
                "消息体被截断时应当报错");
    }
}
