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

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.RaftCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code util_test.go} 的 Java 移植。
 */
public class RaftUtilTest {

    /**
     * Go: testFormatter
     */
    private static final RaftUtil.EntryFormatter TEST_FORMATTER =
            data -> new String(data, StandardCharsets.UTF_8).toUpperCase();

    // Go: TestDescribeEntry
    @Test
    public void testDescribeEntry() {
        // Go 里的数据是 "hello\x00world"，中间夹了一个 NUL 字节
        byte[] data = ("hello" + '\0' + "world").getBytes(StandardCharsets.UTF_8);
        Entry entry = new Entry(2, 1, EntryType.NORMAL, data);
        // Go 用的是反引号字符串，"hello\x00world" 里的 \x00 是四个可见字符
        assertEquals("1/2 EntryNormal \"hello\\x00world\"", RaftUtil.describeEntry(entry, null));
        // Go 用的是双引号字符串，\x00 是真正的 NUL 字节
        assertEquals("1/2 EntryNormal HELLO" + '\0' + "WORLD", RaftUtil.describeEntry(entry, TEST_FORMATTER));
    }

    // Go: TestLimitSize
    @Test
    public void testLimitSize() {
        List<Entry> ents = Arrays.asList(
                new Entry(4, 4, EntryType.NORMAL, null),
                new Entry(5, 5, EntryType.NORMAL, null),
                new Entry(6, 6, EntryType.NORMAL, null));

        long size0 = RaftCodec.entrySize(ents.get(0));
        long size1 = RaftCodec.entrySize(ents.get(1));
        long size2 = RaftCodec.entrySize(ents.get(2));

        long[] maxSizes = new long[]{
                Long.MAX_VALUE,           // all entries are returned
                0,                        // Even if maxSize is zero, the first entry should be returned.
                size0 + size1,            // Limit to 2.
                size0 + size1 + size2 / 2,
                size0 + size1 + size2 - 1,
                size0 + size1 + size2,    // All.
        };
        int[] wantCounts = new int[]{3, 1, 2, 2, 2, 3};

        for (int i = 0; i < maxSizes.length; i++) {
            long maxSize = maxSizes[i];
            List<Entry> got = RaftUtil.limitSize(ents, maxSize);
            assertEquals(wantCounts[i], got.size(), "case #" + i + " size");
            for (int j = 0; j < got.size(); j++) {
                assertEquals(ents.get(j), got.get(j), "case #" + i + " entry[" + j + "]");
            }
            long size = Raft.entsSize(got);
            assertTrue(got.size() == 1 || size <= maxSize, "case #" + i + " size limit");
        }
    }

    // Go: TestIsLocalMsg
    @Test
    public void testIsLocalMsg() {
        assertLocalMsg(MessageType.MSG_HUP, true);
        assertLocalMsg(MessageType.MSG_BEAT, true);
        assertLocalMsg(MessageType.MSG_UNREACHABLE, true);
        assertLocalMsg(MessageType.MSG_SNAP_STATUS, true);
        assertLocalMsg(MessageType.MSG_CHECK_QUORUM, true);
        assertLocalMsg(MessageType.MSG_TRANSFER_LEADER, false);
        assertLocalMsg(MessageType.MSG_PROP, false);
        assertLocalMsg(MessageType.MSG_APP, false);
        assertLocalMsg(MessageType.MSG_APP_RESP, false);
        assertLocalMsg(MessageType.MSG_VOTE, false);
        assertLocalMsg(MessageType.MSG_VOTE_RESP, false);
        assertLocalMsg(MessageType.MSG_SNAP, false);
        assertLocalMsg(MessageType.MSG_HEARTBEAT, false);
        assertLocalMsg(MessageType.MSG_HEARTBEAT_RESP, false);
        assertLocalMsg(MessageType.MSG_TIMEOUT_NOW, false);
        assertLocalMsg(MessageType.MSG_READ_INDEX, false);
        assertLocalMsg(MessageType.MSG_READ_INDEX_RESP, false);
        assertLocalMsg(MessageType.MSG_PRE_VOTE, false);
        assertLocalMsg(MessageType.MSG_PRE_VOTE_RESP, false);
        assertLocalMsg(MessageType.MSG_STORAGE_APPEND, true);
        assertLocalMsg(MessageType.MSG_STORAGE_APPEND_RESP, true);
        assertLocalMsg(MessageType.MSG_STORAGE_APPLY, true);
        assertLocalMsg(MessageType.MSG_STORAGE_APPLY_RESP, true);
    }

    private static void assertLocalMsg(MessageType type, boolean isLocal) {
        assertEquals(isLocal, RaftUtil.isLocalMsg(type), "IsLocalMsg(" + type + ")");
    }

    // Go: TestIsResponseMsg
    @Test
    public void testIsResponseMsg() {
        assertResponseMsg(MessageType.MSG_HUP, false);
        assertResponseMsg(MessageType.MSG_BEAT, false);
        assertResponseMsg(MessageType.MSG_UNREACHABLE, true);
        assertResponseMsg(MessageType.MSG_SNAP_STATUS, false);
        assertResponseMsg(MessageType.MSG_CHECK_QUORUM, false);
        assertResponseMsg(MessageType.MSG_TRANSFER_LEADER, false);
        assertResponseMsg(MessageType.MSG_PROP, false);
        assertResponseMsg(MessageType.MSG_APP, false);
        assertResponseMsg(MessageType.MSG_APP_RESP, true);
        assertResponseMsg(MessageType.MSG_VOTE, false);
        assertResponseMsg(MessageType.MSG_VOTE_RESP, true);
        assertResponseMsg(MessageType.MSG_SNAP, false);
        assertResponseMsg(MessageType.MSG_HEARTBEAT, false);
        assertResponseMsg(MessageType.MSG_HEARTBEAT_RESP, true);
        assertResponseMsg(MessageType.MSG_TIMEOUT_NOW, false);
        assertResponseMsg(MessageType.MSG_READ_INDEX, false);
        assertResponseMsg(MessageType.MSG_READ_INDEX_RESP, true);
        assertResponseMsg(MessageType.MSG_PRE_VOTE, false);
        assertResponseMsg(MessageType.MSG_PRE_VOTE_RESP, true);
        assertResponseMsg(MessageType.MSG_STORAGE_APPEND, false);
        assertResponseMsg(MessageType.MSG_STORAGE_APPEND_RESP, true);
        assertResponseMsg(MessageType.MSG_STORAGE_APPLY, false);
        assertResponseMsg(MessageType.MSG_STORAGE_APPLY_RESP, true);
    }

    private static void assertResponseMsg(MessageType type, boolean isResponse) {
        assertEquals(isResponse, RaftUtil.isResponseMsg(type), "IsResponseMsg(" + type + ")");
    }

    /**
     * Go: TestPayloadSizeOfEmptyEntry
     *
     * <p>空条目的负载大小必须是 0：leader 当选时会追加一条空命令，
     * 它不应该计入未提交日志的额度。</p>
     */
    @Test
    public void testPayloadSizeOfEmptyEntry() {
        Entry e = new Entry(1, 1, EntryType.NORMAL, null);
        assertEquals(0, Raft.payloadsSize(Collections.singletonList(e)));
    }
}
