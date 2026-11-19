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

package io.edap.erpc.frame;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 线格式测试 —— 帧头的逐字节布局 + 增量分帧。
 *
 * <p>帧头是<b>线上协议</b>：一旦发布就不能改。所以这里逐字节断言，
 * 任何字段偏移/字节序的改动都会在这里炸掉。</p>
 */
public class ErpcFrameFormatTest {

    // ------------------------------------------------------------------
    // 帧头布局
    // ------------------------------------------------------------------

    @Test
    public void headerLayoutIsBigEndian() {
        ErpcFrameHeader header = new ErpcFrameHeader()
            .setType(ErpcFrameType.REQUEST)
            .setCodec(1)
            .setLength(0x01020304)
            .setMethodId(0x0A0B0C0D)
            .setServiceId(0x11223344)
            .setRequestId(0x55667788)
            .setTraceId(0x0102030405060708L);

        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH];
        header.writeTo(buf, 0);

        // magic：'E','R','P','C'
        assertEquals('E', buf[0]);
        assertEquals('R', buf[1]);
        assertEquals('P', buf[2]);
        assertEquals('C', buf[3]);
        // version
        assertEquals(1, buf[4]);
        // type
        assertEquals(ErpcFrameType.REQUEST.getValue(), buf[5]);
        // flags（当前恒 0）
        assertEquals(0, buf[6]);
        // codec
        assertEquals(1, buf[7]);
        // length：大端
        assertEquals(0x01, buf[8]);
        assertEquals(0x02, buf[9]);
        assertEquals(0x03, buf[10]);
        assertEquals(0x04, buf[11]);
        // methodId：大端
        assertEquals(0x0A, buf[12]);
        assertEquals(0x0B, buf[13]);
        assertEquals(0x0C, buf[14]);
        assertEquals(0x0D, buf[15]);
        // serviceId
        assertEquals(0x11, buf[16]);
        assertEquals(0x22, buf[17]);
        assertEquals(0x33, buf[18]);
        assertEquals(0x44, buf[19]);
        // requestId
        assertEquals(0x55, buf[20]);
        assertEquals(0x66, buf[21]);
        assertEquals(0x77, buf[22]);
        assertEquals(0x88, buf[23] & 0xff);
        // traceId：大端 8 字节
        assertEquals(0x01, buf[24]);
        assertEquals(0x02, buf[25]);
        assertEquals(0x03, buf[26]);
        assertEquals(0x04, buf[27]);
        assertEquals(0x05, buf[28]);
        assertEquals(0x06, buf[29]);
        assertEquals(0x07, buf[30]);
        assertEquals(0x08, buf[31]);

        // 读回来必须一样
        ErpcFrameHeader parsed = ErpcFrameHeader.readFrom(buf, 0);
        assertEquals(ErpcFrameType.REQUEST.getValue(), parsed.getType());
        assertEquals(1, parsed.getCodec());
        assertEquals(0x01020304, parsed.getLength());
        assertEquals(0x0A0B0C0D, parsed.getMethodId());
        assertEquals(0x11223344, parsed.getServiceId());
        assertEquals(0x55667788, parsed.getRequestId());
        assertEquals(0x0102030405060708L, parsed.getTraceId());
    }

    @Test
    public void magicIsChecked() {
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH];
        new ErpcFrameHeader().writeTo(buf, 0);
        assertTrue(ErpcFrameHeader.checkMagic(buf, 0));

        buf[1] = 'X';
        assertFalse(ErpcFrameHeader.checkMagic(buf, 0));
    }

    // ------------------------------------------------------------------
    // 分帧
    // ------------------------------------------------------------------

    @Test
    public void frameSplitAcrossThreeReadsIsReassembled() {
        byte[] frame = frame(0x2A, "hello".getBytes(StandardCharsets.UTF_8));

        ErpcFrameCodec codec = new ErpcFrameCodec();
        // 前 10 字节
        codec.append(frame, 0, 10);
        assertFalse(codec.hasFrame(), "帧没收全时不能切出帧");
        // 再 20 字节（头齐了但载荷没收全）
        codec.append(frame, 10, 20);
        assertFalse(codec.hasFrame(), "载荷没收全时不能切出帧");
        // 剩下的
        codec.append(frame, 30, frame.length - 30);
        assertTrue(codec.hasFrame());

        ErpcFrameHeader header = codec.header();
        assertEquals(0x2A, header.getMethodId());
        assertEquals(5, header.getLength());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), codec.payload());
        codec.consume();
        assertFalse(codec.hasFrame());
        assertEquals(0, codec.buffered());
    }

    @Test
    public void threeFramesInOneReadAreSplit() {
        byte[] f1 = frame(1, new byte[]{1, 2, 3});
        byte[] f2 = frame(2, new byte[]{4});
        byte[] f3 = frame(3, new byte[0]);

        byte[] all = new byte[f1.length + f2.length + f3.length];
        System.arraycopy(f1, 0, all, 0, f1.length);
        System.arraycopy(f2, 0, all, f1.length, f2.length);
        System.arraycopy(f3, 0, all, f1.length + f2.length, f3.length);

        ErpcFrameCodec codec = new ErpcFrameCodec();
        codec.append(all, 0, all.length);

        assertEquals(1, codec.header().getMethodId());
        assertArrayEquals(new byte[]{1, 2, 3}, codec.payload());
        codec.consume();

        assertEquals(2, codec.header().getMethodId());
        assertArrayEquals(new byte[]{4}, codec.payload());
        codec.consume();

        assertEquals(3, codec.header().getMethodId());
        assertEquals(0, codec.payload().length);
        codec.consume();

        assertFalse(codec.hasFrame());
    }

    @Test
    public void payloadContainingMagicBytesIsNotMistakenForAFrame() {
        // 载荷里塞满 'E','R','P','C' —— 分帧器只按 length 字段切，不会误切
        byte[] payload = new byte[]{'E', 'R', 'P', 'C', 0, 'E', 'R', 'P', 'C'};
        byte[] f1 = frame(7, payload);
        byte[] f2 = frame(8, new byte[]{'C', 'P', 'R', 'E'});

        byte[] all = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, all, 0, f1.length);
        System.arraycopy(f2, 0, all, f1.length, f2.length);

        ErpcFrameCodec codec = new ErpcFrameCodec();
        codec.append(all, 0, all.length);

        assertEquals(7, codec.header().getMethodId());
        assertArrayEquals(payload, codec.payload());
        codec.consume();

        assertEquals(8, codec.header().getMethodId());
        assertArrayEquals(new byte[]{'C', 'P', 'R', 'E'}, codec.payload());
        codec.consume();

        assertFalse(codec.hasFrame());
    }

    @Test
    public void manySmallFramesAreReassembled() {
        // 连续 100 个小帧（每个 1 字节载荷）—— 验证累积缓冲的搬移逻辑
        ErpcFrameCodec codec = new ErpcFrameCodec();
        for (int i = 0; i < 100; i++) {
            byte[] f = frame(i + 1, new byte[]{(byte) i});
            codec.append(f, 0, f.length);
        }
        for (int i = 0; i < 100; i++) {
            assertTrue(codec.hasFrame());
            assertEquals(i + 1, codec.header().getMethodId());
            assertArrayEquals(new byte[]{(byte) i}, codec.payload());
            codec.consume();
        }
        assertFalse(codec.hasFrame());
    }

    // ------------------------------------------------------------------
    // flags：bit0 = 压缩预留，bit1~bit7 = 7-bit 超时代码
    // ------------------------------------------------------------------

    @Test
    public void flagsLiveInByteSix() {
        // ★ flags 的偏移不能动 —— 动了整条线上的帧全部错位
        ErpcFrameHeader header = new ErpcFrameHeader()
            .setType(ErpcFrameType.REQUEST)
            .setFlags(0xFE)                 // bit1~bit7 全置位
            .setCodec(1)
            .setLength(0);
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH];
        header.writeTo(buf, 0);

        assertEquals(0xFE, buf[6] & 0xff, "flags 必须在第 6 字节");
        // 其它字节不受影响
        assertEquals(ErpcFrameType.REQUEST.getValue(), buf[5]);
        assertEquals(1, buf[7]);
        assertEquals(0, buf[8]);
    }

    @Test
    public void oldFrameWithZeroFlagsStillParses() {
        // 老版本写出来的帧 flags 恒为 0 —— 必须照常解析
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH];
        new ErpcFrameHeader()
            .setType(ErpcFrameType.REQUEST)
            .setFlags(0)
            .setCodec(1)
            .setLength(7)
            .setMethodId(42)
            .setRequestId(43)
            .writeTo(buf, 0);

        ErpcFrameHeader parsed = ErpcFrameHeader.readFrom(buf, 0);
        assertEquals(0, parsed.getFlags());
        assertEquals(42, parsed.getMethodId());
        assertEquals(43, parsed.getRequestId());
        assertEquals(7, parsed.getLength());
    }

    @Test
    public void newFrameWithTimeoutCodeParses() {
        // ★ 帧长与字段偏移一个字节都没动 —— 只是 flags 的高位有了值
        int flags = 0x7E;                 // 超时代码 0x3F
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH];
        new ErpcFrameHeader()
            .setType(ErpcFrameType.REQUEST)
            .setFlags(flags)
            .setCodec(0)
            .setLength(3)
            .setMethodId(11)
            .writeTo(buf, 0);

        assertEquals(ErpcFrameHeader.HEADER_LENGTH, buf.length);
        ErpcFrameHeader parsed = ErpcFrameHeader.readFrom(buf, 0);
        assertEquals(flags, parsed.getFlags());
        assertEquals(11, parsed.getMethodId());
        assertEquals(3, parsed.getLength());
    }

    @Test
    public void timeoutCodeDoesNotTouchTheCompressionBit() {
        // ★ bit0 是「压缩预留」—— 任何路径都不许写它
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH];
        new ErpcFrameHeader().setFlags(0xFE).writeTo(buf, 0);
        assertEquals(0, buf[6] & 0x01, "bit0（压缩预留）被写了");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static byte[] frame(int methodId, byte[] payload) {
        ErpcFrameHeader header = new ErpcFrameHeader()
            .setType(ErpcFrameType.REQUEST)
            .setCodec(0)
            .setLength(payload.length)
            .setMethodId(methodId)
            .setRequestId(methodId);
        byte[] buf = new byte[ErpcFrameHeader.HEADER_LENGTH + payload.length];
        header.writeTo(buf, 0);
        System.arraycopy(payload, 0, buf, ErpcFrameHeader.HEADER_LENGTH, payload.length);
        return buf;
    }
}
