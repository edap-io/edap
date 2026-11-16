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

/**
 * 提案信封：{@code [magic(4B) | requestId(8B) | payload]}。
 *
 * <p><b>为什么要有信封</b>：raft 协议层没有「提案回执」消息
 * （{@code MessageType} 里只有 {@code MSG_PROP}，没有 {@code MSG_PROP_RESP}），
 * 回执必须在应用层产生。requestId 就是把「apply 时收到的一条日志」
 * 与「发出去的那次请求」对应起来的钥匙。</p>
 *
 * <p><b>requestId 同时是幂等键</b>：重试时用<b>同一个</b> requestId，
 * leader 侧按 requestId 去重 ⇒ 业务状态机只应用一次。</p>
 *
 * <p><b>★ 为什么带 magic</b>：编排层的每一条提案都套信封，
 * 所以「哪些字节是信封」必须<b>无歧义</b>。如果只靠长度判断，
 * 业务载荷恰好以信封头的字节开头时就会被误拆 —— 载荷会被吃掉 12 个字节。
 * magic 让「是不是信封」成为一个确定的事实：不是信封的数据原样透传给业务。</p>
 *
 * <p>信封只在这一层存在，用户的 ApplyHandler 收到的是<b>裸 payload</b>
 * （信封在拆开后就被丢掉）。</p>
 */
public final class RaftProposalEnvelope {

    /** 请求 id 的字节数 */
    public static final int REQUEST_ID_LEN = 8;

    /** 魔数：'E''R''F''T' */
    private static final int MAGIC_0 = 0xED;
    private static final int MAGIC_1 = 0x52;
    private static final int MAGIC_2 = 0x46;
    private static final int MAGIC_3 = 0x54;

    /** 固定头长度：magic(4) + requestId(8) */
    public static final int HEADER_LEN = 12;

    private RaftProposalEnvelope() {
    }

    /**
     * 给 payload 套上信封。
     */
    public static byte[] wrap(long requestId, byte[] payload) {
        byte[] p = payload == null ? new byte[0] : payload;
        byte[] out = new byte[HEADER_LEN + p.length];
        out[0] = (byte) MAGIC_0;
        out[1] = (byte) MAGIC_1;
        out[2] = (byte) MAGIC_2;
        out[3] = (byte) MAGIC_3;
        writeLong(out, 4, requestId);
        System.arraycopy(p, 0, out, HEADER_LEN, p.length);
        return out;
    }

    /**
     * 这段数据是不是信封。
     *
     * <p>只有<b>完整</b>的信封才算 —— 头不全就当不是，
     * 免得把业务载荷的后半截当 requestId 读出来。</p>
     */
    public static boolean isEnvelope(byte[] data) {
        return data != null && data.length >= HEADER_LEN
                && (data[0] & 0xFF) == MAGIC_0
                && (data[1] & 0xFF) == MAGIC_1
                && (data[2] & 0xFF) == MAGIC_2
                && (data[3] & 0xFF) == MAGIC_3;
    }

    /**
     * 取信封里的 requestId。
     *
     * @return 信封里的 requestId；<b>不是信封时返回 0</b>（0 表示「没有请求 id」）
     */
    public static long requestIdOf(byte[] data) {
        if (!isEnvelope(data)) {
            return 0L;
        }
        return readLong(data, 4);
    }

    /**
     * 取信封里的裸 payload。
     *
     * <p><b>不是信封时原样返回</b> —— 但调用方应当先
     * {@link #isEnvelope(byte[])} 判一下：raft 内部的条目
     * （leader 上任时的空 no-op）也会走到这里，它们不是业务载荷，
     * 不能交给业务状态机。</p>
     */
    public static byte[] payloadOf(byte[] data) {
        if (data == null) {
            return new byte[0];
        }
        if (!isEnvelope(data)) {
            return data;
        }
        byte[] out = new byte[data.length - HEADER_LEN];
        System.arraycopy(data, HEADER_LEN, out, 0, out.length);
        return out;
    }

    private static void writeLong(byte[] out, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            out[offset + i] = (byte) ((value >>> ((7 - i) * 8)) & 0xFF);
        }
    }

    private static long readLong(byte[] in, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (in[offset + i] & 0xFFL);
        }
        return v;
    }
}
