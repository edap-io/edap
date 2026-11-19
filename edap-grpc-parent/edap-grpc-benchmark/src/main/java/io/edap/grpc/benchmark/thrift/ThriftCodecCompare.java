/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.grpc.benchmark.thrift;

import io.edap.grpc.GrpcCodec;
import io.edap.grpc.benchmark.BenchPayloads;
import io.edap.grpc.benchmark.Payload;
import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Thrift 与 protobuf 的<b>编解码成本</b> micro 对比。
 *
 * <p>★ <b>这个对比要回答的问题</b>：端到端的差距里，有多少来自「编解码器」？
 * 前面三个臂（eRPC / edap-gRPC / grpc-java）用的是同一套 protobuf 实现，
 * 编解码成本互相抵消 ⇒ 端到端的差距<b>不可能</b>是 protobuf 编解码造成的。
 * Thrift 臂换了序列化体系 ⇒ 必须单独把编解码拆出来量。</p>
 *
 * <p><b>三条口径</b>：thrift binary / thrift compact / protobuf。</p>
 *
 * <p>★ <b>口径说明（2026-11-15 修正）</b>：</p>
 * <ul>
 *   <li><b>thrift 的编码</b>：直接写进带缓冲的输出流，<b>不拷出 byte[]</b>
 *       —— 这就是 thrift 真实路径的成本；</li>
 *   <li><b>protobuf 的编码</b>：本类同时量两个口径 ——
 *       <b>口径 A</b>：{@code GrpcCodec.encode}（末尾 {@code writer.toByteArray()}），
 *       <b>口径 B</b>：只 encode、不 toByteArray。
 *       ★ <b>口径 B 才是 eRPC/gRPC 真实线上路径的成本</b>：
 *       eRPC 的 {@code ProtobufErpcCodec.encode} 借出 {@code ProtoBufWriter} 的线程本地缓冲、
 *       gRPC 的 {@code GrpcFrameEncoder.encode} 把 5 字节帧头内联在同一块缓冲里
 *       ⇒ <b>两者的载荷都只被拷一次</b>。
 *       ⇒ <b>口径 A 多付的那一次「分配 + 整块拷贝」在线上路径上并不存在</b>。</li>
 *   <li><b>thrift 的解码</b>：本类同时量两个口径 ——
 *       <b>手写 DTO</b>（blob 声明成 {@code byte[]}，多一次 {@code new byte[]} + {@code get()}）
 *       与 <b>0.14 形态</b>（blob 保持 {@code ByteBuffer}，即 thrift 生成代码的真实形态）。
 *       ⇒ <b>0.14 形态才是 thrift 真实路径的成本</b>。</li>
 *   <li>⇒ <b>「净增」必须用「口径 B + 0.14 形态」算</b>，否则量到的是「编解码逻辑 + 内存拷贝」的混合体。</li>
 * </ul>
 */
public final class ThriftCodecCompare {

    /**
     * 一组对比结果。
     */
    public static final class CompareResult {
        public final int blobSize;

        /** 三种编码各自的字节数（载荷，不含任何帧头）。 */
        public final int protobufBytes;
        public final int thriftBinaryBytes;
        public final int thriftCompactBytes;

        /** 编码 ns/op。 */
        public final double protobufEncodeNs;
        public final double thriftBinaryEncodeNs;
        public final double thriftCompactEncodeNs;

        /**
         * ★ protobuf 编码 ns（<b>口径 B：只 encode，不 toByteArray</b>）——
         * 这才是 eRPC / gRPC <b>真实线上路径</b>的成本：
         * <ul>
         *   <li>eRPC：{@code ProtobufErpcCodec.encode} 借出 {@code ProtoBufWriter}
         *       的线程本地缓冲（不拷出）；</li>
         *   <li>gRPC：{@code GrpcFrameEncoder.encode} 把 5 字节帧头内联在同一块缓冲里（不拷出）。</li>
         * </ul>
         * ⇒ 两者的载荷都<b>只被拷一次</b>（拷进出站 FastBuf 那一次）。
         * 而 {@code GrpcCodec.encode} 末尾的 {@code writer.toByteArray()}
         * 额外多付「1 分配 + 1 整块拷贝」—— <b>那一次在真实路径上并不存在</b>。
         */
        public final double protobufEncodeNoCopyNs;

        /** 解码 ns/op。 */
        public final double protobufDecodeNs;
        public final double thriftBinaryDecodeNs;
        public final double thriftCompactDecodeNs;

        /**
         * ★ thrift 解码 ns（<b>thrift 0.14 生成代码形态</b>：blob 保持 {@code ByteBuffer}）
         * —— 本基准手写的 {@link ThriftPayload} 把 blob 声明成 {@code byte[]}，
         * 多付了一次「{@code new byte[]} + {@code buffer.get()}」的分配+拷贝，
         * 那是<b>手写代码引入的</b>，不是 thrift 协议的固有成本。
         */
        public final double thriftBinaryDecodeBbNs;
        public final double thriftCompactDecodeBbNs;

        CompareResult(int blobSize, int protobufBytes, int thriftBinaryBytes, int thriftCompactBytes,
                      double protobufEncodeNs, double protobufEncodeNoCopyNs,
                      double thriftBinaryEncodeNs, double thriftCompactEncodeNs,
                      double protobufDecodeNs, double thriftBinaryDecodeNs, double thriftCompactDecodeNs,
                      double thriftBinaryDecodeBbNs, double thriftCompactDecodeBbNs) {
            this.blobSize = blobSize;
            this.protobufBytes = protobufBytes;
            this.thriftBinaryBytes = thriftBinaryBytes;
            this.thriftCompactBytes = thriftCompactBytes;
            this.protobufEncodeNs = protobufEncodeNs;
            this.protobufEncodeNoCopyNs = protobufEncodeNoCopyNs;
            this.thriftBinaryEncodeNs = thriftBinaryEncodeNs;
            this.thriftCompactEncodeNs = thriftCompactEncodeNs;
            this.protobufDecodeNs = protobufDecodeNs;
            this.thriftBinaryDecodeNs = thriftBinaryDecodeNs;
            this.thriftCompactDecodeNs = thriftCompactDecodeNs;
            this.thriftBinaryDecodeBbNs = thriftBinaryDecodeBbNs;
            this.thriftCompactDecodeBbNs = thriftCompactDecodeBbNs;
        }

        /**
         * ★ <b>公平口径下的净增</b>（单边 ns）——
         * 双方都用各自<b>真实线上路径</b>的形态：
         * <ul>
         *   <li>protobuf 编码：只 encode，不 toByteArray（eRPC/gRPC 真实路径）；</li>
         *   <li>thrift 解码：blob 保持 ByteBuffer（thrift 0.14 生成代码形态）。</li>
         * </ul>
         */
        public double fairBinaryDeltaNs() {
            return (thriftBinaryEncodeNs + thriftBinaryDecodeBbNs)
                - (protobufEncodeNoCopyNs + protobufDecodeNs);
        }

        public double fairCompactDeltaNs() {
            return (thriftCompactEncodeNs + thriftCompactDecodeBbNs)
                - (protobufEncodeNoCopyNs + protobufDecodeNs);
        }

        /** 原报告口径的净增（protobuf 编码带 toByteArray + thrift 解码用手写 DTO）。 */
        public double rawBinaryDeltaNs() {
            return (thriftBinaryEncodeNs + thriftBinaryDecodeNs)
                - (protobufEncodeNs + protobufDecodeNs);
        }

        public double rawCompactDeltaNs() {
            return (thriftCompactEncodeNs + thriftCompactDecodeNs)
                - (protobufEncodeNs + protobufDecodeNs);
        }
    }

    private ThriftCodecCompare() {
    }

    public static List<CompareResult> run(int[] blobSizes, long millisPerCase) throws Exception {
        List<CompareResult> results = new ArrayList<>();
        for (int blobSize : blobSizes) {
            results.add(runOne(blobSize, millisPerCase));
        }
        return results;
    }

    private static CompareResult runOne(int blobSize, long millisPerCase) throws Exception {
        Payload protobufPayload = BenchPayloads.edap(blobSize);
        ThriftPayload thriftPayload = new ThriftPayload(
            protobufPayload.getName(), protobufPayload.getValue(), protobufPayload.getBlob());
        // ★ thrift 0.14 生成代码形态的 DTO（blob 保持 ByteBuffer）
        //   —— 它写出来的 wire 字节与 thriftPayload 完全相同（blob 都是 binary 字段），
        //   ⇒ 解码臂可以直接复用同一份字节。
        ThriftPayloadBB thriftPayloadBB = new ThriftPayloadBB(
            protobufPayload.getName(), protobufPayload.getValue(), protobufPayload.getBlob());

        // ---- protobuf 侧：edap 自研实现（与 eRPC / gRPC 臂用的是同一套） ----
        byte[] protobufBytes = GrpcCodec.encode(Payload.class, protobufPayload);

        // ---- thrift 侧：binary / compact 两套协议 ----
        ResettableOutputStream binaryOut = new ResettableOutputStream();
        TProtocol binaryWriter = ThriftBenchServer.protocolFactory(
            ThriftBenchServer.PROTOCOL_BINARY).getProtocol(
                new org.apache.thrift.transport.TIOStreamTransport(binaryOut));
        thriftPayload.write(binaryWriter);
        binaryWriter.getTransport().flush();
        byte[] thriftBinaryBytes = binaryOut.toByteArray();

        ResettableOutputStream compactOut = new ResettableOutputStream();
        TProtocol compactWriter = ThriftBenchServer.protocolFactory(
            ThriftBenchServer.PROTOCOL_COMPACT).getProtocol(
                new org.apache.thrift.transport.TIOStreamTransport(compactOut));
        thriftPayload.write(compactWriter);
        compactWriter.getTransport().flush();
        byte[] thriftCompactBytes = compactOut.toByteArray();

        // ---- 解码端：输入流可复位 ⇒ 每轮都从同一份字节重新解 ----
        ByteArrayInputStream binaryIn = new ByteArrayInputStream(thriftBinaryBytes);
        TProtocol binaryReader = ThriftBenchServer.protocolFactory(
            ThriftBenchServer.PROTOCOL_BINARY).getProtocol(
                new org.apache.thrift.transport.TIOStreamTransport(binaryIn));
        ByteArrayInputStream compactIn = new ByteArrayInputStream(thriftCompactBytes);
        TProtocol compactReader = ThriftBenchServer.protocolFactory(
            ThriftBenchServer.PROTOCOL_COMPACT).getProtocol(
                new org.apache.thrift.transport.TIOStreamTransport(compactIn));
        // ★ 0.14 形态的解码臂：同一个 reader，只是 DTO 不同（blob 保持 ByteBuffer）
        ByteArrayInputStream binaryBbIn = new ByteArrayInputStream(thriftBinaryBytes);
        TProtocol binaryBbReader = ThriftBenchServer.protocolFactory(
            ThriftBenchServer.PROTOCOL_BINARY).getProtocol(
                new org.apache.thrift.transport.TIOStreamTransport(binaryBbIn));
        ByteArrayInputStream compactBbIn = new ByteArrayInputStream(thriftCompactBytes);
        TProtocol compactBbReader = ThriftBenchServer.protocolFactory(
            ThriftBenchServer.PROTOCOL_COMPACT).getProtocol(
                new org.apache.thrift.transport.TIOStreamTransport(compactBbIn));

        // ---- 预热 ----
        measure(300, () -> GrpcCodec.encode(Payload.class, protobufPayload).length);
        measure(300, () -> GrpcCodec.decode(Payload.class, protobufBytes, 0, protobufBytes.length).getValue());
        measure(300, () -> {
            binaryOut.clearBuffer();
            thriftPayload.write(binaryWriter);
            return binaryOut.size();
        });
        measure(300, () -> {
            compactOut.clearBuffer();
            thriftPayload.write(compactWriter);
            return compactOut.size();
        });
        measure(300, () -> {
            binaryIn.reset();
            ThriftPayload p = new ThriftPayload();
            p.read(binaryReader);
            return p.getValue();
        });
        measure(300, () -> {
            compactIn.reset();
            ThriftPayload p = new ThriftPayload();
            p.read(compactReader);
            return p.getValue();
        });
        measure(300, () -> {
            binaryBbIn.reset();
            ThriftPayloadBB p = new ThriftPayloadBB();
            p.read(binaryBbReader);
            return p.getValue();
        });
        measure(300, () -> {
            compactBbIn.reset();
            ThriftPayloadBB p = new ThriftPayloadBB();
            p.read(compactBbReader);
            return p.getValue();
        });

        final long[] sink = {0};

        double protobufEncodeNs = measure(millisPerCase, () -> {
            byte[] bytes = GrpcCodec.encode(Payload.class, protobufPayload);
            sink[0] += bytes.length;
            return bytes.length;
        });
        // ★ 口径 B：只 encode、不 toByteArray —— eRPC/gRPC 真实线上路径的形态
        //   （eRPC 借出线程本地缓冲；gRPC 帧头内联在同一块缓冲里 ⇒ 载荷只拷一次）
        double protobufEncodeNoCopyNs = measure(millisPerCase, () -> {
            ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
            writer.reset();
            BufOut out = writer.getBufOut();
            out.reset();
            try {
                ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(Payload.class);
                encoder.encode(writer, protobufPayload);
                int len = writer.size();
                sink[0] += len;
                return len;
            } catch (EncodeException e) {
                throw new IllegalStateException(e);
            } finally {
                out.reset();
            }
        });
        double thriftBinaryEncodeNs = measure(millisPerCase, () -> {
            binaryOut.clearBuffer();
            thriftPayload.write(binaryWriter);
            sink[0] += binaryOut.size();
            return binaryOut.size();
        });
        double thriftCompactEncodeNs = measure(millisPerCase, () -> {
            compactOut.clearBuffer();
            thriftPayload.write(compactWriter);
            sink[0] += compactOut.size();
            return compactOut.size();
        });

        double protobufDecodeNs = measure(millisPerCase, () -> {
            Payload p = GrpcCodec.decode(Payload.class, protobufBytes, 0, protobufBytes.length);
            sink[0] += p.getValue();
            return p.getValue();
        });
        double thriftBinaryDecodeNs = measure(millisPerCase, () -> {
            binaryIn.reset();
            ThriftPayload p = new ThriftPayload();
            p.read(binaryReader);
            sink[0] += p.getValue();
            return p.getValue();
        });
        double thriftCompactDecodeNs = measure(millisPerCase, () -> {
            compactIn.reset();
            ThriftPayload p = new ThriftPayload();
            p.read(compactReader);
            sink[0] += p.getValue();
            return p.getValue();
        });
        // ★ thrift 0.14 生成代码形态的解码（blob 保持 ByteBuffer ⇒ 不多付那次分配+拷贝）
        double thriftBinaryDecodeBbNs = measure(millisPerCase, () -> {
            binaryBbIn.reset();
            ThriftPayloadBB p = new ThriftPayloadBB();
            p.read(binaryBbReader);
            return p.getValue();
        });
        double thriftCompactDecodeBbNs = measure(millisPerCase, () -> {
            compactBbIn.reset();
            ThriftPayloadBB p = new ThriftPayloadBB();
            p.read(compactBbReader);
            return p.getValue();
        });
        if (sink[0] == Long.MIN_VALUE) {
            throw new IllegalStateException("不可能发生 —— 只是为了用掉 sink");
        }

        return new CompareResult(blobSize, protobufBytes.length, thriftBinaryBytes.length, thriftCompactBytes.length,
            protobufEncodeNs, protobufEncodeNoCopyNs,
            thriftBinaryEncodeNs, thriftCompactEncodeNs,
            protobufDecodeNs, thriftBinaryDecodeNs, thriftCompactDecodeNs,
            thriftBinaryDecodeBbNs, thriftCompactDecodeBbNs);
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

    /**
     * 可复位的 {@link ByteArrayOutputStream} —— 每次编码前把长度归零，
     * 这样编码这一档就不会把「分配一个新数组」算进去。
     *
     * <p>★ 方法名不能叫 {@code reset()} —— {@link ByteArrayOutputStream#reset()} 是
     * {@code public void reset() throws IOException}，同名会撞签名。</p>
     */
    private static final class ResettableOutputStream extends ByteArrayOutputStream {
        void clearBuffer() {
            count = 0;
        }
    }
}
