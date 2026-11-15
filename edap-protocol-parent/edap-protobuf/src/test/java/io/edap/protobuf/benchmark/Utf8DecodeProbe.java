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

import java.nio.charset.StandardCharsets;

/**
 * 探针：拆开标准读端 {@code ByteArrayReader.readString()} 的三步，逐段计时。
 *
 * <p>标准读端（{@code ByteArrayReader.java:332-350}）：
 * <pre>
 *   String s = new String(buf, pos, len, StandardCharsets.UTF_8);   // ① JDK UTF-8 解码
 *   if (s.indexOf('�') >= 0) {                                   // ② 全文线性扫描找 U+FFFD
 *       s = decodeUtf8Strict(len);                                 // ③ 再手写解码一遍
 *   }
 * </pre>
 *
 * <p><b>★ 方法学：一个 JVM 只跑一个测点</b>。
 * 同一个 {@code bench()} 被 4 个不同 lambda 调用 ⇒ {@code Task.run()} 调用点变成 megamorphic
 * ⇒ lambda 体被单独编译 ⇒ {@code String.<init>} 被内联进去 ⇒ 逃逸分析发现
 * 解码出来的 String 不逃逸 ⇒ <b>把整段字节级解码当成无用计算优化掉</b>
 * ⇒ 报出来的数比真实成本低 4 倍（实测 mix-1cn：7529 ns vs 真实 27277 ns）。
 * ⇒ 必须像 {@link EscapeProbe} 那样<b>一个测点一个 JVM</b>。</p>
 *
 * <p>用法：{@code Utf8DecodeProbe <payloadId> <variant>}，
 * variant ∈ {@code decode} / {@code indexOf} / {@code combo} / {@code count}。</p>
 */
public final class Utf8DecodeProbe {

    private static long sink;
    /**
     * ★ 逃逸锚点：解码出来的 String 必须真的逃逸出去，
     * 否则 C2 的逃逸分析会把整段字节级解码当成无用计算优化掉。
     */
    private static volatile Object esc;

    private Utf8DecodeProbe() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法：Utf8DecodeProbe <payloadId> <decode|indexOf|combo|count>");
            System.exit(2);
        }
        String name = args[0];
        String variant = args[1];

        int len = 16384;
        String s = switch (name) {
            case "ascii-16k" -> BenchPayloads.ascii(len);
            case "mix-1cn" -> BenchPayloads.mixedOneCn(len);
            case "mix-cn10" -> BenchPayloads.mixedRatio(len, 10);
            case "mix-cn50" -> BenchPayloads.mixedRatio(len, 50);
            default -> throw new IllegalArgumentException(name);
        };
        final byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        // 解码结果的内部 coder：LATIN1(0) 还是 UTF16(1)
        final String decoded = new String(utf8, StandardCharsets.UTF_8);
        String coder = StringUtilCoder.of(decoded);
        boolean hasFffd = decoded.indexOf('�') >= 0;

        System.out.printf("%n=== %s : chars=%d utf8Bytes=%d decodedChars=%d coder=%s hasFFFD=%s%n",
                name, s.length(), utf8.length, decoded.length(), coder, hasFffd);

        double t = switch (variant) {
            case "decode" -> bench(() -> {
                String x = new String(utf8, StandardCharsets.UTF_8);
                esc = x;
                sink += x.length();
                return 0;
            });
            case "indexOf" -> bench(() -> {
                sink += decoded.indexOf('�');
                return 0;
            });
            case "combo" -> bench(() -> {
                String x = new String(utf8, StandardCharsets.UTF_8);
                esc = x;
                if (x.indexOf('�') >= 0) {
                    sink += x.length();
                }
                sink += x.length();
                return 0;
            });
            case "count" -> bench(() -> {
                int c = 0;
                for (int i = 0; i < utf8.length; i++) {
                    if (utf8[i] < 0) {
                        c++;
                    }
                }
                sink += c;
                return 0;
            });
            default -> throw new IllegalArgumentException(variant);
        };

        System.out.printf("  变体 %-8s → %9.1f ns%n", variant, t);
    }

    private static double bench(Task r) {
        for (int i = 0; i < 2000; i++) {
            r.run();
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long deadline = System.nanoTime() + 200_000_000L;
            long ops = 0;
            long start = System.nanoTime();
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 20; i++) {
                    r.run();
                    ops++;
                }
            }
            long elapsed = System.nanoTime() - start;
            double ns = ops == 0 ? 0 : (double) elapsed / ops;
            if (ns < best) {
                best = ns;
            }
        }
        return best;
    }

    /**
     * 只为拿 String 的内部 coder，不依赖 edap 的 Unsafe 工具（探针要能独立跑）。
     */
    static final class StringUtilCoder {
        static String of(String s) {
            try {
                java.lang.reflect.Field f = String.class.getDeclaredField("coder");
                f.setAccessible(true);
                byte c = (byte) f.get(s);
                return c == 0 ? "LATIN1" : "UTF16";
            } catch (Exception e) {
                return "unknown(" + e + ")";
            }
        }
    }

    private interface Task {
        long run();
    }
}
