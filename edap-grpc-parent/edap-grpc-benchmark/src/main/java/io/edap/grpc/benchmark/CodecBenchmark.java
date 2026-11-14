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

import com.google.protobuf.CodedOutputStream;
import io.edap.grpc.GrpcCodec;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 编解码器的 micro 对比 —— edap 自研 protobuf vs google protobuf-java。
 *
 * <p><b>口径</b>（★ 两档编码口径不是同一件事，别混着比）：</p>
 * <table border="1">
 *   <caption>编码口径</caption>
 *   <tr><th>口径</th><th>edap</th><th>google</th><th>拷贝次数</th></tr>
 *   <tr><td><b>编码核心</b>（主口径）</td>
 *       <td>写进 writer 缓冲，只读 {@code size()}（不产出 byte[]）</td>
 *       <td>{@code writeTo(CodedOutputStream)} 写进已有数组</td><td>两边都是 1 次</td></tr>
 *   <tr><td>产出 byte[]</td>
 *       <td>{@code GrpcCodec.encode} = 写进 writer 缓冲 <b>+ 拷成新 byte[]</b></td>
 *       <td>{@code toByteArray()} = 直接写进最终数组，零中间数组</td>
 *       <td>edap 2 次 / google 1 次</td></tr>
 * </table>
 *
 * <p>⇒ 「产出 byte[]」这一档，edap 多做了一次整块拷贝，<b>拿它跟 google 比是不对等的</b>
 * （16KB 上差 686ns、64KB 上差 1656ns，而一次 64KB 的 {@code System.arraycopy} 实测 1335ns）。
 * 要比编解码器的能力，看「编码核心」那一档。</p>
 *
 * <p>解码两边同构：edap 是 {@code GrpcCodec.decode}；google 是 {@code Bench.Payload.parseFrom}。</p>
 *
 * <p>另外会断言两边编码出来的字节<b>逐字节相同</b> —— 这是 wire 格式兼容的
 * 最直接证据（互操作的必要条件）。</p>
 */
public final class CodecBenchmark {

    /**
     * 一组编解码对比结果。
     */
    public static final class CodecResult {
        public final int blobSize;
        public final long wireBytes;
        public final boolean wireIdentical;
        /** 对等口径：edap 只写进 writer 缓冲（不产出 byte[]）。 */
        public final double edapEncodeCoreNs;
        /** 对等口径：google writeTo 写进已有数组。 */
        public final double googleEncodeCoreNs;
        /** 产出 byte[] 口径：edap 的 {@code GrpcCodec.encode}（含那次整块拷贝）。 */
        public final double edapEncodeNs;
        public final double googleEncodeNs;
        public final double edapDecodeNs;
        public final double googleDecodeNs;

        CodecResult(int blobSize, long wireBytes, boolean wireIdentical,
                    double edapEncodeCoreNs, double googleEncodeCoreNs,
                    double edapEncodeNs, double googleEncodeNs,
                    double edapDecodeNs, double googleDecodeNs) {
            this.blobSize = blobSize;
            this.wireBytes = wireBytes;
            this.wireIdentical = wireIdentical;
            this.edapEncodeCoreNs = edapEncodeCoreNs;
            this.googleEncodeCoreNs = googleEncodeCoreNs;
            this.edapEncodeNs = edapEncodeNs;
            this.googleEncodeNs = googleEncodeNs;
            this.edapDecodeNs = edapDecodeNs;
            this.googleDecodeNs = googleDecodeNs;
        }
    }

    private CodecBenchmark() {
    }

    /**
     * 跑一轮编解码对比。
     *
     * @param blobSizes     负载矩阵
     * @param millisPerCase 每个用例的测量时长
     */
    public static List<CodecResult> run(int[] blobSizes, long millisPerCase) {
        List<CodecResult> results = new ArrayList<>();
        for (int blobSize : blobSizes) {
            results.add(runOne(blobSize, millisPerCase));
        }
        return results;
    }

    private static CodecResult runOne(int blobSize, long millisPerCase) {
        Payload edapPayload = BenchPayloads.edap(blobSize);
        Bench.Payload googlePayload = BenchPayloads.google(blobSize);

        byte[] edapBytes = GrpcCodec.encode(Payload.class, edapPayload);
        byte[] googleBytes = googlePayload.toByteArray();
        boolean identical = Arrays.equals(edapBytes, googleBytes);

        // 对等口径的目标数组：google 的 writeTo 直接写进它，零中间数组
        byte[] googleTarget = new byte[Math.max(1024, googleBytes.length + 64)];
        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(Payload.class);

        // 预热
        measure(300, () -> GrpcCodec.encode(Payload.class, edapPayload).length);
        measure(300, () -> googlePayload.toByteArray().length);
        measure(300, () -> encodeIntoBuffer(writer, encoder, edapPayload));
        measure(300, () -> {
            CodedOutputStream cos = CodedOutputStream.newInstance(googleTarget);
            googlePayload.writeTo(cos);
            cos.flush();
            return cos.getTotalBytesWritten();
        });
        measure(300, () -> GrpcCodec.decode(Payload.class, edapBytes, 0, edapBytes.length).getValue());
        measure(300, () -> Bench.Payload.parseFrom(googleBytes).getValue());

        // sink 用来防止 JIT 把结果当成无用计算优化掉（数组长度要被用掉）
        final long[] sink = {0};
        double edapEncodeCoreNs = measure(millisPerCase, () -> {
            int len = encodeIntoBuffer(writer, encoder, edapPayload);
            sink[0] += len;
            return len;
        });
        double googleEncodeCoreNs = measure(millisPerCase, () -> {
            CodedOutputStream cos = CodedOutputStream.newInstance(googleTarget);
            googlePayload.writeTo(cos);
            cos.flush();
            long len = cos.getTotalBytesWritten();
            sink[0] += len;
            return len;
        });
        double edapEncodeNs = measure(millisPerCase, () -> {
            byte[] b = GrpcCodec.encode(Payload.class, edapPayload);
            sink[0] += b.length;
            return b.length;
        });
        double googleEncodeNs = measure(millisPerCase, () -> {
            byte[] b = googlePayload.toByteArray();
            sink[0] += b.length;
            return b.length;
        });
        double edapDecodeNs = measure(millisPerCase, () -> {
            Payload p = GrpcCodec.decode(Payload.class, edapBytes, 0, edapBytes.length);
            sink[0] += p.getValue();
            return p.getValue();
        });
        double googleDecodeNs = measure(millisPerCase, () -> {
            Bench.Payload p = Bench.Payload.parseFrom(googleBytes);
            sink[0] += p.getValue();
            return p.getValue();
        });
        if (sink[0] == Long.MIN_VALUE) {
            throw new IllegalStateException("不可能发生 —— 只是为了用掉 sink");
        }

        return new CodecResult(blobSize, edapBytes.length, identical,
                edapEncodeCoreNs, googleEncodeCoreNs,
                edapEncodeNs, googleEncodeNs, edapDecodeNs, googleDecodeNs);
    }

    /**
     * 把 edap 的消息编码进 writer 缓冲，只返回写入的字节数（<b>不产出 byte[]</b>）——
     * 这是「编码核心」的对等口径。
     */
    static int encodeIntoBuffer(ProtoBufWriter writer, ProtoBufEncoder encoder, Payload p) {
        try {
            writer.reset();
            writer.getBufOut().reset();
            encoder.encode(writer, p);
            return writer.size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
