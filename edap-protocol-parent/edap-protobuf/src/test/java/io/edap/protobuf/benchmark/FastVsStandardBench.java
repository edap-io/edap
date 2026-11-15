/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.protobuf.benchmark;

import io.edap.protobuf.ProtoBuf;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * edap protobuf 两套编解码路径的对比测量器。
 *
 * <p><b>被测的两组公开方法</b>（严格照搬它们各自走的路径，不另写「等价实现」）：</p>
 * <table border="1">
 *   <caption>候选</caption>
 *   <tr><th>candidate</th><th>方法</th><th>encoder / decoder</th><th>writer / reader</th></tr>
 *   <tr><td>std-enc</td><td>{@link ProtoBuf#toByteArray(Object)}</td>
 *       <td>{@code REGISTER.getEncoder(cls)}</td><td>THREAD_WRITER（StandardProtoBufWriter）</td></tr>
 *   <tr><td>fast-enc</td><td>{@link ProtoBuf#fastToByteArray(Object)}</td>
 *       <td>{@code REGISTER.getEncoder(cls, FAST)}</td><td>THREAD_FAST_WRITER（FastProtoBufWriter）</td></tr>
 *   <tr><td>std-dec</td><td>{@link ProtoBuf#toObject(byte[], Class)}</td>
 *       <td>{@code REGISTER.getDecoder(cls)}</td><td>ByteArrayReader</td></tr>
 *   <tr><td>fast-dec</td><td>{@link ProtoBuf#fastToObject(byte[], Class)}</td>
 *       <td>{@code REGISTER.getDecoder(cls, FAST)}</td><td>ByteArrayFastReader</td></tr>
 * </table>
 *
 * <p><b>★ 为什么必须做自检</b>：{@code toByteArray} / {@code fastToByteArray} 捕获
 * {@code EncodeException} 之后<b>返回 null</b>，{@code toObject} / {@code fastToObject} 捕获
 * 任何异常之后<b>也返回 null</b>（见 {@code ProtoBuf.java}）。
 * 也就是说：一个坏掉的改进版路径会表现为「快得离谱」而不是报错。
 * ⇒ 计时之前必须断言：编码非 null、长度符合预期、解码结果与原对象逐字段相等。</p>
 *
 * <p><b>★ 防死代码消除</b>：所有被测操作的结果都累加进静态字段 {@link #sink}，
 * 否则 JIT 会把「解出来的对象」当成没人用的无用计算优化掉。</p>
 *
 * <p><b>★ 必须预热</b>：FAST 的 encoder/decoder 是首次使用时用 ASM 现场生成的
 * （{@code ProtoBufCodecRegister}），第一次调用含代码生成开销。</p>
 *
 * <p>用法：{@code FastVsStandardBench <payloadId> <candidate> <millis> <rounds>}</p>
 */
public final class FastVsStandardBench {

    /**
     * ★ 累加所有被测操作的结果 —— 防止 JIT 把写入缓冲 / 解析出来的数据当成无用计算消掉。
     */
    private static long sink;

    private FastVsStandardBench() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeStd(byte[] bs, Class<?> cls) {
        return ProtoBuf.toObject(bs, (Class) cls);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeFast(byte[] bs, Class<?> cls) {
        return ProtoBuf.fastToObject(bs, (Class) cls);
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("用法：FastVsStandardBench <payloadId> <candidate> <millis> <rounds>");
            System.err.println("candidate ∈ {std-enc, fast-enc, std-dec, fast-dec, all}");
            System.exit(2);
        }
        String payloadId = args[0];
        String candidate = args[1];
        long millis = Long.parseLong(args[2]);
        int rounds = Integer.parseInt(args[3]);

        BenchPayloads.Payload p = BenchPayloads.of(payloadId);

        // ── 两种格式各编一份：既是自检素材，也是解码候选的输入 ──────────
        byte[] stdBytes = ProtoBuf.toByteArray(p.obj);
        byte[] fastBytes = ProtoBuf.fastToByteArray(p.obj);
        if (stdBytes == null || stdBytes.length == 0) {
            throw new IllegalStateException("★ 标准版编码返回 null/空（EncodeException 被吞掉了）");
        }
        if (fastBytes == null || fastBytes.length == 0) {
            throw new IllegalStateException("★ 改进版编码返回 null/空（EncodeException 被吞掉了）");
        }
        Object stdBack = decodeStd(stdBytes, p.cls);
        Object fastBack = decodeFast(fastBytes, p.cls);
        if (stdBack == null) {
            throw new IllegalStateException("★ 标准版解码返回 null（异常被吞掉了）");
        }
        if (fastBack == null) {
            throw new IllegalStateException("★ 改进版解码返回 null（异常被吞掉了）");
        }
        if (!same(p.obj, stdBack)) {
            throw new IllegalStateException("★ 标准版 round-trip 内容不一致：" + payloadId);
        }
        if (!same(p.obj, fastBack)) {
            throw new IllegalStateException("★ 改进版 round-trip 内容不一致：" + payloadId);
        }

        if ("all".equals(candidate)) {
            System.out.printf("RESULT payload=%s candidate=all stdBytes=%d fastBytes=%d%n",
                    payloadId, stdBytes.length, fastBytes.length);
            return;
        }

        Op op;
        switch (candidate) {
            case "std-enc":
                op = () -> {
                    byte[] b = ProtoBuf.toByteArray(p.obj);
                    if (b == null) {
                        throw new IllegalStateException("标准版编码返回 null");
                    }
                    sink += b.length + (b.length > 0 ? (b[0] & 0xFF) : 0);
                    return b.length;
                };
                break;
            case "fast-enc":
                op = () -> {
                    byte[] b = ProtoBuf.fastToByteArray(p.obj);
                    if (b == null) {
                        throw new IllegalStateException("改进版编码返回 null");
                    }
                    sink += b.length + (b.length > 0 ? (b[0] & 0xFF) : 0);
                    return b.length;
                };
                break;
            case "std-dec": {
                final byte[] bs = stdBytes;
                op = () -> {
                    Object o = decodeStd(bs, p.cls);
                    if (o == null) {
                        throw new IllegalStateException("标准版解码返回 null");
                    }
                    sink += p.sinkOf.applyAsInt(o);
                    return 0;
                };
                break;
            }
            case "fast-dec": {
                final byte[] bs = fastBytes;
                op = () -> {
                    Object o = decodeFast(bs, p.cls);
                    if (o == null) {
                        throw new IllegalStateException("改进版解码返回 null");
                    }
                    sink += p.sinkOf.applyAsInt(o);
                    return 0;
                };
                break;
            }
            default:
                throw new IllegalArgumentException("未知候选：" + candidate);
        }

        // ── 预热：FAST 的 codec 是首次调用时 ASM 生成的，必须先跑掉 ──────
        warmup(op);

        // ── 计时：rounds 轮取 min（min 比中位数更抗噪） ─────────────────
        double[] ns = new double[rounds];
        for (int i = 0; i < rounds; i++) {
            ns[i] = measure(millis, op);
        }
        double best = Double.MAX_VALUE;
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < rounds; i++) {
            if (ns[i] < best) {
                best = ns[i];
            }
            if (i > 0) {
                all.append(',');
            }
            all.append(String.format("%.1f", ns[i]));
        }

        // ── ★ 计时之后再验一次内容 ────────────────────────────────────────
        // 改进版的 UTF16LE 分支用 Unsafe 直接写 String 的 final 字段（value/coder），
        // 存在被 JIT 当成常量折叠的风险 ⇒ 启动时那次自检（还在解释执行）不算数，
        // 必须等 JIT 编译完（预热 500 次 + 计时若干万次）之后再验一次。
        Object stdAfter = decodeStd(stdBytes, p.cls);
        if (!same(p.obj, stdAfter)) {
            throw new IllegalStateException("★ 标准版 round-trip 内容不一致（计时后）：" + payloadId);
        }
        Object fastAfter = decodeFast(fastBytes, p.cls);
        if (!same(p.obj, fastAfter)) {
            throw new IllegalStateException("★ 改进版 round-trip 内容不一致（计时后）：" + payloadId);
        }

        System.out.printf("RESULT payload=%s candidate=%s ns=%.1f stdBytes=%d fastBytes=%d rounds=%s%n",
                payloadId, candidate, best, stdBytes.length, fastBytes.length, all);
        // 让 sink 逃逸，JIT 不能把它当常量折叠掉
        if (sink == Long.MIN_VALUE) {
            System.out.println("sink=" + sink);
        }
    }

    private static void warmup(Op op) {
        for (int i = 0; i < 500; i++) {
            try {
                op.call();
            } catch (Exception e) {
                throw new IllegalStateException("预热失败", e);
            }
        }
    }

    /**
     * 跑满 {@code millis} 毫秒，返回纳秒/次。
     */
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
            throw new IllegalStateException("测量失败", e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    // =====================================================================
    // 内容比较（round-trip 自检）
    // =====================================================================

    /**
     * 两个对象内容是否一致。
     *
     * <p>集合字段允许「一边是 null、另一边是空集合」——解码器对没有元素的 repeated 字段
     * 不会建对象，两种写法在协议上等价。除此之外必须逐元素一致。</p>
     */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BenchStr) {
            return eq(((BenchStr) a).getS(), ((BenchStr) b).getS());
        }
        if (a instanceof BenchInner) {
            BenchInner x = (BenchInner) a;
            BenchInner y = (BenchInner) b;
            return x.getV() == y.getV() && eq(x.getS(), y.getS());
        }
        if (a instanceof BenchNest) {
            BenchNest x = (BenchNest) a;
            BenchNest y = (BenchNest) b;
            if (!same(x.getInner(), y.getInner())) {
                return false;
            }
            if (!sameList(x.getInners(), y.getInners())) {
                return false;
            }
            if (!sameList(x.getNums(), y.getNums())) {
                return false;
            }
            Map<String, String> ma = x.getAttrs();
            Map<String, String> mb = y.getAttrs();
            if (ma == null || ma.isEmpty()) {
                if (mb != null && !mb.isEmpty()) {
                    return false;
                }
            } else if (!ma.equals(mb)) {
                return false;
            }
            return Arrays.equals(x.getBlob(), y.getBlob());
        }
        return a.equals(b);
    }

    private static boolean sameList(List<?> a, List<?> b) {
        if (a == null || a.isEmpty()) {
            return b == null || b.isEmpty();
        }
        if (b == null || b.size() != a.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private interface Op {
        long call() throws Exception;
    }
}
