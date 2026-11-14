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
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * <b>packed int 编码路径的专用探针</b> —— 只测 {@code writePackedInts} 这一条路。
 *
 * <p>为什么单独写一个探针：{@link ProtoCodecBenchmark} 一次要跑 11 个负载、3~5 轮，
 * 单次 JVM 要 3 分钟；而 packed int 这一条路是本轮唯一在动的代码，
 * 需要<b>「改一版 ⇒ 立刻量一版」</b>的迭代速度。</p>
 *
 * <p>★ 口径与 {@link ProtoCodecBenchmark} 完全一致：</p>
 * <ul>
 *   <li><b>编码核心</b>：edap 写进 writer 缓冲 / google {@code writeTo(CodedOutputStream)}
 *       ⇒ 两把对等的尺子（都是往已有缓冲里写一次）；</li>
 *   <li>protobuf-java 的对照列在<b>同一个 JVM、同一次运行</b>里测 ⇒ 机器噪声被自动归一化
 *       （跨批次比绝对值是不可靠的，见 memory 的基准纪律）；</li>
 *   <li>edap 与 google <b>交替</b>测量 ⇒ 避免「后测的负载背前一个的形态」。</li>
 * </ul>
 *
 * <p>★ <b>每个候选一个 JVM</b>：本探针只测一条路，没有多候选同 JVM 的问题；
 * 换候选实现时用 {@code -cp} 前置一个补丁 class 目录即可（不用重装 jar）。</p>
 *
 * <p>★ wire 一致性：每行都把 edap 编出来的字节与 protobuf-java 逐字节比对。</p>
 */
public final class PackedIntProbe {

    private static long sink;

    private PackedIntProbe() {
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        long millis = args.length > 1 ? Long.parseLong(args[1]) : 300;
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 5;

        List<Integer> nums = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nums.add(i);
        }

        CodecNest en = new CodecNest();
        en.setInner(null);
        en.setInners(new ArrayList<>());
        en.setNums(nums);
        en.setAttrs(new LinkedHashMap<>());

        Bench.CodecNest gn = Bench.CodecNest.newBuilder().addAllNums(nums).build();

        byte[] edapBytes = ProtoBuf.toByteArray(en);
        byte[] gBytes = gn.toByteArray();
        boolean wireSame = Arrays.equals(edapBytes, gBytes);
        System.err.printf("packed-int-%d  wire %s (edap %d 字节 / google %d 字节)%n",
                n, wireSame ? "同" : "★异", edapBytes.length, gBytes.length);

        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(CodecNest.class);
        byte[] sinkArray = new byte[Math.max(1024, gBytes.length + 64)];

        // ── 预热 ────────────────────────────────────────────────────────
        for (int i = 0; i < 3000; i++) {
            sink += encodeIntoBuffer(writer, encoder, en);
            sink += googleWrite(gn, sinkArray);
        }

        // ── 交替测量（edap / google），每轮各测一次，取 min ──────────────
        double edapMin = Double.MAX_VALUE;
        double googleMin = Double.MAX_VALUE;
        for (int r = 0; r < rounds; r++) {
            double e = measure(millis, () -> encodeIntoBuffer(writer, encoder, en));
            double g = measure(millis, () -> googleWrite(gn, sinkArray));
            if (r % 2 == 0) {
                // 交替次序，消除「后测吃亏」
                edapMin = Math.min(edapMin, e);
                googleMin = Math.min(googleMin, g);
            } else {
                googleMin = Math.min(googleMin, g);
                edapMin = Math.min(edapMin, e);
            }
            System.err.printf("  round %d: edap %8.0f ns   google %8.0f ns   比值 %.2fx%n",
                    r + 1, e, g, g == 0 ? 0 : e / g);
        }
        System.err.printf("packed-int-%d  min: edap %8.0f ns   google %8.0f ns   ★比值 %.2fx%n",
                n, edapMin, googleMin, googleMin == 0 ? 0 : edapMin / googleMin);
        System.err.printf("  writer 缓冲长度 = %d 字节%n", writer.getBufOut().getWriteBuf().bs.length);
        if (sink == 42) {
            System.out.println("sink=" + sink);
        }
    }

    private static int encodeIntoBuffer(ProtoBufWriter writer, ProtoBufEncoder encoder, Object obj) {
        try {
            writer.reset();
            writer.getBufOut().reset();
            encoder.encode(writer, obj);
            return writer.size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int googleWrite(Bench.CodecNest gn, byte[] sinkArray) {
        try {
            CodedOutputStream cos = CodedOutputStream.newInstance(sinkArray);
            gn.writeTo(cos);
            cos.flush();
            sink += sinkArray[0];
            return cos.getTotalBytesWritten();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static double measure(long millis, Op op) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long ops = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 50; i++) {
                op.call();
                ops++;
            }
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    private interface Op {
        int call();
    }
}
