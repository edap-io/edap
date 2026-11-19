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
 * 四臂对比（Thrift / eRPC / edap-gRPC / grpc-java）的柱状图渲染。
 *
 * <p>★ <b>输入是 markdown 报告本身</b>（{@code ThriftVsErpcVsGrpcMain} 的产物），
 * 解析的是「## 二、原始轮次」那张表 —— 每臂每轮一行，
 * 由此算出每个场景每臂的 rps / p50 / p99 <b>中位数</b>。
 * 好处：<b>不必重跑基准</b>，任何一份历史报告都能直接出图。</p>
 *
 * <p>产出三类图（{@link BenchChart} 的 Java2D 渲染，零三方依赖）：</p>
 * <ol>
 *   <li><b>按负载分图的吞吐图</b>（{@code fourway-rps-<payload>.png}）——
 *       四个负载的吞吐量级差很大（0B 与 64KB 差一个数量级），
 *       放同一张图上小负载的柱子只剩几个像素 ⇒ 按负载分开画；</li>
 *   <li><b>按负载分图的尾延迟图</b>（{@code fourway-p99-<payload>.png}，越小越好）；</li>
 *   <li><b>全景相对图</b>（{@code fourway-relative.png}）——
 *       以 Thrift = 1.00 归一，12 个场景一屏看完谁快谁慢。</li>
 * </ol>
 *
 * <p>★ 图里的文字一律 ASCII（Java2D 取不到中文字形会画成方框，见 {@link BenchChart}）。</p>
 *
 * <p>用法：{@code FourWayChartMain <report.md> <输出目录>}</p>
 */
public final class FourWayChartMain {

    /** 四个臂的顺序 = 柱子的左右顺序。 */
    private static final String[] LABELS = {"Thrift", "eRPC", "edap-gRPC", "grpc-java"};

    /** 臂名 → 下标。 */
    private static int armIndex(String arm) {
        for (int i = 0; i < LABELS.length; i++) {
            if (LABELS[i].equals(arm)) {
                return i;
            }
        }
        return -1;
    }

    private FourWayChartMain() {
    }

    /** 一个场景（负载 + 并发）下某个臂的多轮原始数据。 */
    private static final class ArmRuns {
        final List<Double> rps = new ArrayList<>();
        final List<Double> p50 = new ArrayList<>();
        final List<Double> p99 = new ArrayList<>();

        double medianRps() {
            return median(rps);
        }

        double medianP50() {
            return median(p50);
        }

        double medianP99() {
            return median(p99);
        }
    }

    /** 一个场景下四个臂的数据。 */
    private static final class Row {
        String payload;
        int conc;
        /** runs[臂下标]。 */
        final ArmRuns[] runs = new ArmRuns[LABELS.length];

        Row() {
            for (int i = 0; i < runs.length; i++) {
                runs[i] = new ArmRuns();
            }
        }

        double[] rps() {
            double[] v = new double[LABELS.length];
            for (int i = 0; i < v.length; i++) {
                v[i] = runs[i].medianRps();
            }
            return v;
        }

        double[] p99() {
            double[] v = new double[LABELS.length];
            for (int i = 0; i < v.length; i++) {
                v[i] = runs[i].medianP99();
            }
            return v;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：FourWayChartMain <report.md> <输出目录>");
            System.exit(2);
        }
        Path report = Paths.get(args[0]);
        Path dir = Paths.get(args[1]);
        Files.createDirectories(dir);

        List<Row> rows = parse(report);
        if (rows.isEmpty()) {
            System.err.println("报告里没解析到数据行: " + report);
            System.exit(1);
        }

        // 按负载分组（保持报告里的出现顺序）
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
                rpsGroups.add(new BenchChart.Group("c" + row.conc, LABELS.clone(), row.rps()));
                p99Groups.add(new BenchChart.Group("c" + row.conc, LABELS.clone(), row.p99()));
            }

            Path rpsFile = dir.resolve("fourway-rps-" + payload + ".png");
            BenchChart.png(rpsGroups,
                "End-to-end unary throughput (" + payload + " payload)",
                "requests per second (median of rounds)", "%.0f", rpsFile);
            System.out.println("图已写入: " + rpsFile.toAbsolutePath());

            Path p99File = dir.resolve("fourway-p99-" + payload + ".png");
            BenchChart.png(p99Groups,
                "End-to-end unary p99 (" + payload + ", lower is better)",
                "microseconds (median of rounds)", "%.0f", p99File);
            System.out.println("图已写入: " + p99File.toAbsolutePath());
        }

        // 二、全景相对图（以 Thrift = 1.00 归一）
        List<BenchChart.Group> relative = new ArrayList<>();
        for (Row row : rows) {
            double base = row.rps()[0];
            double[] values = new double[LABELS.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = base <= 0 ? 0 : row.rps()[i] / base;
            }
            relative.add(new BenchChart.Group(row.payload + "@c" + row.conc, LABELS.clone(), values));
        }
        Path relFile = dir.resolve("fourway-relative.png");
        BenchChart.png(relative,
            "Relative end-to-end throughput (Thrift = 1.00)",
            "x Thrift (median of rounds)", "%.2f", relFile);
        System.out.println("图已写入: " + relFile.toAbsolutePath());

        // 三、终端里也打印一份 ASCII 版（PNG 没生成时报告正文仍有图）
        System.out.println();
        System.out.println(BenchChart.ascii(relative, "%.2f"));
    }

    /**
     * 解析报告的「## 二、原始轮次」表。
     *
     * <p>表格形如：{@code | 0B | 1 | Thrift | 30931 | 31 | 67 | 0 |}
     * —— 负载、并发、臂、req/s、p50、p99、错误数。</p>
     */
    private static List<Row> parse(Path report) throws Exception {
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        Map<String, Row> index = new LinkedHashMap<>();

        boolean inRounds = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                // 进入「原始轮次」小节才解析（第一节是汇总表，列不同）
                inRounds = trimmed.contains("原始轮次");
                continue;
            }
            if (!inRounds || !trimmed.startsWith("|")) {
                continue;
            }
            // ★ limit = -1：否则尾部空串会被丢掉，末尾的 "|" 就少算一列
            String[] f = trimmed.split("\\|", -1);
            // split 会得到 ["", 负载, 并发, 臂, rps, p50, p99, 错误, ""]
            if (f.length < 9) {
                continue;
            }
            String payload = f[1].trim();
            String arm = f[3].trim();
            int armIdx = armIndex(arm);
            if (armIdx < 0) {
                continue;
            }
            int conc;
            double rps;
            double p50;
            double p99;
            try {
                conc = Integer.parseInt(f[2].trim());
                rps = Double.parseDouble(f[4].trim());
                p50 = Double.parseDouble(f[5].trim());
                p99 = Double.parseDouble(f[6].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String key = payload + "@" + conc;
            Row row = index.computeIfAbsent(key, k -> {
                Row r = new Row();
                r.payload = payload;
                r.conc = conc;
                return r;
            });
            row.runs[armIdx].rps.add(rps);
            row.runs[armIdx].p50.add(p50);
            row.runs[armIdx].p99.add(p99);
        }
        return new ArrayList<>(index.values());
    }

    private static double median(List<Double> values) {
        double[] v = new double[values.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = values.get(i);
        }
        return BenchRunner.medianOf(v);
    }
}
