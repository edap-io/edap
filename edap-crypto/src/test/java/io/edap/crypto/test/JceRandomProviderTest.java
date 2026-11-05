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

package io.edap.crypto.test;

import io.edap.crypto.random.JceRandomProvider;
import io.edap.crypto.random.RandomProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCE 随机数实现。
 */
public class JceRandomProviderTest {

    @Test
    @DisplayName("nextBytes 填满整个缓冲区")
    public void testNextBytesFillsBuffer() {
        RandomProvider provider = new JceRandomProvider();
        byte[] buf = new byte[64];
        provider.nextBytes(buf);
        assertFalse(allZero(buf), "随机数不应全为 0");
    }

    @Test
    @DisplayName("两次调用结果不同")
    public void testTwoCallsDiffer() {
        RandomProvider provider = new JceRandomProvider();
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        provider.nextBytes(a);
        provider.nextBytes(b);
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    @DisplayName("nextBytes(offset, len) 只写指定区间")
    public void testNextBytesWithOffset() {
        RandomProvider provider = new JceRandomProvider();
        byte[] buf = new byte[32];
        Arrays.fill(buf, (byte) 0x7F);

        provider.nextBytes(buf, 8, 8);

        // 区间外必须保持 0x7F
        for (int i = 0; i < 8; i++) {
            assertEquals((byte) 0x7F, buf[i], "index " + i);
        }
        for (int i = 16; i < 32; i++) {
            assertEquals((byte) 0x7F, buf[i], "index " + i);
        }
        // 区间内应当被改写（概率极低地可能不变，所以只断言不是全 0x7F）
        boolean changed = false;
        for (int i = 8; i < 16; i++) {
            if (buf[i] != (byte) 0x7F) {
                changed = true;
                break;
            }
        }
        assertTrue(changed, "指定区间应当被写入随机数据");
    }

    @Test
    @DisplayName("nextBytes(offset, len) 越界 ⇒ IndexOutOfBoundsException")
    public void testNextBytesOutOfBounds() {
        RandomProvider provider = new JceRandomProvider();
        byte[] buf = new byte[8];
        assertThrows(IndexOutOfBoundsException.class, () -> provider.nextBytes(buf, -1, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> provider.nextBytes(buf, 0, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> provider.nextBytes(buf, 4, -1));
        assertThrows(IllegalArgumentException.class, () -> provider.nextBytes(null, 0, 1));
    }

    @Test
    @DisplayName("len = 0 是合法的空操作")
    public void testZeroLength() {
        RandomProvider provider = new JceRandomProvider();
        byte[] buf = new byte[8];
        Arrays.fill(buf, (byte) 0x11);
        provider.nextBytes(buf, 2, 0);
        for (byte b : buf) {
            assertEquals((byte) 0x11, b);
        }
    }

    @Test
    @DisplayName("DRBG 引擎可用")
    public void testDrbgEngine() {
        RandomProvider provider = new JceRandomProvider(JceRandomProvider.DRBG_ENGINE);
        byte[] buf = new byte[32];
        provider.nextBytes(buf);
        assertFalse(allZero(buf));
        assertEquals(JceRandomProvider.DRBG_ENGINE, provider.algorithm());
    }

    @Test
    @DisplayName("引擎名非法 ⇒ IllegalArgumentException")
    public void testInvalidEngine() {
        assertThrows(IllegalArgumentException.class, () -> new JceRandomProvider(null));
        assertThrows(IllegalArgumentException.class, () -> new JceRandomProvider(""));
        assertThrows(IllegalArgumentException.class, () -> new JceRandomProvider("NoSuchEngine"));
    }

    @Test
    @DisplayName("默认引擎名是 default")
    public void testDefaultEngineName() {
        assertEquals(JceRandomProvider.DEFAULT_ENGINE, new JceRandomProvider().algorithm());
    }

    private static boolean allZero(byte[] data) {
        for (byte b : data) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
