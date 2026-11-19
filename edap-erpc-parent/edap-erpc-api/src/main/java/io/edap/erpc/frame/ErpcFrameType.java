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
 *
 * <p>★ <b>这个字节的布局</b>：</p>
 * <pre>{@code
 *   bit7        bit0 ~ bit6
 *   ───────     ─────────────
 *   带上下文?   帧种类（1 ~ 127）
 * }</pre>
 *
 * <p><b>bit7 = 「本帧带 RPC 上下文」</b>（见 {@link #HAS_CONTEXT}）：
 * 置位时帧的<b>内容区</b>多一段「RPC 上下文」，与业务 DTO <b>分开</b>
 * （上下文在前、DTO 在后）。</p>
 *
 * <p>★ <b>为什么借用 type 的一个 bit，而不是新增一个帧类型</b>：</p>
 * <ul>
 *   <li>帧种类空间保持很小（1~6），<b>不会随扩展膨胀</b>；</li>
 *   <li>「带不带上下文」与「这一帧是什么」是<b>正交</b>的两件事 ——
 *       用两个维度表达比「再复制一套帧类型」清楚；</li>
 *   <li>帧头 <b>32 字节不变</b>，{@code version} 恒为 1（version 是协议版本，
 *       不该兼职当「有没有上下文」的标记）。</li>
 * </ul>
 *
 * <p>★ <b>判断时记得掩掉标志位</b>：用 {@link #kindOf(int)} 取种类、
 * 用 {@link #hasContext(int)} 判断标志。</p>
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
    PONG(5),
    /**
     * 连接级鉴权握手：客户端 → 服务端。
     *
     * <p>★ <b>连接级</b>：一条连接只发一次（建连后），校验通过后把「已鉴权」
     * 记在那条连接的上下文里 ⇒ 同一连接的后续请求免校验。</p>
     */
    AUTH(6);

    /**
     * 帧种类的掩码 —— 低 7 位。
     */
    public static final int KIND_MASK = 0x7F;

    /**
     * 「本帧带 RPC 上下文」标志位 —— 取 {@code type} 字节的 <b>bit7</b>。
     *
     * <p>置位时帧内容区 = {@code [RPC 上下文段][业务 DTO]}；
     * 不置位时帧内容区 = {@code [业务 DTO]}。</p>
     */
    public static final int HAS_CONTEXT = 0x80;

    private final int value;

    ErpcFrameType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * 取帧种类（<b>掩掉「带上下文」标志位</b>）。
     *
     * @param type 帧头的 type 字节
     * @return 帧种类（1~127）
     */
    public static int kindOf(int type) {
        return type & KIND_MASK;
    }

    /**
     * 本帧是否带 RPC 上下文。
     *
     * @param type 帧头的 type 字节
     * @return true 表示内容区开头有一段 RPC 上下文
     */
    public static boolean hasContext(int type) {
        return (type & HAS_CONTEXT) != 0;
    }

    /**
     * 组合帧类型字节。
     *
     * @param kind        帧种类
     * @param hasContext  是否带 RPC 上下文
     * @return 帧头的 type 字节
     */
    public static int type(int kind, boolean hasContext) {
        return hasContext ? (kind | HAS_CONTEXT) : kind;
    }

    /**
     * 按线上值解析（<b>自动掩掉「带上下文」标志位</b>）。
     *
     * @return 未知类型返回 {@code null}
     */
    public static ErpcFrameType of(int value) {
        int kind = kindOf(value);
        for (ErpcFrameType t : values()) {
            if (t.value == kind) {
                return t;
            }
        }
        return null;
    }
}
