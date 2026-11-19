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

import io.edap.erpc.frame.ErpcFrameHeader;

/**
 * ★ 把「消息 + 帧头」拼成一帧交给写出方 —— eRPC 侧的编帧入口。
 *
 * <p><b>对照物</b>：gRPC 侧有 {@code io.edap.grpc.GrpcFrameEncoder}，
 * 它把 5 字节 gRPC 帧头<b>内联</b>在编码缓冲里，载荷只拷一次。
 * eRPC 之前是「编码产出独立 {@code byte[]} → 再整块拷进待写缓冲」，
 * 载荷要拷两次 ⇒ 本类让 codec 直接交出自己缓冲里的字节（{@link ErpcWriter}），
 * 载荷<b>只拷一次</b>。</p>
 *
 * <p><b>★ 编码顺序</b>：帧头字段先填（{@code length} 除外，它依赖载荷长度）
 * → 编码载荷 → 回填 {@code length} → 交给 {@link FrameSink}。
 * 帧头<b>字节</b>由 sink 直接写进目标缓冲（不经过载荷那块）
 * ⇒ 帧头的 32 字节不占拷贝预算。</p>
 */
public final class ErpcFrameEncoder {

    /**
     * 帧的消费者。
     *
     * <p>★ 载荷数组是<b>借用</b>的（来自序列化器的线程本地缓冲），
     * 实现必须在返回前把它拷走，不得持有引用。</p>
     */
    @FunctionalInterface
    public interface FrameSink {
        /**
         * 写出一帧。
         *
         * @param header  帧头（32 字节；只在本次调用内有效）
         * @param payload 载荷字节（<b>借用</b>的；{@code [0, length)} 是有效内容）
         * @param length  载荷长度（0 表示这一帧没有载荷）
         */
        void accept(ErpcFrameHeader header, byte[] payload, int length);
    }

    /**
     * 线程本地的帧头复用对象 —— 帧头每次都要填一遍，用复用对象省掉一次分配。
     *
     * <p>★ 它只在 {@link #encode} 的调用栈里活着（{@link FrameSink} 是同步回调），
     * 所以不需要加锁。</p>
     */
    private static final ThreadLocal<ErpcFrameHeader> HEADERS =
        ThreadLocal.withInitial(ErpcFrameHeader::new);

    private static final byte[] EMPTY = new byte[0];

    private ErpcFrameEncoder() {
    }

    /**
     * 把消息编成一帧交给 {@code sink}。
     *
     * @param codec    编解码器
     * @param type     帧类型（{@link io.edap.erpc.frame.ErpcFrameType#getValue()}）
     * @param codecId  编解码器 id（原样抄回请求帧的 codec）
     * @param methodId 方法号
     * @param serviceId 服务号
     * @param requestId 请求号
     * @param traceId  链路号
     * @param message  业务对象（可为 {@code null}）
     * @param sink     帧的消费者
     */
    public static void encode(ErpcCodec codec,
                              int type, int codecId, int methodId, int serviceId,
                              int requestId, long traceId,
                              Object message, FrameSink sink) {
        encode(codec, type, codecId, methodId, serviceId, requestId, traceId, 0, message, sink);
    }

    /**
     * 把消息编成一帧交给 {@code sink}（<b>带 flags</b>）。
     *
     * @param codec    编解码器
     * @param type     帧类型（{@link io.edap.erpc.frame.ErpcFrameType#getValue()}）
     * @param codecId  编解码器 id（原样抄回请求帧的 codec）
     * @param methodId 方法号
     * @param serviceId 服务号
     * @param requestId 请求号
     * @param traceId  链路号
     * @param flags    帧头 flags（bit0 = 压缩预留；bit1~bit7 = 超时代码，
     *                 见 {@link io.edap.rpc.RpcTimeout#toErpcFlags(int, int)}）
     * @param message  业务对象（可为 {@code null}）
     * @param sink     帧的消费者
     */
    public static void encode(ErpcCodec codec,
                              int type, int codecId, int methodId, int serviceId,
                              int requestId, long traceId, int flags,
                              Object message, FrameSink sink) {
        ErpcFrameHeader header = HEADERS.get();
        header.setType(type)
            .setFlags(flags)
            .setCodec(codecId)
            .setMethodId(methodId)
            .setServiceId(serviceId)
            .setRequestId(requestId)
            .setTraceId(traceId);

        // ★ 编码在锁外：codec 把载荷写进它自己的线程本地缓冲，
        //   sink 在锁内把这段字节拷进目标缓冲 ⇒ 载荷只被拷一次。
        ErpcWriter payload = codec.encode(message);
        int payloadLen = payload == null ? 0 : payload.size();
        header.setLength(payloadLen);
        sink.accept(header, payload == null ? EMPTY : payload.buffer(), payloadLen);
    }
}
