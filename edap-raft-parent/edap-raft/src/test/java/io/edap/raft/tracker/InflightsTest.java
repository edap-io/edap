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

import io.edap.raft.RaftException;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code tracker/inflights_test.go} 的 Java 移植。
 *
 * <p>Go 的测试直接比较 {@code Inflights} 结构体里的环形缓冲区，
 * Java 侧按环形顺序取出在途记录来比较，语义一致。</p>
 */
public class InflightsTest {

    /**
     * Go: TestInflightsAdd
     */
    @Test
    public void testInflightsAdd() {
        // 不发生环绕的情况
        Inflights in = new Inflights(10, 0);
        for (int i = 0; i < 5; i++) {
            in.add(i, 100 + i);
        }
        assertInflights(in, 0, 5, 510, 10,
                new long[]{0, 1, 2, 3, 4},
                new long[]{100, 101, 102, 103, 104});

        for (int i = 5; i < 10; i++) {
            in.add(i, 100 + i);
        }
        assertInflights(in, 0, 10, 1045, 10,
                new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                new long[]{100, 101, 102, 103, 104, 105, 106, 107, 108, 109});

        // 发生环绕的情况
        Inflights rotating = new Inflights(10, 0);
        // Go 里是直接给结构体的 start 字段赋值 5
        rotating.setStart(5);
        for (int i = 0; i < 5; i++) {
            rotating.add(i, 100 + i);
        }
        assertInflights(rotating, 5, 5, 510, 10,
                new long[]{0, 1, 2, 3, 4},
                new long[]{100, 101, 102, 103, 104});

        for (int i = 5; i < 10; i++) {
            rotating.add(i, 100 + i);
        }
        assertInflights(rotating, 5, 10, 1045, 10,
                new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                new long[]{100, 101, 102, 103, 104, 105, 106, 107, 108, 109});
    }

    /**
     * Go: TestInflightFreeTo
     */
    @Test
    public void testInflightFreeTo() {
        // 不发生环绕的情况
        Inflights in = new Inflights(10, 0);
        for (int i = 0; i < 10; i++) {
            in.add(i, 100 + i);
        }

        in.freeLE(0);
        assertInflights(in, 1, 9, 945, 10,
                new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9},
                new long[]{101, 102, 103, 104, 105, 106, 107, 108, 109});

        in.freeLE(4);
        assertInflights(in, 5, 5, 535, 10,
                new long[]{5, 6, 7, 8, 9},
                new long[]{105, 106, 107, 108, 109});

        in.freeLE(8);
        assertInflights(in, 9, 1, 109, 10,
                new long[]{9},
                new long[]{109});

        // 发生环绕的情况
        for (int i = 10; i < 15; i++) {
            in.add(i, 100 + i);
        }
        in.freeLE(12);
        assertInflights(in, 3, 2, 227, 10,
                new long[]{13, 14},
                new long[]{113, 114});

        in.freeLE(14);
        assertInflights(in, 0, 0, 0, 10,
                new long[]{},
                new long[]{});
    }

    /**
     * Go: TestInflightsFull
     */
    @Test
    public void testInflightsFull() {
        assertFull("always-full", 0, 0, 0, 0, 0);
        assertFull("single-entry", 1, 0, 1, 1, 2);
        assertFull("single-entry-overflow", 1, 10, 1, 1, 2);
        assertFull("multi-entry", 15, 0, 15, 6, 22);
        assertFull("slight-overflow", 8, 400, 4, 2, 7);
        assertFull("exact-max-bytes", 8, 406, 4, 3, 8);
        assertFull("larger-overflow", 15, 408, 5, 1, 6);
    }

    private static void assertFull(String name, int size, long maxBytes,
                                   int fullAt, long freeLE, int againAt) {
        Inflights in = new Inflights(size, maxBytes);
        addUntilFull(in, name, 0, fullAt);
        in.freeLE(freeLE);
        addUntilFull(in, name, fullAt, againAt);

        // 窗口满了之后再加入必须失败（Go 里是 panic）
        assertThrows(RaftException.class, () -> in.add(100, 1024), name + ": Add() did not panic");
    }

    /**
     * Go: addUntilFull
     */
    private static void addUntilFull(Inflights in, String name, int begin, int end) {
        for (int i = begin; i < end; i++) {
            assertFalse(in.full(), name + ": full at " + i + ", want " + end);
            in.add(i, 100 + i);
        }
        assertTrue(in.full(), name + ": not full at " + end);
    }

    /**
     * Go: TestInflightsReset
     */
    @Test
    public void testInflightsReset() {
        Inflights in = new Inflights(10, 1000);
        // 模拟一段比较真实的流量：窗口会被反复重置，
        // 字节数不能跨重置「泄漏」累积。
        long index = 0;
        for (int epoch = 0; epoch < 100; epoch++) {
            in.reset();
            // 加入 5 条消息，还不该撑满上限
            for (int i = 0; i < 5; i++) {
                assertFalse(in.full());
                index++;
                in.add(index, 16);
            }
            // 确认掉除最后 2 条之外的所有消息
            in.freeLE(index - 2);
            assertFalse(in.full());
            assertEquals(2, in.count());
        }
        in.freeLE(index);
        assertEquals(0, in.count());
    }

    /**
     * 断言窗口的各个状态与在途记录。
     */
    private static void assertInflights(Inflights in, int start, int count, long bytes, int size,
                                        long[] indices, long[] sizes) {
        assertEquals(count, in.count(), "count");
        assertEquals(bytes, in.bytes(), "bytes");
        assertEquals(size, in.size(), "size");
        assertEquals(start, in.getStart(), "start");

        List<Inflights.Inflight> entries = in.bufferEntries();
        assertEquals(indices.length, entries.size(), "buffer length");
        for (int i = 0; i < indices.length; i++) {
            assertEquals(indices[i], entries.get(i).getIndex(), "buffer[" + i + "].index");
            assertEquals(sizes[i], entries.get(i).getBytes(), "buffer[" + i + "].bytes");
        }
    }
}
