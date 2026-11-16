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
 * eRPC 载荷的编解码实现（SPI）。
 *
 * <p><b>什么被编解码</b>：只有「业务载荷」——请求 DTO 与响应 DTO。
 * 路由信息（methodId / requestId / traceId）在帧头里，不走这里（见
 * {@link io.edap.erpc.frame.ErpcFrameHeader}）。</p>
 *
 * <p><b>实现约定</b>：</p>
 * <ul>
 *   <li>{@link #id()} 是线上协议的稳定标识，见 {@link ErpcCodecIds}；</li>
 *   <li>{@link #name()} 是配置名（{@code erpc.codec: json} 里的那个），大小写不敏感；</li>
 *   <li>{@link #encode(Object)} 把对象编码进<b>实现方自己的缓冲</b>并交出
 *       {@link ErpcWriter}；{@code obj} 为 {@code null} 时返回 {@code null}
 *       （对应「这一帧没有载荷」，帧头的 length 写 0）；</li>
 *   <li>{@link #decode(byte[], int, int, Class)} 只读 {@code [offset, offset+len)} 这一段，
 *       <b>不能</b>假设数组里只有这一段数据。</li>
 * </ul>
 *
 * <p>★ 扩展方式：实现本接口 + 在 {@code META-INF/services/io.edap.erpc.codec.ErpcCodec}
 * 里登记 ⇒ {@link ErpcCodecRegistry} 启动时自动装载。也可以在代码里
 * {@link ErpcCodecRegistry#register(ErpcCodec)} 手工注册。</p>
 */
public interface ErpcCodec {

    /**
     * @return 线上协议标识，见 {@link ErpcCodecIds}
     */
    int id();

    /**
     * @return 配置名，如 {@code protobuf} / {@code json}
     */
    String name();

    /**
     * 把业务对象编码成字节。
     *
     * <p>★ 交出的是<b>借来的</b>缓冲（实现方自己的线程本地缓冲）——
     * 调用方必须立即拷走，不得持有。</p>
     *
     * @param obj 业务对象，可能为 {@code null}
     * @return 载荷载体；{@code obj} 为 {@code null} 时返回 {@code null}
     */
    ErpcWriter encode(Object obj);

    /**
     * 把字节反序列化成业务对象。
     *
     * @param data   字节数组
     * @param offset 起始下标
     * @param len    长度
     * @param type   目标类型
     * @return 反序列化后的对象
     */
    <T> T decode(byte[] data, int offset, int len, Class<T> type);
}
