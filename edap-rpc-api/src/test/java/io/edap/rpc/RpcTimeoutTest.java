/*
 * Copyright 2020 The edap Project
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

package io.edap.rpc;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcTimeout} 的编解码测试。
 *
 * <p>两条<b>不变式</b>由测试守住：</p>
 * <ol>
 *   <li>解码出来的时长<b>不短于</b>编码前的请求值（向上取整）；</li>
 *   <li>eRPC 的 flags 里 bit0（压缩预留）永远不被写。</li>
 * </ol>
 */
public class RpcTimeoutTest {

    // ==================================================================
    // gRPC: grpc-timeout
    // ==================================================================

    @Test
    public void grpcTimeoutRoundTrip() {
        assertEquals(1_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1m"));
        assertEquals(999_000_000L, RpcTimeout.parseGrpcTimeoutNanos("999m"));
        assertEquals(1_000_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1S"));
        assertEquals(60_000_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1M"));
        assertEquals(3_600_000_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1H"));
        assertEquals(1_000L, RpcTimeout.parseGrpcTimeoutNanos("1u"));
        assertEquals(1L, RpcTimeout.parseGrpcTimeoutNanos("1n"));
        assertEquals(0L, RpcTimeout.parseGrpcTimeoutNanos("0S"));
    }

    @Test
    public void grpcTimeoutUnitIsCaseSensitive() {
        // M 是分钟，m 是毫秒 —— 与 grpc-java 一致
        assertEquals(60_000_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1M"));
        assertEquals(1_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1m"));
        assertEquals(1_000_000_000L, RpcTimeout.parseGrpcTimeoutNanos("1S"));
        // 小写 s 不是合法单位
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("1s"));
        // 小写 h 不是合法单位
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("1h"));
    }

    @Test
    public void grpcTimeoutRejectsIllegalValues() {
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos(null));
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos(""));
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("m"));       // 没有数字
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("1"));       // 没有单位
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("1ms"));     // 单位不是最后一个字符
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("1x"));      // 未知单位
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("100000000S")); // 超过 8 位
        assertEquals(-1L, RpcTimeout.parseGrpcTimeoutNanos("-1S"));     // 负号不是数字
    }

    @Test
    public void grpcTimeoutAcceptsEightDigits() {
        assertEquals(99_999_999L * 1_000_000_000L, RpcTimeout.parseGrpcTimeoutNanos("99999999S"));
    }

    @Test
    public void grpcTimeoutEncodePrefersExactLargestUnit() {
        // 整除时用「最大的整除单位」—— 无损、人类友好
        assertEquals("1H", RpcTimeout.encodeGrpcTimeout(3_600_000_000_000L));
        assertEquals("1M", RpcTimeout.encodeGrpcTimeout(60_000_000_000L));
        assertEquals("1S", RpcTimeout.encodeGrpcTimeout(1_000_000_000L));
        assertEquals("1m", RpcTimeout.encodeGrpcTimeout(1_000_000L));
        assertEquals("1u", RpcTimeout.encodeGrpcTimeout(1_000L));
        assertEquals("250m", RpcTimeout.encodeGrpcTimeout(250_000_000L));
        assertEquals("30S", RpcTimeout.encodeGrpcTimeout(30_000_000_000L));
    }

    @Test
    public void grpcTimeoutEncodeDoesNotLosePrecision() {
        // ★ 不能只看「数字塞得进 8 位」：那样 1_000_500 ns 会被编成 "1H"，
        //   服务端会让一个客户端 1ms 就放弃的调用再跑一小时。
        String encoded = RpcTimeout.encodeGrpcTimeout(1_000_500L);
        assertNotNull(encoded);
        long decoded = RpcTimeout.parseGrpcTimeoutNanos(encoded);
        assertTrue(decoded >= 1_000_500L, "变短了: " + encoded);
        assertTrue(decoded < 3_600_000_000_000L, "超调过大: " + encoded);
        assertEquals("1000500n", encoded);
    }

    @Test
    public void grpcTimeoutNeverShorterThanRequested() {
        long[] samples = {
            1L, 999L, 1_000L, 1_001L, 999_999L, 1_000_000L, 1_500_000L,
            999_999_999L, 1_000_000_000L, 60_000_000_000L, 3_600_000_000_000L,
            3_600_000_000_001L, 123_456_789L, 123_456_789_012L
        };
        for (long nanos : samples) {
            String encoded = RpcTimeout.encodeGrpcTimeout(nanos);
            assertNotNull(encoded, "nanos=" + nanos);
            long decoded = RpcTimeout.parseGrpcTimeoutNanos(encoded);
            assertTrue(decoded >= nanos,
                "编码后变短了: nanos=" + nanos + " encoded=" + encoded + " decoded=" + decoded);
        }
    }

    @Test
    public void grpcTimeoutOverRequestIsBounded() {
        // 随机时长：超调不能大到离谱（否则服务端的 deadline 会比客户端宽松太多）
        Random random = new Random(20261117L);
        for (int i = 0; i < 10_000; i++) {
            long nanos = 1 + Math.abs(random.nextLong() % (3600L * 1_000_000_000L));
            String encoded = RpcTimeout.encodeGrpcTimeout(nanos);
            assertNotNull(encoded, "nanos=" + nanos);
            long decoded = RpcTimeout.parseGrpcTimeoutNanos(encoded);
            assertTrue(decoded >= nanos, "变短了: nanos=" + nanos + " encoded=" + encoded);
            // 超调不超过 1 秒（对 >= 1 秒的时长）或 1 毫秒（对更短的时长）
            long slack = nanos < 1_000_000_000L ? 1_000_000L : 1_000_000_000L;
            assertTrue(decoded - nanos <= slack,
                "超调过大: nanos=" + nanos + " encoded=" + encoded + " decoded=" + decoded);
        }
    }

    @Test
    public void grpcTimeoutNoTimeoutReturnsNull() {
        assertNull(RpcTimeout.encodeGrpcTimeout(0L));
        assertNull(RpcTimeout.encodeGrpcTimeout(-1L));
    }

    // ==================================================================
    // eRPC: flags 的 7-bit 超时代码
    // ==================================================================

    @Test
    public void erpcCodeZeroMeansNoTimeout() {
        assertEquals(0L, RpcTimeout.decodeErpcTimeoutNanos(0));
        assertEquals(0, RpcTimeout.encodeErpcTimeoutCode(0L));
        assertEquals(0, RpcTimeout.encodeErpcTimeoutCode(-1L));
    }

    @Test
    public void erpcCodeRoundTrip() {
        long[] samples = {
            1_000_000L,             // 1ms
            1_250_000L,             // 1.25ms
            10_000_000L,            // 10ms
            100_000_000L,           // 100ms
            1_000_000_000L,         // 1s
            30_000_000_000L,        // 30s
            3_600_000_000_000L      // 1h
        };
        for (long nanos : samples) {
            int code = RpcTimeout.encodeErpcTimeoutCode(nanos);
            assertTrue(code > 0 && code <= 124, "code 越界: " + code);
            long decoded = RpcTimeout.decodeErpcTimeoutNanos(code);
            assertTrue(decoded >= nanos,
                "编码后变短了: nanos=" + nanos + " code=" + code + " decoded=" + decoded);
            // 相对超调不超过 25%
            assertTrue(decoded <= nanos * 5 / 4,
                "超调过大: nanos=" + nanos + " decoded=" + decoded);
        }
    }

    @Test
    public void erpcDecodeIsStrictlyMonotonic() {
        long prev = -1;
        for (int code = 1; code <= 124; code++) {
            long v = RpcTimeout.decodeErpcTimeoutNanos(code);
            assertTrue(v > prev, "code=" + code + " 不是严格递增: " + v + " <= " + prev);
            prev = v;
        }
    }

    @Test
    public void erpcEncodeNeverShorterThanRequested() {
        // 固定种子 —— 出问题能复现
        Random random = new Random(20261117L);
        for (int i = 0; i < 100_000; i++) {
            long nanos = 1 + Math.abs(random.nextLong() % (24L * 3600 * 1_000_000_000L));
            int code = RpcTimeout.encodeErpcTimeoutCode(nanos);
            long decoded = RpcTimeout.decodeErpcTimeoutNanos(code);
            assertTrue(decoded >= nanos,
                "编码后变短了: nanos=" + nanos + " code=" + code + " decoded=" + decoded);
        }
    }

    @Test
    public void erpcCoversMillisecondToDays() {
        assertEquals(1_000_000L, RpcTimeout.decodeErpcTimeoutNanos(RpcTimeout.encodeErpcTimeoutCode(1_000_000L)));
        // 最大码覆盖到数天
        long max = RpcTimeout.decodeErpcTimeoutNanos(124);
        assertTrue(max > TimeUnit.DAYS.toNanos(20), "最大码覆盖不到 20 天: " + max);
    }

    @Test
    public void erpcFlagsKeepCompressionBitClear() {
        int flags = RpcTimeout.toErpcFlags(0, RpcTimeout.encodeErpcTimeoutCode(1_000_000_000L));
        assertEquals(0, flags & 0x01, "bit0（压缩预留）被写了");
        assertTrue(RpcTimeout.fromErpcFlags(flags) > 0);
    }

    @Test
    public void erpcFlagsRoundTrip() {
        for (int code = 0; code <= 124; code++) {
            int flags = RpcTimeout.toErpcFlags(0, code);
            assertEquals(code, RpcTimeout.fromErpcFlags(flags));
            assertEquals(0, flags & 0x01);
        }
    }

    @Test
    public void erpcEncodeFlagsIsNotTheSameAsTheCode() {
        // ★ 「码值」和「flags 字节」是两回事：码值要左移一位才落在 bit1~bit7 上。
        //   写帧头时必须用 encodeErpcFlags —— 直接把 encodeErpcTimeoutCode 的返回值
        //   写进 flags，服务端会解出另一个（短得多的）时长。
        long[] samples = {1_000_000L, 100_000_000L, 1_000_000_000L, 2_000_000_000L, 30_000_000_000L};
        for (long nanos : samples) {
            int flags = RpcTimeout.encodeErpcFlags(nanos);
            int code = RpcTimeout.fromErpcFlags(flags);
            assertEquals(0, flags & 0x01, "bit0（压缩预留）被写了: nanos=" + nanos);
            assertEquals(RpcTimeout.encodeErpcTimeoutCode(nanos), code,
                "取出来的码值不对: nanos=" + nanos + " flags=" + flags);
            long decoded = RpcTimeout.decodeErpcTimeoutNanos(code);
            assertTrue(decoded >= nanos,
                "编码后变短了: nanos=" + nanos + " decoded=" + decoded);
        }
        // 无超时 ⇒ flags 恒 0
        assertEquals(0, RpcTimeout.encodeErpcFlags(0L));
        assertEquals(0, RpcTimeout.encodeErpcFlags(-1L));
    }

    @Test
    public void erpcOldFrameMeansNoTimeout() {
        // 老版本写出来的帧 flags 恒为 0
        assertEquals(0, RpcTimeout.fromErpcFlags(0));
    }

    @Test
    public void erpcFlagsDoNotDisturbOtherBits() {
        // flags 里将来要加别的位（bit0 压缩已经预留）时，超时位不能覆盖它们
        int flags = RpcTimeout.toErpcFlags(0x01, 30);
        assertEquals(0x01, flags & 0x01, "bit0 被清掉了");
        assertEquals(30, RpcTimeout.fromErpcFlags(flags));
    }
}
