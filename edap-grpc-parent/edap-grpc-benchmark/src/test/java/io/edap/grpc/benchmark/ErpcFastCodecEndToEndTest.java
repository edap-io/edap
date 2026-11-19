/*
 * Copyright 2026 The edap Project
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

package io.edap.grpc.benchmark;

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.codec.fastprotobuf.FastProtobufCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 的 <b>FAST 序列化框架</b>端到端冒烟测试。
 *
 * <p>本测试锁住三件事：</p>
 * <ol>
 *   <li><b>FAST codec 能真正跑通 eRPC 链路</b>（服务端按帧协商，一行没改）；</li>
 *   <li><b>ASCII / 中文两种 name 都能正确往返</b>（字符串是两个框架唯一有实质差异的字段）；</li>
 *   <li><b>FAST 与标准版的线上格式不互通</b>，且体积差符合公式
 *       {@code fast − std = (A − B) + 1}（A=ASCII 字数，B=BMP 非 ASCII 字数）——
 *       这条是<b>回归锁</b>：哪天实现变了，测试立刻红。</li>
 * </ol>
 *
 * <p><b>不做性能断言</b>（绝对吞吐受机器负载影响，CI 上必然 flaky）。</p>
 */
public class ErpcFastCodecEndToEndTest {

    private ErpcBenchServer erpcServer;
    private int erpcPort;

    @BeforeEach
    void startServer() throws Exception {
        // ★ FAST 不是内置 codec ⇒ 必须注册，服务端才认得帧头里的 codec=2
        ErpcCodecRegistry.instance().register(new FastProtobufCodec());
        erpcPort = ErpcBenchServer.freePort();
        erpcServer = new ErpcBenchServer(erpcPort);
        erpcServer.start();
    }

    @AfterEach
    void stopServer() {
        if (erpcServer != null) {
            erpcServer.close();
            erpcServer = null;
        }
    }

    /**
     * ★ FAST codec 走完整链路：客户端编 → 服务端按帧协商解 → 服务端编 → 客户端解。
     */
    @Test
    void fastCodecRoundTripsThroughErpc() {
        ErpcCodec fast = new FastProtobufCodec();
        try (ErpcBenchClient client = new ErpcBenchClient("127.0.0.1", erpcPort, fast)) {
            Payload resp = client.unary(BenchPayloads.edap(1024));
            assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
            assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
            assertNotNull(resp.getBlob());
            assertEquals(1024, resp.getBlob().length);
        }
    }

    /**
     * ★ 中文 name 也要能正确往返（字符串在两个框架里走的路径完全不同）。
     */
    @Test
    void chineseNameRoundTrips() {
        ErpcCodec fast = new FastProtobufCodec();
        try (ErpcBenchClient client = new ErpcBenchClient("127.0.0.1", erpcPort, fast)) {
            Payload resp = client.unary(BenchPayloads.edap(1024, BenchConstants.REQUEST_NAME_CN));
            assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME_CN, resp.getName());
            assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
            assertNotNull(resp.getBlob());
            assertEquals(1024, resp.getBlob().length);
        }
    }

    /**
     * ★ 两个 codec 的<b>业务结果必须一致</b> —— 否则「谁的数字更快」就没有意义。
     */
    @Test
    void bothCodecsReturnTheSameBusinessResult() {
        ErpcCodec std = new ProtobufErpcCodec();
        ErpcCodec fast = new FastProtobufCodec();
        try (ErpcBenchClient stdClient = new ErpcBenchClient("127.0.0.1", erpcPort, std);
             ErpcBenchClient fastClient = new ErpcBenchClient("127.0.0.1", erpcPort, fast)) {
            Payload a = stdClient.unary(BenchPayloads.edap(512));
            Payload b = fastClient.unary(BenchPayloads.edap(512));
            assertEquals(a.getName(), b.getName());
            assertEquals(a.getValue(), b.getValue());
            assertArrayEquals(a.getBlob(), b.getBlob());
        }
    }

    /**
     * ★ <b>线上格式不互通</b> —— FAST 不是标准 protobuf ⇒ 字节必须不同，
     * 而且用 FAST 去解标准版的字节**得不到原来的内容**。
     */
    @Test
    void wireFormatsAreNotInterchangeable() {
        ErpcCodec std = new ProtobufErpcCodec();
        ErpcCodec fast = new FastProtobufCodec();
        Payload payload = BenchPayloads.edap(0);

        byte[] stdBytes = bytesOf(std.encode(payload));
        byte[] fastBytes = bytesOf(fast.encode(payload));

        assertFalse(Arrays.equals(stdBytes, fastBytes),
            "FAST 与标准版的线上字节必须不同（它们是两种格式）");

        // 用 FAST 解标准版的字节 ⇒ 不得还原出原对象
        Payload decodedByFast = null;
        try {
            decodedByFast = fast.decode(stdBytes, 0, stdBytes.length, Payload.class);
        } catch (RuntimeException expected) {
            // 解不了是允许的结果之一
        }
        if (decodedByFast != null) {
            assertFalse(payload.getName().equals(decodedByFast.getName())
                    && payload.getValue() == decodedByFast.getValue(),
                "FAST 不该能解出标准版字节里的原内容");
        }
    }

    /**
     * ★ 体积差必须符合公式（回归锁）。
     *
     * <p><b>FAST 按 String 的内部 coder 一刀切</b>，所以分两种情况：</p>
     * <ul>
     *   <li><b>整个串是 Latin1</b>（每个字符 &lt; 256）⇒ 内部 1 字节/字：
     *       {@code fast − std = 1 − H}（H = 0x80..0xFF 的字符数，
     *       它们在 UTF-8 里占 2 字节）；纯 ASCII 时就是 {@code +1}
     *       （FAST 多一个 charset 标记字节）；</li>
     *   <li><b>含 ≥ U+0100 的字符</b>⇒ 内部是 UTF-16（2 字节/字，补充平面 4 字节）：
     *       {@code fast − std = (A − B) + 1}（A=ASCII 字数，B=BMP 非 ASCII 字数）。</li>
     * </ul>
     */
    @Test
    void payloadSizeDifferenceFollowsTheFormula() {
        ErpcCodec std = new ProtobufErpcCodec();
        ErpcCodec fast = new FastProtobufCodec();

        for (String name : new String[]{BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_NAME_CN}) {
            Payload payload = BenchPayloads.edap(0, name);
            int stdLen = bytesOf(std.encode(payload)).length;
            int fastLen = bytesOf(fast.encode(payload)).length;

            int ascii = 0;
            int latin1High = 0;
            int bmpNonAscii = 0;
            boolean latin1 = true;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c < 128) {
                    ascii++;
                } else if (c < 256) {
                    latin1High++;
                } else {
                    bmpNonAscii++;
                }
                if (c >= 256) {
                    latin1 = false;
                }
            }
            int expected = latin1 ? (1 - latin1High) : ((ascii - bmpNonAscii) + 1);
            assertEquals(expected, fastLen - stdLen,
                "name=" + name + " 的体积差必须是 " + expected);
        }
    }

    /**
     * ★ FAST codec 必须能被注册表按线上 id 找到（服务端就是靠 id 分发解码器的）。
     */
    @Test
    void fastCodecIsRegisteredByItsWireId() {
        assertNotNull(ErpcCodecRegistry.instance().byId(ErpcCodecIds.FAST_PROTOBUF),
            "注册表里必须能按线上 id 找到 FAST codec");
        assertEquals("fastprotobuf", new FastProtobufCodec().name());
    }

    private static byte[] bytesOf(ErpcWriter writer) {
        assertNotNull(writer, "编码不能返回 null");
        return Arrays.copyOf(writer.buffer(), writer.size());
    }
}
