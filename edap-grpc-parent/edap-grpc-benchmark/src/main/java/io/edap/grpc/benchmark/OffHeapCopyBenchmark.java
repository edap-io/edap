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
import io.edap.util.UnsafeUtil;

/**
 * ★ Phase C（C-0）的收益实测探针 —— 「protobuf 编码器直写 HTTP/2 堆外缓冲」到底值多少。
 *
 * <p><b>背景</b>：帧头内联之后，edap 一条响应只剩 <b>1 次</b>整块拷贝：</p>
 * <pre>
 *   protobuf 写入堆内 bs(byte[]) → Http2FrameWriter.writeFrame:146  buf.write(payload,off,len)
 *                                = UnsafeUtil.copyMemory(byte[], int, long, int)   ★这一次
 * </pre>
 * <p>Phase C 想做的是「编码器直接写堆外」⇒ 这一次拷贝消失。
 * 但 <b>AbstractWriter 有 72 处逐字节写</b>（{@code bs[pos++]}），
 * 而堆外逐字节写（{@code Unsafe.putByte(long,byte)}）比 {@code System.arraycopy} 慢一个数量级
 * ⇒ **「直写」必须批量，否则收益是负的**。本探针就是把这两条路的成本量出来。</p>
 *
 * <p><b>测四条路径</b>（同一段载荷）：</p>
 * <table border="1">
 *   <caption>四条路径</caption>
 *   <tr><th>路径</th><th>做法</th><th>代表</th></tr>
 *   <tr><td><b>A 现状</b></td><td>{@code UnsafeUtil.copyMemory(byte[], int, long, int)}</td>
 *       <td>现在真实发生的那次堆内 → 堆外拷贝 —— <b>Phase C 要消除的就是它</b></td></tr>
 *   <tr><td><b>B 逐字节直写</b></td><td>{@code UnsafeUtil.writeByte(long, byte)} 循环</td>
 *       <td>「把 AbstractWriter 的逐字节写原样搬到堆外」的<b>天真实现</b></td></tr>
 *   <tr><td><b>C 8 字节批量直写</b></td><td>拼成 long 再 {@code putLong}</td>
 *       <td>数据已经是 8 字节粒度时的批量直写（<b>注意：拼装本身是 Java 级开销</b>）</td></tr>
 *   <tr><td><b>D 分块直写</b></td><td>4KB 堆内小块 + 逐块 bulk copy</td>
 *       <td>「编码器逐字节产出」时<b>直写能落地的最优形态</b></td></tr>
 * </table>
 *
 * <p><b>口径</b>：纳秒/次，多轮取 min。载荷长度对齐端到端基准的 4 个档位：0B / 1KB / 16KB / 64KB。</p>
 *
 * <p><b>怎么用这个数字</b>：拿 A 的耗时 ÷ 端到端每请求耗时（64KB@32 ≈ 20,700 req/s ⇒ 48.3 µs/请求）
 * ⇒ 就是 Phase C 的<b>端到端收益上限</b>。若 &lt; 2% ⇒ 按方案「停在设计评审，不实施」。
 * （端到端的<b>实测</b>边际成本见 `doc/bench/phaseC-c0-offheap-20261113.md`。）</p>
 */
public final class OffHeapCopyBenchmark {

    /**
     * ★ 累加所有被测操作的结果 —— 防止 JIT 把写入的数据当成无用计算消掉。
     */
    private static long sink;

    private static final int[] SIZES = {0, 1024, 16384, 65536};

    private OffHeapCopyBenchmark() {
    }

    public static void main(String[] args) {
        long millis = args.length > 0 ? Long.parseLong(args[0]) : 400L;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        System.out.printf("Phase C（C-0）收益实测 —— 堆内 → 堆外的那一次拷贝%n");
        System.out.printf("每项测量 %d ms × %d 轮取 min%n%n", millis, rounds);

        System.out.printf("%-10s %14s %14s %14s %14s %10s%n",
                "载荷", "A 现状copy", "B 逐字节直写", "C 8字节批量", "D 分块直写", "B/A");
        System.out.println("-".repeat(90));

        for (int size : SIZES) {
            byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) {
                payload[i] = (byte) i;
            }
            FastBuf buf = new FastBuf(Math.max(1, size + 64));
            byte[] chunk = new byte[Math.min(4096, Math.max(1, size))];

            // 预热
            for (int i = 0; i < 2000; i++) {
                copy(payload, buf);
                byteByByte(payload, buf);
                bulk(payload, buf);
                chunked(payload, chunk, buf);
            }

            long a = min(rounds, millis, () -> copy(payload, buf));
            long b = min(rounds, millis, () -> byteByByte(payload, buf));
            long c = min(rounds, millis, () -> bulk(payload, buf));
            long d = min(rounds, millis, () -> chunked(payload, chunk, buf));

            System.out.printf("%-10s %14d %14d %14d %14d %9.1fx%n",
                    size == 0 ? "0B" : (size / 1024) + "KB",
                    a, b, c, d, a == 0 ? 0 : (double) b / a);
        }

        System.out.println();
        System.out.printf("★ 端到端收益上限 = 「A 现状copy」耗时 ÷ 端到端每请求耗时%n");
        System.out.printf("  端到端基线（matrix-20261113-frameinline）：%n");
        System.out.printf("    0B@32  78,428 req/s ⇒ %8.2f µs/请求%n", 1e6 / 78428);
        System.out.printf("    1KB@32 76,285 req/s ⇒ %8.2f µs/请求%n", 1e6 / 76285);
        System.out.printf("   16KB@32 44,963 req/s ⇒ %8.2f µs/请求%n", 1e6 / 44963);
        System.out.printf("   64KB@32 20,811 req/s ⇒ %8.2f µs/请求%n", 1e6 / 20811);
    }

    // =====================================================================
    // 三条路径
    // =====================================================================

    /**
     * A 现状：{@code Http2FrameWriter.writeFrame:146} 走的就是这条
     * （{@code buf.write(payload, offset, length)} → {@code UnsafeUtil.copyMemory}）。
     */
    private static int copy(byte[] payload, FastBuf buf) {
        buf.wpos(buf.address());
        int n = buf.write(payload, 0, payload.length);
        sink += n;
        return n;
    }

    /**
     * B 天真直写：把 {@code AbstractWriter} 的逐字节写原样搬到堆外。
     */
    private static int byteByByte(byte[] payload, FastBuf buf) {
        long p = buf.address();
        for (int i = 0; i < payload.length; i++) {
            UnsafeUtil.writeByte(p + i, payload[i]);
        }
        buf.wpos(p + payload.length);
        sink += p;
        return payload.length;
    }

    /**
     * C 批量直写：每 8 字节一次 {@code putLong} ⇒ 8 倍少的 native 调用。
     *
     * <p>★ 这是 Phase C 成立的<b>成本下限</b>：编码器必须像这样批量写，
     * 否则 {@link #byteByByte} 的数字就是 Phase C 的实际结果（负收益）。</p>
     */
    private static int bulk(byte[] payload, FastBuf buf) {
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

    /**
     * D 分块 bulk copy：把同一段载荷按 4KB 一块搬进堆外。
     *
     * <p>★ 代表「Phase C <b>保留了这次拷贝</b>、只是改了落点/粒度」的形态 ——
     * 编码器照样逐字节写进堆内小块（那部分成本今天已经付了，不重复计），
     * 编码完再逐块搬到堆外。
     * ⇒ 它的成本<b>不可能低于 A</b>（A 一次搬完，最省）⇒
     * <b>Phase C 的收益上限就是 A 的耗时</b>。</p>
     */
    private static int chunked(byte[] payload, byte[] chunk, FastBuf buf) {
        long p = buf.address();
        int len = payload.length;
        int off = 0;
        while (off < len) {
            int n = Math.min(chunk.length, len - off);
            UnsafeUtil.copyMemory(payload, off, p + off, n);
            off += n;
        }
        buf.wpos(p + len);
        sink += p;
        return len;
    }

    // =====================================================================
    // 测量
    // =====================================================================

    private interface Op {
        long call() throws Exception;
    }

    private static long min(int rounds, long millis, Op op) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            long v = measure(millis, op);
            if (v < best) {
                best = v;
            }
        }
        return best;
    }

    private static long measure(long millis, Op op) {
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
            throw new IllegalStateException("C-0 探针失败", e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : elapsed / ops;
    }
}
