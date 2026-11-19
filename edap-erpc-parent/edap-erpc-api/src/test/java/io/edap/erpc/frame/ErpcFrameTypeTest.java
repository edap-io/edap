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

package io.edap.erpc.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErpcFrameType} 的测试 —— <b>type 字节的 bit7 是「本帧带 RPC 上下文」标志位</b>。
 *
 * <p>★ 这条不变式很关键：<b>kindOf 必须掩掉 bit7</b>，
 * 否则带上下文的请求帧（type = 0x81）会被当成「未知帧类型」丢掉。</p>
 */
public class ErpcFrameTypeTest {

    @Test
    public void frameKindsKeepTheirValues() {
        // ★ 帧种类空间是线上协议 —— 值不能动
        assertEquals(1, ErpcFrameType.REQUEST.getValue());
        assertEquals(2, ErpcFrameType.RESPONSE.getValue());
        assertEquals(3, ErpcFrameType.ERROR.getValue());
        assertEquals(4, ErpcFrameType.PING.getValue());
        assertEquals(5, ErpcFrameType.PONG.getValue());
        assertEquals(6, ErpcFrameType.AUTH.getValue());
    }

    @Test
    public void kindOfMasksTheContextFlag() {
        assertEquals(ErpcFrameType.REQUEST.getValue(),
            ErpcFrameType.kindOf(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true)));
        assertEquals(ErpcFrameType.REQUEST.getValue(),
            ErpcFrameType.kindOf(ErpcFrameType.REQUEST.getValue()));
    }

    @Test
    public void hasContextReadsBitSeven() {
        assertTrue(ErpcFrameType.hasContext(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true)));
        assertFalse(ErpcFrameType.hasContext(ErpcFrameType.REQUEST.getValue()));
    }

    @Test
    public void typeComposesKindAndFlag() {
        assertEquals(0x81, ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true));
        assertEquals(0x01, ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), false));
        assertEquals(0x80, ErpcFrameType.HAS_CONTEXT);
        assertEquals(0x7F, ErpcFrameType.KIND_MASK);
    }

    /**
     * ★ {@code of()} 会自动掩掉标志位 —— 带上下文的请求帧仍能被解析成 REQUEST。
     */
    @Test
    public void ofIgnoresTheContextFlag() {
        assertSame(ErpcFrameType.REQUEST,
            ErpcFrameType.of(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true)));
        assertSame(ErpcFrameType.REQUEST, ErpcFrameType.of(ErpcFrameType.REQUEST.getValue()));
        assertSame(ErpcFrameType.AUTH, ErpcFrameType.of(ErpcFrameType.AUTH.getValue()));
        assertNull(ErpcFrameType.of(99));
    }

    /**
     * ★ 带上下文的帧头在线上仍是<b>同一个 32 字节布局</b> —— 只是 type 字节的 bit7 变了。
     */
    @Test
    public void contextFlagOnlyChangesTheTypeByte() {
        byte[] plain = new byte[ErpcFrameHeader.HEADER_LENGTH];
        new ErpcFrameHeader().setType(ErpcFrameType.REQUEST).setCodec(1).setLength(7)
            .writeTo(plain, 0);

        byte[] withCtx = new byte[ErpcFrameHeader.HEADER_LENGTH];
        new ErpcFrameHeader()
            .setType(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true))
            .setCodec(1).setLength(7)
            .writeTo(withCtx, 0);

        assertEquals(plain.length, withCtx.length);
        assertEquals(0x01, plain[5], "普通请求帧的 type 字节");
        assertEquals(0x81, withCtx[5] & 0xff, "带上下文的请求帧：type 字节的 bit7 置位");
        // 其它字节完全一样
        for (int i = 0; i < plain.length; i++) {
            if (i == 5) {
                continue;
            }
            assertEquals(plain[i], withCtx[i], "第 " + i + " 字节不该变");
        }
        // 读回来时 type 是 0x81（不是负数）
        assertEquals(0x81, ErpcFrameHeader.readFrom(withCtx, 0).getType());
    }
}
