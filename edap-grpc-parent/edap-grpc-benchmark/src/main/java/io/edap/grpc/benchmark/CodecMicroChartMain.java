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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 <b>micro 报告</b>（{@code run-codec-compare.sh} 的输出）渲染成柱状图 PNG。
 *
 * <p>micro 报告里每行是：</p>
 * <pre>
 * ascii-0B | 编码rawStd 编码rawFast 比值 | 编码Std 编码Fast 比值 | 解码Std 解码Fast 比值
 *          | 帧写出Std 帧写出Fast 比值 | 字节Std 字节Fast
 * </pre>
 *
 * <p>★ 为什么比率单独出一张图：绝对 ns 跨负载差两个数量级（15 ns ~ 2800 ns），
 * 画在一根轴上小负载会被压成一条线；而<b>「标准版 / FAST」的比值天然围绕 1.0</b>
 * ⇒ 比率图才是回答「谁更快」的那张。</p>
 *
 * <p>用法：{@code CodecMicroChartMain <micro-report.md> <输出目录>}</p>
 */
public final class CodecMicroChartMain {

    /** 四档的短名（顺序 = 报告里的列顺序）。 */
    private static final String[] TIERS = {"enc-raw", "enc", "dec", "frame"};

    /** 每档的中文说明（进图标题）。 */
    private static final String[] TIER_TITLES = {
        "Encode only - framework body (ns/op)",
        "Encode + copy out (ns/op)",
        "Decode (ns/op)",
        "Frame write - real RPC path (ns/op)"
    };

    /** 每档的 PNG 文件名。 */
    private static final String[] TIER_FILES = {
        "erpc-codec-micro-encraw.png", "erpc-codec-micro-enc.png",
        "erpc-codec-micro-dec.png", "erpc-codec-micro-frame.png"
    };

    /** 两个臂的标签（图例）。 */
    private static final String[] ARM_LABELS = {"eRPC-PROTOBUF", "eRPC-FAST"};

    private CodecMicroChartMain() {
    }

    /**
     * 一行 micro 数据。
     */
    private static final class Row {
        final String payload;
        /** values[tier][0] = 标准版，values[tier][1] = FAST。 */
        final double[][] values = new double[TIERS.length][2];
        /** ratio[tier] = 标准版 / FAST。 */
        final double[] ratio = new double[TIERS.length];

        Row(String payload) {
            this.payload = payload;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：CodecMicroChartMain <micro-report.md> <输出目录>");
            System.exit(2);
        }
        Path report = Paths.get(args[0]);
        Path dir = Paths.get(args[1]);
        Files.createDirectories(dir);

        List<Row> rows = parse(report);
        if (rows.isEmpty()) {
            System.err.println("★ 没从 " + report + " 里解析到数据行");
            System.exit(1);
        }
        System.out.println("解析到 " + rows.size() + " 行负载");

        for (int t = 0; t < TIERS.length; t++) {
            List<BenchChart.Group> groups = new ArrayList<>();
            for (Row r : rows) {
                groups.add(new BenchChart.Group(r.payload, ARM_LABELS.clone(), r.values[t]));
            }
            Path png = dir.resolve(TIER_FILES[t]);
            BenchChart.png(groups, TIER_TITLES[t], "nanoseconds per operation", "%.1f", png);
            System.out.println("图已写入: " + png.toAbsolutePath());
        }

        // 比率图：一组 = 一个负载，4 根柱 = 4 档的比值
        List<BenchChart.Group> ratioGroups = new ArrayList<>();
        for (Row r : rows) {
            ratioGroups.add(new BenchChart.Group(r.payload, TIERS.clone(), r.ratio.clone()));
        }
        Path ratioPng = dir.resolve("erpc-codec-micro-ratio.png");
        BenchChart.png(ratioGroups, "Micro ratio: standard / FAST (>1 means FAST faster)",
            "ratio (standard / FAST)", "%.2fx", ratioPng);
        System.out.println("图已写入: " + ratioPng.toAbsolutePath());
    }

    /**
     * 解析 micro 报告的主表。
     *
     * <p>★ 只取第一张表（eRPC 两套框架那张）；protobuf-java 参照表在另一个 {@code ==} 段里，
     * 段头一出现就停。</p>
     */
    private static List<Row> parse(Path report) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
            if (line.startsWith("== protobuf-java")) {
                break;
            }
            if (!line.contains("|")) {
                continue;
            }
            String[] parts = line.split("\\|");
            // payload | 4 个 (std, fast, ratio) 三元组 | 2 个字节数
            if (parts.length != 6) {
                continue;
            }
            String payload = parts[0].trim();
            if (!payload.startsWith("ascii-") && !payload.startsWith("cn-")) {
                continue;
            }
            Row row = new Row(payload);
            try {
                for (int t = 0; t < TIERS.length; t++) {
                    String[] cells = parts[t + 1].trim().split("\\s+");
                    if (cells.length != 3) {
                        throw new NumberFormatException("三元组列数不对：" + parts[t + 1]);
                    }
                    row.values[t][0] = Double.parseDouble(cells[0]);
                    row.values[t][1] = Double.parseDouble(cells[1]);
                    row.ratio[t] = Double.parseDouble(cells[2].replace("x", ""));
                }
            } catch (NumberFormatException e) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }
}
