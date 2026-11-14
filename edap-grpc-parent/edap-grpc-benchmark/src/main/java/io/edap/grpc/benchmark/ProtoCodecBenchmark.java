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
import com.google.protobuf.Message;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯 protobuf 编解码器的负载矩阵探针 —— edap 自研编解码器 vs google protobuf-java。
 *
 * <p><b>为什么要有这个探针</b>：{@link CodecBenchmark} 用的是 gRPC 的
 * {@code Payload{name,value,blob}} —— 一个<b>平坦结构</b>，
 * 它打不中 edap protobuf 编解码器的关键路径。本探针用一组专门的消息
 * （{@code CodecStr} / {@code CodecInner} / {@code CodecNest}，见 bench.proto）
 * 去打：</p>
 *
 * <ul>
 *   <li><b>字符串</b>：长 ASCII（Latin1 coder）与长中文（UTF-16 coder）
 *       ⇒ 打 {@code writeString0} 的两条分支 与 {@code readString} 的手写 UTF-8 解码；</li>
 *   <li><b>嵌套消息</b>：内层 1KB × N ⇒ 打「预留 1 字节 → 写内容 → 整块右移」的长度前缀路径；</li>
 *   <li><b>packed int</b>：1K / 16K 个 int ⇒ 打 {@code writePackedInts}；</li>
 *   <li><b>map&lt;string,string&gt;</b>：1K 条 ⇒ 打 {@code writeMap}；</li>
 *   <li><b>bytes</b>：对照组（两边本就同构）。</li>
 * </ul>
 *
 * <p><b>口径（三档，与 {@link CodecBreakdown} 一致）</b>：</p>
 * <table border="1">
 *   <caption>口径</caption>
 *   <tr><th>口径</th><th>edap</th><th>google</th><th>拷贝次数</th></tr>
 *   <tr><td>编码核心</td><td>写进 writer 缓冲（不产出 byte[]）</td>
 *       <td>{@code writeTo(CodedOutputStream)} 写进已有数组</td><td>1 / 1</td></tr>
 *   <tr><td>产出 byte[]</td><td>{@code ProtoBuf.toByteArray}</td>
 *       <td>{@code toByteArray()}</td><td>2 / 1</td></tr>
 * </table>
 *
 * <p>「编码核心」是<b>两把对等的尺子</b>（都是往一个已存在的缓冲里写一次），
 * 才是编解码器能力的真正对比；「产出 byte[]」多出来的拷贝是分配策略的差异，
 * 不是编解码能力的差异。</p>
 *
 * <p><b>★ 防死代码消除</b>：所有被测操作的结果都会累加进静态字段 {@link #SINK}，
 * 否则 JIT 会把「写进一个没人读的缓冲」当成无用计算优化掉，
 * 测出来的数字会比真实值小一个数量级（编解码尤其危险 —— 写入的就是纯粹的数据）。</p>
 *
 * <p><b>wire 一致性</b>：每一行都会把 edap 编出来的字节与 protobuf-java 编出来的字节
 * 逐字节比对。消息定义来自<b>同一份 bench.proto</b>（同时喂两个生成器）⇒ 字节天然应当一致。
 * 不一致时本探针<b>只报告不中断</b>，因为改代码之前 edap 确实存在一处已知的不一致
 * （Latin1 字符串写出非法 UTF-8），需要留档「改前 / 改后」两批数字。</p>
 */
public final class ProtoCodecBenchmark {

    /**
     * ★ 累加所有被测操作的结果 —— 防止 JIT 把写入缓冲的数据当成无用计算消掉。
     */
    private static long sink;

    private ProtoCodecBenchmark() {
    }

    /**
     * 一个负载用例：edap 的 POJO 与 google 的 message 内容逐字段对齐。
     */
    private static final class Case {
        final String name;
        final Class<?> edapClass;
        final Object edap;
        final Message google;

        Case(String name, Class<?> edapClass, Object edap, Message google) {
            this.name = name;
            this.edapClass = edapClass;
            this.edap = edap;
            this.google = google;
        }
    }

    public static void main(String[] args) {
        long millis = args.length > 0 ? Long.parseLong(args[0]) : 800L;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        System.out.printf("编解码器负载矩阵探针 —— edap vs protobuf-java%n");
        System.out.printf("每项测量 %d ms × %d 轮取中位数%n%n", millis, rounds);

        System.out.printf("%-22s %6s | %10s %10s %7s | %10s %10s %7s | %10s %10s %7s%n",
                "负载", "wire", "编码核edap", "编码核goog", "比值",
                "byte[]edap", "byte[]goog", "比值",
                "解码edap", "解码goog", "比值");
        System.out.println("-".repeat(140));

        for (Case c : buildCases()) {
            runCase(c, millis, rounds);
        }

        System.out.println();
        System.out.printf("★ 字符串编码候选实现对比：<b>不在这里跑</b>%n");
        System.out.printf("  多个候选塞进同一个 JVM 会让 op.call() 变成 megamorphic，");
        System.out.printf("实测同一个候选两次跑出 13259 / 24317 这种 2 倍差异。%n");
        System.out.printf("  可比的做法是「一个候选一个 JVM」（多轮取 min）：%n");
        System.out.printf("      ./run-string-candidates.sh 200 3 4%n");

        System.out.println();
        System.out.printf("★ 字符串解码候选实现对比（同一段 UTF-8 字节，五种算法）%n");
        DecodeCandidates.run(millis, rounds);
    }

    // =====================================================================
    // 负载矩阵
    // =====================================================================

    private static List<Case> buildCases() {
        List<Case> cases = new ArrayList<>();

        // ── 1. 长 ASCII 字符串（Latin1 coder）────────────────────────────
        for (int len : new int[]{1024, 16384}) {
            String s = ascii(len);
            cases.add(new Case("str-ascii-" + len, CodecStr.class,
                    strEdap(s), Bench.CodecStr.newBuilder().setS(s).build()));
        }

        // ── 2. 长中文串（UTF-16 coder）──────────────────────────────────
        for (int len : new int[]{1024, 16384}) {
            String s = chinese(len);
            cases.add(new Case("str-cn-" + len, CodecStr.class,
                    strEdap(s), Bench.CodecStr.newBuilder().setS(s).build()));
        }

        // ── 3. 中英混排（★ 字符串编码的盲区：ASCII/非 ASCII 切换）──────
        for (int len : new int[]{1024, 16384}) {
            String s = mixed(len);
            cases.add(new Case("str-mixed-" + len, CodecStr.class,
                    strEdap(s), Bench.CodecStr.newBuilder().setS(s).build()));
        }

        // ── 4. Latin1 高位字符（★ 改前 wire 不一致的已知 bug 现场）──────
        {
            String s = latin1('c', 'a', 'f', 0xE9);
            cases.add(new Case("str-latin1-high", CodecStr.class,
                    strEdap(s), Bench.CodecStr.newBuilder().setS(s).build()));
        }

        // ── 5. 嵌套消息（内层 1KB）× N ───────────────────────────────────
        for (int n : new int[]{1, 8, 64}) {
            String inner = ascii(1024);
            List<CodecInner> edapInners = new ArrayList<>(n);
            List<Bench.CodecInner> goInners = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                edapInners.add(innerEdap(inner, i));
                goInners.add(Bench.CodecInner.newBuilder().setS(inner).setV(i).build());
            }
            CodecNest en = new CodecNest();
            en.setInner(innerEdap(inner, 0));
            en.setInners(edapInners);
            en.setNums(new ArrayList<>());
            en.setAttrs(new LinkedHashMap<>());

            Bench.CodecNest gn = Bench.CodecNest.newBuilder()
                    .setInner(Bench.CodecInner.newBuilder().setS(inner).setV(0).build())
                    .addAllInners(goInners)
                    .build();
            cases.add(new Case("nest-1k-x" + n, CodecNest.class, en, gn));
        }

        // ── 6. packed int 列表 ──────────────────────────────────────────
        for (int n : new int[]{1000, 16000}) {
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
            cases.add(new Case("packed-int-" + n, CodecNest.class, en, gn));
        }

        // ── 7. map<string,string> ───────────────────────────────────────
        {
            int n = 1000;
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                attrs.put("key-" + i, "value-" + i);
            }
            CodecNest en = new CodecNest();
            en.setInner(null);
            en.setInners(new ArrayList<>());
            en.setNums(new ArrayList<>());
            en.setAttrs(attrs);

            Bench.CodecNest gn = Bench.CodecNest.newBuilder().putAllAttrs(attrs).build();
            cases.add(new Case("map-str-" + n, CodecNest.class, en, gn));
        }

        // ── 8. bytes 对照组（两边本就同构）─────────────────────────────
        {
            int len = 16384;
            byte[] blob = blob(len);
            Payload ep = new Payload();
            ep.setName("bytes-control");
            ep.setValue(7);
            ep.setBlob(blob);
            Bench.Payload gp = Bench.Payload.newBuilder()
                    .setName("bytes-control")
                    .setValue(7)
                    .setBlob(com.google.protobuf.ByteString.copyFrom(blob))
                    .build();
            cases.add(new Case("bytes-" + len, Payload.class, ep, gp));
        }

        return cases;
    }

    // =====================================================================
    // 单个用例的测量
    // =====================================================================

    private static void runCase(Case c, long millis, int rounds) {
        byte[] edapBytes = ProtoBuf.toByteArray(c.edap);
        byte[] googleBytes = c.google.toByteArray();
        boolean wireSame = Arrays.equals(edapBytes, googleBytes);

        ProtoBufWriter writer = ProtoBuf.THREAD_WRITER.get();
        ProtoBufEncoder encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(c.edapClass);
        byte[] sinkArray = new byte[Math.max(1024, googleBytes.length + 64)];

        // 预热
        try {
            for (int i = 0; i < 300; i++) {
                encodeIntoBuffer(writer, encoder, c.edap);
                c.google.toByteArray();
                ProtoBuf.toByteArray(c.edap);
                c.google.writeTo(CodedOutputStream.newInstance(sinkArray));
                ProtoBuf.toObject(edapBytes, 0, edapBytes.length, c.edapClass);
                c.google.getParserForType().parseFrom(edapBytes);
            }
        } catch (Exception e) {
            System.out.printf("%-22s %6s | ★用例异常：%s%n", c.name, "★异", e);
            return;
        }

        double edapCore;
        double googleCore;
        double edapArr;
        double googleArr;
        double edapDec;
        double googleDec;
        try {
            edapCore = median(rounds, millis, () -> {
                int len = encodeIntoBuffer(writer, encoder, c.edap);
                sink += writer.getBufOut().getWriteBuf().bs[0];
                return len;
            });
            googleCore = median(rounds, millis, () -> {
                CodedOutputStream cos = CodedOutputStream.newInstance(sinkArray);
                c.google.writeTo(cos);
                cos.flush();
                sink += sinkArray[0];
                return cos.getTotalBytesWritten();
            });
            edapArr = median(rounds, millis, () -> {
                byte[] b = ProtoBuf.toByteArray(c.edap);
                sink += b[0];
                return b.length;
            });
            googleArr = median(rounds, millis, () -> {
                byte[] b = c.google.toByteArray();
                sink += b[0];
                return b.length;
            });
            edapDec = median(rounds, millis, () -> {
                Object o = ProtoBuf.toObject(edapBytes, 0, edapBytes.length, c.edapClass);
                sink += sinkOf(o);
                return 0;
            });
            googleDec = median(rounds, millis, () -> {
                Message m = c.google.getParserForType().parseFrom(edapBytes);
                sink += sinkOf(m);
                return 0;
            });
        } catch (Exception e) {
            System.out.printf("%-22s %6s | ★测量异常：%s%n", c.name, "★异", e);
            return;
        }

        System.out.printf("%-22s %6s | %10.0f %10.0f %6.2fx | %10.0f %10.0f %6.2fx | %10.0f %10.0f %6.2fx  (%d 字节)%n",
                c.name, wireSame ? "同" : "★异",
                edapCore, googleCore, googleCore == 0 ? 0 : edapCore / googleCore,
                edapArr, googleArr, googleArr == 0 ? 0 : edapArr / googleArr,
                edapDec, googleDec, googleDec == 0 ? 0 : edapDec / googleDec,
                googleBytes.length);

        if (!wireSame) {
            System.out.printf("        ★ wire 不一致：edap %d 字节 vs google %d 字节，首个不同位置 %s%n",
                    edapBytes.length, googleBytes.length, firstDiff(edapBytes, googleBytes));
        }
    }

    /**
     * 从解码出来的对象里取一个「便宜但能证明内容真的被解出来」的量。
     */
    private static int sinkOf(Object o) {
        if (o instanceof CodecStr) {
            String s = ((CodecStr) o).getS();
            return s == null ? 0 : s.length();
        }
        if (o instanceof CodecNest) {
            CodecNest n = (CodecNest) o;
            int v = n.getNums() == null ? 0 : n.getNums().size();
            v += n.getAttrs() == null ? 0 : n.getAttrs().size();
            v += n.getInners() == null ? 0 : n.getInners().size();
            return v;
        }
        if (o instanceof Bench.CodecStr) {
            String s = ((Bench.CodecStr) o).getS();
            return s == null ? 0 : s.length();
        }
        if (o instanceof Bench.CodecNest) {
            Bench.CodecNest n = (Bench.CodecNest) o;
            int v = n.getNumsCount();
            v += n.getAttrsCount();
            v += n.getInnersCount();
            return v;
        }
        if (o instanceof Payload) {
            byte[] b = ((Payload) o).getBlob();
            return b == null ? 0 : b.length;
        }
        if (o instanceof Bench.Payload) {
            return ((Bench.Payload) o).getBlob().size();
        }
        return o == null ? 0 : 1;
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

    /**
     * 跑 {@code rounds} 轮，返回每轮结果的中位数（纳秒/次）。
     */
    private static double median(int rounds, long millis, Op op) {
        double[] ns = new double[rounds];
        for (int i = 0; i < rounds; i++) {
            ns[i] = measure(millis, op);
        }
        Arrays.sort(ns);
        return ns[rounds / 2];
    }

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
            throw new IllegalStateException("编解码探针失败", e);
        }
        long elapsed = System.nanoTime() - start;
        return ops == 0 ? 0 : (double) elapsed / ops;
    }

    private static String firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return String.format("%d (edap=0x%02X google=0x%02X)", i, a[i], b[i]);
            }
        }
        return n < Math.max(a.length, b.length) ? String.valueOf(n) + " (长度不同)" : "无";
    }

    // =====================================================================
    // 负载构造
    // =====================================================================

    private static CodecStr strEdap(String s) {
        CodecStr cs = new CodecStr();
        cs.setS(s);
        return cs;
    }

    private static CodecInner innerEdap(String s, int v) {
        CodecInner ci = new CodecInner();
        ci.setS(s);
        ci.setV(v);
        return ci;
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

    /**
     * ★ <b>中英混排</b>（80% ASCII + 20% 中文）⇒ UTF-16 coder。
     *
     * <p>这是现实里最常见的文本形态（中文里夹英文/数字/标点）。
     * ★ 它是<b>字符串编码的盲区</b>：纯 ASCII 走 Latin1 分支、纯中文走 UTF-16 分支，
     * 混排串同时踩到「分支预测失败」与「ASCII/非 ASCII 切换」两个成本。</p>
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
     * 用 ISO_8859_1 字节数组构造 ⇒ JDK 一定把它存成 Latin1（coder==0）。
     */
    private static String latin1(int... bytes) {
        byte[] bs = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            bs[i] = (byte) bytes[i];
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    private static byte[] blob(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    private interface Op {
        long call() throws Exception;
    }
}
