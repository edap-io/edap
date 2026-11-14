/*
 * Copyright 2020 The edap Project
 *
 * The NetBeans Project licenses this file to you under the Apache License,
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

package io.edap.protobuf.test.v3;

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.test.message.v3.ListMessage;
import io.edap.protobuf.test.message.v3.ListMessageOuterClass;
import io.edap.protobuf.test.message.v3.OneMessage;
import io.edap.protobuf.test.message.v3.OneMessageOuterClass;
import io.edap.protobuf.test.message.v3.Proj;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 嵌套消息的<b>长度前缀</b>回归测试。
 *
 * <p>编码器现在对嵌套消息<b>预留 2 字节</b>的长度前缀（而不是旧实现的 1 字节），
 * 写完载荷后按实际 varint 长度与预留值的差值平移 ⇒ 载荷落在 128..16383 字节时一次都不用搬移。</p>
 *
 * <p>本测试覆盖 varint 长度前缀的<b>全部 4 档</b>（1/2/3/4 字节），
 * 并逐字节与 protobuf-java 的输出比对，确保 wire 格式没有变化。</p>
 */
public class TestNestedLenPrefix {

    /**
     * 构造一个嵌套载荷（Proj）字节数恰好落在给定区间的消息。
     *
     * <p>Proj 的 wire 结构：{@code id(1字节tag + varint) + name(1字节tag + varint长度 + n字节)}，
     * id 固定为 1（1 字节 varint）⇒ 载荷长度 = 3 + varintLen(n) + n。</p>
     */
    private static OneMessage buildEdap(int nameLen) {
        Proj proj = new Proj();
        proj.setId(1L);
        proj.setName(repeat('a', nameLen));
        OneMessage oneMessage = new OneMessage();
        oneMessage.setProj(proj);
        return oneMessage;
    }

    private static OneMessageOuterClass.OneMessage buildGoogle(int nameLen) {
        return OneMessageOuterClass.OneMessage.newBuilder()
                .setValue(OneMessageOuterClass.Proj.newBuilder()
                        .setId(1L)
                        .setName(repeat('a', nameLen))
                        .build())
                .build();
    }

    private static String repeat(char c, int len) {
        char[] cs = new char[len];
        java.util.Arrays.fill(cs, c);
        return new String(cs);
    }

    /**
     * 读取 {@code bs[off]} 处的 varint，返回 [值, 字节数]。
     */
    private static long[] readVarint(byte[] bs, int off) {
        long v = 0;
        int shift = 0;
        int i = off;
        while (true) {
            byte b = bs[i++];
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return new long[]{v, i - off};
    }

    /**
     * 逐字节与 protobuf-java 比对 + 断言长度前缀的档位 + edap 自身往返。
     */
    private void check(int nameLen, int expectPrefixBytes) throws Exception {
        OneMessage edapMsg = buildEdap(nameLen);
        OneMessageOuterClass.OneMessage googleMsg = buildGoogle(nameLen);

        byte[] pb = googleMsg.toByteArray();
        byte[] epb = ProtoBuf.toByteArray(edapMsg);

        // ① wire 字节必须与 protobuf-java 完全一致
        assertArrayEquals(pb, epb);

        // ② 嵌套字段的 tag 必须是 LENGTH_DELIMITED(2)，即 0x0A
        assertEquals(0x0A, epb[0] & 0xFF);

        // ③ 长度前缀的档位必须符合预期，且前缀值 == 嵌套载荷长度
        long[] lenInfo = readVarint(epb, 1);
        assertEquals(expectPrefixBytes, lenInfo[1]);
        assertEquals(googleMsg.getValue().getSerializedSize(), lenInfo[0]);

        // ④ edap 自身往返
        OneMessage decoded = ProtoBuf.toObject(epb, OneMessage.class);
        assertEquals(googleMsg.getValue().getId(), decoded.getProj().getId().longValue());
        assertEquals(googleMsg.getValue().getName(), decoded.getProj().getName());
    }

    @Test
    public void testPrefix1Byte() throws Exception {
        // 载荷 = 3 + 1 + n < 128 ⇒ n <= 123
        check(10, 1);
        check(100, 1);
        check(120, 1);
    }

    @Test
    public void testPrefix2Bytes() throws Exception {
        // 载荷 = 3 + 2 + n，落在 128..16383
        check(124, 2);
        check(1000, 2);
        check(16000, 2);
    }

    @Test
    public void testPrefix3Bytes() throws Exception {
        // 载荷 >= 16384
        check(16383, 3);
        check(20000, 3);
        check(70000, 3);
    }

    @Test
    public void testPrefix4Bytes() throws Exception {
        // 载荷 >= 2^21 = 2097152
        check(2097150, 4);
    }

    /**
     * 嵌套消息<b>为空</b>时的边界：载荷长度 0 ⇒ 前缀 1 字节。
     */
    @Test
    public void testEmptyNested() throws Exception {
        OneMessage edapMsg = new OneMessage();
        OneMessageOuterClass.OneMessage googleMsg = OneMessageOuterClass.OneMessage.newBuilder().build();

        byte[] pb = googleMsg.toByteArray();
        byte[] epb = ProtoBuf.toByteArray(edapMsg);
        assertArrayEquals(pb, epb);
        assertEquals(0, epb.length);
    }

    /**
     * 重复的嵌套消息：每个元素各有自己的长度前缀 ⇒ 大小混排时会同时命中
     * 「前缀 1 字节（向左回缩）」与「前缀 2 字节（零搬移）」两条路径。
     */
    @Test
    public void testRepeatedNestedMixedSize() throws Exception {
        int[] nameLens = new int[]{10, 500, 10, 20000, 120, 1};
        ListMessage edapMsg = new ListMessage();
        edapMsg.list = new ArrayList<>();
        ListMessageOuterClass.ListMessage.Builder gb = ListMessageOuterClass.ListMessage.newBuilder();
        for (int nameLen : nameLens) {
            Proj proj = new Proj();
            proj.setId((long) nameLen);
            proj.setName(repeat('z', nameLen));
            edapMsg.list.add(proj);
            gb.addValue(OneMessageOuterClass.Proj.newBuilder()
                    .setId((long) nameLen)
                    .setName(repeat('z', nameLen))
                    .build());
        }

        byte[] pb = gb.build().toByteArray();
        byte[] epb = ProtoBuf.toByteArray(edapMsg);
        assertArrayEquals(pb, epb);

        ListMessage decoded = ProtoBuf.toObject(epb, ListMessage.class);
        assertEquals(nameLens.length, decoded.list.size());
        for (int i = 0; i < nameLens.length; i++) {
            assertEquals(nameLens[i], decoded.list.get(i).getName().length());
        }
    }
}