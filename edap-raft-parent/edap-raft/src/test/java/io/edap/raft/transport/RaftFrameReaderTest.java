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

package io.edap.raft.transport;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧解析器的单测，重点验证跨 read 的字节累积
 */
class RaftFrameReaderTest {

    private static Message message(long index) {
        return new Message()
                .setType(MessageType.MSG_VOTE)
                .setTerm(index)
                .setFrom(index)
                .setTo(1)
                .setIndex(index)
                .setLogTerm(index);
    }

    @Test
    @DisplayName("一帧一次读完")
    void singleFrame() {
        RaftFrameReader reader = new RaftFrameReader();
        byte[] frame = RaftCodec.encode(message(1));
        reader.append(frame, 0, frame.length);
        Message decoded = reader.next();
        assertNotNull(decoded);
        assertEquals(1L, decoded.getTerm());
        assertNull(reader.next());
    }

    @Test
    @DisplayName("一帧被切成多片也能拼回来")
    void splitFrame() {
        RaftFrameReader reader = new RaftFrameReader();
        byte[] frame = RaftCodec.encode(message(7));
        for (int i = 0; i < frame.length; i++) {
            reader.append(frame, i, 1);
            Message decoded = reader.next();
            if (i < frame.length - 1) {
                assertNull(decoded, "帧还没收全不能解析出来");
            } else {
                assertNotNull(decoded);
                assertEquals(7L, decoded.getTerm());
            }
        }
    }

    @Test
    @DisplayName("多帧粘在一起")
    void multipleFrames() {
        RaftFrameReader reader = new RaftFrameReader();
        List<byte[]> frames = new ArrayList<>();
        int total = 0;
        for (int i = 1; i <= 5; i++) {
            byte[] frame = RaftCodec.encode(message(i));
            frames.add(frame);
            total += frame.length;
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, all, offset, frame.length);
            offset += frame.length;
        }
        reader.append(all, 0, all.length);
        for (int i = 1; i <= 5; i++) {
            Message decoded = reader.next();
            assertNotNull(decoded);
            assertEquals(i, decoded.getTerm());
        }
        assertNull(reader.next());
    }

    @Test
    @DisplayName("多帧被随机切片也能拼回来")
    void randomChunks() {
        RaftFrameReader reader = new RaftFrameReader();
        List<byte[]> frames = new ArrayList<>();
        int total = 0;
        for (int i = 1; i <= 5; i++) {
            byte[] frame = RaftCodec.encode(message(i));
            frames.add(frame);
            total += frame.length;
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] frame : frames) {
            System.arraycopy(frame, 0, all, offset, frame.length);
            offset += frame.length;
        }
        List<Integer> decodedTerms = new ArrayList<>();
        int pos = 0;
        int chunk = 3;
        while (pos < all.length) {
            int len = Math.min(chunk, all.length - pos);
            reader.append(all, pos, len);
            pos += len;
            Message decoded;
            while ((decoded = reader.next()) != null) {
                decodedTerms.add((int) decoded.getTerm());
            }
            chunk = chunk == 3 ? 7 : 3;
        }
        assertEquals(5, decodedTerms.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, decodedTerms.get(i));
        }
    }

    @Test
    @DisplayName("缓冲里的旧数据会被清理掉")
    void compaction() {
        RaftFrameReader reader = new RaftFrameReader();
        byte[] frame = RaftCodec.encode(message(3));
        reader.append(frame, 0, frame.length);
        assertNotNull(reader.next());
        assertEquals(0, reader.remain());
        byte[] next = RaftCodec.encode(message(4));
        reader.append(next, 0, next.length);
        Message decoded = reader.next();
        assertNotNull(decoded);
        assertEquals(4L, decoded.getTerm());
    }

    @Test
    @DisplayName("长度字段非法时抛异常")
    void illegalLength() {
        RaftFrameReader reader = new RaftFrameReader();
        reader.append(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1}, 0, 5);
        assertThrows(RaftException.class, reader::next);
    }

    @Test
    @DisplayName("未知消息类型抛异常")
    void unknownType() {
        RaftFrameReader reader = new RaftFrameReader();
        // length = 0, type = 99
        reader.append(new byte[]{0, 0, 0, 0, 99}, 0, 5);
        assertThrows(RaftException.class, reader::next);
    }

    @Test
    @DisplayName("大帧也能正确解析")
    void bigFrame() {
        RaftFrameReader reader = new RaftFrameReader(16);
        byte[] payload = new byte[70000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        ConfState cs = new ConfState(Collections.singletonList(1L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
        Message msg = new Message()
                .setType(MessageType.MSG_SNAP)
                .setTerm(1L)
                .setFrom(2L)
                .setTo(1L)
                .setIndex(3L)
                .setLogTerm(4L)
                .setSnapshot(new Snapshot(payload, new SnapshotMetadata(cs, 3, 4)));
        byte[] frame = RaftCodec.encode(msg);
        // 分片喂进去
        int pos = 0;
        int chunk = 1000;
        Message decoded = null;
        while (pos < frame.length) {
            int len = Math.min(chunk, frame.length - pos);
            reader.append(frame, pos, len);
            pos += len;
            Message m = reader.next();
            if (m != null) {
                decoded = m;
            }
        }
        assertNotNull(decoded);
        assertEquals(payload.length, decoded.getSnapshot().getData().length);
        assertTrue(java.util.Arrays.equals(payload, decoded.getSnapshot().getData()));
        assertEquals(3L, decoded.getSnapshot().getLastIncludedIndex());
    }

    @Test
    @DisplayName("带日志条目的消息也能正确解析")
    void entriesRoundTrip() {
        RaftFrameReader reader = new RaftFrameReader();
        List<Entry> ents = new ArrayList<>();
        ents.add(new Entry(1, 1, EntryType.NORMAL, "a".getBytes()));
        ents.add(new Entry(2, 1, EntryType.NORMAL, "b".getBytes()));
        Message msg = new Message()
                .setType(MessageType.MSG_APP)
                .setTerm(1L)
                .setFrom(1L)
                .setTo(2L)
                .setIndex(0L)
                .setLogTerm(0L)
                .setEntries(ents);
        byte[] frame = RaftCodec.encode(msg);
        reader.append(frame, 0, frame.length);
        Message decoded = reader.next();
        assertNotNull(decoded);
        assertEquals(MessageType.MSG_APP, decoded.getType());
        assertEquals(2, decoded.getEntries().size());
        assertArrayEquals("b".getBytes(), decoded.getEntries().get(1).getData());
    }
}
