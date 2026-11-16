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
 * ★ 载荷载体 —— 序列化器交出来的一段<b>已编码</b>字节。
 *
 * <p><b>为什么要有它</b>：eRPC 的出站路径是「消息 → 编码 → 帧字节 → 待写缓冲」。
 * 如果编码这一步产出的是一块<b>独立的</b> {@code byte[]}，那么载荷在网络上会被拷两次
 * （编码器 → 独立数组 → 待写缓冲），还多一次大数组分配。
 * 让序列化器<b>直接交出自己缓冲里的字节视图</b>，载荷在网络上就只拷一次
 * （与 gRPC 侧的 {@code io.edap.grpc.GrpcFrameEncoder} 同一形态）。</p>
 *
 * <p><b>★ 返回的缓冲是借来的</b>：实现方通常用的是<b>线程本地</b>的 writer 缓冲
 * （protobuf 的 {@code ProtoBuf.THREAD_WRITER}、json 的 {@code Eson.THREAD_WRITER}），
 * 下一次编码就会覆盖它 ⇒ 调用方<b>必须在返回前拷走</b>，不得持有。</p>
 *
 * <p>★ <b>帧头不在这里</b>：帧头由 {@link ErpcFrameEncoder} 单独交给
 * {@link ErpcFrameEncoder.FrameSink}，写出方直接把它写进目标缓冲
 * ⇒ 帧头的 32 字节不占载荷的拷贝预算。</p>
 */
public interface ErpcWriter {

    /**
     * 已编码的<b>载荷</b>字节数（不含帧头）。
     */
    int size();

    /**
     * 载荷的<b>堆内</b>视图 —— {@code [0, size())} 是有效内容。
     *
     * <p>★ <b>借来的</b>：不得持有引用，调用方必须在返回前拷走。</p>
     */
    byte[] buffer();
}
