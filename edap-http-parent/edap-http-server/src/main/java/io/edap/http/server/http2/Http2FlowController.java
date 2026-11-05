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

package io.edap.http.server.http2;

import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;

/**
 * HTTP/2 流控（RFC 9113 §6.9）。
 *
 * <p><b>四个窗口</b>：连接级发送/接收 + 流级发送/接收。连接级的两个窗口由本类持有，
 * 流级的两个窗口由 {@link Http2Stream} 持有（每条流一份）。</p>
 *
 * <p><b>窗口初值</b>（RFC 9113 §6.9.2）：</p>
 * <ul>
 *   <li>连接级发送/接收窗口初值恒为 65535，<b>不</b>受 SETTINGS_INITIAL_WINDOW_SIZE 影响；</li>
 *   <li>流级<b>发送</b>窗口初值 = <b>对端</b>通告的 SETTINGS_INITIAL_WINDOW_SIZE
 *       （对端说「我能接收多少」⇒ 决定我们能发多少）；</li>
 *   <li>流级<b>接收</b>窗口初值 = <b>本端</b>通告的 SETTINGS_INITIAL_WINDOW_SIZE
 *       （我们说「我能接收多少」⇒ 决定对端能发多少）。</li>
 * </ul>
 *
 * <p><b>为什么要「用一半才归还」</b>：收到 DATA 后必须发 WINDOW_UPDATE 把窗口补回去，
 * 否则对端发超过一个窗口的数据就会卡死。每收一个包就还一次会产生大量小帧，
 * 所以攒到初始窗口的一半再一次性归还。</p>
 */
public class Http2FlowController {

    /** 初始窗口（RFC 9113 §6.9.2） */
    public static final int DEFAULT_WINDOW = 65535;

    /** 窗口上限（RFC 9113 §6.9.1：超过 2^31-1 是 FLOW_CONTROL_ERROR） */
    public static final int MAX_WINDOW = 0x7fffffff;

    /** 连接级发送窗口：对端还能接收多少字节 */
    private int connSendWindow = DEFAULT_WINDOW;

    /** 连接级接收窗口：我们还能接收多少字节 */
    private int connRecvWindow = DEFAULT_WINDOW;

    /** 对端通告的 INITIAL_WINDOW_SIZE（新流的发送窗口初值） */
    private int peerInitialWindowSize = DEFAULT_WINDOW;

    /** 本端通告的 INITIAL_WINDOW_SIZE（新流的接收窗口初值） */
    private int localInitialWindowSize = DEFAULT_WINDOW;

    /** 已收到但还没归还给对端的连接级字节数 */
    private int pendingConnRecvCredit;

    /**
     * 对端通告的 INITIAL_WINDOW_SIZE 初值。
     */
    public int peerInitialWindowSize() {
        return peerInitialWindowSize;
    }

    /**
     * 本端通告的 INITIAL_WINDOW_SIZE。
     */
    public int localInitialWindowSize() {
        return localInitialWindowSize;
    }

    public void setLocalInitialWindowSize(int size) {
        this.localInitialWindowSize = size;
    }

    /**
     * 对端改了 SETTINGS_INITIAL_WINDOW_SIZE。
     *
     * @param newSize 新的值
     * @return 与旧值的差值 —— 必须传播给所有已存在的流的发送窗口（RFC 9113 §6.9.2）
     */
    public int applyPeerInitialWindowSize(int newSize) {
        int delta = newSize - peerInitialWindowSize;
        peerInitialWindowSize = newSize;
        return delta;
    }

    /**
     * 连接级发送窗口。
     */
    public int connSendWindow() {
        return connSendWindow;
    }

    /**
     * 收到连接级 WINDOW_UPDATE（streamId == 0）。
     */
    public void increaseConnSendWindow(int increment) {
        long next = (long) connSendWindow + increment;
        if (next > MAX_WINDOW) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "连接级发送窗口溢出: " + connSendWindow + " + " + increment);
        }
        connSendWindow = (int) next;
    }

    /**
     * 扣减连接级发送窗口。
     *
     * @param want 想要发送的字节数
     * @return 实际可以发送的字节数（受连接级窗口限制）
     */
    public int consumeConnSendWindow(int want) {
        int n = Math.min(want, connSendWindow);
        if (n < 0) {
            n = 0;
        }
        connSendWindow -= n;
        return n;
    }

    /**
     * 扣减连接级接收窗口。
     *
     * @param length 收到的字节数（DATA 帧的<b>整个</b>载荷，含 padding）
     * @throws Http2Exception 窗口变成负数 —— 连接级 FLOW_CONTROL_ERROR
     */
    public void consumeConnRecvWindow(int length) {
        connRecvWindow -= length;
        if (connRecvWindow < 0) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "连接级接收窗口溢出: " + (connRecvWindow + length) + " - " + length);
        }
        pendingConnRecvCredit += length;
    }

    /**
     * 取出待归还的连接级 credit（攒够初始窗口的一半才返回）。
     */
    public int takeConnRecvCredit() {
        int threshold = Math.max(1, DEFAULT_WINDOW / 2);
        if (pendingConnRecvCredit < threshold) {
            return 0;
        }
        int credit = pendingConnRecvCredit;
        pendingConnRecvCredit = 0;
        connRecvWindow += credit;
        return credit;
    }

    /**
     * 连接级接收窗口（测试用）。
     */
    public int connRecvWindow() {
        return connRecvWindow;
    }
}
