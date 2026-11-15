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

import java.nio.charset.StandardCharsets;

/**
 * 决定性实验：标准版解码的 UTF-8 解码成本，是否被 JIT 的逃逸分析（EA）优化掉了。
 *
 * <p>四个变体，同一段字节、同一份源码，唯一差别是「解码出来的 String 是否逃逸」：</p>
 * <ol>
 *   <li>{@code whole-esc}：{@code new String(bytes, UTF_8)}，结果写进 static 字段 ⇒ 逃逸</li>
 *   <li>{@code whole-noesc}：同式，但只读 {@code length()} ⇒ 不逃逸（EA 可干掉解码）</li>
 *   <li>{@code off-esc}：{@code new String(bytes, 0, len, UTF_8)}，结果写进 static 字段 ⇒ 逃逸</li>
 *   <li>{@code off-noesc}：同式，只读 {@code length()} ⇒ 不逃逸</li>
 * </ol>
 *
 * <p>用法：{@code EscapeProbe <payloadId> <variant>}（一个负载一个 JVM）。</p>
 */
public final class EscapeProbe {

    private static long sink;
    /** ★ 逃逸锚点：让解码出来的 String 真的逃逸出去，EA 就不能把解码优化掉。 */
    private static volatile Object esc;

    private EscapeProbe() {
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "mix-1cn";
        String variant = args.length > 1 ? args[1] : "off-esc";

        BenchPayloads.Payload p = BenchPayloads.of(id);
        byte[] wire = ProtoBuf.toByteArray(p.obj);

        // 剥出 payload：tag(1) + varint 长度
        int pos = 1;
        int len = 0;
        int shift = 0;
        while (true) {
            int b = wire[pos++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        final byte[] payload = new byte[len];
        System.arraycopy(wire, pos, payload, 0, len);

        double t = switch (variant) {
            case "whole-esc" -> bench(() -> {
                String s = new String(payload, StandardCharsets.UTF_8);
                esc = s;
                sink += s.length();
                return 0;
            });
            case "whole-noesc" -> bench(() -> {
                String s = new String(payload, StandardCharsets.UTF_8);
                sink += s.length();
                return 0;
            });
            case "off-esc" -> bench(() -> {
                String s = new String(payload, 0, payload.length, StandardCharsets.UTF_8);
                esc = s;
                sink += s.length();
                return 0;
            });
            case "off-noesc" -> bench(() -> {
                String s = new String(payload, 0, payload.length, StandardCharsets.UTF_8);
                sink += s.length();
                return 0;
            });
            case "whole-noesc-ret" -> bench(() -> {
                // ★ 与 Utf8DecodeProbe ① 完全同形（lambda 返回 x.length()）
                String x = new String(payload, StandardCharsets.UTF_8);
                sink += x.length();
                return x.length();
            });
            case "getbytes-esc" -> bench(() -> {
                // ★ 数组来源换成 getBytes(UTF_8)（Utf8DecodeProbe 用的是这个来源）
                byte[] gb = ((BenchStr) p.obj).getS().getBytes(StandardCharsets.UTF_8);
                String s = new String(gb, StandardCharsets.UTF_8);
                esc = s;
                sink += s.length();
                return 0;
            });
            case "preamble-esc" -> {
                // ★ 把 Utf8DecodeProbe 的前置语句搬进来：先解一份同样的串 + 反射读 coder + 全文扫 U+FFFD
                final String pre = new String(payload, StandardCharsets.UTF_8);
                String coder = Utf8DecodeProbe.StringUtilCoder.of(pre);
                boolean preHasFffd = pre.indexOf('�') >= 0;
                System.out.printf("   （前置：coder=%s hasFFFD=%s）%n", coder, preHasFffd);
                yield bench(() -> {
                    String x = new String(payload, StandardCharsets.UTF_8);
                    esc = x;
                    sink += x.length();
                    return 0;
                });
            }
            default -> throw new IllegalArgumentException(variant);
        };

        System.out.printf("%-12s %-12s payload=%d  →  %9.1f ns%n", id, variant, payload.length, t);
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
