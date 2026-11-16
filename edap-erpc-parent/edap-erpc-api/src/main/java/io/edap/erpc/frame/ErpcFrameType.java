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

/**
 * eRPC 帧类型（帧头的 {@code type} 字节）。
 *
 * <p>★ **方向判定只认这个字节**，不看载荷：proto3 会省略默认值
 * （枚举值 0 不上线），靠载荷判方向在「第一个枚举值恰好是 0」时会判反。</p>
 */
public enum ErpcFrameType {

    /** 请求：客户端 → 服务端。 */
    REQUEST(1),
    /** 响应：服务端 → 客户端。 */
    RESPONSE(2),
    /** 错误：服务端 → 客户端。载荷是 {@link io.edap.erpc.RpcException}。 */
    ERROR(3),
    /** 心跳请求：客户端 → 服务端。 */
    PING(4),
    /** 心跳响应：服务端 → 客户端。 */
    PONG(5);

    private final int value;

    ErpcFrameType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * 按线上值解析。
     *
     * @return 未知类型返回 {@code null}
     */
    public static ErpcFrameType of(int value) {
        for (ErpcFrameType t : values()) {
            if (t.value == value) {
                return t;
            }
        }
        return null;
    }
}
