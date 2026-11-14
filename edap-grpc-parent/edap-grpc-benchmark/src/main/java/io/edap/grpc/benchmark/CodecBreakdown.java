/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.edap.grpc.benchmark;

import com.google.protobuf.CodedOutputStream;
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcFrame;
import io.edap.grpc.GrpcFrameEncoder;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;

/**
 * 编解码开销的**分解**测量 —— 回答「edap 的编解码到底慢在哪一段」。
 *
 * <p>《编解码器 micro 对比》（{@code CodecBenchmark}）只给了两个数字：
 * edap 编码 vs google 编码、edap 解码 vs google 解码。
 * 这个数字差在 16KB / 64KB 上是 edap 慢 ~1.4x，但**没说差在哪一步**。
 * 这里把「编码」拆成两个口径，把「解码」拆成三个口径：</p>
 *
 * <table border="1">
 *   <caption>口径</caption>
 *   <tr><th>项目</th><th>做什么</th><th>拷贝次数</th></tr>
 *   <tr><td>edap byte[]</td><td>{@code GrpcCodec.encode} —— 写进 writer 缓冲，再拷成新数组</td><td>2</td></tr>
 *   <tr><td>edap 缓冲</td><td>直接写进 writer 缓冲，只读 {@code size()}（不产出 byte[]）</td><td>1</td></tr>
 *   <tr><td>google byte[]</td><td>{@code toByteArray()} —— 直接写进最终数组</td><td>1</td></tr>
 *   <tr><td>google 缓冲</td><td>{@code writeTo(CodedOutputStream)} 写进已有数组</td><td>1</td></tr>
 * </table>
 *
 * <p>「edap byte[] − edap 缓冲」= 那一次多余的整块拷贝；
 * 「edap 缓冲 vs google 缓冲」= 编解码核心（写字段）的真实差距。</p>
 */
public final class CodecBreakdown {

    private CodecBreakdown() {
    }

    public static void main(String[] args) throws Exception {
        int[] sizes = {0, 1024, 16384, 65536};
        long millis = 1500;

        System.out.printf("%-7s | %-14s %10s | %-14s %10s | %10s%n",
                "负载", "edap byte[]", "ns/op", "google byte[]", "ns/op", "比值");
        System.out.println("-".repeat(80));

        for (int size : sizes) {
            Payload ep = BenchPayloads.edap(size);
            Bench.Payload gp = BenchPayloads.google(size);
            byte[] eb = GrpcCodec.encode(Payload.class, ep);
            byte[] gb = gp.toByteArray();

            ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
            ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(Payload.class);
            byte[] big = new byte[Math.max(1024, gb.length + 64)];

            // 预热（顺便把 edap 的 writer 缓冲撑到足够大，之后不再增长）
            for (int i = 0; i < 2000; i++) {
                GrpcCodec.encode(Payload.class, ep);
                gp.toByteArray();
                CodecBenchmark.encodeIntoBuffer(writer, encoder, ep);

                gp.writeTo(CodedOutputStream.newInstance(big));
            }

            double edapByteArray = measure(millis, () -> GrpcCodec.encode(Payload.class, ep).length);
            double edapBuffer = measure(millis, () -> CodecBenchmark.encodeIntoBuffer(writer, encoder, ep));
            double googleByteArray = measure(millis, () -> gp.toByteArray().length);
            double googleBuffer = measure(millis, () -> {
                CodedOutputStream cos = CodedOutputStream.newInstance(big);
                gp.writeTo(cos);
                cos.flush();
                return gp.getSerializedSize();
            });

            System.out.printf("%-7s | %-14s %10.0f | %-14s %10.0f | %6.2fx%n",
                    sizeLabel(size), "", edapByteArray, "", googleByteArray,
                    googleByteArray == 0 ? 0 : edapByteArray / googleByteArray);
            System.out.printf("        |   edap 写缓冲(不产 byte[]) %8.0f |   google 写缓冲 %8.0f | %6.2fx%n",
                    edapBuffer, googleBuffer, googleBuffer == 0 ? 0 : edapBuffer / googleBuffer);
            System.out.printf("        |   多出来的那次拷贝 = %8.0f ns%n",
                    edapByteArray - edapBuffer);

            // 解码：GrpcCodec.decode 里除了 ProtoBuf.toObject 还多一层 validate()
            double edapDecode = measure(millis, () -> GrpcCodec.decode(Payload.class, eb, 0, eb.length).getValue());
            double edapDecodeRaw = measure(millis, () -> ProtoBuf.toObject(eb, 0, eb.length, Payload.class).getValue());
            double googleDecode = measure(millis, () -> Bench.Payload.parseFrom(gb).getValue());
            System.out.printf("        |   解码: edap %6.0f ns | edap(去掉validate) %6.0f ns | google %6.0f ns | %5.2fx%n",
                    edapDecode, edapDecodeRaw, googleDecode,
                    googleDecode == 0 ? 0 : edapDecode / googleDecode);

            // 参照：单独一次整块拷贝的代价
            byte[] tmp = new byte[eb.length];
            double copy = measure(millis, () -> {
                System.arraycopy(eb, 0, tmp, 0, eb.length);
                return tmp[0];
            });
            System.out.printf("        |   参照: System.arraycopy(%d 字节) = %.0f ns%n", eb.length, copy);

            // ── 真实链路口径 ─────────────────────────────────────────────
            // gRPC 一条响应的完整编码路径：
            //   edap 老路径: GrpcCodec.encode → byte[]   （拷贝2）
            //                GrpcFrame.encode → byte[]   （拷贝3）
            //   edap 新路径: GrpcFrameEncoder.encode —— 帧头内联进编码缓冲，
            //                一次编码直接产出「帧头 + 载荷」（拷贝2：writer 缓冲 → 目标缓冲）
            //   google     : message.writeTo(CodedOutputStream) 直接写进目标数组（1 次拷贝，零中间数组）
            byte[] frameTarget = new byte[Math.max(1024, gb.length + 64)];
            double edapChainNow = measure(millis, () -> GrpcFrame.encode(GrpcCodec.encode(Payload.class, ep)).length);
            // ★ 新路径（已实施）：帧头内联进编码缓冲，sink 当场把「帧头 + 载荷」拷进目标缓冲
            double edapChainNew = measure(millis, () -> {
                GrpcFrameEncoder.encode(Payload.class, ep, (buf, offset, length) ->
                        System.arraycopy(buf, offset, frameTarget, 0, length));
                return frameTarget.length;
            });
            double googleChain = measure(millis, () -> {
                CodedOutputStream cos = CodedOutputStream.newInstance(frameTarget);
                gp.writeTo(cos);
                cos.flush();
                return gp.getSerializedSize();
            });
            System.out.printf("        |   真实链路: edap老路径 %6.0f ns | edap新路径(帧头内联) %6.0f ns | google %6.0f ns | 老 %5.2fx / 新 %5.2fx%n",
                    edapChainNow, edapChainNew, googleChain,
                    googleChain == 0 ? 0 : edapChainNow / googleChain,
                    googleChain == 0 ? 0 : edapChainNew / googleChain);
            System.out.println();
        }
    }

    private static String sizeLabel(int size) {
        if (size >= 1024) {
            return (size / 1024) + "KB";
        }
        return size + "B";
    }

    private static double measure(long millis, Op op) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long ops = 0;
        long start = System.nanoTime();
        try {
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 50; i++) {
                    op.call();
                    ops++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    private interface Op {
        long call() throws Exception;
    }
}
