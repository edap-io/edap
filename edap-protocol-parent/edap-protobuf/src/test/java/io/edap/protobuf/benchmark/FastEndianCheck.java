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
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * FAST codec 的**端序一致性**自检。
 *
 * <p><b>背景</b>：JDK 的 String 内部 UTF-16 字节序 = <b>原生端序</b>
 * （{@code java.lang.StringUTF16} 的 {@code <clinit>} 调 native {@code isBigEndian()}），
 * 而 FAST 的 writer 把 String 的内部 byte[] <b>原样</b>写出去
 * ⇒ wire 上的字节序 = 生产方机器的端序。
 * ⇒ 现在的协议是「<b>UTF16 标记携带发送方的字节序</b>」：
 * 写端按本机端序选标记（{@code UTF16LE_BYTE}=1 / {@code UTF16BE_BYTE}=3），
 * 读端只在「对方端序 ≠ 自己端序」时才翻一次。</p>
 *
 * <p><b>本机没有大端 JVM</b>，所以大端侧是「模拟」的：
 * 大端写端写出的东西 = 「把 LE 字节翻成 BE + 标记换成 3」，
 * 本检查直接构造这种字节，喂给真实的读端代码路径，验证它能还原。</p>
 *
 * <p>用法：{@code java ... io.edap.protobuf.benchmark.FastEndianCheck}</p>
 */
public final class FastEndianCheck {

    private FastEndianCheck() {
    }

    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("本机原生端序 = " + (StringUtil.NATIVE_LITTLE_ENDIAN ? "小端 (LE)" : "大端 (BE)"));
        System.out.println();

        String cn = "中文测试abc中文";
        byte[] enc = encode(cn);
        if (enc == null) {
            System.out.println("★ 编码失败");
            System.exit(1);
        }

        // ── wire 布局：末尾是 [长度 varint][charset 标记][payload] ──────
        int payloadLen = cn.length() * 2;
        int markerPos = enc.length - payloadLen - 1;
        byte marker = enc[markerPos];

        check("写端标记 = 1(LE) 或 3(BE)", marker == 1 || marker == 3);
        check("标记与本机端序一致", (marker == 1) == StringUtil.NATIVE_LITTLE_ENDIAN);
        check("payload 长度正确", payloadLen == enc.length - markerPos - 1);
        check("wire 字节序 = 本机端序",
                StringUtil.NATIVE_LITTLE_ENDIAN ? isLittleEndianPayload(enc, markerPos + 1) : isBigEndianPayload(enc, markerPos + 1));

        // ── 1. 本机 round-trip ──────────────────────────────────────────
        check("本机 round-trip 内容相等", cn.equals(decode(enc)));

        // ── 2. 模拟「大端写端 + 新协议」（标记 3 + BE 字节）────────────
        byte[] beNew = enc.clone();
        beNew[markerPos] = 3;
        StringUtil.swapUtf16ByteOrder(beNew, markerPos + 1, payloadLen);
        check("大端写端（新协议）产出 BE 字节", isBigEndianPayload(beNew, markerPos + 1));
        check("★ 跨端序读端能正确还原（大端写 + 小端读）", cn.equals(decode(beNew)));

        // ── 3. 模拟「旧大端写端」（标记 1 + BE 字节）—— 已知是坏的 ────
        byte[] beOld = enc.clone();
        StringUtil.swapUtf16ByteOrder(beOld, markerPos + 1, payloadLen);
        String oldRes = decode(beOld);
        check("旧大端写端（标记1+BE字节）解出乱码（已知缺陷，记录在案）", oldRes != null && !cn.equals(oldRes));

        // ── 4. 未知标记 ⇒ fail-fast（不再当 UTF-8 静默解）──────────────
        byte[] badMarker = enc.clone();
        badMarker[markerPos] = 7;
        check("未知 charset 标记 ⇒ 解码失败（不再静默乱码）", decode(badMarker) == null);

        // ── 5. 翻字节是对合的（读端翻一次能还原）────────────────────────
        byte[] t = "AB".getBytes(StandardCharsets.ISO_8859_1);
        byte[] t2 = t.clone();
        StringUtil.swapUtf16ByteOrder(t2, 0, 2);
        StringUtil.swapUtf16ByteOrder(t2, 0, 2);
        check("swapUtf16ByteOrder 是对合的", Arrays.equals(t, t2));

        // ── 6. Latin1 / UTF8 分支与端序无关 ────────────────────────────
        byte[] ascii = encode("hello ascii");
        check("Latin1 串 round-trip", "hello ascii".equals(decode(ascii)));

        System.out.println();
        System.out.println(failed == 0 ? "★ 全部通过" : ("★ 有 " + failed + " 项失败"));
        System.exit(failed == 0 ? 0 : 1);
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static byte[] encode(String s) {
        BenchStr b = new BenchStr();
        b.setS(s);
        return ProtoBuf.fastToByteArray(b);
    }

    private static String decode(byte[] bs) {
        return ProtoBuf.fastToObject(bs, BenchStr.class) == null ? null : ProtoBuf.fastToObject(bs, BenchStr.class).getS();
    }

    /** payload 是否是小端字节序（每个字符低字节在前）。 */
    private static boolean isLittleEndianPayload(byte[] bs, int off) {
        // "中" = U+4E2D ⇒ LE = 2D 4E，BE = 4E 2D
        return (bs[off] & 0xFF) == 0x2D && (bs[off + 1] & 0xFF) == 0x4E;
    }

    /** payload 是否是大端字节序。 */
    private static boolean isBigEndianPayload(byte[] bs, int off) {
        return (bs[off] & 0xFF) == 0x4E && (bs[off + 1] & 0xFF) == 0x2D;
    }

    private static void check(String what, boolean ok) {
        System.out.printf("%s %s%n", ok ? "  ✔" : "  ✘", what);
        if (!ok) {
            failed++;
        }
    }
}
