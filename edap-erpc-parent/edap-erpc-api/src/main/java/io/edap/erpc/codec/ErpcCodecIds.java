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

package io.edap.erpc.codec;

/**
 * eRPC 线上协议的序列化器 id 常量。
 *
 * <p><b>id 是线上协议的稳定标识</b> —— 它写在每一帧的帧头里（{@code codec} 字节），
 * 收发双方靠它决定这一帧的载荷怎么编解码。因此：</p>
 * <ul>
 *   <li><b>一旦发布就不能改</b>，否则新旧节点互相不认识对方的载荷；</li>
 *   <li><b>不能复用</b>（删掉一个实现后它的 id 也不能给新实现用），
 *       否则老节点会把新载荷按老实现解。</li>
 * </ul>
 *
 * <p>★ 帧头里带 codec ⇒ **序列化器是「按帧协商」的**，不是「整条连接一种」。
 * 一条连接上可以先发 protobuf 帧再发 json 帧，双方各自按帧头的 codec 解。
 * 服务端回帧时把请求帧的 codec 原样抄回去，客户端一定解得动。</p>
 */
public final class ErpcCodecIds {

    /**
     * protobuf —— 默认实现。
     */
    public static final int PROTOBUF = 0;

    /**
     * json。
     */
    public static final int JSON = 1;
    /**
     * 改进后的protobuf的协议，主要优化String和内嵌消息的算法
     */
    public static final int FAST_PROTOBUF = 2;

    private ErpcCodecIds() {
    }
}
