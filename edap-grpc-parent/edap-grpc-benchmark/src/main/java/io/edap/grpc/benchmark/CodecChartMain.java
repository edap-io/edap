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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从<b>跨批次中位数</b>的 CSV 渲染柱状图 PNG。
 *
 * <p>★ 为什么不直接用某一批的图：端到端对比要跨 3 批取中位数，
 * 报告正文里的数字是中位数 ⇒ 图也必须画中位数，否则图与表对不上。</p>
 *
 * <p>CSV 格式（{@code compare-codec-reports.py --csv} 产出）：
 * {@code name,blob,conc,std,fast,edap,google}</p>
 *
 * <p>用法：{@code CodecChartMain <summary.csv> <输出目录>}</p>
 */
public final class CodecChartMain {

    /** 臂名（与 {@code CodecCompareMain} 一致，PNG 的图例顺序按它）。 */
    private static final String[] ARM_LABELS = {
        "eRPC-PROTOBUF", "eRPC-FAST", "edap-gRPC", "grpc-java"
    };

    private CodecChartMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：CodecChartMain <summary.csv> <输出目录>");
            System.exit(2);
        }
        Path csv = Paths.get(args[0]);
        Path dir = Paths.get(args[1]);
        Files.createDirectories(dir);

        // 按 name 变体分组，保持文件里的出现顺序
        Map<String, List<BenchChart.Group>> byVariant = new LinkedHashMap<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] f = trimmed.split(",");
            if (f.length < 7) {
                continue;
            }
            String variant = f[0].trim();
            String title = variant + "-" + f[1].trim() + "-c" + f[2].trim();
            double[] values = {
                Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                Double.parseDouble(f[5]), Double.parseDouble(f[6])
            };
            List<BenchChart.Group> groups = byVariant.computeIfAbsent(variant, k -> new ArrayList<>());
            groups.add(new BenchChart.Group(title, ARM_LABELS.clone(), values));
        }

        for (Map.Entry<String, List<BenchChart.Group>> entry : byVariant.entrySet()) {
            List<BenchChart.Group> groups = entry.getValue();
            if (groups.isEmpty()) {
                continue;
            }
            Path png = dir.resolve("erpc-codec-compare-end2end-" + entry.getKey() + ".png");
            BenchChart.png(groups, "End-to-end unary throughput (" + entry.getKey() + " name)",
                "requests per second (median of 3 batches)", png);
            System.out.println("图已写入: " + png.toAbsolutePath());
        }
    }
}
