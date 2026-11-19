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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三方对比（grpc-java / edap-gRPC / eRPC）的柱状图渲染。
 *
 * <p>输入是 {@code scripts/compare-threeway-reports.py --csv} 产出的 CSV：
 * {@code payload,conc,grpc_java_rps,edap_grpc_rps,erpc_rps,grpc_java_p99,edap_grpc_p99,erpc_p99}。</p>
 *
 * <p>产出三类图（全部用 {@link BenchChart} 的 Java2D 渲染，零三方依赖）：</p>
 * <ol>
 *   <li><b>按负载分图的吞吐图</b>（{@code threeway-rps-<payload>.png}）——
 *       四个负载的吞吐量级差 17 倍（6,392 ~ 110,302 req/s），
 *       放同一张图上小负载的柱子只剩几个像素 ⇒ 按负载分开画；</li>
 *   <li><b>按负载分图的尾延迟图</b>（{@code threeway-p99-<payload>.png}，越小越好）；</li>
 *   <li><b>全景相对图</b>（{@code threeway-relative.png}）——
 *       以 grpc-java = 1.0 归一，12 个场景一屏看完谁快谁慢。</li>
 * </ol>
 *
 * <p>★ 图里的文字一律 ASCII（Java2D 取不到中文字形会画成方框，见 {@link BenchChart}）。</p>
 *
 * <p>用法：{@code ThreeWayChartMain <summary.csv> <输出目录>}</p>
 */
public final class ThreeWayChartMain {

    /** 三个臂的顺序 = 柱子的左右顺序。 */
    private static final String[] LABELS = {"grpc-java", "edap-gRPC", "eRPC"};

    /** CSV 里三方的列下标（rps 与 p99 各一份）。 */
    private static final int IDX_GRPC_JAVA = 0;
    private static final int IDX_EDAP_GRPC = 1;
    private static final int IDX_ERPC = 2;

    private ThreeWayChartMain() {
    }

    /** 一行 = 一个场景（负载 + 并发）。 */
    private static final class Row {
        String payload;
        int conc;
        final double[] rps = new double[3];
        final double[] p99 = new double[3];
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：ThreeWayChartMain <summary.csv> <输出目录>");
            System.exit(2);
        }
        Path csv = Paths.get(args[0]);
        Path dir = Paths.get(args[1]);
        Files.createDirectories(dir);

        List<Row> rows = readCsv(csv);
        if (rows.isEmpty()) {
            System.err.println("CSV 里没有数据行: " + csv);
            System.exit(1);
        }

        // 按负载分组（保持 CSV 里的出现顺序）
        Map<String, List<Row>> byPayload = new LinkedHashMap<>();
        for (Row row : rows) {
            byPayload.computeIfAbsent(row.payload, k -> new ArrayList<>()).add(row);
        }

        // 一、按负载分图的吞吐图 + 尾延迟图
        for (Map.Entry<String, List<Row>> entry : byPayload.entrySet()) {
            String payload = entry.getKey();
            List<Row> group = entry.getValue();

            List<BenchChart.Group> rpsGroups = new ArrayList<>();
            List<BenchChart.Group> p99Groups = new ArrayList<>();
            for (Row row : group) {
                rpsGroups.add(new BenchChart.Group("c" + row.conc, LABELS.clone(), row.rps.clone()));
                p99Groups.add(new BenchChart.Group("c" + row.conc, LABELS.clone(), row.p99.clone()));
            }

            BenchChart.png(rpsGroups,
                "End-to-end unary throughput (" + payload + " payload)",
                "requests per second (median of 3 batches)", "%.0f",
                dir.resolve("threeway-rps-" + payload + ".png"));
            System.out.println("图已写入: " + dir.resolve("threeway-rps-" + payload + ".png").toAbsolutePath());

            BenchChart.png(p99Groups,
                "End-to-end unary p99 (" + payload + ", lower is better)",
                "microseconds (median of 3 batches)", "%.0f",
                dir.resolve("threeway-p99-" + payload + ".png"));
            System.out.println("图已写入: " + dir.resolve("threeway-p99-" + payload + ".png").toAbsolutePath());
        }

        // 二、全景相对图（以 grpc-java = 1.0 归一）
        List<BenchChart.Group> relative = new ArrayList<>();
        for (Row row : rows) {
            double base = row.rps[IDX_GRPC_JAVA];
            double[] values = new double[3];
            for (int i = 0; i < 3; i++) {
                values[i] = base <= 0 ? 0 : row.rps[i] / base;
            }
            relative.add(new BenchChart.Group(row.payload + "@c" + row.conc, LABELS.clone(), values));
        }
        BenchChart.png(relative,
            "Relative end-to-end throughput (grpc-java = 1.00)",
            "x grpc-java (median of 3 batches)", "%.2f",
            dir.resolve("threeway-relative.png"));
        System.out.println("图已写入: " + dir.resolve("threeway-relative.png").toAbsolutePath());

        // 三、终端里也打印一份 ASCII 版（PNG 没生成时报告正文仍有图）
        System.out.println();
        System.out.println(BenchChart.ascii(relative, "%.2f"));
    }

    /**
     * 读 CSV —— {@code payload,conc,rps×3,p99×3}。
     */
    private static List<Row> readCsv(Path csv) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("payload,")) {
                continue;
            }
            String[] f = trimmed.split(",");
            if (f.length < 8) {
                continue;
            }
            Row row = new Row();
            row.payload = f[0].trim();
            row.conc = Integer.parseInt(f[1].trim());
            row.rps[IDX_GRPC_JAVA] = Double.parseDouble(f[2].trim());
            row.rps[IDX_EDAP_GRPC] = Double.parseDouble(f[3].trim());
            row.rps[IDX_ERPC] = Double.parseDouble(f[4].trim());
            row.p99[IDX_GRPC_JAVA] = Double.parseDouble(f[5].trim());
            row.p99[IDX_EDAP_GRPC] = Double.parseDouble(f[6].trim());
            row.p99[IDX_ERPC] = Double.parseDouble(f[7].trim());
            rows.add(row);
        }
        return rows;
    }
}
