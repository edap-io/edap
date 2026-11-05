/*
 * Copyright 2023 The edap Project
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

package io.edap.http.server.test.http2;

import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;
import io.edap.http.server.http2.Http2FlowController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接级流控窗口的单测（RFC 9113 §6.9）。
 */
public class Http2FlowControllerTest {

    @Test
    public void testInitialWindows() {
        Http2FlowController fc = new Http2FlowController();
        assertEquals(65535, fc.connSendWindow(), "连接级发送窗口初值");
        assertEquals(65535, fc.connRecvWindow(), "连接级接收窗口初值");
        assertEquals(65535, fc.peerInitialWindowSize(), "对端 INITIAL_WINDOW_SIZE 初值");
        assertEquals(65535, fc.localInitialWindowSize(), "本端 INITIAL_WINDOW_SIZE 初值");
    }

    /**
     * 连接级窗口初值<b>不</b>受 SETTINGS_INITIAL_WINDOW_SIZE 影响（RFC 9113 §6.9.2）。
     */
    @Test
    public void testConnectionWindowUnaffectedByInitialWindowSize() {
        Http2FlowController fc = new Http2FlowController();
        fc.setLocalInitialWindowSize(1024);
        fc.applyPeerInitialWindowSize(2048);
        assertEquals(65535, fc.connSendWindow());
        assertEquals(65535, fc.connRecvWindow());
        assertEquals(2048, fc.peerInitialWindowSize());
        assertEquals(1024, fc.localInitialWindowSize());
    }

    @Test
    public void testConsumeConnSendWindow() {
        Http2FlowController fc = new Http2FlowController();
        assertEquals(100, fc.consumeConnSendWindow(100));
        assertEquals(65435, fc.connSendWindow());
        // 窗口不够时只能发剩余部分
        assertEquals(65435, fc.consumeConnSendWindow(100000));
        assertEquals(0, fc.connSendWindow());
        assertEquals(0, fc.consumeConnSendWindow(1));
    }

    @Test
    public void testConsumeConnRecvWindowOverflowIsConnectionError() {
        Http2FlowController fc = new Http2FlowController();
        fc.consumeConnRecvWindow(65535);
        Http2Exception e = assertThrows(Http2Exception.class, () -> fc.consumeConnRecvWindow(1));
        assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, e.getErrorCode());
        assertFalse(e.isStreamError(), "连接级流控违规必须是连接级错误（GOAWAY）");
    }

    /**
     * 接收窗口要「攒够一半才归还」，避免每收一个包就发一次 WINDOW_UPDATE。
     */
    @Test
    public void testTakeConnRecvCredit() {
        Http2FlowController fc = new Http2FlowController();
        fc.consumeConnRecvWindow(1000);
        assertEquals(0, fc.takeConnRecvCredit(), "不足阈值（窗口的一半）不归还");
        fc.consumeConnRecvWindow(32267);
        assertEquals(33267, fc.takeConnRecvCredit());
        assertEquals(0, fc.takeConnRecvCredit(), "credit 已取走");
        assertEquals(65535, fc.connRecvWindow(), "归还后窗口应恢复到初值");
    }

    /**
     * 对端改 INITIAL_WINDOW_SIZE ⇒ 返回差值，连接层要传播给所有已存在的流。
     */
    @Test
    public void testApplyPeerInitialWindowSizeReturnsDelta() {
        Http2FlowController fc = new Http2FlowController();
        assertEquals(0, fc.applyPeerInitialWindowSize(65535), "值没变 ⇒ 差值为 0");
        assertEquals(1024, fc.applyPeerInitialWindowSize(66559));
        assertEquals(66559, fc.peerInitialWindowSize());
        assertEquals(-1024, fc.applyPeerInitialWindowSize(65535), "缩小也要传播差值");
    }

    @Test
    public void testIncreaseConnSendWindowOverflow() {
        Http2FlowController fc = new Http2FlowController();
        Http2Exception e = assertThrows(Http2Exception.class,
                () -> fc.increaseConnSendWindow(Integer.MAX_VALUE));
        assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, e.getErrorCode());
    }

    @Test
    public void testIncreaseConnSendWindow() {
        Http2FlowController fc = new Http2FlowController();
        fc.increaseConnSendWindow(1000);
        assertEquals(66535, fc.connSendWindow());
    }

    @Test
    public void testMaxWindowConstant() {
        assertEquals(0x7fffffff, Http2FlowController.MAX_WINDOW, "RFC 9113 §6.9.1 的窗口上限");
        assertEquals(65535, Http2FlowController.DEFAULT_WINDOW);
        assertTrue(Http2FlowController.MAX_WINDOW > Http2FlowController.DEFAULT_WINDOW);
    }
}
