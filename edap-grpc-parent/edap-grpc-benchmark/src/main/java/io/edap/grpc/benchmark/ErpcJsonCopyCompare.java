/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.grpc.benchmark;

import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.codec.json.JsonErpcCodec;
import io.edap.json.Eson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * eRPC 的 <b>json 载荷拷贝</b> micro —— 验 2026-11-15 SPI 重构（{@code ErpcWriter}）的核心收益。
 *
 * <p>★ <b>为什么单独测 json</b>：本次重构对 <b>protobuf</b> 是「拷贝次数不变」
 * （本来就是 1 拷），唯一被真正改掉的是 <b>json</b>：</p>
 *
 * <table border="1">
 *   <caption>两臂的唯一差别</caption>
 *   <tr><th>臂</th><th>编码</th><th>进待写缓冲</th><th>拷贝次数</th></tr>
 *   <tr><td>A 旧</td>
 *       <td>{@code Eson.toJsonBytes(obj)} —— 内部 {@code writer.toByteArray()} 整块拷一次，
 *           产出<b>独立</b> byte[]</td>
 *       <td>再拷一次</td>
 *       <td><b>2 拷</b></td></tr>
 *   <tr><td>B 新</td>
 *       <td>{@code JsonErpcCodec.encode(obj)} —— 交出<b>借来的</b>缓冲（不拷）</td>
 *       <td>拷一次</td>
 *       <td><b>1 拷</b></td></tr>
 * </table>
 *
 * <p>★ 两臂的可见工作量<b>完全同构</b>：都是「编码 → 把载荷搬进待写缓冲」，
 * 唯一差别就是「编码那一步要不要自己拷一份」⇒ 测出来的差就是那一次整块拷贝。</p>
 *
 * <p>★ <b>不变量</b>：两臂编出的字节必须<b>逐字节相同</b>（json 的线上格式没变）。
 * 这一条由 {@link ErpcCodecTest} 也保证，这里再验一次（因为本探针走的是
 * 「{@code Eson.toJsonBytes} vs 交出视图」这两个不同入口）。</p>
 *
 * <p>★ <b>口径与已知偏差</b>（同 {@link ErpcCodecCompare}）：</p>
 * <ul>
 *   <li>两臂跑在<b>同一个 JVM</b>、同一个线程 ⇒ 共享 {@code Eson.THREAD_WRITER}
 *       ⇒ 测的是「拷贝次数」的差，不是两个实现的差；</li>
 *   <li>两臂<b>轮流先跑</b>（A→B→B→A）并各自取 min ⇒ 摊掉「先跑的那一方占便宜」
 *       这个位置偏差（JIT profile 污染 / 缓存预热）；</li>
 *   <li>目标缓冲的<b>最后一个字节</b>每次都被读出来（逃逸锚点）⇒ 拷贝不会被
 *       JIT 当成死代码消掉。</li>
 * </ul>
 */
public final class ErpcJsonCopyCompare {

    /**
     * 一档的结果。
     */
    public static final class JsonResult {
        public final int blobSize;
        /** 线上载荷字节数。 */
        public final long payloadBytes;
        /** 两臂编出的字节是否逐字节相同（必须相同）。 */
        public final boolean bytesIdentical;

        /** 旧路径（2 拷）ns/op。 */
        public final double legacyNs;
        /** 新路径（1 拷）ns/op。 */
        public final double currentNs;

        JsonResult(int blobSize, long payloadBytes, boolean bytesIdentical,
                   double legacyNs, double currentNs) {
            this.blobSize = blobSize;
            this.payloadBytes = payloadBytes;
            this.bytesIdentical = bytesIdentical;
            this.legacyNs = legacyNs;
            this.currentNs = currentNs;
        }
    }

    private ErpcJsonCopyCompare() {
    }

    public static void main(String[] args) throws Exception {
        long millisPerCase = 1000;
        String out = "target/erpc-json-copy-compare.md";
        int[] blobSizes = BenchConstants.BLOB_SIZES;

        for (int i = 0; i < args.length; i++) {
            if ("--codec".equals(args[i])) {
                millisPerCase = Long.parseLong(args[++i]);
            } else if ("--out".equals(args[i])) {
                out = args[++i];
            } else if ("--blob".equals(args[i])) {
                blobSizes = parseInts(args[++i]);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# eRPC json 载荷拷贝 —— SPI 重构（ErpcWriter）前后的同构对比\n\n");
        sb.append("> 旧路径 = `Eson.toJsonBytes(obj)`（内部整块拷一次，产出独立 byte[]）→ 再拷进待写缓冲 = **2 拷**\n");
        sb.append("> 新路径 = `JsonErpcCodec.encode(obj)`（交出借来的缓冲，不拷）→ 拷进待写缓冲 = **1 拷**\n\n");
        sb.append("## 一、结果\n\n");
        sb.append("| 负载 | 载荷字节 | 两边字节相同 | 旧路径 ns | 新路径 ns | 新/旧 |\n");
        sb.append("|---|---|---|---|---|---|\n");

        for (int blobSize : blobSizes) {
            JsonResult r = runOne(blobSize, millisPerCase);
            sb.append(String.format("| %dB | %d | %s | %.0f | %.0f | %.2fx |%n",
                r.blobSize, r.payloadBytes, r.bytesIdentical ? "是" : "**否**",
                r.legacyNs, r.currentNs,
                r.legacyNs == 0 ? 0 : r.currentNs / r.legacyNs));
        }

        sb.append("\n> ★ **怎么读**：两臂的可见工作量完全同构（编码 + 搬进待写缓冲），\n");
        sb.append("> 唯一差别是编码那一步要不要自己拷一份 ⇒ **新/旧 < 1.00x 的幅度就是省下的那次整块拷贝**。\n");
        sb.append("> protobuf 侧**不在此表**：它本来就是 1 拷，本次重构对它没有拷贝收益\n");
        sb.append("> （protobuf 的收益要靠 micro「帧写出」与端到端两档看，见 eRPC vs gRPC 的汇总）。\n");

        Path path = Paths.get(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(sb);
        System.out.println("报告已写入: " + path.toAbsolutePath());
    }

    private static JsonResult runOne(int blobSize, long millisPerCase) {
        Payload payload = BenchPayloads.edap(blobSize);
        JsonErpcCodec codec = new JsonErpcCodec();

        // 不变量：两臂编出的字节必须逐字节相同
        byte[] legacyBytes = Eson.toJsonBytes(payload);
        ErpcWriter w = codec.encode(payload);
        byte[] currentBytes = Arrays.copyOf(w.buffer(), w.size());
        boolean identical = Arrays.equals(legacyBytes, currentBytes);

        // 待写缓冲的目标数组（预分配，避免把分配成本算进拷贝那一档）
        byte[] target = new byte[Math.max(1024, legacyBytes.length + 64)];

        // 预热两臂
        measure(300, () -> {
            System.arraycopy(Eson.toJsonBytes(payload), 0, target, 32, legacyBytes.length);
            return target[target.length - 1];
        });
        measure(300, () -> {
            ErpcWriter ww = codec.encode(payload);
            System.arraycopy(ww.buffer(), 0, target, 32, ww.size());
            return target[target.length - 1];
        });

        // ★ 两臂轮流先跑（A→B→B→A），各自取 min ⇒ 摊掉位置偏差
        double legacy1 = measure(millisPerCase, () -> {
            byte[] b = Eson.toJsonBytes(payload);
            System.arraycopy(b, 0, target, 32, b.length);
            return target[target.length - 1];
        });
        double current1 = measure(millisPerCase, () -> {
            ErpcWriter ww = codec.encode(payload);
            System.arraycopy(ww.buffer(), 0, target, 32, ww.size());
            return target[target.length - 1];
        });
        double current2 = measure(millisPerCase, () -> {
            ErpcWriter ww = codec.encode(payload);
            System.arraycopy(ww.buffer(), 0, target, 32, ww.size());
            return target[target.length - 1];
        });
        double legacy2 = measure(millisPerCase, () -> {
            byte[] b = Eson.toJsonBytes(payload);
            System.arraycopy(b, 0, target, 32, b.length);
            return target[target.length - 1];
        });

        return new JsonResult(blobSize, legacyBytes.length, identical,
            Math.min(legacy1, legacy2), Math.min(current1, current2));
    }

    /**
     * 跑一段固定时长，返回每次操作的纳秒数。
     */
    private static double measure(long millis, Op op) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long ops = 0;
        long start = System.nanoTime();
        try {
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 100; i++) {
                    op.call();
                    ops++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("json 拷贝 micro 基准失败", e);
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

    private static int[] parseInts(String text) {
        String[] parts = text.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }
}
