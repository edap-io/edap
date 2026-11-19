/*
 * Copyright 2026 The edap Project
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

package io.edap.grpc.benchmark;

import io.edap.erpc.codec.ErpcFrameEncoder;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcFrame;
import io.edap.grpc.GrpcFrameEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * eRPC 与 edap-gRPC 的<b>编解码 / 帧写出路径</b> micro 对比。
 *
 * <p>★ <b>这个对比要回答的问题是：「端到端的差距到底是不是编解码器造成的？」</b>
 * 答案预期是「不是」—— 两边用的是<b>同一套</b> protobuf 实现（edap 自研的
 * {@code ProtoBufCodecRegister} + {@code ProtoBufWriter}），连代码都是逐行相同的：</p>
 *
 * <table border="1">
 *   <caption>三档口径</caption>
 *   <tr><th>口径</th><th>gRPC 侧</th><th>eRPC 侧</th><th>用户态整块拷贝</th></tr>
 *   <tr><td>编码</td>
 *       <td>{@code GrpcCodec.encode}</td>
 *       <td>{@code ProtobufErpcCodec.encode} + 拷出借来的缓冲</td>
 *       <td>两边都是 2 次（写进 writer 缓冲 + 拷成 byte[]）</td></tr>
 *   <tr><td>解码</td>
 *       <td>{@code GrpcCodec.decode}</td>
 *       <td>{@code ProtobufErpcCodec.decode}</td>
 *       <td>两边同构（都是 {@code ProtoBuf.toObject}）</td></tr>
 *   <tr><td><b>帧写出</b></td>
 *       <td>{@code GrpcFrameEncoder.encode} → sink 拷走</td>
 *       <td>{@code ErpcFrameEncoder.encode} → sink 拷走</td>
 *       <td><b>两边都是 1 次</b>（2026-11-14 eRPC 补上了帧头内联）</td></tr>
 * </table>
 *
 * <p>★ <b>「编码」这一档要显式拷出才同构</b>：eRPC 的 {@code encode} 现在交出的是
 * <b>借来的</b>缓冲（不拷贝），gRPC 的 {@code GrpcCodec.encode} 交出的是<b>独立</b>
 * {@code byte[]}（拷一次）⇒ 基准里对 eRPC 侧显式拷一次，两臂才测的是同一件事
 * （「产出一份可长期持有的字节」）。</p>
 *
 * <p>★ <b>「帧写出」这一档是本对比的关键</b>：
 * gRPC 在 2026-11-13 做了「帧头内联」（{@code GrpcFrameEncoder}），
 * 编码器直接写进最终的目标缓冲 ⇒ 载荷只被拷一次；
 * eRPC 在 2026-11-14 补上了同一形态（{@code ErpcFrameEncoder}）——
 * 此前是「先 {@code encode} 产出 byte[]，再拷进待写缓冲」⇒ 载荷被拷两次。
 * 一次 64KB 的整块拷贝实测约 1.3µs —— 那就是大负载下 eRPC 落后的主要来源。</p>
 *
 * <p>★ <b>两臂口径必须同构</b>：两个 sink 都提前建好（不把 lambda 分配算进测量），
 * 都是「编码进 writer 缓冲 → 帧头写进目标缓冲 → 载荷拷进目标缓冲」。</p>
 */
public final class ErpcCodecCompare {

    /**
     * 一组对比结果。
     */
    public static final class CompareResult {
        public final int blobSize;
        /** 线上载荷字节数（不含帧头）。 */
        public final long payloadBytes;
        /** 两边编出来的字节是否逐字节相同（应当相同 —— 同一套 protobuf 实现）。 */
        public final boolean bytesIdentical;

        /** 编码（产出 byte[]）ns/op。 */
        public final double grpcEncodeNs;
        public final double erpcEncodeNs;
        /** 解码 ns/op。 */
        public final double grpcDecodeNs;
        public final double erpcDecodeNs;
        /** 帧写出（编码 + 把载荷搬进待写缓冲）ns/op。 */
        public final double grpcFrameWriteNs;
        public final double erpcFrameWriteNs;

        CompareResult(int blobSize, long payloadBytes, boolean bytesIdentical,
                      double grpcEncodeNs, double erpcEncodeNs,
                      double grpcDecodeNs, double erpcDecodeNs,
                      double grpcFrameWriteNs, double erpcFrameWriteNs) {
            this.blobSize = blobSize;
            this.payloadBytes = payloadBytes;
            this.bytesIdentical = bytesIdentical;
            this.grpcEncodeNs = grpcEncodeNs;
            this.erpcEncodeNs = erpcEncodeNs;
            this.grpcDecodeNs = grpcDecodeNs;
            this.erpcDecodeNs = erpcDecodeNs;
            this.grpcFrameWriteNs = grpcFrameWriteNs;
            this.erpcFrameWriteNs = erpcFrameWriteNs;
        }
    }

    private ErpcCodecCompare() {
    }

    public static List<CompareResult> run(int[] blobSizes, long millisPerCase) {
        List<CompareResult> results = new ArrayList<>();
        for (int blobSize : blobSizes) {
            results.add(runOne(blobSize, millisPerCase));
        }
        return results;
    }

    private static CompareResult runOne(int blobSize, long millisPerCase) {
        Payload payload = BenchPayloads.edap(blobSize);
        ErpcCodec serializer = new ProtobufErpcCodec();

        byte[] grpcBytes = GrpcCodec.encode(Payload.class, payload);
        byte[] erpcBytes = erpcBytes(serializer, payload);
        boolean identical = Arrays.equals(grpcBytes, erpcBytes);

        // 待写缓冲的目标数组（预分配，避免把分配成本算进拷贝那一档）
        byte[] target = new byte[Math.max(1024, grpcBytes.length + 64)];

        // ★ 两个 sink 都<b>提前建好</b>：两臂的额外开销必须完全同构，
        //   否则测出来的是「谁的 lambda 多分配了一次」，不是「谁的帧写出更快」。
        GrpcFrameEncoder.FrameSink grpcSink = (buf, off, len) -> System.arraycopy(buf, off, target, 0, len);
        ErpcFrameEncoder.FrameSink erpcSink = (header, buf, len) -> {
            header.writeTo(target, 0);
            System.arraycopy(buf, 0, target, ErpcFrameHeader.HEADER_LENGTH, len);
        };

        // 预热
        measure(300, () -> GrpcCodec.encode(Payload.class, payload).length);
        measure(300, () -> erpcBytes(serializer, payload).length);
        measure(300, () -> GrpcCodec.decode(Payload.class, grpcBytes, 0, grpcBytes.length).getValue());
        measure(300, () -> serializer.decode(grpcBytes, 0, grpcBytes.length, Payload.class).getValue());
        measure(300, () -> {
            GrpcFrameEncoder.encode(Payload.class, payload, grpcSink);
            return target[0];
        });
        measure(300, () -> {
            ErpcFrameEncoder.encode(serializer, ErpcFrameType.REQUEST.getValue(), serializer.id(),
                0, 0, 0, 0L, payload, erpcSink);
            return target[0];
        });

        final long[] sink = {0};

        double grpcEncodeNs = measure(millisPerCase, () -> {
            byte[] b = GrpcCodec.encode(Payload.class, payload);
            sink[0] += b.length;
            return b.length;
        });
        double erpcEncodeNs = measure(millisPerCase, () -> {
            byte[] b = erpcBytes(serializer, payload);
            sink[0] += b.length;
            return b.length;
        });
        double grpcDecodeNs = measure(millisPerCase, () -> {
            Payload p = GrpcCodec.decode(Payload.class, grpcBytes, 0, grpcBytes.length);
            sink[0] += p.getValue();
            return p.getValue();
        });
        double erpcDecodeNs = measure(millisPerCase, () -> {
            Payload p = serializer.decode(grpcBytes, 0, grpcBytes.length, Payload.class);
            sink[0] += p.getValue();
            return p.getValue();
        });

        // ★ 帧写出：两边都是「帧头内联」——
        //   gRPC：编码器写进 writer 缓冲（[0,5) 是帧头），sink 把整帧拷进目标缓冲；
        //   eRPC：编码器写进 writer 缓冲（载荷从 0 开始，<b>没有帧头占位</b>），
        //         sink 把帧头写进目标缓冲、把载荷拷进目标缓冲 ⇒ 载荷都只被拷一次。
        double grpcFrameWriteNs = measure(millisPerCase, () -> {
            GrpcFrameEncoder.encode(Payload.class, payload, grpcSink);
            sink[0] += target[0];
            return target[0];
        });
        double erpcFrameWriteNs = measure(millisPerCase, () -> {
            ErpcFrameEncoder.encode(serializer, ErpcFrameType.REQUEST.getValue(), serializer.id(),
                0, 0, 0, 0L, payload, erpcSink);
            sink[0] += target[0];
            return target[0];
        });
        if (sink[0] == Long.MIN_VALUE) {
            throw new IllegalStateException("不可能发生 —— 只是为了用掉 sink");
        }

        return new CompareResult(blobSize, grpcBytes.length, identical,
            grpcEncodeNs, erpcEncodeNs, grpcDecodeNs, erpcDecodeNs,
            grpcFrameWriteNs, erpcFrameWriteNs);
    }

    /**
     * eRPC 侧「产出一份可长期持有的字节」。
     *
     * <p>★ eRPC 的 {@code encode} 交出的是<b>借来的</b>缓冲（不拷贝），
     * 而 gRPC 的 {@code GrpcCodec.encode} 交出的是<b>独立</b> {@code byte[]}（拷一次）
     * ⇒ 「编码」这一档要比同一件事，就必须在这里显式拷一次。</p>
     */
    private static byte[] erpcBytes(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        return writer == null ? null : Arrays.copyOf(writer.buffer(), writer.size());
    }

    /**
     * 跑一段固定时长，返回每次操作的纳秒数。
     */
    private static double measure(long millis, Op op) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long ops = 0;
        long start = System.nanoTime();
        try {
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 100; i++) {
                    op.call();
                    ops++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("编解码 micro 基准失败", e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    /**
     * micro 操作。
     */
    private interface Op {
        long call() throws Exception;
    }
}
