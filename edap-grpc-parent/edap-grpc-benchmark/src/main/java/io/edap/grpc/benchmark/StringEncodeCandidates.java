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
import io.edap.util.StringUtil;
import io.edap.util.UnsafeUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 字符串<b>编码</b>路径的候选实现对比 —— 回答「{@code AbstractWriter.writeString0} 该怎么改」。
 *
 * <p>protobuf 的 {@code string} 字段的 wire 格式是 UTF-8。当前 {@code writeString0} 有两条分支：</p>
 * <ul>
 *   <li><b>Latin1</b>（JDK9+ compact string）：{@code v.getBytes(UTF_8)} + 整块拷进写缓冲
 *       ⇒ 1 次分配 + 1 次编码 + 1 次拷贝；</li>
 *   <li><b>UTF16</b>（含中文等非 Latin1 字符）：{@code toCharArray()} + 逐字符逐字节
 *       {@code Unsafe.writeByte} + 整块右移补长度前缀
 *       ⇒ 1 次 char[] 分配 + 1 次拷贝 + N 次单字节写 + 1 次 memmove。</li>
 * </ul>
 *
 * <p>实测：ASCII 路径 edap 已经比 protobuf-java 快一个数量级，但<b>中文路径慢 1.3x 左右</b>
 * ⇒ 本探针要回答的就是「中文路径怎么改最快」。</p>
 *
 * <p><b>探针的核心目的：把 27% 的差距拆开归因</b>。protobuf-java 的
 * {@code Utf8.encodeUtf8}（JDK9+ 走 {@code UnsafeProcessor}）与 edap 的差别有 4 个可分离的因子：</p>
 * <ol>
 *   <li><b>memmove</b>：edap 先写内容再整块右移补长度前缀；pb 先<b>预留</b> varint 字节、
 *       写完内容再回退补长度 ⇒ 无 memmove；</li>
 *   <li><b>charAt vs char[]</b>：pb 用 {@code charAt}，edap 用 {@code toCharArray()}；</li>
 *   <li><b>索引宽度</b>：pb 的输出指针是 {@code long}，edap 是 {@code int}；</li>
 *   <li><b>循环形态</b>：pb 的循环里没有「按参数分支选择写法」的多态路径。</li>
 * </ol>
 * 下面的候选把这 4 个因子正交拆开，逐个测出来。
 *
 * <p>所有候选产出的字节必须<b>逐字节相同</b>（探针会断言），否则就是错的。</p>
 */
public final class StringEncodeCandidates {

    private StringEncodeCandidates() {
    }

    // =====================================================================
    // 候选实现
    // =====================================================================

    /**
     * 候选 0：<b>现状</b>（{@code AbstractWriter.writeString0} 的等价复刻）。
     *
     * <p>Latin1 ⇒ {@code getBytes(UTF_8)} + 拷贝；UTF16 ⇒ {@code toCharArray()}
     * + 逐字节 {@code Unsafe.writeByte} + 整块右移补长度前缀。</p>
     */
    static int candCurrent(String v, byte[] dst, int off) {
        int charLen = v.length();
        if (StringUtil.IS_BYTE_ARRAY && StringUtil.isLatin1(v)) {
            byte[] utf8 = v.getBytes(StandardCharsets.UTF_8);
            int n = varintLen(utf8.length);
            writeVarint(dst, off, utf8.length);
            System.arraycopy(utf8, 0, dst, off + n, utf8.length);
            return n + utf8.length;
        }
        // UTF16：先写内容，再整块右移补长度前缀
        char[] chars = v.toCharArray();
        int len = encodeCharsUnsafe(chars, dst, off + 1);
        int n = varintLen(len);
        if (n > 1) {
            System.arraycopy(dst, off + 1, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 14：<b>Latin1 直写</b> —— 不调 {@code String.getBytes(UTF_8)}，
     * 直接拿 String <b>内部</b>的 Latin1 {@code byte[]}：
     *
     * <ul>
     *   <li><b>ASCII 段</b>（{@code 0x00~0x7F}）：<b>一次 {@code System.arraycopy}</b>
     *       （byte[]→byte[]，即 memcpy）⇒ 代替 N 次逐字节写；</li>
     *   <li><b>高位段</b>（{@code 0x80~0xFF}，Latin-1）：每字符 2 字节 UTF-8，逐字节写。</li>
     * </ul>
     *
     * <p>★ 相比现状省掉：<b>一次数组分配</b>（{@code getBytes} 的结果）
     * + JDK 自己的一遍逐字节写 + edap 的一遍 {@code arraycopy}（现在合成一次）。</p>
     *
     * <p>⚠ Latin-1 高位字节（0x80~0xFF）不是合法 UTF-8，必须编码成 2 字节 ——
     * 这正是现状「必须让 JDK 做 UTF-8 编码」的原因，本候选只把<b>编码算法</b>内联，
     * 不改变字节结果。</p>
     */
    static int candLatin1Direct(String v, byte[] dst, int off) {
        if (!StringUtil.IS_BYTE_ARRAY || !StringUtil.isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        byte[] internal = latin1Internal(v);
        if (internal == null) {
            return candCurrent(v, dst, off);
        }
        int n = internal.length;
        // 精确 UTF-8 长度：每字符 1 字节，Latin-1 高位字符（0x80~0xFF）多 1 字节
        int high = 0;
        for (int i = 0; i < n; i++) {
            if (internal[i] < 0) {
                high++;
            }
        }
        int len = n + high;
        int vn = varintLen(len);
        writeVarint(dst, off, len);
        // 分段写：连续 ASCII 段一次 arraycopy（memcpy），高位字符逐个 2 字节
        int p = off + vn;
        int i = 0;
        while (i < n) {
            if (internal[i] >= 0) {
                int start = i;
                while (i < n && internal[i] >= 0) {
                    i++;
                }
                System.arraycopy(internal, start, dst, p, i - start);
                p += i - start;
            } else {
                int u = internal[i] & 0xFF;
                dst[p++] = (byte) (0xC0 | (u >> 6));
                dst[p++] = (byte) (0x80 | (u & 0x3F));
                i++;
            }
        }
        return vn + len;
    }

    /**
     * 候选 17：<b>Latin1 主路径</b> —— 「纯 ASCII ⇒ 一次 arraycopy，零分配」。
     *
     * <p>★ 形态来自「Latin-1 高位字符场景罕见、多处理一遍也可以」的约束：
     * 主路径只做两件事：<b>① 判定有没有高位字符</b>（向量化）<b>② 一次 arraycopy</b>；
     * 一旦发现高位字符（罕见）就退回现状路径（{@code getBytes(UTF_8)}），慢一点无所谓。</p>
     *
     * <p>对比现状（{@code getBytes(UTF_8)} + {@code arraycopy}）省掉：
     * <b>一次数组分配 + JDK 的一遍写 + edap 的一遍 arraycopy</b> ⇒ 只剩一次 arraycopy。</p>
     */
    static int candLatin1AsciiFast(String v, byte[] dst, int off) {
        if (!StringUtil.IS_BYTE_ARRAY || !StringUtil.isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        byte[] internal = latin1Internal(v);
        if (internal == null || hasHighByte(internal)) {
            // 罕见路径：有 Latin-1 高位字符 ⇒ 退回现状
            return candCurrent(v, dst, off);
        }
        int len = internal.length;
        int vn = varintLen(len);
        writeVarint(dst, off, len);
        System.arraycopy(internal, 0, dst, off + vn, len);
        return vn + len;
    }

    /**
     * 判定 byte[] 里有没有 Latin-1 高位字节（{@code 0x80~0xFF}）。
     *
     * <p>★ <b>8 字节一批</b>：用一个 long 装 8 个字节，掩码 {@code 0x8080808080808080}
     * 一次测出这 8 个字节里有没有高位字节 ⇒ 16KB 只需 2048 次循环，
     * 比逐字节分支循环（实测 2905ns/16KB）快一个数量级。</p>
     */
    static boolean hasHighByte(byte[] a) {
        int n = a.length;
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            if ((UnsafeUtil.getLong(a, i) & 0x8080808080808080L) != 0L) {
                return true;
            }
        }
        for (; i < n; i++) {
            if (a[i] < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定 byte[] 里有没有 Latin-1 高位字节 —— <b>无分支求和版</b>。
     *
     * <p>★ 关键技巧：{@code (b >> 7) & 1} 对 signed byte 恰好是「负数得 1、正数得 0」，
     * 于是统计高位字符数<b>不需要任何分支，也没有循环携带依赖</b>
     * ⇒ C2 可以把这段循环<b>自动向量化</b>（一次处理 4~8 个字节）。</p>
     */
    static int countHighBranchless(byte[] a) {
        int n = a.length;
        int high = 0;
        int i = 0;
        for (; i + 4 <= n; i += 4) {
            high += (a[i] >> 7) & 1;
            high += (a[i + 1] >> 7) & 1;
            high += (a[i + 2] >> 7) & 1;
            high += (a[i + 3] >> 7) & 1;
        }
        for (; i < n; i++) {
            high += (a[i] >> 7) & 1;
        }
        return high;
    }

    /**
     * 候选 20：<b>int 偏移 + 预留 varint</b>（不右移）——
     * 与候选 3（long 偏移 + 预留 varint）只差「偏移类型」，
     * 与候选 0（int 偏移 + 右移）只差「memmove」⇒ 用来把两者拆开。
     */
    static int candCharArrUnsafeIntReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int len = encodeCharsUnsafe(chars, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 21：<b>long 偏移 + 右移</b>（现状的 memmove 形态）——
     * 与候选 0（int 偏移 + 右移）只差「偏移类型」，
     * 与候选 3（long 偏移 + 预留 varint）只差「memmove」。
     */
    static int candCharArrUnsafeLongMove(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int len = encodeCharsUnsafeLong(chars, dst, off + 1);
        int n = varintLen(len);
        if (n > 1) {
            System.arraycopy(dst, off + 1, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 22：<b>分段 + ASCII 段 8 字符拼 long 批量 + 非 ASCII 段 int 偏移逐字节</b>。
     *
     * <p>★ 动机：mixed（中英混排）负载上，long 偏移版比 int 偏移版<b>慢 38%</b>
     * （23755 vs 17242），而纯中文负载上 long 版快 27% ⇒ 同一个「偏移类型」在两类负载上
     * 方向相反 ⇒ 根因是 <b>JIT 编译质量</b>（分支布局/预测），不是语言语义。</p>
     *
     * <p>★ 解法：<b>按内容分段</b> —— 先扫出连续 ASCII 段，段内分支恒定（可预测），
     * ASCII 段还顺带批量写（8 字符 1 个 long）。非 ASCII 段用 int 偏移（与现状一致）。</p>
     */
    static int candSegAsciiBatchInt(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int p = off + reserve;
        int i = 0;
        while (i < charLen) {
            char c = chars[i];
            if (c < 0x80) {
                int start = i;
                while (i < charLen && chars[i] < 0x80) {
                    i++;
                }
                int j = start;
                for (; j + 8 <= i; j += 8) {
                    long val = 0;
                    for (int k = 0; k < 8; k++) {
                        val = (val << 8) | (chars[j + k] & 0xFFL);
                    }
                    UnsafeUtil.putLong(dst, p, val);
                    p += 8;
                }
                for (; j < i; j++) {
                    UnsafeUtil.writeByte(dst, p++, (byte) chars[j]);
                }
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
                i++;
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.writeByte(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i += 2;
            } else {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
                i++;
            }
        }
        int len = p - (off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 23：<b>只分段（ASCII 段也逐字节），非 ASCII 段 int 偏移</b> ——
     * 用来把「分段」的收益与「ASCII 批量」的收益拆开。
     */
    static int candSegOnlyInt(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int p = off + reserve;
        int i = 0;
        while (i < charLen) {
            char c = chars[i];
            if (c < 0x80) {
                int start = i;
                while (i < charLen && chars[i] < 0x80) {
                    i++;
                }
                for (int j = start; j < i; j++) {
                    UnsafeUtil.writeByte(dst, p++, (byte) chars[j]);
                }
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
                i++;
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.writeByte(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i += 2;
            } else {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
                i++;
            }
        }
        int len = p - (off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 19：<b>只做「高位字符计数」（无分支求和版）</b>（不写字节）——
     * 与候选 18（long 批量 + 提前退出）对照，看哪种判定更快。
     */
    static int candLatin1CountHigh(String v, byte[] dst, int off) {
        if (!StringUtil.IS_BYTE_ARRAY || !StringUtil.isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        byte[] internal = latin1Internal(v);
        if (internal == null) {
            return candCurrent(v, dst, off);
        }
        return countHighBranchless(internal);
    }

    /**
     * 候选 18：<b>只做「有无高位字符」判定</b>（不写字节）——
     * 用来量候选 17 主路径里判定这一步的成本。
     */
    static int candLatin1HasHigh(String v, byte[] dst, int off) {
        if (!StringUtil.IS_BYTE_ARRAY || !StringUtil.isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        byte[] internal = latin1Internal(v);
        if (internal == null) {
            return candCurrent(v, dst, off);
        }
        return hasHighByte(internal) ? 1 : 0;
    }

    /**
     * 候选 16：<b>只取内部数组 + 统计高位字符</b>（不写任何字节）——
     * 用来定位候选 14 的开销构成（{@code Unsafe.getObject} + 扫描各占多少）。
     */
    static int candLatin1Probe(String v, byte[] dst, int off) {
        if (!StringUtil.IS_BYTE_ARRAY || !StringUtil.isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        byte[] internal = latin1Internal(v);
        if (internal == null) {
            return candCurrent(v, dst, off);
        }
        int n = internal.length;
        int high = 0;
        for (int i = 0; i < n; i++) {
            if (internal[i] < 0) {
                high++;
            }
        }
        return high;
    }

    /**
     * 候选 15：<b>UTF-16 + ASCII 段 8 字符拼 long 批量写</b>，非 ASCII 段仍逐字节。
     *
     * <p>★ 回应「UTF-16 转 UTF-8 还是逐 char」的质疑：中文每字 3 字节，与 8 字节边界
     * <b>天然不对齐</b> ⇒ 纯中文串无法批量化（候选 12 实测慢 2 倍）。
     * 但 <b>ASCII 段</b>是 1 字节/字符 ⇒ 8 个字符正好拼成一个 long，一次写出去。</p>
     *
     * <p>拼装必须<b>大端</b>（{@code v = (v << 8) | b0} … {@code (v << 8) | b7}），
     * 才与 {@code Unsafe.putLong(byte[], …)} 的大端语义一致。</p>
     */
    static int candUtf16AsciiBatch(String v, byte[] dst, int off) {
        if (StringUtil.IS_BYTE_ARRAY && StringUtil.isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        int charLen = v.length();
        char[] chars = v.toCharArray();
        int reserve = varintLen(charLen * 3);
        long p = off + reserve;
        int i = 0;
        while (i < charLen) {
            char c = chars[i];
            if (c < 0x80) {
                int start = i;
                while (i < charLen && chars[i] < 0x80) {
                    i++;
                }
                int j = start;
                for (; j + 8 <= i; j += 8) {
                    long val = 0;
                    for (int k = 0; k < 8; k++) {
                        val = (val << 8) | (chars[j + k] & 0xFFL);
                    }
                    UnsafeUtil.putLong(dst, p, val);
                    p += 8;
                }
                for (; j < i; j++) {
                    UnsafeUtil.writeByte(dst, p++, (byte) chars[j]);
                }
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
                i++;
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.writeByte(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i += 2;
            } else {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
                i++;
            }
        }
        int len = (int) (p - (off + reserve));
        int vn = varintLen(len);
        if (vn != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + vn, len);
        }
        writeVarint(dst, off, len);
        return vn + len;
    }

    // =====================================================================
    // 候选 25/26/27：char → UTF-8 <b>查表</b>
    // =====================================================================

    /**
     * 候选 25 的表：{@code byte[65536]}，每项是该 char 的 UTF-8 字节（<b>代理项为 null</b>）。
     */
    private static final byte[][] TBL_BB;
    /**
     * 候选 26 的表：扁平 {@code byte[65536*3]}，每个 char 的 UTF-8 字节<b>左对齐</b>存放。
     */
    private static final byte[] TBL_FLAT;
    /**
     * 候选 26/27 的长度表：每个 char 的 UTF-8 字节数（1/2/3）。
     */
    private static final byte[] TBL_LEN;
    /**
     * 候选 27 的表：{@code int[65536]}，<b>按小端内存序预置</b>
     * ⇒ 直接 {@code Unsafe.putInt} 一次写 4 字节，内存布局 = {@code [b0, b1, b2, pad]}。
     */
    private static final int[] TBL_INT;

    static {
        TBL_BB = new byte[0x10000][];
        TBL_FLAT = new byte[0x10000 * 3];
        TBL_LEN = new byte[0x10000];
        TBL_INT = new int[0x10000];
        for (int c = 0; c < 0x10000; c++) {
            if (c >= 0xD800 && c <= 0xDFFF) {
                // 代理项：单 char 无法表达一个代理对 ⇒ 表里留空，循环里走「代理对」分支
                continue;
            }
            byte[] e = utf8Of(c);
            TBL_BB[c] = e;
            int o = c * 3;
            TBL_FLAT[o] = e[0];
            TBL_FLAT[o + 1] = e.length > 1 ? e[1] : 0;
            TBL_FLAT[o + 2] = e.length > 2 ? e[2] : 0;
            TBL_LEN[c] = (byte) e.length;
            // 小端序预置：内存布局 [b0, b1, b2, pad] ⇒ int 的 bit0-7=b0, bit8-15=b1, bit16-23=b2
            // （实测：Unsafe.putInt(byte[], ARRAY_BYTE_BASE_OFFSET+字节偏移, v) 写 4 个字节，x86 是小端）
            TBL_INT[c] = ((e.length > 2 ? (e[2] & 0xFF) : 0) << 16)
                    | ((e.length > 1 ? (e[1] & 0xFF) : 0) << 8)
                    | (e[0] & 0xFF);
        }
    }

    /**
     * 单个 BMP 字符（非代理）的 UTF-8 字节。
     */
    private static byte[] utf8Of(int c) {
        if (c < 0x80) {
            return new byte[]{(byte) c};
        }
        if (c < 0x800) {
            return new byte[]{(byte) (0xC0 | (c >> 6)), (byte) (0x80 | (c & 0x3F))};
        }
        return new byte[]{(byte) (0xE0 | (c >> 12)), (byte) (0x80 | ((c >> 6) & 0x3F)), (byte) (0x80 | (c & 0x3F))};
    }

    /**
     * 候选 25：<b>char → UTF-8 查表</b>（{@code byte[][]} 表）。
     *
     * <p>思路：用「查表」替代循环里的<b>分支判定</b>（{@code c<0x80 / c<0x800 / 代理}），
     * 每步只做一次数组读 + 若干次字节写。</p>
     *
     * <p>⚠ 代理项（0xD800~0xDFFF）在表里是 {@code null} ⇒ 循环里要额外判一次 {@code null}，
     * 这正是「查表」方案的成本之一。</p>
     */
    static int candTableByteArray(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int p = off + reserve;
        for (int i = 0; i < charLen; i++) {
            byte[] e = TBL_BB[chars[i]];
            if (e == null) {
                int cp = Character.toCodePoint(chars[i], chars[i + 1]);
                dst[p++] = (byte) (0xF0 | ((cp >> 18) & 0x07));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                dst[p++] = e[0];
                if (e.length > 1) {
                    dst[p++] = e[1];
                    if (e.length > 2) {
                        dst[p++] = e[2];
                    }
                }
            }
        }
        int len = p - (off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 26：<b>char → UTF-8 查表</b>（扁平 {@code byte[]} 表 + 长度表）。
     *
     * <p>与候选 25 的差别：表项不是对象引用而是<b>一段连续内存</b> ⇒ 少一次指针解引用，
     * 代价是要多一次「长度表」读。</p>
     */
    static int candTableFlat(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int p = off + reserve;
        for (int i = 0; i < charLen; i++) {
            char c = chars[i];
            if (c >= 0xD800 && c <= 0xDFFF) {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                dst[p++] = (byte) (0xF0 | ((cp >> 18) & 0x07));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                int o = c * 3;
                dst[p] = TBL_FLAT[o];
                dst[p + 1] = TBL_FLAT[o + 1];
                dst[p + 2] = TBL_FLAT[o + 2];
                p += TBL_LEN[c];
            }
        }
        int len = p - (off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 27：<b>char → UTF-8 查表</b>（{@code int} 表 + 一次 {@code Unsafe.putInt} 写 4 字节）。
     *
     * <p>★ 关键技巧：表里存的是<b>按小端内存序预置</b>的 int
     * ⇒ {@code Unsafe.putInt}（x86 是小端）写出的 4 个字节正好是 {@code [b0, b1, b2, pad]}；
     * 实际只用前 {@code LEN[c]} 个字节，<b>多写的字节会被下一个字符覆盖</b>
     * ⇒ 循环里既没有分支、也没有多次单字节写。</p>
     *
     * <p>⚠ 需要目标缓冲尾部有 ≥3 字节的 slack（探针的 dst 预留了 32 字节）。</p>
     */
    static int candTableInt(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int p = off + reserve;
        for (int i = 0; i < charLen; i++) {
            char c = chars[i];
            if (c >= 0xD800 && c <= 0xDFFF) {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                dst[p++] = (byte) (0xF0 | ((cp >> 18) & 0x07));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                UnsafeUtil.UNSAFE.putInt(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p, TBL_INT[c]);
                p += TBL_LEN[c];
            }
        }
        int len = p - (off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    // =====================================================================
    // 候选 28/29/30：{@code getBytes(UTF_8)} 的成本分解
    // =====================================================================

    /**
     * 候选 28：<b>只调 {@code getBytes(UTF_8)}</b>（不拷进 dst）——
     * 量「分配 + JDK 的向量化编码」这一段的总成本。
     */
    static int candGetBytesOnly(String v, byte[] dst, int off) {
        return v.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 候选 29：<b>读内部 Latin1 数组 + 一次 arraycopy</b>（零分配、零编码）——
     * 这是「跳过 {@code getBytes} 的 byte[] 生成」能做到的<b>理论下限</b>
     * （前提是字符串是纯 ASCII；高位字符会产出错误字节，故不参与字节断言）。
     */
    static int candInternalCopyOnly(String v, byte[] dst, int off) {
        byte[] internal = latin1Internal(v);
        if (internal == null) {
            return 0;
        }
        System.arraycopy(internal, 0, dst, off, internal.length);
        return internal.length;
    }

    /**
     * 候选 30：<b>只调 {@code getBytes(ISO_8859_1)}</b>（不拷进 dst）——
     * JDK 对 Latin1 串的 ISO-8859-1 编码 = {@code Arrays.copyOf(内部数组)}
     * ⇒ 与候选 28 的差 ≈ 「SIMD 判负 + 高位字符逐字节处理」的成本。
     */
    static int candGetBytesLatin1Only(String v, byte[] dst, int off) {
        return v.getBytes(StandardCharsets.ISO_8859_1).length;
    }

    /**
     * 候选 31：{@code char[]} + <b>普通数组写</b>（<b>long</b> 偏移，强转成 int 索引）+ 预留 varint。
     *
     * <p>⇒ 与候选 3（{@code char[]} + {@code Unsafe} 写 + long 偏移 + 预留）<b>只差「写的形式」</b>；
     * 与候选 4（{@code char[]} + 普通写 + <b>int</b> 偏移 + 预留）<b>只差「偏移类型」</b>。
     * 用来回答「生产代码的 {@code Unsafe} 单字节写是不是比普通数组写慢」。</p>
     */
    static int candCharArrPlainLongReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        long p = off + reserve;
        final int n = chars.length;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (c < 128) {
                dst[(int) p++] = (byte) c;
            } else if (c < 0x800) {
                dst[(int) p++] = (byte) ((0xF << 6) | (c >>> 6));
                dst[(int) p++] = (byte) (0x80 | (0x3F & c));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                dst[(int) p++] = (byte) (0xF0 | ((cp >> 18) & 0x07));
                dst[(int) p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[(int) p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[(int) p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                dst[(int) p++] = (byte) ((0xF << 5) | (c >>> 12));
                dst[(int) p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                dst[(int) p++] = (byte) (0x80 | (0x3F & c));
            }
        }
        int len = (int) (p - (off + reserve));
        int vn = varintLen(len);
        if (vn != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + vn, len);
        }
        writeVarint(dst, off, len);
        return vn + len;
    }

    /**
     * 候选 32：<b>混合式查表</b> —— ASCII 仍走生产的逐字节写，
     * <b>只有 ≥0x80 的字符走 int 表</b>（一次 {@code putInt} 写 4 字节）。
     *
     * <p>动机：候选 27 的表把「3 次单字节写」压成「1 次 4 字节写」，
     * 但对 <b>ASCII 字符反而更贵</b>（多 2 次表读，只省 0 次写）
     * ⇒ 混排负载（80% ASCII）实测慢 15%。</p>
     *
     * <p>⇒ 本候选只在「本来就要写 ≥2 字节」的地方用表 ⇒ ASCII 路径与生产完全一致。</p>
     */
    static int candTableHybrid(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        long p = off + reserve;
        for (int i = 0; i < charLen; i++) {
            char c = chars[i];
            if (c < 0x80) {
                UnsafeUtil.writeByte(dst, p++, (byte) c);
            } else if (c >= 0xD800 && c <= 0xDFFF) {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.writeByte(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                UnsafeUtil.UNSAFE.putInt(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p, TBL_INT[c]);
                p += TBL_LEN[c];
            }
        }
        int len = (int) (p - (off + reserve));
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 拿 String 内部的 Latin1 {@code byte[]}（<b>只读</b>，共享的内部数组，不可修改）。
     */
    private static byte[] latin1Internal(String v) {
        try {
            Object o = UnsafeUtil.getValue(v, StringUtil.VALUE_FIELD_OFFSET);
            return (o instanceof byte[]) ? (byte[]) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // 候选 33/34：把 JDK 的 <b>SIMD ASCII 判定</b>拿过来用
    // =====================================================================

    /**
     * JDK 的 {@code java.lang.StringCoding.hasNegatives(byte[],int,int)} 的 MethodHandle。
     *
     * <p><b>★ 第四轮的一个结论是错的，这里更正</b>：第四轮写「intrinsic 按类+方法+签名精确匹配
     * ⇒ 反射调用不会命中 SIMD」。<b>实测不成立</b>：
     * 通过 {@code MethodHandle.invokeExact} 调用，C2 <b>照样</b>把
     * {@code java.lang.StringCoding::hasNegatives} 替换成 intrinsic
     * （{@code -XX:+PrintIntrinsics} 打出 {@code @ 21 java.lang.StringCoding::hasNegatives (25 bytes) (intrinsic)}）。
     * 同样的结论在 {@code java.util.Base64$Encoder::encodeBlock} 上也成立
     * ⇒ edap 的 {@code Base64URL} 那个反射<b>确实拿到了向量化</b>。</p>
     *
     * <p>JDK 17 的 {@code String.getBytes(UTF_8)} 对 Latin1 串正是靠它：
     * {@code if (!hasNegatives(val,0,val.length)) return Arrays.copyOf(val, val.length);}
     * ⇒ 纯 ASCII ⇒ 整块 memcpy（<b>但必然分配一个新数组</b>）。</p>
     *
     * <p>⚠ 需要 {@code --add-opens java.base/java.lang=ALL-UNNAMED}；
     * 拿不到时为 null ⇒ 候选自动回退到现状。</p>
     */
    private static final MethodHandle HAS_NEGATIVES;

    static {
        MethodHandle mh = null;
        try {
            java.lang.reflect.Method m = Class.forName("java.lang.StringCoding")
                    .getDeclaredMethod("hasNegatives", byte[].class, int.class, int.class);
            m.setAccessible(true);
            mh = MethodHandles.lookup().unreflect(m);
        } catch (Throwable t) {
            mh = null;
        }
        HAS_NEGATIVES = mh;
    }

    /**
     * 这段字节里有没有负数（= 有没有 ≥0x80 的字节）。
     *
     * <p>拿不到 intrinsic 时返回 {@code true}（保守 ⇒ 走回退路径，行为不变）。</p>
     */
    static boolean hasHighBytes(byte[] b, int off, int len) {
        if (HAS_NEGATIVES == null) {
            return true;
        }
        try {
            return (boolean) HAS_NEGATIVES.invokeExact(b, off, len);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 候选 33：<b>SIMD 判定纯 ASCII ⇒ 直接把 String 内部的 Latin1 字节拷进目标缓冲</b>。
     *
     * <p>纯 ASCII 时 Latin1 字节 == UTF-8 字节 ⇒ 可以<b>零分配、一次 memcpy</b>搞定，
     * 省掉现状的「{@code getBytes(UTF_8)} 的一次分配 + 一次 memcpy + edap 的一次 arraycopy」。</p>
     */
    static int candAsciiSimdDirect(String v, byte[] dst, int off) {
        if (StringUtil.IS_BYTE_ARRAY && StringUtil.isLatin1(v)) {
            byte[] internal = latin1Internal(v);
            if (internal != null && !hasHighBytes(internal, 0, internal.length)) {
                int len = internal.length;
                int n = varintLen(len);
                writeVarint(dst, off, len);
                System.arraycopy(internal, 0, dst, off + n, len);
                return n + len;
            }
            byte[] utf8 = v.getBytes(StandardCharsets.UTF_8);
            int n = varintLen(utf8.length);
            writeVarint(dst, off, utf8.length);
            System.arraycopy(utf8, 0, dst, off + n, utf8.length);
            return n + utf8.length;
        }
        // ★ UTF-16 分支：回退到<b>生产形态</b>（候选 3），不是老的 candCurrent
        //   —— candCurrent 是「memmove 补前缀 + int 索引逐字节写」的旧路径，
        //   而生产代码（Phase B 后）已经是「预留 varint + long 索引」。
        //   ⚠ 回退到旧路径会让 UTF-16 负载凭空慢 28~46%（实测），那是<b>口径错误</b>不是方案问题。
        return candCharArrUnsafeLongReserve(v, dst, off);
    }

    /**
     * 候选 34：同候选 33，但拷贝用的是<b>公开 API</b>
     * {@code String.getBytes(int,int,byte[],int)}（deprecated；Latin1 串 ⇒ 直接 memcpy 进 dst，零分配），
     * <b>不依赖 Unsafe 读内部数组</b>（判定仍要用内部数组）。
     */
    @SuppressWarnings("deprecation")
    static int candAsciiSimdPublic(String v, byte[] dst, int off) {
        if (StringUtil.IS_BYTE_ARRAY && StringUtil.isLatin1(v)) {
            int len = v.length();
            byte[] internal = latin1Internal(v);
            if (internal != null && !hasHighBytes(internal, 0, len)) {
                int n = varintLen(len);
                writeVarint(dst, off, len);
                v.getBytes(0, len, dst, off + n);
                return n + len;
            }
            byte[] utf8 = v.getBytes(StandardCharsets.UTF_8);
            int n = varintLen(utf8.length);
            writeVarint(dst, off, utf8.length);
            System.arraycopy(utf8, 0, dst, off + n, utf8.length);
            return n + utf8.length;
        }
        // ★ UTF-16 分支：回退到<b>生产形态</b>（候选 3），不是老的 candCurrent
        //   —— candCurrent 是「memmove 补前缀 + int 索引逐字节写」的旧路径，
        //   而生产代码（Phase B 后）已经是「预留 varint + long 索引」。
        //   ⚠ 回退到旧路径会让 UTF-16 负载凭空慢 28~46%（实测），那是<b>口径错误</b>不是方案问题。
        return candCharArrUnsafeLongReserve(v, dst, off);
    }

    /**
     * 候选 1：<b>protobuf-java 精确复刻</b> —— {@code charAt} + {@code long} 输出指针
     * + {@code Unsafe} 单字节写 + <b>预留 varint</b>（无 memmove）。
     *
     * <p>对应 protobuf-java 4.29.3 的 {@code Utf8.UnsafeProcessor.encodeUtf8}
     * 与 {@code CodedOutputStream.writeStringNoTag} 的「预留 minLengthVarIntSize → 回退补长度」。</p>
     */
    static int candPbExact(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        int charLen = v.length();
        int reserve = varintLen(charLen * 3);
        int end = encodeCharAtLong(v, dst, off + reserve);
        int len = end - (off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            // 实际 varint 比预留的短 ⇒ 内容左移（罕见路径）
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 2：{@code char[]} + {@code Unsafe}（int 索引）+ <b>预留 varint</b>（无 memmove）。
     * ⇒ 与候选 1 的差别只有「char[] vs charAt」。
     */
    static int candCharArrUnsafeReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int len = encodeCharsUnsafe(chars, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 3：{@code char[]} + {@code Unsafe}（<b>long</b> 索引）+ 预留 varint。
     * ⇒ 与候选 2 的差别只有「long vs int 索引」。
     */
    static int candCharArrUnsafeLongReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int len = encodeCharsUnsafeLong(chars, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 4：{@code char[]} + <b>普通数组写</b> + 预留 varint。
     * ⇒ 与候选 2 的差别只有「Unsafe 写 vs 普通写」。
     */
    static int candCharArrPlainReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int len = encodeCharsPlain(chars, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 5：{@code charAt} + {@code Unsafe}（int 索引）+ 预留 varint。
     * ⇒ 与候选 1 的差别只有「long vs int 索引」。
     */
    static int candCharAtUnsafeReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        int charLen = v.length();
        int reserve = varintLen(charLen * 3);
        int len = encodeCharAtInt(v, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 6：{@code charAt} + <b>普通数组写</b> + 预留 varint。
     */
    static int candCharAtPlainReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        int charLen = v.length();
        int reserve = varintLen(charLen * 3);
        int len = encodeCharAtPlain(v, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 7：<b>复用 char[] 缓冲</b>（无分配）+ {@code Unsafe}（int 索引）+ 预留 varint。
     * ⇒ 与候选 2 的差别只有「{@code toCharArray()} 的分配 + 零填充成本」。
     */
    static int candCharArrReuseReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        int charLen = v.length();
        char[] chars = scratch(charLen);
        v.getChars(0, charLen, chars, 0);
        int reserve = varintLen(charLen * 3);
        int len = encodeCharsUnsafe(chars, 0, charLen, dst, off + reserve);
        int n = varintLen(len);
        if (n != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + n, len);
        }
        writeVarint(dst, off, len);
        return n + len;
    }

    /**
     * 候选 8：{@code char[]} + {@code Unsafe} + <b>两遍</b>（先数 UTF-8 长度 ⇒ 长度前缀直接写，无 memmove）。
     */
    static int candCharArrTwoPass(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int len = utf8Length(chars);
        int n = varintLen(len);
        writeVarint(dst, off, len);
        encodeCharsUnsafe(chars, dst, off + n);
        return n + len;
    }

    /**
     * 候选 9：一律 {@code getBytes(UTF_8)} + 拷贝（参照系：完全交给 JDK 编码）。
     */
    static int candAlwaysGetBytes(String v, byte[] dst, int off) {
        byte[] utf8 = v.getBytes(StandardCharsets.UTF_8);
        int n = varintLen(utf8.length);
        writeVarint(dst, off, utf8.length);
        System.arraycopy(utf8, 0, dst, off + n, utf8.length);
        return n + utf8.length;
    }

    /**
     * 候选 11：{@code char[]} + <b>直接</b> {@code UNSAFE.putByte}（int 索引，不经 {@code UnsafeUtil.writeByte} 包装）+ 预留 varint。
     * ⇒ 与候选 2 的差别只有「是否经过 {@code UnsafeUtil.writeByte} 这一层包装」。
     */
    static int candCharArrDirectInt(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        int p = off + reserve;
        final int n = chars.length;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (c < 128) {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + (long) p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        int len = p - (off + reserve);
        int vn = varintLen(len);
        if (vn != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + vn, len);
        }
        writeVarint(dst, off, len);
        return vn + len;
    }

    /**
     * 候选 12：{@code char[]} + <b>直接</b> {@code UNSAFE.putByte}（<b>long</b> 索引）+ 预留 varint。
     * ⇒ 与候选 3 的差别只有「是否经过 {@code putByteLong} 这一层包装」。
     */
    static int candCharArrDirectLong(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        char[] chars = v.toCharArray();
        int charLen = chars.length;
        int reserve = varintLen(charLen * 3);
        long p = off + reserve;
        final int n = chars.length;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (c < 128) {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        int len = (int) (p - (off + reserve));
        int vn = varintLen(len);
        if (vn != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + vn, len);
        }
        writeVarint(dst, off, len);
        return vn + len;
    }

    /**
     * 候选 13：<b>复用 char[] 缓冲</b>（无分配）+ {@code Unsafe}（long 索引）+ 预留 varint。
     * ⇒ 与候选 3 的差别只有「{@code toCharArray()} 的 32KB 分配 + 零填充成本」。
     */
    static int candCharArrReuseLongReserve(String v, byte[] dst, int off) {
        if (isLatin1(v)) {
            return candCurrent(v, dst, off);
        }
        int charLen = v.length();
        char[] chars = scratch(charLen);
        v.getChars(0, charLen, chars, 0);
        int reserve = varintLen(charLen * 3);
        long p = off + reserve;
        final int n = charLen;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (c < 128) {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, chars[i + 1]);
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        int len = (int) (p - (off + reserve));
        int vn = varintLen(len);
        if (vn != reserve) {
            System.arraycopy(dst, off + reserve, dst, off + vn, len);
        }
        writeVarint(dst, off, len);
        return vn + len;
    }

    // =====================================================================
    // 底层工具
    // =====================================================================

    private static final ThreadLocal<char[]> SCRATCH = ThreadLocal.withInitial(() -> new char[0]);

    static char[] scratch(int len) {
        char[] c = SCRATCH.get();
        if (c.length < len) {
            c = new char[Math.max(len, 1024)];
            SCRATCH.set(c);
        }
        return c;
    }

    static boolean isLatin1(String v) {
        return StringUtil.IS_BYTE_ARRAY && StringUtil.isLatin1(v);
    }

    /**
     * protobuf-java {@code Utf8.UnsafeProcessor.encodeUtf8} 的逐字节复刻：
     * {@code charAt} + {@code long} 输出指针 + {@code Unsafe} 写。
     */
    static int encodeCharAtLong(String v, byte[] dst, int pos) {
        long outIx = pos;
        final long outLimit = dst.length;
        final int inLimit = v.length();
        int inIx = 0;
        for (char c; inIx < inLimit && (c = v.charAt(inIx)) < 0x80; ++inIx) {
            putByteLong(dst, outIx++, (byte) c);
        }
        if (inIx == inLimit) {
            return (int) outIx;
        }
        for (char c; inIx < inLimit; ++inIx) {
            c = v.charAt(inIx);
            if (c < 0x80 && outIx < outLimit) {
                putByteLong(dst, outIx++, (byte) c);
            } else if (c < 0x800 && outIx <= outLimit - 2L) {
                putByteLong(dst, outIx++, (byte) ((0xF << 6) | (c >>> 6)));
                putByteLong(dst, outIx++, (byte) (0x80 | (0x3F & c)));
            } else if ((c < 0xD800 || 0xDFFF < c) && outIx <= outLimit - 3L) {
                putByteLong(dst, outIx++, (byte) ((0xF << 5) | (c >>> 12)));
                putByteLong(dst, outIx++, (byte) (0x80 | (0x3F & (c >>> 6))));
                putByteLong(dst, outIx++, (byte) (0x80 | (0x3F & c)));
            } else if (outIx <= outLimit - 4L) {
                final char low;
                if (inIx + 1 == inLimit || !isSurrogatePair(c, (low = v.charAt(++inIx)))) {
                    throw new IllegalArgumentException("unpaired surrogate");
                }
                int cp = Character.toCodePoint(c, low);
                putByteLong(dst, outIx++, (byte) ((0xF << 4) | (cp >>> 18)));
                putByteLong(dst, outIx++, (byte) (0x80 | (0x3F & (cp >>> 12))));
                putByteLong(dst, outIx++, (byte) (0x80 | (0x3F & (cp >>> 6))));
                putByteLong(dst, outIx++, (byte) (0x80 | (0x3F & cp)));
            } else {
                throw new IllegalArgumentException("buffer overflow");
            }
        }
        return (int) outIx;
    }

    static boolean isSurrogatePair(char lead, char trail) {
        return Character.isHighSurrogate(lead) && Character.isLowSurrogate(trail);
    }

    /**
     * {@code charAt} + {@code Unsafe}（int 索引）。
     */
    static int encodeCharAtInt(String v, byte[] dst, int pos) {
        int p = pos;
        final int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c < 0x80) {
                UnsafeUtil.writeByte(dst, p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, v.charAt(i + 1));
                UnsafeUtil.writeByte(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return p - pos;
    }

    /**
     * {@code charAt} + 普通数组写。
     */
    static int encodeCharAtPlain(String v, byte[] dst, int pos) {
        int p = pos;
        final int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c < 0x80) {
                dst[p++] = (byte) c;
            } else if (c < 0x800) {
                dst[p++] = (byte) ((0xF << 6) | (c >>> 6));
                dst[p++] = (byte) (0x80 | (0x3F & c));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, v.charAt(i + 1));
                dst[p++] = (byte) (0xF0 | ((cp >> 18) & 0x07));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                dst[p++] = (byte) ((0xF << 5) | (c >>> 12));
                dst[p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                dst[p++] = (byte) (0x80 | (0x3F & c));
            }
        }
        return p - pos;
    }

    /**
     * 逐字符 UTF-8 编码（{@code Unsafe} 单字节写），走 {@code char[]}。
     */
    static int encodeCharsUnsafe(char[] value, byte[] dst, int pos) {
        return encodeCharsUnsafe(value, 0, value.length, dst, pos);
    }

    /**
     * 逐字符 UTF-8 编码（{@code Unsafe} 单字节写），走 {@code char[]}，只编码 {@code [from, to)}。
     */
    static int encodeCharsUnsafe(char[] value, int from, int to, byte[] dst, int pos) {
        int p = pos;
        for (int i = from; i < to; i++) {
            char c = value[i];
            if (c < 128) {
                UnsafeUtil.writeByte(dst, p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, value[i + 1]);
                UnsafeUtil.writeByte(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                UnsafeUtil.writeByte(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(dst, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return p - pos;
    }

    // =====================================================================
    // 候选 24：真实生产路径
    // =====================================================================

    private static final ProtoBufWriter PROD_WRITER = ProtoBuf.THREAD_WRITER.get();
    private static final ProtoBufEncoder PROD_ENCODER =
            ProtoBufCodecRegister.INSTANCE.getEncoder(CodecStr.class);

    /**
     * 候选 24：<b>真实生产路径</b> —— {@code ProtoBufWriter} + ASM 生成的 {@code ProtoBufEncoder}
     * ⇒ 走的是线上真正跑的那条路（{@code AbstractWriter.writeString0} 本体）。
     *
     * <p>★ 为什么需要它：其它候选都是「在探针里复刻一遍生产逻辑」，
     * 而复刻可能与生产<b>不等价</b>（例如分段切法、扩容时机）。
     * 第三轮实测曾出现「复刻 14944ns / 生产 9432ns」的矛盾，
     * 只有把<b>真实生产路径</b>放进同一张表，才能判断「候选 vs 生产」的真实差距。</p>
     *
     * <p>⚠ 口径差异：本候选写进 writer 自己的缓冲，其它候选写进预分配的 {@code dst}；
     * 两者都是预分配的 byte[]（测量期间不扩容），差异只在多出的字段 tag 写入上。</p>
     */
    static int candProduction(String v, byte[] dst, int off) {
        int len = encodeProduction(v);
        System.arraycopy(PROD_WRITER.buffer(), 0, dst, off, len);
        return len;
    }

    /**
     * 只编码不拷贝 —— 计时用的版本。
     */
    static int encodeProduction(String v) {
        CodecStr cs = new CodecStr();
        cs.setS(v);
        try {
            PROD_WRITER.reset();
            PROD_WRITER.getBufOut().reset();
            PROD_ENCODER.encode(PROD_WRITER, cs);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return PROD_WRITER.size();
    }

    /**
     * 逐字符 UTF-8 编码（{@code Unsafe} 单字节写，<b>long 索引</b>），走 {@code char[]}。
     */
    static int encodeCharsUnsafeLong(char[] value, byte[] dst, int pos) {
        long p = pos;
        final int n = value.length;
        for (int i = 0; i < n; i++) {
            char c = value[i];
            if (c < 128) {
                putByteLong(dst, p++, (byte) c);
            } else if (c < 0x800) {
                putByteLong(dst, p++, (byte) ((0xF << 6) | (c >>> 6)));
                putByteLong(dst, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, value[i + 1]);
                putByteLong(dst, p++, (byte) (0xF0 | ((cp >> 18) & 0x07)));
                putByteLong(dst, p++, (byte) (0x80 | ((cp >> 12) & 0x3F)));
                putByteLong(dst, p++, (byte) (0x80 | ((cp >> 6) & 0x3F)));
                putByteLong(dst, p++, (byte) (0x80 | (cp & 0x3F)));
                i++;
            } else {
                putByteLong(dst, p++, (byte) ((0xF << 5) | (c >>> 12)));
                putByteLong(dst, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                putByteLong(dst, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return (int) (p - pos);
    }

    /**
     * 逐字符 UTF-8 编码（<b>普通数组写</b>），走 {@code char[]}。
     */
    static int encodeCharsPlain(char[] value, byte[] dst, int pos) {
        int p = pos;
        for (int i = 0; i < value.length; i++) {
            char c = value[i];
            if (c < 128) {
                dst[p++] = (byte) c;
            } else if (c < 0x800) {
                dst[p++] = (byte) ((0xF << 6) | (c >>> 6));
                dst[p++] = (byte) (0x80 | (0x3F & c));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int cp = Character.toCodePoint(c, value[i + 1]);
                dst[p++] = (byte) (0xF0 | ((cp >> 18) & 0x07));
                dst[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                dst[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                dst[p++] = (byte) (0x80 | (cp & 0x3F));
                i++;
            } else {
                dst[p++] = (byte) ((0xF << 5) | (c >>> 12));
                dst[p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
                dst[p++] = (byte) (0x80 | (0x3F & c));
            }
        }
        return p - pos;
    }

    /**
     * {@code Unsafe} 写（long 索引）。
     */
    static void putByteLong(byte[] dst, long p, byte b) {
        UnsafeUtil.UNSAFE.putByte(dst, UnsafeUtil.UNSAFE.ARRAY_BYTE_BASE_OFFSET + p, b);
    }

    /**
     * UTF-8 字节长度（两遍方案的第一步）。
     */
    static int utf8Length(char[] value) {
        int n = value.length;
        int len = n;
        for (int i = 0; i < n; i++) {
            char c = value[i];
            if (c < 0x80) {
                continue;
            } else if (c < 0x800) {
                len += 1;
            } else if (c >= '\ud800' && c <= '\udfff') {
                len += 2;
                i++;
            } else {
                len += 2;
            }
        }
        return len;
    }

    static int varintLen(int value) {
        if ((value & ~0x7F) == 0) {
            return 1;
        }
        if ((value & ~0x3FFF) == 0) {
            return 2;
        }
        if ((value & ~0x1FFFFF) == 0) {
            return 3;
        }
        if ((value & ~0x0FFFFFFF) == 0) {
            return 4;
        }
        return 5;
    }

    static void writeVarint(byte[] dst, int off, int value) {
        int p = off;
        int v = value;
        if ((v & ~0x7F) == 0) {
            dst[p] = (byte) v;
            return;
        }
        dst[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        if ((v & ~0x7F) == 0) {
            dst[p] = (byte) v;
            return;
        }
        dst[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        if ((v & ~0x7F) == 0) {
            dst[p] = (byte) v;
            return;
        }
        dst[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        if ((v & ~0x7F) == 0) {
            dst[p] = (byte) v;
            return;
        }
        dst[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        dst[p] = (byte) v;
    }

    // =====================================================================
    // 跑对比
    // =====================================================================

    public static void main(String[] args) {
        long millis = args.length > 0 ? Long.parseLong(args[0]) : 800L;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int only = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        int onlyString = args.length > 3 ? Integer.parseInt(args[3]) : -1;
        if (only >= 0) {
            runSingle(only, millis, rounds, onlyString);
        } else {
            run(millis, rounds);
        }
    }

    /**
     * <b>单候选隔离测量</b>：一个 JVM 只跑一个候选（可选：再只跑一个串）。
     *
     * <p>为什么必须隔离：本机 load ≈ 9~11（10 核），而且把所有候选塞进同一个 JVM 时，
     * {@code sink(op)} 的 {@code op.call()} 调用点会变成 megamorphic，
     * 各候选被 JIT 编译成形态不一致的代码 ⇒ 实测出现过同一个候选两次跑出 13259 / 24317
     * 这种 2 倍差异。<b>单候选 JVM</b>下调用点是 monomorphic，
     * 得到的数字才可跨 JVM、跨机器比较。</p>
     *
     * <p>★★ <b>为什么还要「一串一个 JVM」</b>（{@code onlyString}）：<br>
     * HotSpot 一旦把某个方法 C2 编译成<b>针对上一个负载的分支形态</b>，
     * 后续换一个分支分布完全不同的负载时<b>不会自动重编译</b>
     * ⇒ 实测：在 emoji-1k（全是代理对 ⇒ 4 字节路径）之后测 mixed-16k（80% ASCII），
     * mixed-16k 测出 <b>15188ns</b>；而把 mixed-16k 放在 emoji 之前测，测出 <b>9194ns</b>
     * ⇒ <b>差 1.65 倍，纯粹是 JIT profile 污染</b>，与代码无关。
     * 「每串预热」<b>修不好</b>（HotSpot 不会因为预热而重编译已编译的方法）。
     * ⇒ 唯一可靠的做法：<b>(候选, 串) 组合各起一个 JVM</b>。</p>
     */
    static void runSingle(int which, long millis, int rounds, int onlyString) {
        String[] names = {"ascii-1k", "ascii-16k", "cn-1k", "cn-16k", "emoji-1k", "latin1-high",
                "mixed-16k", "latin1-16k",
                // ★ 第四轮：中文串的「长度曲线」——用来定位查表方案在哪个长度上开始占优
                "cn-2k", "cn-4k", "cn-8k", "cn-32k",
                // ★ 第五轮：短串——SIMD 判定（MethodHandle 调用）的固定开销会不会吃掉收益？
                "ascii-64"};
        String[] values = {
                ascii(1024), ascii(16384), chinese(1024), chinese(16384),
                emoji(1024),
                latin1('c', 'a', 'f', 0xE9),
                mixed(16384),
                latin1Big(16384),
                chinese(2048), chinese(4096), chinese(8192), chinese(32768),
                ascii(64)
        };
        for (int k = 0; k < names.length; k++) {
            if (onlyString >= 0 && k != onlyString) {
                continue;
            }
            String v = values[k];
            byte[] dst = new byte[v.length() * 3 + 32];
            // ★ 先断言：这个候选在这个串上产出的字节必须与「现状」和 protobuf-java 逐字节相同
            //   候选 16 / 18 / 19 是「只做判定不写字节」的分解探针，不参与字节断言。
            //   候选 24 走真实生产路径 ⇒ 产出的是「整条消息」（多一个字段 tag 0x0A），单独断言。
            if (which == 24) {
                byte[] ref = encode(StringEncodeCandidates::candCurrent, v);
                byte[] got = encode(encOf(which), v);
                if (got.length != ref.length + 1 || (got[0] & 0xFF) != 0x0A
                        || !Arrays.equals(ref, Arrays.copyOfRange(got, 1, got.length))) {
                    throw new IllegalStateException("真实生产路径在串 " + names[k] + " 上产出的字节与现状不一致");
                }
            } else if (which != 16 && which != 18 && which != 19
                    && which != 28 && which != 29 && which != 30) {
                byte[] ref = encode(StringEncodeCandidates::candCurrent, v);
                byte[] got = encode(encOf(which), v);
                if (!Arrays.equals(ref, got)) {
                    throw new IllegalStateException("候选 #" + which + " 在串 " + names[k] + " 上产出的字节与现状不一致");
                }
                if (!Arrays.equals(ref, googleBytes(v))) {
                    throw new IllegalStateException("候选 #" + which + " 在串 " + names[k] + " 上与 protobuf-java 的字节不一致");
                }
            }
            Op op = opOf(which, v, dst);
            // ★ 每串预热：把「解释执行 + C1」的爬坡期挡在测量窗口之外。
            //   ⚠ 预热<b>不能</b>消除 JIT profile 污染（HotSpot 不会因为预热重编译已编译的方法）
            //   ⇒ 真正的解法是「一串一个 JVM」（见 {@code runSingle} 的注释）。
            warmup(op, Math.max(200, millis));
            double best = Double.MAX_VALUE;
            for (int r = 0; r < rounds; r++) {
                best = Math.min(best, measureOnce(millis, op));
            }
            System.out.printf("%-12s cand#%-2d %12.0f%n", names[k], which, best);
        }
    }

    /**
     * 预热：跑一段固定时间（不计入测量），让 JIT 编译出针对当前负载的代码。
     */
    private static void warmup(Op op, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 50; i++) {
                sink(op);
            }
        }
    }

    private static Enc encOf(int which) {
        switch (which) {
            case 0: return StringEncodeCandidates::candCurrent;
            case 1: return StringEncodeCandidates::candPbExact;
            case 2: return StringEncodeCandidates::candCharArrUnsafeReserve;
            case 3: return StringEncodeCandidates::candCharArrUnsafeLongReserve;
            case 4: return StringEncodeCandidates::candCharArrPlainReserve;
            case 5: return StringEncodeCandidates::candCharAtUnsafeReserve;
            case 6: return StringEncodeCandidates::candCharAtPlainReserve;
            case 7: return StringEncodeCandidates::candCharArrReuseReserve;
            case 8: return StringEncodeCandidates::candCharArrTwoPass;
            case 9: return StringEncodeCandidates::candAlwaysGetBytes;
            case 11: return StringEncodeCandidates::candCharArrDirectInt;
            case 12: return StringEncodeCandidates::candCharArrDirectLong;
            case 13: return StringEncodeCandidates::candCharArrReuseLongReserve;
            case 14: return StringEncodeCandidates::candLatin1Direct;
            case 15: return StringEncodeCandidates::candUtf16AsciiBatch;
            case 16: return StringEncodeCandidates::candLatin1Probe;
            case 17: return StringEncodeCandidates::candLatin1AsciiFast;
            case 18: return StringEncodeCandidates::candLatin1HasHigh;
            case 19: return StringEncodeCandidates::candLatin1CountHigh;
            case 20: return StringEncodeCandidates::candCharArrUnsafeIntReserve;
            case 21: return StringEncodeCandidates::candCharArrUnsafeLongMove;
            case 22: return StringEncodeCandidates::candSegAsciiBatchInt;
            case 23: return StringEncodeCandidates::candSegOnlyInt;
            case 24: return StringEncodeCandidates::candProduction;
            case 25: return StringEncodeCandidates::candTableByteArray;
            case 26: return StringEncodeCandidates::candTableFlat;
            case 27: return StringEncodeCandidates::candTableInt;
            case 28: return StringEncodeCandidates::candGetBytesOnly;
            case 29: return StringEncodeCandidates::candInternalCopyOnly;
            case 30: return StringEncodeCandidates::candGetBytesLatin1Only;
            case 31: return StringEncodeCandidates::candCharArrPlainLongReserve;
            case 32: return StringEncodeCandidates::candTableHybrid;
            case 33: return StringEncodeCandidates::candAsciiSimdDirect;
            case 34: return StringEncodeCandidates::candAsciiSimdPublic;
            default: return StringEncodeCandidates::candCurrent;
        }
    }

    private static Op opOf(int which, String v, byte[] dst) {
        switch (which) {
            case 0: return () -> candCurrent(v, dst, 0);
            case 1: return () -> candPbExact(v, dst, 0);
            case 2: return () -> candCharArrUnsafeReserve(v, dst, 0);
            case 3: return () -> candCharArrUnsafeLongReserve(v, dst, 0);
            case 4: return () -> candCharArrPlainReserve(v, dst, 0);
            case 5: return () -> candCharAtUnsafeReserve(v, dst, 0);
            case 6: return () -> candCharAtPlainReserve(v, dst, 0);
            case 7: return () -> candCharArrReuseReserve(v, dst, 0);
            case 8: return () -> candCharArrTwoPass(v, dst, 0);
            case 9: return () -> candAlwaysGetBytes(v, dst, 0);
            case 10: return () -> googleEncode(v, dst);
            case 11: return () -> candCharArrDirectInt(v, dst, 0);
            case 12: return () -> candCharArrDirectLong(v, dst, 0);
            case 13: return () -> candCharArrReuseLongReserve(v, dst, 0);
            case 14: return () -> candLatin1Direct(v, dst, 0);
            case 15: return () -> candUtf16AsciiBatch(v, dst, 0);
            case 16: return () -> candLatin1Probe(v, dst, 0);
            case 17: return () -> candLatin1AsciiFast(v, dst, 0);
            case 18: return () -> candLatin1HasHigh(v, dst, 0);
            case 19: return () -> candLatin1CountHigh(v, dst, 0);
            case 20: return () -> candCharArrUnsafeIntReserve(v, dst, 0);
            case 21: return () -> candCharArrUnsafeLongMove(v, dst, 0);
            case 22: return () -> candSegAsciiBatchInt(v, dst, 0);
            case 23: return () -> candSegOnlyInt(v, dst, 0);
            case 24: return () -> encodeProduction(v);
            case 25: return () -> candTableByteArray(v, dst, 0);
            case 26: return () -> candTableFlat(v, dst, 0);
            case 27: return () -> candTableInt(v, dst, 0);
            case 28: return () -> candGetBytesOnly(v, dst, 0);
            case 29: return () -> candInternalCopyOnly(v, dst, 0);
            case 30: return () -> candGetBytesLatin1Only(v, dst, 0);
            case 31: return () -> candCharArrPlainLongReserve(v, dst, 0);
            case 32: return () -> candTableHybrid(v, dst, 0);
            case 33: return () -> candAsciiSimdDirect(v, dst, 0);
            case 34: return () -> candAsciiSimdPublic(v, dst, 0);
            default: throw new IllegalArgumentException("候选下标越界: " + which);
        }
    }

    static void run(long millis, int rounds) {
        String[] names = {"ascii-1k", "ascii-16k", "cn-1k", "cn-16k", "emoji-1k", "latin1-high"};
        String[] values = {
                ascii(1024), ascii(16384), chinese(1024), chinese(16384),
                emoji(1024),
                latin1('c', 'a', 'f', 0xE9)
        };

        Enc[] encs = {
                StringEncodeCandidates::candCurrent,
                StringEncodeCandidates::candPbExact,
                StringEncodeCandidates::candCharArrUnsafeReserve,
                StringEncodeCandidates::candCharArrUnsafeLongReserve,
                StringEncodeCandidates::candCharArrPlainReserve,
                StringEncodeCandidates::candCharAtUnsafeReserve,
                StringEncodeCandidates::candCharAtPlainReserve,
                StringEncodeCandidates::candCharArrReuseReserve,
                StringEncodeCandidates::candCharArrTwoPass,
                StringEncodeCandidates::candAlwaysGetBytes
        };
        String[] labels = {
                "0 现状", "1 pb精确", "2 char[]+Uns+rsv", "3 char[]+UnsL+rsv", "4 char[]+plain+rsv",
                "5 charAt+Uns+rsv", "6 charAt+plain+rsv", "7 char[]复用+Uns+rsv",
                "8 char[]+Uns+两遍", "9 一律getBytes"
        };

        System.out.printf("%-12s |", "串");
        for (String label : labels) {
            System.out.printf(" %16s |", label);
        }
        System.out.printf(" %16s%n", "参照:protobuf-java");
        System.out.println("-".repeat(160));

        for (int k = 0; k < names.length; k++) {
            String v = values[k];
            byte[] dst = new byte[v.length() * 3 + 32];

            // ★ 先断言：所有候选产出的字节必须一致
            byte[] ref = encode(encs[0], v);
            for (int j = 1; j < encs.length; j++) {
                byte[] got = encode(encs[j], v);
                if (!Arrays.equals(ref, got)) {
                    throw new IllegalStateException("候选 " + labels[j] + " 产出的字节不一致（串=" + names[k] + "）");
                }
            }
            // 与 protobuf-java 对齐
            byte[] pb = googleBytes(v);
            if (!Arrays.equals(ref, pb)) {
                throw new IllegalStateException("与 protobuf-java 的字节不一致（串=" + names[k] + "）");
            }

            double[] t = new double[encs.length + 1];
            // ★ 每个候选用<b>各自的 lambda</b>（静态方法直调）——
            //   若共用一个 lambda + 接口多态派发，调用点会变成 megamorphic，
            //   各候选被编译成互不相关的方法，测出来的相对关系不可信。
            Op[] ops = {
                    () -> candCurrent(v, dst, 0),
                    () -> candPbExact(v, dst, 0),
                    () -> candCharArrUnsafeReserve(v, dst, 0),
                    () -> candCharArrUnsafeLongReserve(v, dst, 0),
                    () -> candCharArrPlainReserve(v, dst, 0),
                    () -> candCharAtUnsafeReserve(v, dst, 0),
                    () -> candCharAtPlainReserve(v, dst, 0),
                    () -> candCharArrReuseReserve(v, dst, 0),
                    () -> candCharArrTwoPass(v, dst, 0),
                    () -> candAlwaysGetBytes(v, dst, 0),
                    () -> googleEncode(v, dst)
            };
            // ★ 轮转测：每一轮把所有候��各测一遍，最后取每个候选的<b>最小值</b>。
            //   本机 load ≈ 9~11（10 核），噪声很大；「逐候选测完再比」会被负载尖峰带偏，
            //   轮转 + 取 min 才能得到可比的无负载基线。
            double[][] all = new double[ops.length][rounds];
            for (int r = 0; r < rounds; r++) {
                for (int j = 0; j < ops.length; j++) {
                    all[j][r] = measureOnce(millis, ops[j]);
                }
            }
            for (int j = 0; j < ops.length; j++) {
                double[] row = all[j];
                Arrays.sort(row);
                t[j] = row[0];   // min
            }

            System.out.printf("%-12s |", names[k]);
            for (double x : t) {
                System.out.printf(" %16.0f |", x);
            }
            System.out.println();
        }
    }

    private static byte[] googleBytes(String v) {
        byte[] dst = new byte[v.length() * 3 + 32];
        int n = googleEncode(v, dst);
        return Arrays.copyOf(dst, n);
    }

    private static int googleEncode(String v, byte[] dst) {
        try {
            CodedOutputStream cos = CodedOutputStream.newInstance(dst);
            cos.writeStringNoTag(v);
            cos.flush();
            return cos.getTotalBytesWritten();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encode(Enc enc, String v) {
        byte[] dst = new byte[v.length() * 3 + 32];
        int len = enc.encode(v, dst, 0);
        return Arrays.copyOf(dst, len);
    }

    private static double measureOnce(long millis, Op op) {
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
        return ops == 0 ? 0 : (double) elapsed / ops;
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
            sb.append("😁");
        }
        return sb.toString();
    }

    private static String latin1(int... bytes) {
        byte[] bs = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            bs[i] = (byte) bytes[i];
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    /**
     * <b>混合负载</b>：80% ASCII + 20% 中文 ⇒ UTF-16 coder（Latin1 装不下中文）。
     * 这是真实业务里最常见的形态（英文多、中文少），用来测「ASCII 段批量化」的收益。
     */
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

    /**
     * <b>Latin1 负载</b>：稀疏的高位字符（Latin-1 的 0x80~0xFF）⇒ Latin1 coder。
     * 高位字符稀疏 ⇒ ASCII 段很长 ⇒ 能放大「ASCII 段一次 arraycopy」的收益。
     */
    private static String latin1Big(int len) {
        byte[] bs = new byte[len];
        for (int i = 0; i < len; i++) {
            bs[i] = (byte) ((i % 64 == 0) ? 0xE9 : ('a' + (i % 26)));
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    private interface Enc {
        int encode(String v, byte[] dst, int off);
    }

    private interface Op {
        long call() throws Exception;
    }
}
