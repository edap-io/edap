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

import io.edap.util.UnsafeUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 字符串<b>解码</b>路径的候选实现对比 —— 回答「改 {@code ByteArrayReader.readString} 时该选哪种算法」。
 *
 * <p>现状（候选 A）：逐字节手写 UTF-8 解码 → {@code char[]}（4096 以内用 ThreadLocal 复用，
 * 超过就 new）→ {@code new String(char[], 0, index)}
 * = <b>2 遍全量拷贝 + 1 个中间 char[]</b>。</p>
 *
 * <p>protobuf-java 走 {@code new String(bytes, off, len, UTF_8)}：JDK 内置解码器对纯 ASCII
 * 直接产出 <b>Latin1 String，零中间 char[]</b>。</p>
 *
 * <p>五个候选（合法输入下必须产出完全相同的字符串，探针会断言）：</p>
 * <table border="1">
 *   <caption>候选</caption>
 *   <tr><th>候选</th><th>做法</th><th>非法 UTF-8 时</th></tr>
 *   <tr><td>A 现状</td><td>手写解码 → char[] → new String</td><td>抛 RuntimeException</td></tr>
 *   <tr><td>B 扫描+裸拷</td><td>逐字节扫，�� ASCII ⇒ new String(buf, ISO_8859_1)；否则走 A</td>
 *       <td>抛 RuntimeException</td></tr>
 *   <tr><td>C 8字节扫描</td><td>同 B，但 ASCII 判定用 {@code Unsafe.getLong} 一次读 8 字节</td>
 *       <td>抛 RuntimeException</td></tr>
 *   <tr><td>D JDK UTF-8</td><td>直接 {@code new String(buf, off, len, UTF_8)}</td>
 *       <td><b>替换成 U+FFFD</b>（宽松）</td></tr>
 *   <tr><td>E JDK+严格</td><td>同 D，再扫一遍有没有 U+FFFD；有则回退 A 抛异常</td>
 *       <td>抛 RuntimeException</td></tr>
 * </table>
 */
public final class DecodeCandidates {

    private DecodeCandidates() {
    }

    /**
     * 候选 A：现状 —— 手写 UTF-8 解码（与 {@code ByteArrayReader.readString} 同算法）。
     */
    static String candCurrent(byte[] buf, int pos, int len) {
        char[] cs;
        if (len < 4096) {
            cs = TMP_CHAR.get();
        } else {
            cs = new char[len];
        }
        int index = 0;
        int tmpPos = pos;
        for (int i = 0; i < len; i++) {
            int b = buf[tmpPos++];
            if ((b & 0x80) == 0) {
                cs[index++] = (char) b;
            } else {
                byte b2 = buf[tmpPos++];
                i++;
                if ((b & 0xE0) == 0xC0) {
                    cs[index++] = (char) (((b & 0x1F) << 6) + (b2 & 0x3F));
                } else {
                    byte b3 = buf[tmpPos++];
                    i++;
                    if ((b & 0xF0) == 0xE0) {
                        cs[index++] = (char) (((b & 0x0F) << 12)
                                + ((b2 & 0x3F) << 6) + (b3 & 0x3F));
                    } else {
                        byte b4 = buf[tmpPos++];
                        i++;
                        if ((b & 0xF8) == 0xF0) {
                            b = ((b & 0x07) << 18) + ((b2 & 0x3F) << 12)
                                    + ((b3 & 0x3F) << 6) + (b4 & 0x3F);
                        } else {
                            throw new RuntimeException();
                        }
                        if (b >= 0x10000) {
                            if (b >= 0x110000) {
                                throw new RuntimeException();
                            }
                            int sup = b - 0x10000;
                            cs[index++] = (char) ((sup >>> 10) + 0xd800);
                            cs[index++] = (char) ((sup & 0x3ff) + 0xdc00);
                        }
                    }
                }
            }
        }
        return new String(cs, 0, index);
    }

    /**
     * 候选 B：逐字节 ASCII 扫描 + 裸拷。
     */
    static String candScanCopy(byte[] buf, int pos, int len) {
        int end = pos + len;
        int i = pos;
        while (i < end && (buf[i] & 0x80) == 0) {
            i++;
        }
        if (i == end) {
            return new String(buf, pos, len, StandardCharsets.ISO_8859_1);
        }
        return candCurrent(buf, pos, len);
    }

    /**
     * 候选 C：8 字节一次读 + 裸拷。
     */
    static String candScan8Copy(byte[] buf, int pos, int len) {
        int end = pos + len;
        int i = pos;
        try {
            for (; i + 8 <= end; i += 8) {
                if ((UnsafeUtil.UNSAFE.getLong(buf, (long) i) & 0x8080808080808080L) != 0) {
                    break;
                }
            }
        } catch (Throwable e) {
            return candScanCopy(buf, pos, len);
        }
        if (i + 8 > end) {
            for (; i < end; i++) {
                if ((buf[i] & 0x80) != 0) {
                    return candCurrent(buf, pos, len);
                }
            }
            return new String(buf, pos, len, StandardCharsets.ISO_8859_1);
        }
        return candCurrent(buf, pos, len);
    }

    /**
     * 候选 D：JDK 内置 UTF-8 解码（宽松：非法序列替换成 U+FFFD）。
     */
    static String candJdkUtf8(byte[] buf, int pos, int len) {
        return new String(buf, pos, len, StandardCharsets.UTF_8);
    }

    /**
     * 候选 E：JDK 内置 UTF-8 解码 + U+FFFD 检测（严格：非法序列仍抛异常）。
     */
    static String candJdkUtf8Strict(byte[] buf, int pos, int len) {
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0) {
            // 可能含合法的 U+FFFD（源字节是 EF BF BD），交回手写解码器判定
            return candCurrent(buf, pos, len);
        }
        return s;
    }

    /**
     * 候选 F：<b>真实的</b> {@code ByteArrayReader.readString()}（带长度前缀的完整帧）。
     *
     * <p>用来核对「本文件的复刻版（候选 A）与线上的实现是不是同一件事」——
     * 两者数字对不上就说明复刻漏了什么。</p>
     */
    static String candRealReader(byte[] framed) {
        try {
            return new io.edap.protobuf.reader.ByteArrayReader(framed).readString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 给定载荷构造「长度前缀 + 载荷」的 protobuf 帧（readString 自己要读 varint 长度）。
     */
    static byte[] frame(byte[] payload) {
        int len = payload.length;
        byte[] framed = new byte[len + 5];
        int p = 0;
        int v = len;
        while ((v & ~0x7F) != 0) {
            framed[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        framed[p++] = (byte) v;
        System.arraycopy(payload, 0, framed, p, len);
        return framed;
    }

    // =====================================================================
    // 跑对比
    // =====================================================================

    static void run(long millis, int rounds) {
        String[] names = {"ascii-1k", "ascii-16k", "cn-1k", "cn-16k", "emoji-1k"};
        byte[][] payloads = new byte[names.length][];

        for (int k = 0; k < names.length; k++) {
            String s;
            switch (k) {
                case 0:
                    s = ascii(1024);
                    break;
                case 1:
                    s = ascii(16384);
                    break;
                case 2:
                    s = chinese(1024);
                    break;
                case 3:
                    s = chinese(16384);
                    break;
                default:
                    s = emoji(1024);
                    break;
            }
            payloads[k] = s.getBytes(StandardCharsets.UTF_8);
        }

        System.out.printf("%n%-14s | %10s %10s %10s %10s %10s %10s | %10s%n",
                "串", "A 现状", "B 扫描裸拷", "C 8字节扫描", "D JDK UTF8", "E JDK+严格", "F 真实reader", "参照:纯拷贝");
        System.out.println("-".repeat(112));

        for (int k = 0; k < names.length; k++) {
            byte[] payload = payloads[k];
            int len = payload.length;
            byte[] framed = frame(payload);

            // ★ 先断言：所有候选必须解出同一个字符串
            String a = candCurrent(payload, 0, len);
            String b = candScanCopy(payload, 0, len);
            String c = candScan8Copy(payload, 0, len);
            String d = candJdkUtf8(payload, 0, len);
            String e = candJdkUtf8Strict(payload, 0, len);
            String f = candRealReader(framed);
            if (!a.equals(b) || !a.equals(c) || !a.equals(d) || !a.equals(e) || !a.equals(f)) {
                throw new IllegalStateException("候选解码结果不一致：" + names[k]);
            }

            double ta = measure(millis, rounds, () -> {
                String s = candCurrent(payload, 0, len);
                return s.length();
            });
            double tb = measure(millis, rounds, () -> {
                String s = candScanCopy(payload, 0, len);
                return s.length();
            });
            double tc = measure(millis, rounds, () -> {
                String s = candScan8Copy(payload, 0, len);
                return s.length();
            });
            double td = measure(millis, rounds, () -> {
                String s = candJdkUtf8(payload, 0, len);
                return s.length();
            });
            double te = measure(millis, rounds, () -> {
                String s = candJdkUtf8Strict(payload, 0, len);
                return s.length();
            });
            double tf = measure(millis, rounds, () -> {
                String s = candRealReader(framed);
                return s.length();
            });

            // 参照：单纯一次整块拷贝的代价
            byte[] raw = new byte[len];
            double copy = measure(millis, rounds, () -> {
                System.arraycopy(payload, 0, raw, 0, len);
                return raw[0];
            });

            System.out.printf("%-14s | %10.0f %10.0f %10.0f %10.0f %10.0f %10.0f | %10.0f%n",
                    names[k], ta, tb, tc, td, te, tf, copy);
        }
    }

    private static double measure(long millis, int rounds, Op op) {
        double[] ns = new double[rounds];
        for (int i = 0; i < rounds; i++) {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            long ops = 0;
            long start = System.nanoTime();
            while (System.nanoTime() < deadline) {
                for (int j = 0; j < 50; j++) {
                    sink(op);
                    ops++;
                }
            }
            long elapsed = System.nanoTime() - start;
            ns[i] = ops == 0 ? 0 : (double) elapsed / ops;
        }
        Arrays.sort(ns);
        return ns[rounds / 2];
    }

    private static long sink(Op op) {
        try {
            return op.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private static String emoji(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("😀");
        }
        return sb.toString();
    }

    private static final ThreadLocal<char[]> TMP_CHAR = ThreadLocal.withInitial(() -> new char[4096]);

    private interface Op {
        long call() throws Exception;
    }
}
