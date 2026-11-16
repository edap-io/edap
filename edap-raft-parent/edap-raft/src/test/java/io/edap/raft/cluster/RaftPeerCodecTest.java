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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成员地址在 {@code ConfChange.context} 里的编解码。
 */
public class RaftPeerCodecTest {

    /**
     * 编解码往返。
     */
    @Test
    void roundTrip() {
        byte[] ctx = RaftPeerCodec.encode("10.0.0.7", 9092, 1111);
        assertTrue(RaftPeerCodec.isEncoded(ctx));
        RaftMember m = RaftPeerCodec.decode(ctx);
        assertEquals("10.0.0.7", m.getHost());
        assertEquals(9092, m.getPort());
        assertEquals(1111, m.getManagerPort());
    }

    /**
     * 主机名也能编。
     */
    @Test
    void hostnameRoundTrip() {
        byte[] ctx = RaftPeerCodec.encode("raft-1.example.com", 9092, 1111);
        assertEquals("raft-1.example.com", RaftPeerCodec.decode(ctx).getHost());
    }

    /**
     * 不是本编码产出的字节 ⇒ 解出 null，调用方按「没有地址」处理。
     */
    @Test
    void foreignContextDecodesToNull() {
        assertNull(RaftPeerCodec.decode(null));
        assertNull(RaftPeerCodec.decode(new byte[]{1, 2, 3}));
        assertFalse(RaftPeerCodec.isEncoded(new byte[]{1, 2, 3}));
    }

    /**
     * 头齐但 host 长度超出实际数据 ⇒ 解出 null（不越界）。
     */
    @Test
    void truncatedHostDecodesToNull() {
        byte[] ctx = RaftPeerCodec.encode("10.0.0.7", 9092, 1111);
        byte[] cut = new byte[ctx.length - 4];
        System.arraycopy(ctx, 0, cut, 0, cut.length);
        assertNull(RaftPeerCodec.decode(cut));
    }
}
