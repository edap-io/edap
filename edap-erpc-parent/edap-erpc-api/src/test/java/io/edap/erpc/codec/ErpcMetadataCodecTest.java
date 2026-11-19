/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc.codec;

import io.edap.erpc.ErpcProtocolException;
import io.edap.rpc.RpcMetadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErpcMetadataCodec} 的单元测试 —— RPC 上下文段的编解码。
 *
 * <p>★ <b>线格式</b>（自描述、大端、不依赖业务序列化器）：</p>
 * <pre>{@code
 *   metadataLen(4B)   ← 块体的字节数（不含本 4 字节）
 *   entryCount(4B)
 *   [ keyLen(2B) key(UTF-8) valLen(4B) val(UTF-8) ]*
 * }</pre>
 */
public class ErpcMetadataCodecTest {

    // ------------------------------------------------------------------
    // 往返
    // ------------------------------------------------------------------

    /**
     * ★ 空元数据 ⇒ 只返回一个 4 字节的长度 0（块体为空）。
     */
    @Test
    public void emptyMetadataEncodesToLengthZero() {
        byte[] seg = ErpcMetadataCodec.encode(RpcMetadata.empty());
        assertEquals(ErpcMetadataCodec.LENGTH_PREFIX, seg.length,
            "空元数据的段只有开头的长度字段");
        assertEquals(0, ErpcMetadataCodec.blockLength(seg, 0));

        byte[] seg2 = ErpcMetadataCodec.encode(null);
        assertEquals(ErpcMetadataCodec.LENGTH_PREFIX, seg2.length);
        assertEquals(0, ErpcMetadataCodec.blockLength(seg2, 0));
    }

    @Test
    public void roundTripPreservesEntries() {
        RpcMetadata m = RpcMetadata.create();
        m.put("txid", "0f9a-42");
        m.put("tenant", "acme");
        byte[] seg = ErpcMetadataCodec.encode(m);

        // 段的总长 = 4（长度字段）+ 块体长度
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        assertEquals(seg.length, ErpcMetadataCodec.LENGTH_PREFIX + blockLen);

        RpcMetadata back = ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen);
        assertEquals(2, back.size());
        assertEquals("0f9a-42", back.get("txid"));
        assertEquals("acme", back.get("tenant"));
    }

    /**
     * ★ 中文 / 特殊字符 / 空值 —— 都是合法的元数据值。
     */
    @Test
    public void roundTripPreservesUnicodeAndSpecialChars() {
        RpcMetadata m = RpcMetadata.create();
        m.put("txid", "分布式事务-42");
        m.put("note", "a=b&c=d\n\t\"quoted\"");
        byte[] seg = ErpcMetadataCodec.encode(m);
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        RpcMetadata back = ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen);
        assertEquals("分布式事务-42", back.get("txid"));
        assertEquals("a=b&c=d\n\t\"quoted\"", back.get("note"));
    }

    @Test
    public void roundTripPreservesLongValues() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append('x');
        }
        RpcMetadata m = RpcMetadata.create();
        m.put("blob", sb.toString());
        byte[] seg = ErpcMetadataCodec.encode(m);
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        RpcMetadata back = ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen);
        assertEquals(5000, back.get("blob").length());
    }

    /**
     * ★ 上下文段的字节布局：长度字段 + 条目数 + 逐条（键长/键/值长/值）。
     */
    @Test
    public void segmentLayoutIsSelfDescribing() {
        RpcMetadata m = RpcMetadata.create();
        m.put("ab", "cd");
        byte[] seg = ErpcMetadataCodec.encode(m);

        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        // 块体 = 条目数(4) + 键长(2) + 键(2) + 值长(4) + 值(2) = 14
        assertEquals(4 + 2 + 2 + 4 + 2, blockLen);
        // 条目数
        assertEquals(1, readInt(seg, 4));
        // 键长 + 键
        assertEquals(2, ((seg[8] & 0xff) << 8) | (seg[9] & 0xff));
        assertEquals("ab", new String(seg, 10, 2, StandardCharsets.UTF_8));
        // 值长 + 值
        assertEquals(2, readInt(seg, 12));
        assertEquals("cd", new String(seg, 16, 2, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 越界 / 非法
    // ------------------------------------------------------------------

    /**
     * ★ 长度字段不完整 ⇒ 抛协议错误。
     */
    @Test
    public void incompleteLengthFieldIsRejected() {
        byte[] seg = new byte[2];
        assertThrows(ErpcProtocolException.class, () -> ErpcMetadataCodec.blockLength(seg, 0));
        assertThrows(ErpcProtocolException.class, () -> ErpcMetadataCodec.blockLength(null, 0));
    }

    /**
     * ★ 块体比声明的长度短 ⇒ 抛协议错误（不静默截断）。
     */
    @Test
    public void truncatedBlockIsRejected() {
        byte[] seg = new byte[16];
        writeInt(seg, 0, 100);      // 声明块体 100 字节
        writeInt(seg, 4, 1);        // 但只有 12 字节
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        assertThrows(ErpcProtocolException.class,
            () -> ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen));
    }

    /**
     * ★ 键长度越界 ⇒ 抛协议错误。
     */
    @Test
    public void keyLengthOutOfBoundsIsRejected() {
        byte[] seg = new byte[16];
        writeInt(seg, 0, 12);      // 块体 12 字节
        writeInt(seg, 4, 1);       // 1 个条目
        writeInt(seg, 8, 0xFFFF);  // 键长 65535 ⇒ 越界
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        assertThrows(ErpcProtocolException.class,
            () -> ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen));
    }

    /**
     * ★ 值长度越界 ⇒ 抛协议错误。
     */
    @Test
    public void valueLengthOutOfBoundsIsRejected() {
        byte[] seg = new byte[20];
        writeInt(seg, 0, 16);      // 块体 16 字节
        writeInt(seg, 4, 1);       // 1 个条目
        writeShort(seg, 8, 2);     // 键长 2
        seg[10] = 'a';
        seg[11] = 'b';
        writeInt(seg, 12, 0x7FFFFFFF);  // 值长 ⇒ 越界
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        assertThrows(ErpcProtocolException.class,
            () -> ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen));
    }

    /**
     * ★ 块体太短（连条目数都放不下）⇒ 抛协议错误。
     */
    @Test
    public void tooShortBlockIsRejected() {
        byte[] seg = new byte[8];
        assertThrows(ErpcProtocolException.class,
            () -> ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, 2));
    }

    /**
     * ★ 线上来的键非法 ⇒ 抛协议错误（对端连错了 ⇒ 让对端知道）。
     */
    @Test
    public void illegalKeyOnTheWireIsRejected() {
        RpcMetadata m = RpcMetadata.create();
        // 绕过 put 的校验，直接往 map 里塞一个非法键
        m.asMap();   // 快照不影响原表
        RpcMetadata broken = RpcMetadata.create();
        broken.put("ok", "v");
        byte[] seg = ErpcMetadataCodec.encode(broken);
        // 手工把键改成非法的（首字母大写）
        int keyOff = ErpcMetadataCodec.LENGTH_PREFIX + 4 + 2;
        seg[keyOff] = 'O';
        int blockLen = ErpcMetadataCodec.blockLength(seg, 0);
        ErpcProtocolException e = assertThrows(ErpcProtocolException.class,
            () -> ErpcMetadataCodec.decode(seg, ErpcMetadataCodec.LENGTH_PREFIX, blockLen));
        assertTrue(e.getMessage().contains("键非法"), "错误消息要说清是键非法: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
             | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off]     = (byte) ((v >>> 24) & 0xff);
        b[off + 1] = (byte) ((v >>> 16) & 0xff);
        b[off + 2] = (byte) ((v >>> 8) & 0xff);
        b[off + 3] = (byte) (v & 0xff);
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off]     = (byte) ((v >>> 8) & 0xff);
        b[off + 1] = (byte) (v & 0xff);
    }
}
