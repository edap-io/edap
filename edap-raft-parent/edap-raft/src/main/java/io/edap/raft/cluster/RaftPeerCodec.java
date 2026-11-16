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

import java.nio.charset.StandardCharsets;

/**
 * 把成员的地址编成 {@link io.edap.raft.Peer#context} 的字节。
 *
 * <p><b>为什么需要它</b>：raft 的 {@code ConfState} 只记节点 id，
 * 不记地址（见 {@code ConfState} 的注释）。地址必须由应用侧自己维护，
 * 而成员之间交换地址的载体就是 {@code ConfChange} 条目的 context ——
 * 主在加成员时把新成员的地址编进去，日志复制时它就跟着走，
 * 其它成员 apply 这条变更时就能学到新成员的地址。</p>
 *
 * <p>布局（全部大端）：</p>
 * <pre>
 * magic(2)=0xED 0x52 | version(1)=1 | hostLen(2) | host(UTF-8) | port(4) | managerPort(4)
 * </pre>
 *
 * <p>id 不编进去 —— {@code ConfChange} 自己带 nodeId。</p>
 */
public final class RaftPeerCodec {

    /** 魔数：'E''R' */
    private static final int MAGIC_0 = 0xED;
    private static final int MAGIC_1 = 0x52;

    /** 版本号 */
    private static final byte VERSION = 1;

    /** 固定头长度：magic(2) + version(1) + hostLen(2) */
    private static final int HEADER_LEN = 5;

    private RaftPeerCodec() {
    }

    /**
     * 把一个成员的地址编成 context 字节。
     */
    public static byte[] encode(String host, int port, int managerPort) {
        byte[] hostBytes = (host == null ? "" : host).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[HEADER_LEN + hostBytes.length + 8];
        int p = 0;
        out[p++] = (byte) MAGIC_0;
        out[p++] = (byte) MAGIC_1;
        out[p++] = VERSION;
        out[p++] = (byte) ((hostBytes.length >>> 8) & 0xFF);
        out[p++] = (byte) (hostBytes.length & 0xFF);
        System.arraycopy(hostBytes, 0, out, p, hostBytes.length);
        p += hostBytes.length;
        writeInt(out, p, port);
        p += 4;
        writeInt(out, p, managerPort);
        return out;
    }

    /**
     * 从 context 字节里解出地址。
     *
     * @param data context 字节
     * @return 解出的地址；格式不对时返回 {@code null}（调用方按「没有地址」处理）
     */
    public static RaftMember decode(byte[] data) {
        if (data == null || data.length < HEADER_LEN + 8) {
            return null;
        }
        int p = 0;
        if ((data[p++] & 0xFF) != MAGIC_0 || (data[p++] & 0xFF) != MAGIC_1) {
            return null;
        }
        if (data[p++] != VERSION) {
            return null;
        }
        int hostLen = ((data[p++] & 0xFF) << 8) | (data[p++] & 0xFF);
        if (data.length < p + hostLen + 8) {
            return null;
        }
        String host = new String(data, p, hostLen, StandardCharsets.UTF_8);
        p += hostLen;
        int port = readInt(data, p);
        p += 4;
        int managerPort = readInt(data, p);
        RaftMember member = new RaftMember();
        member.setHost(host);
        member.setPort(port);
        member.setManagerPort(managerPort);
        return member;
    }

    /**
     * 判断一段 context 是不是本编码产出的。
     */
    public static boolean isEncoded(byte[] data) {
        return data != null && data.length >= HEADER_LEN + 8
                && (data[0] & 0xFF) == MAGIC_0 && (data[1] & 0xFF) == MAGIC_1
                && data[2] == VERSION;
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) ((value >>> 24) & 0xFF);
        out[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        out[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        out[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readInt(byte[] in, int offset) {
        return ((in[offset] & 0xFF) << 24)
                | ((in[offset + 1] & 0xFF) << 16)
                | ((in[offset + 2] & 0xFF) << 8)
                | (in[offset + 3] & 0xFF);
    }
}
