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

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * ★ 归因探针：<b>真实生产编码路径</b> vs <b>探针里的等价复刻</b>，同一个 JVM 里对比。
 *
 * <p><b>为什么需要这个探针</b>：第三轮实测里，
 * {@code StringEncodeCandidates} 的候选 3（声称是「当前生产形态」）
 * 在 mixed-16k 上测出 <b>14944ns</b>（min），
 * 而 {@code ProtoCodecBenchmark} 走<b>真实生产路径</b>测出 <b>9432ns</b>。
 * 两者都声称是「同一段 UTF-16 编码」，差了 1.6 倍 ⇒ 必须查明是谁的数错了。</p>
 *
 * <p>候选 3 与生产 {@code AbstractWriter.writeChars} 的循环体是<b>逐行等价</b>的
 * （同为 char[] + Unsafe 单字节写 + long 偏移），唯一的结构性差别是：</p>
 * <ul>
 *   <li><b>生产</b>：每 1024 字符一段（{@code writeChars(chars, start, end, _pos)}），
 *       每段只碰 2KB 的 char[] 切片 ⇒ 一直在 L1 里；</li>
 *   <li><b>复刻</b>：一次性扫完 16384 字符（32KB char[]）⇒ 整段流式读。</li>
 * </ul>
 *
 * <p>本探针把两者放在<b>同一个 JVM、同一批串、同一个测量窗口</b>里跑，
 * 消除「跨批次绝对值不可比」的问题 ⇒ 直接看差值来自哪里。</p>
 *
 * <p>★ 三个口径都测，避免「换了口径又得出不同结论」：
 * <ol>
 *   <li><b>生产路径</b>：{@code ProtoBufWriter} + {@code ProtoBufEncoder}（真实链路）；</li>
 *   <li><b>复刻（一次扫完）</b>：{@code candCharArrUnsafeLongReserve}（探针里的候选 3）；</li>
 *   <li><b>复刻（分 1024 段）</b>：同样的循环，但按生产的方式切段。</li>
 * </ol>
 */
public final class ProductionVsReplica {

    private static long sink;

    private ProductionVsReplica() {
    }

    public static void main(String[] args) {
        long millis = args.length > 0 ? Long.parseLong(args[0]) : 600L;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int mode = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        if (mode == 1) {
            // ★ 复刻 StringEncodeCandidates 的 harness：只测生产路径、返回值丢弃、单独 JVM
            prodOnly(millis, rounds, false);
            return;
        }
        if (mode == 2) {
            // ★ 与 mode 1 完全相同，只把<b>串的顺序</b>换成探针的顺序（emoji 排在 mixed 之前）
            //   ⇒ 用来验证「JIT profile 被上一个串污染」这个假设
            prodOnly(millis, rounds, true);
            return;
        }

        System.out.printf("真实生产路径 vs 探针复刻 —— 同一 JVM 同批串%n");
        System.out.printf("每项 %d ms × %d 轮取 min%n%n", millis, rounds);
        System.out.printf("%-14s %10s %10s %10s | %10s %10s%n",
                "串", "生产路径", "复刻1次扫", "复刻分1024", "1次扫/生产", "分1024/生产");
        System.out.println("-".repeat(84));

        String[] names = {"ascii-1k", "ascii-16k", "cn-1k", "cn-16k",
                "mixed-1k", "mixed-16k", "emoji-1k", "latin1-16k"};
        String[] values = {
                ascii(1024), ascii(16384), chinese(1024), chinese(16384),
                mixed(1024), mixed(16384), emoji(1024), latin1Big(16384)
        };

        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(CodecStr.class);

        for (int k = 0; k < names.length; k++) {
            String v = values[k];
            byte[] dst = new byte[v.length() * 3 + 64];

            // 预热
            for (int i = 0; i < 2000; i++) {
                encodeProduction(writer, encoder, v);
                StringEncodeCandidates.candCharArrUnsafeLongReserve(v, dst, 0);
                encodeSegmented(v, dst);
            }

            double prod = min(rounds, millis, () -> {
                int len = encodeProduction(writer, encoder, v);
                sink += len;
                return len;
            });
            double rep1 = min(rounds, millis, () -> {
                int len = StringEncodeCandidates.candCharArrUnsafeLongReserve(v, dst, 0);
                sink += len;
                return len;
            });
            double repSeg = min(rounds, millis, () -> {
                int len = encodeSegmented(v, dst);
                sink += len;
                return len;
            });

            System.out.printf("%-14s %10.0f %10.0f %10.0f | %9.2fx %9.2fx%n",
                    names[k], prod, rep1, repSeg,
                    prod == 0 ? 0 : rep1 / prod,
                    prod == 0 ? 0 : repSeg / prod);
        }
        System.out.println();
        System.out.printf("sink=%d%n", sink);
    }

    /**
     * ★ 复刻 {@code StringEncodeCandidates.runSingle} 的 harness：
     * 单方法、返回值<b>丢弃</b>、每串预热 ⇒ 用来定位「两个 harness 差 1.7x」的来源。
     */
    private static void prodOnly(long millis, int rounds, boolean probeOrder) {
        System.out.printf("★ prod-only 模式（复刻 StringEncodeCandidates 的 harness）串顺序=%s%n%n",
                probeOrder ? "探针顺序（emoji 在 mixed 之前）" : "mixed 在 emoji 之前");
        String[] names;
        String[] values;
        if (probeOrder) {
            names = new String[]{"ascii-1k", "ascii-16k", "cn-1k", "cn-16k",
                    "emoji-1k", "latin1-high", "mixed-16k", "latin1-16k"};
            values = new String[]{ascii(1024), ascii(16384), chinese(1024), chinese(16384),
                    emoji(1024), latin1(0x63, 0x61, 0x66, 0xE9), mixed(16384), latin1Big(16384)};
        } else {
            names = new String[]{"ascii-1k", "ascii-16k", "cn-1k", "cn-16k",
                    "mixed-1k", "mixed-16k", "emoji-1k", "latin1-16k"};
            values = new String[]{ascii(1024), ascii(16384), chinese(1024), chinese(16384),
                    mixed(1024), mixed(16384), emoji(1024), latin1Big(16384)};
        }
        for (int k = 0; k < names.length; k++) {
            String v = values[k];
            for (int i = 0; i < 20000; i++) {
                StringEncodeCandidates.encodeProduction(v);
            }
            double best = Double.MAX_VALUE;
            for (int r = 0; r < rounds; r++) {
                best = Math.min(best, measureDiscard(millis, () -> StringEncodeCandidates.encodeProduction(v)));
            }
            System.out.printf("%-14s %12.0f%n", names[k], best);
        }
    }

    private static String latin1(int... bytes) {
        byte[] bs = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            bs[i] = (byte) bytes[i];
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    /**
     * 真实生产路径：{@code ProtoBufWriter} + {@code ProtoBufEncoder}（不含 byte[] 产出）。
     */
    private static int encodeProduction(ProtoBufWriter writer, ProtoBufEncoder encoder, String v) {
        try {
            CodecStr cs = new CodecStr();
            cs.setS(v);
            writer.reset();
            writer.getBufOut().reset();
            encoder.encode(writer, cs);
            return writer.size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 复刻生产，但按生产的方式<b>每 1024 字符切一段</b>（其余与候选 3 相同）。
     */
    private static int encodeSegmented(String v, byte[] dst) {
        if (!StringEncodeCandidates.isLatin1(v)) {
            char[] chars = v.toCharArray();
            int charLen = chars.length;
            int reserve = StringEncodeCandidates.varintLen(charLen * 3);
            int p = reserve;
            int start = 0;
            int end = Math.min(start + 1024, charLen);
            while (start < charLen) {
                p += encodeSegment(chars, start, end, dst, p);
                start += 1024;
                end = Math.min(start + 1024, charLen);
            }
            int len = p - reserve;
            int n = StringEncodeCandidates.varintLen(len);
            if (n != reserve) {
                System.arraycopy(dst, reserve, dst, n, len);
            }
            StringEncodeCandidates.writeVarint(dst, 0, len);
            return n + len;
        }
        return StringEncodeCandidates.candCurrent(v, dst, 0);
    }

    /**
     * 与探针的 {@code encodeCharsUnsafeLong} 逐行等价，但只编码 {@code [start,end)} 一段。
     */
    private static int encodeSegment(char[] value, int start, int end, byte[] dst, int pos) {
        long p = pos;
        for (int i = start; i < end; i++) {
            char c = value[i];
            if (c < 128) {
                StringEncodeCandidates.putByteLong(dst, p++, (byte) c);
            } else if (c < 0x800) {
                StringEncodeCandidates.putByteLong(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, value[i + 1]);
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                StringEncodeCandidates.putByteLong(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                StringEncodeCandidates.putByteLong(dst, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return (int) (p - pos);
    }

    /**
     * 与探针一致的测量：<b>返回值丢弃</b>（不累加进静态字段）。
     */
    private static double measureDiscard(long millis, Op op) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long ops = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 50; i++) {
                try {
                    op.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                ops++;
            }
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    private static double min(int rounds, long millis, Op op) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            best = Math.min(best, measure(millis, op));
        }
        return best;
    }

    private static double measure(long millis, Op op) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long ops = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 50; i++) {
                try {
                    op.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                ops++;
            }
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    private static String ascii(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static String chinese(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append('中');
        }
        return sb.toString();
    }

    private static String mixed(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            if (i % 5 == 0) {
                sb.append('中');
            } else {
                sb.append((char) ('a' + (i % 26)));
            }
        }
        return sb.toString();
    }

    private static String emoji(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("😀");
        }
        return sb.toString();
    }

    private static String latin1Big(int len) {
        byte[] bs = new byte[len];
        for (int i = 0; i < len; i++) {
            bs[i] = (byte) ((i % 64 == 0) ? 0xE9 : ('a' + (i % 26)));
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    private interface Op {
        long call() throws Exception;
    }
}
