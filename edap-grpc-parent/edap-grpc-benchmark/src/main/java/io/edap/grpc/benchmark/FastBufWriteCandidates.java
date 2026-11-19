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

import io.edap.buffer.FastBuf;
import io.edap.io.BufOut;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.util.UnsafeUtil;

import java.nio.ByteBuffer;

/**
 * ★ 归因探针：「把 eRPC 的 protobuf 编码器改成直写 FastBuf（堆外）」值不值？
 *
 * <p><b>用户的问题</b>：①「现在的 writer 是直接写到 byte[] 里的」；
 * ②「用 FastBuf 的 writer，如果频繁写 byte 会不会比 byte[] 赋值慢呢」。</p>
 *
 * <p><b>先纠正前提</b>（读源码确认）：eRPC 的<b>出站缓冲本来就是堆外 FastBuf</b>
 * （{@code ErpcOutboundWriter.pendingBuf / writeBuf}），
 * 堆内 {@code byte[]} 只出现在<b>编码器那一段</b>
 * （{@code ProtoBuf.THREAD_WRITER} → {@code ProtoBufWriter.buffer()}）。
 * ⇒ 现在真正多出来的那一次搬运是
 * 「编码器堆内缓冲 → 连接堆外累积缓冲」= {@code UnsafeUtil.copyMemory(byte[],int,long,int)}。</p>
 *
 * <p><b>本探针把「写 byte」的六种形态量出来</b>（同一段载荷）：</p>
 * <table border="1">
 *   <caption>候选</caption>
 *   <tr><th>候选</th><th>做法</th><th>代表</th></tr>
 *   <tr><td><b>heap-byte</b></td><td>{@code bs[pos++] = b}</td>
 *       <td>堆内逐字节赋值（用户问的「byte[] 赋值」）</td></tr>
 *   <tr><td><b>heap-bulk</b></td><td>{@code System.arraycopy}</td>
 *       <td>堆内批量 —— <b>今天 blob 字段走的正是这条</b>
 *           （{@code AbstractWriter.writeBytes:981}）</td></tr>
 *   <tr><td><b>offheap-byte</b></td><td>{@code UnsafeUtil.writeByte(long,byte)}</td>
 *       <td>堆外逐字节（FastBuf writer 的「天真正写法」）</td></tr>
 *   <tr><td><b>offheap-bulk</b></td><td>{@code UnsafeUtil.copyMemory(byte[],int,long,int)}</td>
 *       <td>堆外批量 —— <b>今天出站那一次搬运</b></td></tr>
 *   <tr><td><b>offheap-8</b></td><td>拼成 long 再 {@code putLong}</td>
 *       <td>堆外 8 字节批量</td></tr>
 *   <tr><td><b>offheap-dbb</b></td><td>{@code ByteBuffer.put(byte[])}</td>
 *       <td>堆外批量（NIO 路径对照）</td></tr>
 *   <tr><td><b>proto-encode</b></td><td>protobuf 真实编码一个 {@link Payload}</td>
 *       <td>「编码」这一档的总成本（含 blob 的 heap→heap arraycopy）</td></tr>
 * </table>
 *
 * <p><b>口径</b>：★ <b>一个候选一个 JVM</b>（避免调用点 megamorphic + JIT profile 污染），
 * 同一候选多轮取 min；候选顺序按 launch 轮转（避免机器降频把后排候选系统性压慢）。
 * 输出 {@code RESULT candidate=.. size=.. ns=..} 便于脚本收集。</p>
 */
public final class FastBufWriteCandidates {

    /** 累加被测操作的结果 —— 防止 JIT 把写入当成无用计算消掉。 */
    private static long sink;

    private static final int[] SIZES = {0, 1024, 16384, 65536};

    private FastBufWriteCandidates() {
    }

    public static void main(String[] args) throws Exception {
        String candidate = null;
        long millis = 400L;
        int rounds = 5;
        int[] sizes = SIZES;

        for (int i = 0; i < args.length; i++) {
            // ★ 同时接受 "--key value" 与 "--key=value" 两种写法
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
                case "--sizes":
                    sizes = parseInts(value != null ? value : args[++i]);
                    break;
                default:
                    System.out.println("未知参数: " + arg + "（忽略）");
                    break;
            }
        }

        if (candidate == null) {
            System.out.println("必须给 --candidate=<名字>（一个候选一个 JVM）");
            for (String c : new String[] {"heap-byte", "heap-bulk", "offheap-byte", "offheap-bulk",
                    "offheap-8", "offheap-dbb", "proto-encode"}) {
                System.out.println("  " + c);
            }
            return;
        }

        for (int size : sizes) {
            byte[] payload = BenchPayloads.blob(size);
            long ns = measure(candidate, size, payload, millis, rounds);
            System.out.printf("RESULT candidate=%s size=%d ns=%d%n", candidate, size, ns);
        }
    }

    // =====================================================================
    // 测量
    // =====================================================================

    private static long measure(String candidate, int size, byte[] payload, long millis, int rounds) {
        Op op = op(candidate, size, payload);
        // 预热（把候选拉进 JIT 的编译门槛）
        for (int i = 0; i < 2000; i++) {
            try {
                op.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        long best = Long.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            long v = measureOnce(millis, op);
            if (v < best) {
                best = v;
            }
        }
        return best;
    }

    private static long measureOnce(long millis, Op op) {
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
            throw new IllegalStateException("探针失败", e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : elapsed / ops;
    }

    private interface Op {
        long call() throws Exception;
    }

    private static Op op(String candidate, int size, byte[] payload) {
        switch (candidate) {
            case "heap-byte": {
                // ★ 目标缓冲在闭包外分配 —— 每次 op 新建 byte[]/FastBuf 会把分配成本算进测量里
                byte[] dst = new byte[payload.length + 16];
                return () -> heapByte(payload, dst);
            }
            case "heap-bulk": {
                byte[] dst = new byte[payload.length + 16];
                return () -> heapBulk(payload, dst);
            }
            case "offheap-byte": {
                FastBuf buf = new FastBuf(Math.max(1, payload.length + 64));
                return () -> offheapByte(payload, buf);
            }
            case "offheap-bulk": {
                FastBuf buf = new FastBuf(Math.max(1, payload.length + 64));
                return () -> offheapBulk(payload, buf);
            }
            case "offheap-8": {
                FastBuf buf = new FastBuf(Math.max(1, payload.length + 64));
                return () -> offheap8(payload, buf);
            }
            case "offheap-dbb": {
                ByteBuffer bb = ByteBuffer.allocateDirect(Math.max(1, payload.length + 64));
                return () -> offheapDbb(payload, bb);
            }
            case "proto-encode": {
                Payload msg = BenchPayloads.edap(size);
                return () -> protoEncode(msg);
            }
            default:
                throw new IllegalArgumentException("未知候选: " + candidate);
        }
    }

    // =====================================================================
    // 候选实现
    // =====================================================================

    /** 堆内逐字节赋值 —— {@code bs[pos++] = b}（用户问的「byte[] 赋值」）。 */
    private static int heapByte(byte[] payload, byte[] dst) {
        int pos = 0;
        for (int i = 0; i < payload.length; i++) {
            dst[pos++] = payload[i];
        }
        sink += dst[0];
        return pos;
    }

    /** 堆内批量 —— {@code System.arraycopy}（今天 blob 字段的真实写法）。 */
    private static int heapBulk(byte[] payload, byte[] dst) {
        System.arraycopy(payload, 0, dst, 0, payload.length);
        sink += dst[0];
        return payload.length;
    }

    /** 堆外逐字节 —— {@code Unsafe.putByte(long, byte)}（FastBuf writer 的天真正写法）。 */
    private static int offheapByte(byte[] payload, FastBuf buf) {
        long p = buf.address();
        for (int i = 0; i < payload.length; i++) {
            UnsafeUtil.writeByte(p + i, payload[i]);
        }
        buf.wpos(p + payload.length);
        sink += p;
        return payload.length;
    }

    /** 堆外批量 —— {@code UnsafeUtil.copyMemory(byte[], int, long, int)}（今天出站那一次）。 */
    private static int offheapBulk(byte[] payload, FastBuf buf) {
        long p = buf.address();
        UnsafeUtil.copyMemory(payload, 0, p, payload.length);
        buf.wpos(p + payload.length);
        sink += p;
        return payload.length;
    }

    /**
     * 堆外 8 字节批量 —— 拼成 long 再 {@code putLong}。
     *
     * <p>⚠ 字节序是<b>平台序</b>（本机小端）⇒ 写出的字节与源不一致 ——
     * 本探针只测吞吐，不校验内容。</p>
     */
    private static int offheap8(byte[] payload, FastBuf buf) {
        long p = buf.address();
        int len = payload.length;
        int i = 0;
        for (; i + 8 <= len; i += 8) {
            long v = 0;
            for (int k = 7; k >= 0; k--) {
                v = (v << 8) | (payload[i + k] & 0xffL);
            }
            UnsafeUtil.putLong(p + i, v);
        }
        for (; i < len; i++) {
            UnsafeUtil.writeByte(p + i, payload[i]);
        }
        buf.wpos(p + len);
        sink += p;
        return len;
    }

    /** 堆外批量 —— {@code ByteBuffer.put(byte[])}（NIO 路径对照）。 */
    private static int offheapDbb(byte[] payload, ByteBuffer bb) {
        bb.clear();
        bb.put(payload);
        sink += bb.position();
        return payload.length;
    }

    /**
     * protobuf 真实编码 —— 「编码」这一档的总成本。
     *
     * <p>含 blob 字段的 {@code System.arraycopy}（heap→heap），
     * 也含 name 字段的字符串编码与长度前缀回填。</p>
     */
    private static int protoEncode(Payload msg) {
        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        writer.reset();
        BufOut out = writer.getBufOut();
        out.reset();
        try {
            ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(Payload.class);
            encoder.encode(writer, msg);
            sink += writer.size();
            return writer.size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            out.reset();
        }
    }

    private static int[] parseInts(String csv) {
        String[] parts = csv.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }
}
