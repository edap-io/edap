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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 负载矩阵 —— 标准版 vs 改进版（FAST）编解码器的对比负载。
 *
 * <p><b>字符串类</b>（打「字符串编码」这条改进）：</p>
 * <ul>
 *   <li>{@code ascii-*}：纯 ASCII，JDK 把 String 存成 Latin1 coder。
 *       标准版走「纯 ASCII 直拷内部 byte[]」，改进版走「Latin1 裸拷 + 1 字节 charset 标记」。</li>
 *   <li>{@code cn-*}：纯中文，JDK 存成 UTF-16 coder。
 *       标准版要编码成 UTF-8（3 字节/字），改进版直接写 UTF-16LE（2 字节/字）。</li>
 *   <li>{@code mixed-16k}：80% ASCII + 20% 中文 ⇒ UTF-16 coder，
 *       打标准版「ASCII / 非 ASCII 切换」这条路径。</li>
 *   <li>{@code mix-cn10/25/50/75/90}、{@code mix-1cn}：<b>中文占比扫描</b>（总字符数固定 16384；
 *       {@code mix-1cn} 是「16383 ASCII + 1 中文」的悬崖用例）。
 *       改进版是「按整个 String 的 coder 一刀切」（含一个非 Latin1 字符就整串按 UTF-16 存，
 *       2 字节/字），标准版是「逐字符最省」（ASCII 1 字节、中文 3 字节）
 *       ⇒ 体积差完全由中文占比决定，交叉点在 <b>中文占比 50%</b>。</li>
 *   <li>{@code latin1-high-1k}：含 0xE9 的 Latin1 串
 *       ⇒ 标准版必须真做 UTF-8 编码（Latin1 高位字节不是合法 UTF-8）。</li>
 * </ul>
 *
 * <p><b>内嵌消息类</b>（打「内嵌消息」这条改进）：
 * 标准版用 LENGTH 分帧（预留 2 字节长度前缀再回填），载荷落在 128..16383 字节时前缀正好 2 字节
 * ⇒ 内容不用搬；<b>其余区间必然整块 memmove</b>。三个负载分别压这三档：</p>
 * <ul>
 *   <li>{@code nest-64-x8}：内层 ~69B ⇒ 前缀 1 字节 ⇒ <b>左移</b></li>
 *   <li>{@code nest-1k-x8}：内层 ~1029B ⇒ 前缀 2 字节 ⇒ <b>不搬移</b>（标准版最优区间）</li>
 *   <li>{@code nest-20k-x4}：内层 ~20KB ⇒ 前缀 3 字节 ⇒ <b>右移</b></li>
 * </ul>
 *
 * <p><b>对照组</b>：packed int / map / bytes —— 用来证明改进版不是在牺牲其它路径换来的。</p>
 */
public final class BenchPayloads {

    private BenchPayloads() {
    }

    /**
     * 一个负载用例。
     */
    public static final class Payload {
        public final String id;
        public final Class<?> cls;
        public final Object obj;
        /** 从解码结果里取一个「便宜但能证明内容真的被解出来」的量。 */
        public final ToIntFunction<Object> sinkOf;

        Payload(String id, Class<?> cls, Object obj, ToIntFunction<Object> sinkOf) {
            this.id = id;
            this.cls = cls;
            this.obj = obj;
            this.sinkOf = sinkOf;
        }
    }

    // ── sink：刻意做到最便宜 ────────────────────────────────────────────
    private static final ToIntFunction<Object> STR_SINK = o -> {
        String s = ((BenchStr) o).getS();
        return s == null ? 0 : s.length();
    };

    private static final ToIntFunction<Object> NEST_SINK = o -> {
        BenchNest n = (BenchNest) o;
        int v = 0;
        BenchInner one = n.getInner();
        if (one != null && one.getS() != null) {
            v += one.getS().length() + 1;
        }
        List<BenchInner> inners = n.getInners();
        if (inners != null) {
            for (BenchInner i : inners) {
                if (i != null && i.getS() != null) {
                    v += i.getS().length() + 1;
                }
            }
        }
        List<Integer> nums = n.getNums();
        if (nums != null) {
            v += nums.size();
        }
        Map<String, String> attrs = n.getAttrs();
        if (attrs != null) {
            v += attrs.size();
        }
        byte[] blob = n.getBlob();
        if (blob != null) {
            v += blob.length;
        }
        return v;
    };

    /**
     * 所有负载的 id —— 驱动脚本按这个顺序遍历。
     */
    public static final String[] IDS = {
            // 字符串
            "ascii-1k", "ascii-16k", "cn-1k", "cn-16k", "mixed-16k", "latin1-high-1k",
            // 混排的「中文占比」扫描（总字符数固定 16384）
            "mix-cn10", "mix-cn25", "mix-cn50", "mix-cn75", "mix-cn90", "mix-1cn",
            // 内嵌消息
            "nest-64-x8", "nest-1k-x8", "nest-20k-x4", "nest-cn-1k-x8",
            // 对照组
            "packed-int-16k", "map-1k", "bytes-16k"
    };

    public static Payload of(String id) {
        switch (id) {
            // ── 字符串 ───────────────────────────────────────────────────
            case "ascii-1k":
                return str(id, ascii(1024));
            case "ascii-16k":
                return str(id, ascii(16384));
            case "cn-1k":
                return str(id, chinese(1024));
            case "cn-16k":
                return str(id, chinese(16384));
            case "mixed-16k":
                return str(id, mixed(16384));
            case "latin1-high-1k":
                return str(id, latin1High(1024));

            // ── 混排的「中文占比」扫描（总字符数固定 16384）─────────────
            case "mix-cn10":
                return str(id, mixedRatio(16384, 10));
            case "mix-cn25":
                return str(id, mixedRatio(16384, 25));
            case "mix-cn50":
                return str(id, mixedRatio(16384, 50));
            case "mix-cn75":
                return str(id, mixedRatio(16384, 75));
            case "mix-cn90":
                return str(id, mixedRatio(16384, 90));
            case "mix-1cn":
                return str(id, mixedOneCn(16384));

            // ── 内嵌消息 ────────────────────────────────────────────────
            case "nest-64-x8":
                return nest(id, 64, 8, false);
            case "nest-1k-x8":
                return nest(id, 1024, 8, false);
            case "nest-20k-x4":
                return nest(id, 20480, 4, false);
            case "nest-cn-1k-x8":
                return nest(id, 1024, 8, true);

            // ── 对照组 ──────────────────────────────────────────────────
            case "packed-int-16k": {
                BenchNest n = newNest();
                List<Integer> nums = new ArrayList<>(16000);
                for (int i = 0; i < 16000; i++) {
                    nums.add(i);
                }
                n.setNums(nums);
                return new Payload(id, BenchNest.class, n, NEST_SINK);
            }
            case "map-1k": {
                BenchNest n = newNest();
                Map<String, String> attrs = n.getAttrs();
                for (int i = 0; i < 1000; i++) {
                    attrs.put("key-" + i, "value-" + i);
                }
                return new Payload(id, BenchNest.class, n, NEST_SINK);
            }
            case "bytes-16k": {
                BenchNest n = newNest();
                byte[] blob = new byte[16384];
                for (int i = 0; i < blob.length; i++) {
                    blob[i] = (byte) (i & 0xFF);
                }
                n.setBlob(blob);
                return new Payload(id, BenchNest.class, n, NEST_SINK);
            }
            default:
                throw new IllegalArgumentException("未知负载：" + id);
        }
    }

    /**
     * 造一个「集合字段都非 null」的容器。
     *
     * <p>★ 为什么要绕开 null：改进版（FAST）的
     * {@code FastProtoBufWriter.writeMessages(byte[],int,List,ProtoBufEncoder)}
     * <b>漏掉了标准版的 null/empty 判断</b>
     * （标准版 {@code StandardProtoBufWriter.writeMessages} 有 {@code if (CollectionUtils.isEmpty(vs)) return;}，
     * FAST 覆写里直接 {@code int size = vs.size();}）⇒ 集合字段为 null 时 NPE，
     * 而 {@code ProtoBuf.fastToByteArray} 又把 {@code EncodeException} 吞掉返回 null。
     * 本基准把集合字段初始化成空集合，绕开这个缺陷，让两种 codec 编码<b>同一个对象</b>。</p>
     */
    private static BenchNest newNest() {
        BenchNest n = new BenchNest();
        n.setInners(new ArrayList<>());
        n.setNums(new ArrayList<>());
        n.setAttrs(new LinkedHashMap<>());
        return n;
    }

    // ── 构造 ───────────────────────────────────────────────────────────

    private static Payload str(String id, String s) {
        BenchStr b = new BenchStr();
        b.setS(s);
        return new Payload(id, BenchStr.class, b, STR_SINK);
    }

    /**
     * 构造嵌套负载：1 个单独内嵌消息 + n 个 repeated 内嵌消息。
     *
     * @param innerLen 内层字符串长度（决定嵌套载荷落在长度前缀 varint 的哪一档）
     * @param n        repeated 内嵌消息个数
     * @param cn       内层用中文还是 ASCII
     */
    private static Payload nest(String id, int innerLen, int n, boolean cn) {
        String s = cn ? chinese(innerLen) : ascii(innerLen);
        BenchNest b = newNest();
        b.setInner(inner(s, 0));
        List<BenchInner> inners = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            inners.add(inner(s, i));
        }
        b.setInners(inners);
        return new Payload(id, BenchNest.class, b, NEST_SINK);
    }

    private static BenchInner inner(String s, int v) {
        BenchInner i = new BenchInner();
        i.setS(s);
        i.setV(v);
        return i;
    }

    // ── 字符串素材 ─────────────────────────────────────────────────────

    /** 纯 ASCII ⇒ JDK 存成 Latin1 coder。 */
    public static String ascii(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    /** 纯中文 ⇒ JDK 存成 UTF-16 coder。 */
    public static String chinese(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append('中');
        }
        return sb.toString();
    }

    /** 80% ASCII + 20% 中文 ⇒ UTF-16 coder，踩「ASCII / 非 ASCII 切换」。 */
    public static String mixed(int len) {
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
     * 中文占比 {@code cnPct}% 的混排串（总字符数固定 ⇒ 两种 codec 的字节数可直接横向比较）。
     *
     * <p>★ 为什么要有这个：改进版是「<b>按整个 String 的 coder 一刀切</b>」——
     * 只要串里有<b>一个</b>非 Latin1 字符，整个串就按 UTF-16 存（2 字节/字）；
     * 而标准版是「<b>逐字符最省</b>」（ASCII 1 字节、中文 3 字节）。
     * ⇒ 两者的体积差<b>完全取决于中文占比</b>，不是固定比例：
     * <pre>
     *   FAST 字节 = 2·A + 2·B + 4·S   （A=ASCII 字数，B=BMP 非 ASCII 字数，S=补充平面字数）
     *   标准字节 = 1·A + 3·B + 4·S
     *   FAST − 标准 = A − B          ⇒ 补充平面字符（S）在两边<b>完全抵消</b>
     *   FAST 更小 ⟺ A &lt; B ⟺ BMP 非 ASCII 字数 &gt; ASCII 字数
     * </pre>
     * ⇒ 纯 ASCII + BMP 汉字这一常见组合下，<b>交叉点就是「中文占比 50%」</b>。
     * ⇒ 配比扫描就是为了把这个交叉点实测出来。</p>
     *
     * @param len   总字符数
     * @param cnPct 中文占比（0..100）
     */
    public static String mixedRatio(int len, int cnPct) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            if (i % 100 < cnPct) {
                sb.append('中');
            } else {
                sb.append((char) ('a' + (i % 26)));
            }
        }
        return sb.toString();
    }

    /**
     * 16383 个 ASCII + <b>1 个</b>中文 —— 专打「<b>一刀切的悬崖</b>」。
     *
     * <p>JDK 的 String 只要含<b>一个</b>非 Latin1 字符，coder 就整体变成 UTF-16
     * ⇒ 改进版把整串按 2 字节/字写 ⇒ <b>一个字符就能让体积翻倍</b>。
     * 这是改进版体积上最坏的情况（99.99% 是 ASCII 也救不回来）。</p>
     */
    public static String mixedOneCn(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len - 1; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        sb.append('中');
        return sb.toString();
    }

    /**
     * 含 0xE9 的 Latin1 串 —— 用 ISO_8859_1 字节数组构造，JDK 一定把它存成 Latin1 coder。
     *
     * <p>Latin1 的 0xE9 不是合法 UTF-8 ⇒ 标准版必须真做 UTF-8 编码（多一次分配 + 一次拷贝）。</p>
     */
    public static String latin1High(int len) {
        byte[] bs = new byte[len];
        for (int i = 0; i < len; i++) {
            bs[i] = (i % 4 == 3) ? (byte) 0xE9 : (byte) ('a' + (i % 26));
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }
}
