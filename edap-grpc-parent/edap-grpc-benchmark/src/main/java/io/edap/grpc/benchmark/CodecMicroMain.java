/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.grpc.benchmark;

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcFrameEncoder;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.codec.fastprotobuf.FastProtobufCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;

import java.util.Arrays;

/**
 * <b>一个候选一个 JVM</b> 的编解码 micro 基准 —— 比较 edap 自研 protobuf 的两套序列化框架
 * （标准 wire 与 FAST 变体），并把 protobuf-java 作为「不同源」的量级参照。
 *
 * <p><b>★ 为什么必须是「一个候选一个 JVM」</b>：同一 JVM 里连着测多个候选时，
 * JIT 的 profile 会被上一个候选的调用点形状污染（测点变成多态调用点 ⇒ 内联失效），
 * 而且**机器降频会让排在后面的候选被系统性压慢** ⇒ 测出来的「谁更快」里混着「谁跑在后面」。
 * 编排脚本（{@code run-codec-compare.sh}）负责：多轮 launch + 候选顺序按 launch 轮转 + 取 min。</p>
 *
 * <p><b>★ 三档口径</b>（沿用 {@code ErpcCodecCompare} 的定义，保证与历史报告可比）：</p>
 * <table border="1">
 *   <caption>候选</caption>
 *   <tr><th>候选</th><th>测什么</th></tr>
 *   <tr><td>{@code std-enc-raw} / {@code fast-enc-raw}</td>
 *       <td>只调 {@code codec.encode(obj)} 并读 {@code writer.size()}，<b>不拷出</b>
 *           ⇒ 这是<b>框架本体</b>的编码成本（其余各档都含一次拷贝，会稀释差异）</td></tr>
 *   <tr><td>{@code std-enc} / {@code fast-enc}</td>
 *       <td>{@code codec.encode(obj)} + <b>显式拷出</b>成独立 {@code byte[]}
 *           （eRPC 的 encode 交出的是<b>借来的</b>缓冲，不拷出来的话数据可能被 JIT 当成无用计算消掉）</td></tr>
 *   <tr><td>{@code std-dec} / {@code fast-dec}</td>
 *       <td>{@code codec.decode(bytes, ...)} — ★ <b>输入字节用各自的格式</b>（两边 wire 不同！）</td></tr>
 *   <tr><td>{@code std-frame} / {@code fast-frame}</td>
 *       <td>{@code ErpcFrameEncoder.encode(...)} → sink 把载荷拷进目标缓冲（帧头内联，载荷只拷一次）</td></tr>
 *   <tr><td>{@code pbjava-enc} / {@code pbjava-dec}</td>
 *       <td>protobuf-java 的 {@code toByteArray()} / {@code parseFrom(bytes)}
 *           — ★ <b>不同源</b>，只作量级参照；且 {@code toByteArray()} 对<b>同一个对象</b>
 *           会命中 memoized size（两遍法被缓存掩盖）⇒ 这一档对 protobuf-java 偏乐观</td></tr>
 * </table>
 *
 * <p><b>用法</b>：{@code CodecMicroMain <candidate> <millis> <rounds> <nameVariant> <blobSize>}
 * （nameVariant：0=ASCII，1=中文；输出 {@code RESULT ...} 行供脚本解析）</p>
 */
public final class CodecMicroMain {

    /**
     * ★ 累加所有被测操作的结果 —— 防止 JIT 把写入缓冲 / 解析出来的数据当成无用计算消掉。
     */
    private static long sink;

    private CodecMicroMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("用法：CodecMicroMain <candidate> <millis> <rounds> <nameVariant> <blobSize>");
            System.err.println("candidate ∈ {std-enc-raw, fast-enc-raw, std-enc, fast-enc,"
                + " std-dec, fast-dec, std-frame, fast-frame,"
                + " pbjava-enc, pbjava-dec, all}");
            System.exit(2);
        }
        String candidate = args[0];
        long millis = Long.parseLong(args[1]);
        int rounds = Integer.parseInt(args[2]);
        int variant = Integer.parseInt(args[3]);
        int blobSize = Integer.parseInt(args[4]);

        String name = BenchConstants.requestName(variant);
        Payload edapPayload = BenchPayloads.edap(blobSize, name);
        Bench.Payload googlePayload = BenchPayloads.google(blobSize, name);

        ErpcCodec std = new ProtobufErpcCodec();
        ErpcCodec fast = new FastProtobufCodec();

        // ★ 各自格式的字节（wire 不同，不能混用）
        byte[] stdBytes = copyOut(std.encode(edapPayload));
        byte[] fastBytes = copyOut(fast.encode(edapPayload));
        byte[] googleBytes = googlePayload.toByteArray();

        // ── 自检：坏掉的路径会表现为「快得离谱」而不是报错 ⇒ 必须先验 ──────
        if (stdBytes == null || stdBytes.length == 0) {
            throw new IllegalStateException("★ 标准版编码返回 null/空（异常被吞掉了）");
        }
        if (fastBytes == null || fastBytes.length == 0) {
            throw new IllegalStateException("★ FAST 版编码返回 null/空（异常被吞掉了）");
        }
        if (googleBytes == null || googleBytes.length == 0) {
            throw new IllegalStateException("★ protobuf-java 编码返回 null/空");
        }
        Payload stdBack = std.decode(stdBytes, 0, stdBytes.length, Payload.class);
        Payload fastBack = fast.decode(fastBytes, 0, fastBytes.length, Payload.class);
        if (stdBack == null) {
            throw new IllegalStateException("★ 标准版解码返回 null（异常被吞掉了）");
        }
        if (fastBack == null) {
            throw new IllegalStateException("★ FAST 版解码返回 null（异常被吞掉了）");
        }
        if (!samePayload(edapPayload, stdBack)) {
            throw new IllegalStateException("★ 标准版 round-trip 内容不一致");
        }
        if (!samePayload(edapPayload, fastBack)) {
            throw new IllegalStateException("★ FAST 版 round-trip 内容不一致");
        }
        Bench.Payload googleBack = Bench.Payload.parseFrom(googleBytes);
        if (!sameGoogle(googlePayload, googleBack)) {
            throw new IllegalStateException("★ protobuf-java round-trip 内容不一致");
        }

        if ("all".equals(candidate)) {
            System.out.printf("RESULT candidate=all stdBytes=%d fastBytes=%d googleBytes=%d%n",
                stdBytes.length, fastBytes.length, googleBytes.length);
            return;
        }

        Op op = opOf(candidate, std, fast, edapPayload, googlePayload,
            stdBytes, fastBytes, googleBytes);

        // ── 预热：FAST 的 encoder/decoder 是首次使用时 ASM 现场生成的 ─────
        for (int i = 0; i < 500; i++) {
            op.call();
        }

        // ── 计时：rounds 轮取 min（min 比中位数更抗噪） ─────────────────
        double[] ns = new double[rounds];
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < rounds; i++) {
            ns[i] = measure(millis, op);
            if (i > 0) {
                all.append(',');
            }
            all.append(String.format("%.1f", ns[i]));
        }
        double best = Double.MAX_VALUE;
        for (double n : ns) {
            best = Math.min(best, n);
        }

        // ── ★ 计时之后再验一次内容 ────────────────────────────────────────
        // FAST 的 UTF16LE 分支直接写 String 的 final 字段（value/coder），
        // 有被 JIT 当成常量折叠的风险 ⇒ 必须在 JIT 编译完（预热 + 计时）之后再验一次。
        if (!samePayload(edapPayload, std.decode(stdBytes, 0, stdBytes.length, Payload.class))) {
            throw new IllegalStateException("★ 标准版 round-trip 内容不一致（计时后）");
        }
        if (!samePayload(edapPayload, fast.decode(fastBytes, 0, fastBytes.length, Payload.class))) {
            throw new IllegalStateException("★ FAST 版 round-trip 内容不一致（计时后）");
        }
        if (!sameGoogle(googlePayload, Bench.Payload.parseFrom(googleBytes))) {
            throw new IllegalStateException("★ protobuf-java round-trip 内容不一致（计时后）");
        }

        System.out.printf("RESULT candidate=%s ns=%.1f stdBytes=%d fastBytes=%d googleBytes=%d"
                + " variant=%s blob=%d rounds=%s%n",
            candidate, best, stdBytes.length, fastBytes.length, googleBytes.length,
            BenchConstants.requestNameLabel(variant), blobSize, all);
        if (sink == Long.MIN_VALUE) {
            System.out.println("sink=" + sink);
        }
    }

    // =====================================================================
    // 候选
    // =====================================================================

    private static Op opOf(String candidate, ErpcCodec std, ErpcCodec fast,
                           Payload edapPayload, Bench.Payload googlePayload,
                           byte[] stdBytes, byte[] fastBytes, byte[] googleBytes) {
        switch (candidate) {
            case "std-enc-raw":
                return () -> {
                    // ★ 不拷出：只读 size()（写入落在 writer 自己的 byte[] 上，
                    //   是真实副作用，不会被 JIT 消掉）⇒ 测的是「框架本体」成本
                    ErpcWriter w = std.encode(edapPayload);
                    sink += w.size();
                    return w.size();
                };
            case "fast-enc-raw":
                return () -> {
                    ErpcWriter w = fast.encode(edapPayload);
                    sink += w.size();
                    return w.size();
                };
            case "std-enc":
                return () -> {
                    byte[] b = copyOut(std.encode(edapPayload));
                    sink += b.length;
                    return b.length;
                };
            case "fast-enc":
                return () -> {
                    byte[] b = copyOut(fast.encode(edapPayload));
                    sink += b.length;
                    return b.length;
                };
            case "std-dec":
                return () -> {
                    Payload p = std.decode(stdBytes, 0, stdBytes.length, Payload.class);
                    sink += p.getValue();
                    return p.getValue();
                };
            case "fast-dec":
                return () -> {
                    Payload p = fast.decode(fastBytes, 0, fastBytes.length, Payload.class);
                    sink += p.getValue();
                    return p.getValue();
                };
            case "pbjava-enc":
                return () -> {
                    byte[] b = googlePayload.toByteArray();
                    sink += b.length;
                    return b.length;
                };
            case "pbjava-dec":
                return () -> {
                    Bench.Payload p = Bench.Payload.parseFrom(googleBytes);
                    sink += p.getValue();
                    return p.getValue();
                };
            case "std-frame":
            case "fast-frame": {
                ErpcCodec codec = "std-frame".equals(candidate) ? std : fast;
                // ★ 目标缓冲与 sink 都提前建好（不把 lambda 分配算进测量）
                byte[] target = new byte[Math.max(1024, stdBytes.length + fastBytes.length + 128)];
                ErpcFrameEncoder.FrameSink sinkImpl = (header, buf, len) -> {
                    header.writeTo(target, 0);
                    if (len > 0) {
                        System.arraycopy(buf, 0, target, ErpcFrameHeader.HEADER_LENGTH, len);
                    }
                };
                return () -> {
                    ErpcFrameEncoder.encode(codec, ErpcFrameType.REQUEST.getValue(), codec.id(),
                        0, 0, 0, 0L, edapPayload, sinkImpl);
                    sink += target[0];
                    return target[0];
                };
            }
            default:
                throw new IllegalArgumentException("未知候选：" + candidate);
        }
    }

    /**
     * eRPC 的 {@code encode} 交出的是<b>借来的</b>缓冲 ⇒ 拷出来才能长期持有，
     * 也只有拷出来 JIT 才不会把编码当成无用计算消掉。
     */
    private static byte[] copyOut(ErpcWriter writer) {
        return writer == null ? null : Arrays.copyOf(writer.buffer(), writer.size());
    }

    // =====================================================================
    // 内容比较（round-trip 自检）
    // =====================================================================

    /**
     * ★ 空与 null 视为等价：edap 的解码器对「零长度的 bytes 字段」给 null，
     * 而 protobuf-java 给空 {@code ByteString} ⇒ 两边都不能按「null != 空」判失败。
     */
    static boolean samePayload(Payload a, Payload b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!eq(a.getName(), b.getName()) || a.getValue() != b.getValue()) {
            return false;
        }
        byte[] x = a.getBlob();
        byte[] y = b.getBlob();
        if (isEmpty(x) && isEmpty(y)) {
            return true;
        }
        return Arrays.equals(x, y);
    }

    static boolean sameGoogle(Bench.Payload a, Bench.Payload b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!eq(a.getName(), b.getName()) || a.getValue() != b.getValue()) {
            return false;
        }
        return a.getBlob().equals(b.getBlob());
    }

    private static boolean isEmpty(byte[] b) {
        return b == null || b.length == 0;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // =====================================================================
    // 计时
    // =====================================================================

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

    /**
     * micro 操作。
     */
    private interface Op {
        long call() throws Exception;
    }
}
