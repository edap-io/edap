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
import io.edap.grpc.benchmark.BenchConstants;
import io.edap.grpc.benchmark.BenchPayloads;
import io.edap.grpc.benchmark.Payload;
import io.edap.io.BufOut;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import org.apache.thrift.protocol.TProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * ★ 归因探针：**thrift binary 为什么「编码快、解码慢」？**
 *
 * <p><b>现象</b>（四臂 micro，64KB blob）：thrift bin 编码 1655 ns，解码 5892 ns（3.6 倍）；
 * 而 protobuf 是编码 3070 ns、解码 1742 ns（解码反而更快）。</p>
 *
 * <p><b>本探针把「分配次数 × 拷贝次数」拆开</b>（同一条 blob 字段，隔离 JVM 取 min）：</p>
 * <table border="1">
 *   <caption>六个臂</caption>
 *   <tr><th>臂</th><th>做什么</th><th>分配</th><th>拷贝</th></tr>
 *   <tr><td><b>alloc</b></td><td>{@code new byte[size]}</td><td>1（分配+清零）</td><td>0</td></tr>
 *   <tr><td><b>thrift-encode</b></td><td>{@code writeBinary} 进复用输出流</td><td>0</td><td>1</td></tr>
 *   <tr><td><b>thrift-decode-bb</b></td><td>{@code readBinary()} → 拿 {@link ByteBuffer}
 *       （<b>thrift 0.14 生成代码形态</b>：binary 字段就是 ByteBuffer）</td><td>1</td><td>1</td></tr>
 *   <tr><td><b>thrift-decode-arr</b></td><td>{@code readBinary()} → 再 {@code new byte[]} + {@code get()}
 *       （<b>本基准手写 DTO 的形态</b>）</td><td>2</td><td>2</td></tr>
 *   <tr><td><b>protobuf-encode</b></td><td>edap protobuf 编码整条消息</td><td>1</td><td>1(+1)</td></tr>
 *   <tr><td><b>protobuf-decode</b></td><td>edap protobuf 解码整条消息</td><td>1</td><td>1</td></tr>
 * </table>
 *
 * <p>★ <b>要回答的两个问题</b>：① thrift 解码的额外成本来自「协议解析」还是「多分配/多拷贝」？
 * ② 我们手写的 {@link ThriftPayload} 是否比 thrift 自己生成的代码多付了一次 64KB 拷贝？</p>
 *
 * <p><b>口径</b>：一个候选一个 JVM；候选顺序按 launch 轮转；JVM 内多轮取 min。</p>
 */
public final class ThriftCodecAsymmetry {

    /** 累加被测结果 —— 防止 JIT 消掉无用计算。 */
    private static long sink;

    /** 让数组逃逸用的静态字段（否则 JIT 会把分配标量替换掉）。 */
    private static volatile byte[] escaped;

    private ThriftCodecAsymmetry() {
    }

    public static void main(String[] args) throws Exception {
        String candidate = null;
        long millis = 400L;
        int rounds = 5;
        int size = 65536;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                key = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            switch (key) {
                case "--candidate":
                    candidate = value != null ? value : args[++i];
                    break;
                case "--millis":
                    millis = Long.parseLong(value != null ? value : args[++i]);
                    break;
                case "--rounds":
                    rounds = Integer.parseInt(value != null ? value : args[++i]);
                    break;
                case "--size":
                    size = Integer.parseInt(value != null ? value : args[++i]);
                    break;
                default:
                    System.out.println("未知参数: " + arg + "（忽略）");
                    break;
            }
        }

        if (candidate == null) {
            System.out.println("必须给 --candidate=<名字>");
            for (String c : new String[] {"alloc", "alloc-escape", "thrift-encode", "thrift-decode-bb",
                    "thrift-decode-arr", "protobuf-encode", "protobuf-encode-nocopy", "protobuf-decode"}) {
                System.out.println("  " + c);
            }
            return;
        }

        Op op = op(candidate, size);
        for (int i = 0; i < 2000; i++) {
            op.call();
        }
        long best = Long.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            long v = measureOnce(millis, op);
            if (v < best) {
                best = v;
            }
        }
        System.out.printf("RESULT candidate=%s size=%d ns=%d%n", candidate, size, best);
    }

    private interface Op {
        long call() throws Exception;
    }

    private static Op op(String candidate, int size) throws Exception {
        switch (candidate) {
            case "alloc": {
                return () -> {
                    byte[] b = new byte[size];
                    sink += b.length;
                    return b.length;
                };
            }
            case "alloc-escape": {
                // ★ 数组必须逃逸（存进静态字段）⇒ JIT 不能把它标量替换掉
                //   ⇒ 这一档才量得到「真实的分配 + 清零」成本
                return () -> {
                    byte[] b = new byte[size];
                    escaped = b;
                    sink += b.length;
                    return b.length;
                };
            }
            case "thrift-encode": {
                byte[] blob = BenchPayloads.blob(size);
                Resettable out = new Resettable();
                TProtocol p = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                return () -> {
                    out.clearBuffer();
                    p.writeBinary(ByteBuffer.wrap(blob));
                    p.getTransport().flush();
                    sink += out.size();
                    return out.size();
                };
            }
            case "thrift-decode-bb": {
                byte[] blob = BenchPayloads.blob(size);
                Resettable out = new Resettable();
                TProtocol w = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                w.writeBinary(ByteBuffer.wrap(blob));
                w.getTransport().flush();
                byte[] bytes = out.toByteArray();
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                TProtocol r = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(in));
                return () -> {
                    in.reset();
                    // ★ thrift 0.14 生成代码形态：binary 字段就是 ByteBuffer，不额外拷一次
                    ByteBuffer bb = r.readBinary();
                    sink += bb.remaining();
                    return bb.remaining();
                };
            }
            case "thrift-decode-arr": {
                byte[] blob = BenchPayloads.blob(size);
                Resettable out = new Resettable();
                TProtocol w = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                w.writeBinary(ByteBuffer.wrap(blob));
                w.getTransport().flush();
                byte[] bytes = out.toByteArray();
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                TProtocol r = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(in));
                return () -> {
                    in.reset();
                    // ★ 本基准手写 DTO 的形态：ByteBuffer → new byte[] → get()（多一次分配 + 拷贝）
                    ByteBuffer bb = r.readBinary();
                    byte[] arr = new byte[bb.remaining()];
                    bb.get(arr);
                    sink += arr.length;
                    return arr.length;
                };
            }
            case "protobuf-encode": {
                Payload msg = BenchPayloads.edap(size);
                return () -> {
                    byte[] bytes = GrpcCodec.encode(Payload.class, msg);
                    sink += bytes.length;
                    return bytes.length;
                };
            }
            case "protobuf-encode-nocopy": {
                // ★★ 「只 encode，不 toByteArray」—— 这才是 eRPC / gRPC **真实线上路径**的口径：
                //   eRPC：ProtobufErpcCodec.encode 借出 ProtoBufWriter 的线程本地缓冲（不拷出）
                //   gRPC：GrpcFrameEncoder.encode 把帧头内联在同一块缓冲里（不拷出）
                //   ⇒ 两者载荷都只被拷一次（拷进出站 FastBuf 那一次）
                // 而 GrpcCodec.encode 末尾的 writer.toByteArray() 额外多付「1 分配 + 1 整块拷贝」
                //   ⇒ 那一次在真实路径上并不存在。
                Payload msg = BenchPayloads.edap(size);
                return () -> {
                    ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
                    writer.reset();
                    BufOut out = writer.getBufOut();
                    out.reset();
                    try {
                        ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(Payload.class);
                        encoder.encode(writer, msg);
                        int len = writer.size();
                        sink += len;
                        return len;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        out.reset();
                    }
                };
            }
            case "protobuf-decode": {
                Payload msg = BenchPayloads.edap(size);
                byte[] bytes = GrpcCodec.encode(Payload.class, msg);
                return () -> {
                    Payload p = GrpcCodec.decode(Payload.class, bytes, 0, bytes.length);
                    sink += p.getValue();
                    return p.getValue();
                };
            }
            // ---- 整条消息（与四臂报告的 micro 同口径）----
            case "thrift-full-encode": {
                ThriftPayload msg = new ThriftPayload(BenchConstants.NAME_PREFIX
                    + BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE,
                    BenchPayloads.blob(size));
                Resettable out = new Resettable();
                TProtocol p = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                return () -> {
                    out.clearBuffer();
                    msg.write(p);
                    p.getTransport().flush();
                    sink += out.size();
                    return out.size();
                };
            }
            case "thrift-full-decode-arr": {
                ThriftPayload msg = new ThriftPayload(BenchConstants.NAME_PREFIX
                    + BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE,
                    BenchPayloads.blob(size));
                Resettable out = new Resettable();
                TProtocol w = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                msg.write(w);
                w.getTransport().flush();
                byte[] bytes = out.toByteArray();
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                TProtocol r = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(in));
                return () -> {
                    in.reset();
                    ThriftPayload p = new ThriftPayload();
                    p.read(r);
                    sink += p.getValue();
                    return p.getValue();
                };
            }
            case "thrift-full-decode-bb": {
                ThriftPayload msg = new ThriftPayload(BenchConstants.NAME_PREFIX
                    + BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE,
                    BenchPayloads.blob(size));
                Resettable out = new Resettable();
                TProtocol w = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                msg.write(w);
                w.getTransport().flush();
                byte[] bytes = out.toByteArray();
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                TProtocol r = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_BINARY)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(in));
                return () -> {
                    in.reset();
                    // ★ blob 保持 ByteBuffer（thrift 0.14 生成代码形态）⇒ 不多付那次分配+拷贝
                    ThriftPayloadBB p = new ThriftPayloadBB();
                    p.read(r);
                    sink += p.getValue();
                    return p.getValue();
                };
            }
            case "thrift-compact-full-encode": {
                ThriftPayload msg = new ThriftPayload(BenchConstants.NAME_PREFIX
                    + BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE,
                    BenchPayloads.blob(size));
                Resettable out = new Resettable();
                TProtocol p = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_COMPACT)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                return () -> {
                    out.clearBuffer();
                    msg.write(p);
                    p.getTransport().flush();
                    sink += out.size();
                    return out.size();
                };
            }
            case "thrift-compact-full-decode-arr": {
                ThriftPayload msg = new ThriftPayload(BenchConstants.NAME_PREFIX
                    + BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE,
                    BenchPayloads.blob(size));
                Resettable out = new Resettable();
                TProtocol w = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_COMPACT)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                msg.write(w);
                w.getTransport().flush();
                byte[] bytes = out.toByteArray();
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                TProtocol r = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_COMPACT)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(in));
                return () -> {
                    in.reset();
                    ThriftPayload p = new ThriftPayload();
                    p.read(r);
                    sink += p.getValue();
                    return p.getValue();
                };
            }
            case "thrift-compact-full-decode-bb": {
                ThriftPayload msg = new ThriftPayload(BenchConstants.NAME_PREFIX
                    + BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE,
                    BenchPayloads.blob(size));
                Resettable out = new Resettable();
                TProtocol w = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_COMPACT)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(out));
                msg.write(w);
                w.getTransport().flush();
                byte[] bytes = out.toByteArray();
                ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                TProtocol r = ThriftBenchServer.protocolFactory(ThriftBenchServer.PROTOCOL_COMPACT)
                    .getProtocol(new org.apache.thrift.transport.TIOStreamTransport(in));
                return () -> {
                    in.reset();
                    ThriftPayloadBB p = new ThriftPayloadBB();
                    p.read(r);
                    sink += p.getValue();
                    return p.getValue();
                };
            }
            default:
                throw new IllegalArgumentException("未知候选: " + candidate);
        }
    }

    private static long measureOnce(long millis, Op op) {
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
            throw new IllegalStateException("探针失败", e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : elapsed / ops;
    }

    /** 可复位的输出流（复用缓冲 ⇒ 编码这一档不把分配算进去）。 */
    private static final class Resettable extends ByteArrayOutputStream {
        void clearBuffer() {
            count = 0;
        }
    }

    /**
     * ★ 「blob 保持 ByteBuffer」的 DTO 变体 —— <b>thrift 0.14 生成代码形态</b>。
     *
     * <p>thrift 0.14 的 {@code TProtocol.readBinary()} 返回 {@link ByteBuffer}
     * ⇒ 生成代码里 binary 字段就是 ByteBuffer，直接赋值 ⇒ <b>不多付那次
     * 「new byte[] + get()」</b>。</p>
     *
     * <p>本基准手写的 {@link ThriftPayload} 把字段声明成 {@code byte[]}，
     * 于是多了一次 {@code new byte[remaining]} + {@code buffer.get(...)}
     * —— 那是<b>手写代码引入的</b>，不是 thrift 协议的固有成本。</p>
     */
    private static final class ThriftPayloadBB {
        private String name;
        private int value;
        private ByteBuffer blob;

        void read(TProtocol iprot) throws org.apache.thrift.TException {
            iprot.readStructBegin();
            while (true) {
                org.apache.thrift.protocol.TField field = iprot.readFieldBegin();
                if (field.type == org.apache.thrift.protocol.TType.STOP) {
                    break;
                }
                switch (field.id) {
                    case 1:
                        this.name = iprot.readString();
                        break;
                    case 2:
                        this.value = iprot.readI32();
                        break;
                    case 3:
                        this.blob = iprot.readBinary();
                        break;
                    default:
                        org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
                        break;
                }
                iprot.readFieldEnd();
            }
            iprot.readStructEnd();
        }

        int getValue() {
            return value;
        }
    }
}
