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

package io.edap.raft.transport;

/**
 * 传输层的健康度计数。
 *
 * <p>基准要对比不同的传输层（TCP 回环、生产用的 NIO），不能依赖某个
 * 具体的测试类，所以计数定义在 SPI 这一层。</p>
 */
public interface TransportStats {

    /**
     * 发出去的帧数
     */
    long getFramesSent();

    /**
     * 发出去的字节数
     */
    long getBytesSent();

    /**
     * 收到的帧数
     */
    long getFramesReceived();

    /**
     * 因为队列满或者连不上而丢掉的帧数
     */
    long getFramesDropped();

    /**
     * 丢掉的帧里有多少是「复制类」消息（{@code MSG_APP} / {@code MSG_APP_RESP} /
     * {@code MSG_SNAP}）。
     *
     * <p>这类消息丢掉之后，对端要等到下一个心跳才知道自己落后，
     * 复制会停滞一整个心跳周期 —— 是尾延迟的主要来源。
     * 心跳、投票这类消息丢掉只影响一次心跳周期，属于正常现象。</p>
     */
    default long getCriticalFramesDropped() {
        return 0;
    }
}
