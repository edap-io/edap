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

package io.edap.raft.log;

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * etcd {@code types_test.go} 的 Java 实现。
 *
 * <p>覆盖 {@link EntryId} 与 {@link LogSlice} 两个值类型：前者唯一标识一条日志，
 * 后者描述一段格式正确的日志切片。</p>
 */
public class TypesTest {

    /**
     * Go: {@code id(index, term)} —— 造一个 entryID。
     */
    private static EntryId id(long index, long term) {
        return new EntryId(term, index);
    }

    /**
     * Go: {@code e(index, term)} —— 造一条日志条目。
     */
    private static Entry e(long index, long term) {
        return new Entry(index, term, EntryType.NORMAL, null);
    }

    /**
     * Go: TestEntryID
     */
    @Test
    public void testEntryID() {
        // 先看几个显而易见的
        assertEquals(new EntryId(5, 10), new EntryId(5, 10));
        assertNotEquals(new EntryId(4, 10), new EntryId(5, 10));
        assertNotEquals(new EntryId(5, 9), new EntryId(5, 10));

        Entry[] entries = {
                new Entry(0, 0, EntryType.NORMAL, null),
                new Entry(2, 1, EntryType.NORMAL, "data".getBytes()),
                new Entry(123, 10, EntryType.NORMAL, null),
        };
        EntryId[] wants = {
                new EntryId(0, 0),
                new EntryId(1, 2),
                new EntryId(10, 123),
        };
        for (int i = 0; i < entries.length; i++) {
            assertEquals(wants[i], EntryId.of(entries[i]), "entry #" + i);
        }
    }

    /**
     * Go: TestLogSlice
     */
    @Test
    public void testLogSlice() {
        // term / prev / 条目列表 / 是否不合法 / 最后一条的 id
        Object[][] cases = {
                // 空的「占位」切片，起点是日志的原点 (0, 0)
                {0L, null, Collections.emptyList(), false, id(0, 0)},
                // 带着 prev 的空切片。只有 term >= prev.term 时才合法
                {0L, id(123, 10), Collections.emptyList(), true, null},
                {9L, id(123, 10), Collections.emptyList(), true, null},
                {10L, id(123, 10), Collections.emptyList(), false, id(123, 10)},
                {11L, id(123, 10), Collections.emptyList(), false, id(123, 10)},
                // 单条日志
                {0L, null, Collections.singletonList(e(1, 1)), true, null},
                {1L, null, Collections.singletonList(e(1, 1)), false, id(1, 1)},
                {2L, null, Collections.singletonList(e(1, 1)), false, id(1, 1)},
                // 多条日志
                {2L, null, Arrays.asList(e(2, 1), e(3, 1), e(4, 2)), true, null},
                {1L, id(1, 1), Arrays.asList(e(2, 1), e(3, 1), e(4, 2)), true, null},
                {2L, id(1, 1), Arrays.asList(e(2, 1), e(3, 1), e(4, 2)), false, id(4, 2)},
                // 第一条就跟 prev 对不上
                {10L, id(123, 5), Collections.singletonList(e(111, 5)), true, null},
                {10L, id(123, 5), Collections.singletonList(e(124, 4)), true, null},
                {10L, id(123, 5), Collections.singletonList(e(234, 6)), true, null},
                {10L, id(123, 5), Collections.singletonList(e(124, 6)), false, id(124, 6)},
                // 后面几条对不上
                {10L, id(12, 2), Arrays.asList(e(13, 2), e(12, 2)), true, null},
                {10L, id(12, 2), Arrays.asList(e(13, 2), e(15, 2)), true, null},
                {10L, id(12, 2), Arrays.asList(e(13, 2), e(14, 1)), true, null},
                {10L, id(12, 2), Arrays.asList(e(13, 2), e(14, 3)), false, id(14, 3)},
        };

        for (int i = 0; i < cases.length; i++) {
            long term = (Long) cases[i][0];
            EntryId prev = (EntryId) cases[i][1];
            if (prev == null) {
                prev = new EntryId(0, 0);
            }
            @SuppressWarnings("unchecked")
            List<Entry> entries = (List<Entry>) cases[i][2];
            boolean notOk = (Boolean) cases[i][3];
            EntryId want = (EntryId) cases[i][4];

            LogSlice s = new LogSlice(term, prev, entries);

            String err = s.valid();
            assertEquals(notOk, err != null, "case #" + i + " validity: " + err);

            if (!notOk) {
                EntryId last = s.lastEntryId();
                assertEquals(want, last, "case #" + i + " last");
                assertEquals(last.getIndex(), s.lastIndex(), "case #" + i + " lastIndex");
            }
        }
    }
}
