/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft.cluster;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提案信封的编解码。
 */
public class RaftProposalEnvelopeTest {

    /**
     * 套上信封再拆开，载荷原样回来。
     */
    @Test
    void roundTripKeepsPayload() {
        byte[] payload = "hello raft".getBytes(StandardCharsets.UTF_8);
        byte[] env = RaftProposalEnvelope.wrap(12345L, payload);
        assertTrue(RaftProposalEnvelope.isEnvelope(env));
        assertEquals(12345L, RaftProposalEnvelope.requestIdOf(env));
        assertArrayEquals(payload, RaftProposalEnvelope.payloadOf(env));
    }

    /**
     * 空载荷也是信封。
     */
    @Test
    void emptyPayloadStillIsEnvelope() {
        byte[] env = RaftProposalEnvelope.wrap(1L, new byte[0]);
        assertTrue(RaftProposalEnvelope.isEnvelope(env));
        assertEquals(RaftProposalEnvelope.HEADER_LEN, env.length);
        assertEquals(0, RaftProposalEnvelope.payloadOf(env).length);
    }

    /**
     * ★ 负数 requestId 也要能原样往返（最高位是客户端空间的一半）。
     */
    @Test
    void negativeRequestIdRoundTrips() {
        byte[] env = RaftProposalEnvelope.wrap(Long.MIN_VALUE + 7, new byte[]{1, 2, 3});
        assertEquals(Long.MIN_VALUE + 7, RaftProposalEnvelope.requestIdOf(env));
    }

    /**
     * ★ 不是信封的数据原样返回 —— 业务载荷自己带魔数也不怕。
     */
    @Test
    void nonEnvelopeIsPassedThrough() {
        byte[] raw = new byte[]{(byte) 0xED, 0x52, 0x46, 0x54, 9, 9, 9, 9, 9, 9, 9, 9, 7};
        assertTrue(RaftProposalEnvelope.isEnvelope(raw));
        // 头齐但内容不是魔数
        byte[] other = new byte[]{(byte) 0xED, 0x52, 0x46, 0x55, 1, 2, 3};
        assertFalse(RaftProposalEnvelope.isEnvelope(other));
        assertArrayEquals(other, RaftProposalEnvelope.payloadOf(other));
        assertEquals(0L, RaftProposalEnvelope.requestIdOf(other));
    }

    /**
     * ★ 头不全就不算信封（免得把载荷后半截当 requestId 读）。
     */
    @Test
    void truncatedHeaderIsNotEnvelope() {
        byte[] shortEnv = new byte[]{(byte) 0xED, 0x52, 0x46};
        assertFalse(RaftProposalEnvelope.isEnvelope(shortEnv));
        assertEquals(0L, RaftProposalEnvelope.requestIdOf(shortEnv));
        assertArrayEquals(shortEnv, RaftProposalEnvelope.payloadOf(shortEnv));
    }

    /**
     * null 数据：不是信封，payload 变成空数组而不是炸掉。
     */
    @Test
    void nullDataIsSafe() {
        assertFalse(RaftProposalEnvelope.isEnvelope(null));
        assertEquals(0L, RaftProposalEnvelope.requestIdOf(null));
        assertEquals(0, RaftProposalEnvelope.payloadOf(null).length);
    }
}
