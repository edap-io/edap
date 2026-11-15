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
 * 验证 JDK 17 {@code String(byte[],int,int,UTF_8)} 的「Latin1 先解、遇到非 Latin1 再 inflate」路径：
 * <b>解码成本随「第一个非 Latin1 字符在串里的位置」变化</b> ——
 * 越靠后，前面那段 ASCII 就要被拷贝两遍（先拷进 Latin1 缓冲，再 inflate 成 UTF16）。
 *
 * <p>用例：同样 16384 个字符、同样的 UTF-8 字节数（1 个中文 + 16383 个 ASCII），
 * 只挪动那个中文的位置。</p>
 */
public final class NonLatin1PositionProbe {

    private static long sink;
    private static volatile Object esc;

    private NonLatin1PositionProbe() {
    }

    public static void main(String[] args) {
        int len = 16384;
        // 中文在第 idx 个字符处
        int[] positions = {0, 1, 10, 100, 1024, 4096, 8192, 16383};
        for (int idx : positions) {
            String s = build(len, idx);
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            double t = bench(() -> {
                String x = new String(utf8, StandardCharsets.UTF_8);
                esc = x;
                sink += x.length();
                return 0;
            });
            System.out.printf("中文在第 %6d 个字符  (utf8=%d 字节)  →  %9.1f ns%n", idx, utf8.length, t);
        }
        // 对照：纯 ASCII
        byte[] ascii = build(len, -1).getBytes(StandardCharsets.UTF_8);
        double ta = bench(() -> {
            String x = new String(ascii, StandardCharsets.UTF_8);
            esc = x;
            sink += x.length();
            return 0;
        });
        System.out.printf("纯 ASCII                    (utf8=%d 字节)  →  %9.1f ns%n", ascii.length, ta);
    }

    /** @param cnAt 中文所在下标；<0 表示纯 ASCII */
    private static String build(int len, int cnAt) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            if (i == cnAt) {
                sb.append('中');
            } else {
                sb.append((char) ('a' + (i % 26)));
            }
        }
        return sb.toString();
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

    private interface Task {
        long run();
    }
}
