/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.edap.grpc;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;

import java.io.IOException;

/**
 * 把消息编码成 gRPC 帧，<b>帧头内联</b>在编码缓冲里。
 *
 * <p><b>为什么要有这个类</b>：一条 gRPC 消息原来要走三步才能上线 ——
 * <pre>
 *   对象 →[1] writer 缓冲 →[2] payload byte[] →[3] gRPC 帧 byte[] →[4] 网络缓冲
 * </pre>
 * 其中 [2] 和 [3] 两次<b>整块拷贝</b>纯属浪费：帧头只有 5 个字节，
 * 完全可以先在编码缓冲里给它留个位置，编码完再把长度填回去。</p>
 *
 * <p>本类把 [2][3] 合成一步：</p>
 * <pre>
 *   对象 →[1] writer 缓冲（[0,5) 是帧头，[5,total) 是载荷）→[2] 网络缓冲
 * </pre>
 *
 * <p><b>帧头为什么要「先占位、后回填」</b>：帧头里的长度就是载荷长度，
 * 而载荷长度要等编码完才知道 ⇒ 只能先占 5 个字节，编码完再回填。</p>
 *
 * <p><b>缓冲的生命周期</b>：交给 {@link FrameSink} 的数组是<b>借来的</b> ——
 * 它是线程本地的 writer 缓冲，下一次编码会覆盖它。
 * {@link FrameSink} <b>必须在返回前把它拷走</b>（HTTP/2 侧是
 * {@code Http2FrameWriter.writeFrame} 拷进堆外的 FastBuf；
 * 流控挂起时 {@code Http2Stream.appendPendingBody} 也会拷一份）。</p>
 */
public final class GrpcFrameEncoder {

    /**
     * 帧的消费者 —— 拿到「帧头 + 载荷」的连续字节后把它写出去。
     *
     * <p>★ 传入的数组是<b>借用</b>的，实现必须在返回前拷走，不得持有引用。</p>
     */
    @FunctionalInterface
    public interface FrameSink {
        /**
         * 写出一帧。
         *
         * @param buf    帧缓冲（[offset, offset+length) 是「5 字节帧头 + 载荷」）
         * @param offset 帧的起始位置
         * @param length 帧的总长度（含 5 字节帧头）
         */
        void accept(byte[] buf, int offset, int length) throws IOException;
    }

    private GrpcFrameEncoder() {
    }

    /**
     * 把消息编码成 gRPC 帧并交给 sink。
     *
     * @param type    消息类型，可以为 null（此时用消息对象自身的类型）
     * @param message 消息对象
     * @param sink    帧的消费者
     * @throws IOException sink 写出失败
     */
    public static void encode(Class<?> type, Object message, FrameSink sink) throws IOException {
        if (message == null) {
            throw new GrpcException("gRPC 消息不能为 null: " + (type == null ? "null" : type.getName()));
        }
        // 与 GrpcCodec.encode 保持一致：类型安全性检查（OBJECT 之类的私有 wire type 直接拒绝）
        GrpcCodec.validate(type == null ? message.getClass() : type);

        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        writer.reset();
        writer.getBufOut().reset();
        // 给帧头留位置：先占 5 个字节，编码完再回填长度。
        // expand 保证底层数组装得下这 5 个字节（顺带把 pos 之前的内容清掉）。
        writer.expand(GrpcFrame.HEADER_LENGTH);
        writer.setPos(GrpcFrame.HEADER_LENGTH);
        try {
            ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(message.getClass());
            encoder.encode(writer, message);
        } catch (EncodeException e) {
            throw new GrpcException("protobuf 编码失败: " + message.getClass().getName(), e);
        }

        int total = writer.size();
        if (total < GrpcFrame.HEADER_LENGTH) {
            // 理论上不可能（pos 至少是 5）；真发生了说明 size() 溢出了
            throw new GrpcException("protobuf 编码结果长度异常: " + total);
        }
        int payloadLength = total - GrpcFrame.HEADER_LENGTH;
        // 发送侧的长度检查（原来只在解码侧调，发送侧从来没调过）。
        // checkLength 的上限是 4MB ⇒ payloadLength 一定放得进 4 字节长度字段。
        GrpcFrame.checkLength(payloadLength);

        // ★ expand 可能换过底层数组 —— 必须重新取，不能用编码前缓存的引用
        byte[] buf = writer.buffer();
        GrpcFrame.writeHeader(buf, 0, payloadLength);
        sink.accept(buf, 0, total);
    }
}
