#!/usr/bin/env python3
"""多份基准报告的跨批次对比：取各批次的中位数。

用法：compare-reports.py [--out <file>] <report.md> [report.md ...]

每份报告是 BenchMain 生成的 markdown，本脚本解析两个章节：

  - 「一、端到端 unary 性能」—— E→E 与 G→G 的端到端对比（老格式）；
  - 「二、2×2 矩阵」—— 客户端 × 服务端的四个臂（E→E / E→G / G→E / G→G）。

输出各批次的中位数，以及每个批次的原始数字（用于看批内波动）。

★ 为什么用中位数而不是平均值：机器负载高时单轮数字会整体漂移，
  中位数对个别离群轮次不敏感（与 edap-crypto 的 compare-bench.py 同一口径）。
"""

import argparse
import os
import statistics
import sys

# 2×2 矩阵的四个臂在报告里的列顺序（与 BenchMain 一致）。
ARM_LABELS = ["E→E", "E→G", "G→E", "G→G"]


def read_sections(path):
    """把报告按 '## ' 标题切成 {标题: [正文行]}。"""
    sections = {}
    current = None
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("## "):
                current = line[3:].strip()
                sections[current] = []
            elif current is not None:
                sections[current].append(line)
    return sections


def parse_table(lines, min_cells):
    """从一个章节里抽出表格行（跳过表头与分隔行），返回 cells 列表。"""
    rows = []
    for line in lines:
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [c.strip() for c in stripped.strip("|").split("|")]
        if len(cells) < min_cells:
            continue
        if cells[0] in ("负载", "---") or set(cells[0]) == {"-"}:
            continue
        if not cells[1].isdigit():
            continue
        rows.append(cells)
    return rows


def parse_report(path):
    """返回 (端到端表, 2×2 矩阵表)。

    端到端表：{(负载, 并发): {'edap': {...}, 'google': {...}}}
    矩阵表：  {(负载, 并发): {'arms': [...], 'gains': [...]}}
    """
    scenarios = {}
    matrix = {}
    for title, lines in read_sections(path).items():
        if title.startswith("一、"):
            for cells in parse_table(lines, 9):
                try:
                    row = {
                        "edap_rps": float(cells[2]),
                        "google_rps": float(cells[3]),
                        "edap_p50": float(cells[5]),
                        "google_p50": float(cells[6]),
                        "edap_p99": float(cells[7]),
                        "google_p99": float(cells[8]),
                    }
                except ValueError:
                    continue
                scenarios[(cells[0], int(cells[1]))] = row
        elif title.startswith("二、"):
            for cells in parse_table(lines, 10):
                try:
                    arms = [float(cells[2 + i]) for i in range(4)]
                    gains = [float(cells[6 + i].rstrip("x")) for i in range(4)]
                except ValueError:
                    continue
                matrix[(cells[0], int(cells[1]))] = {"arms": arms, "gains": gains}
    return scenarios, matrix


def median(values):
    return statistics.median(values)


def fmt(value):
    return f"{value:,.0f}"


def size_key(label):
    """'0B' → 0, '1KB' → 1024, '16KB' → 16384 —— 用来把负载按大小排序。"""
    text = label.strip()
    if text.endswith("KB"):
        return int(text[:-2]) * 1024
    if text.endswith("B"):
        return int(text[:-1])
    return 0


def render_end_to_end(batches):
    """一、端到端的中位数表（老格式，保持与历史 summary 一致）。"""
    out = []
    out.append("| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |")
    out.append("|---|---|---|---|---|---|---|---|---|")
    for key in sorted(batches[0][1].keys(), key=lambda k: (size_key(k[0]), k[1])):
        rows = [b[1][key] for b in batches if key in b[1]]
        if not rows:
            continue
        edap_rps = median([r["edap_rps"] for r in rows])
        google_rps = median([r["google_rps"] for r in rows])
        ratio = edap_rps / google_rps if google_rps else 0
        out.append("| {} | {} | {} | {} | {:.2f}x | {} | {} | {} | {} |".format(
            key[0], key[1], fmt(edap_rps), fmt(google_rps), ratio,
            fmt(median([r["edap_p50"] for r in rows])),
            fmt(median([r["google_p50"] for r in rows])),
            fmt(median([r["edap_p99"] for r in rows])),
            fmt(median([r["google_p99"] for r in rows]))))

    out.append("")
    out.append("## 各批次原始数字（端到端 edap req/s）")
    out.append("")
    out.append("| 负载 | 并发 | " + " | ".join(name for name, _ in batches) + " |")
    out.append("|---|---|" + "---|" * len(batches))
    for key in sorted(batches[0][1].keys(), key=lambda k: (size_key(k[0]), k[1])):
        cells = []
        for _, scenarios in batches:
            cells.append(fmt(scenarios[key]["edap_rps"]) if key in scenarios else "-")
        out.append("| {} | {} | {} |".format(key[0], key[1], " | ".join(cells)))
    return out


def render_matrix(matrix_batches):
    """二、2×2 矩阵的中位数表。"""
    out = []
    out.append("## 2×2 矩阵中位数（各批次取中位数）")
    out.append("")
    out.append("| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端 | 换服务端 | 全栈 | 交互项 |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    first = matrix_batches[0][1]
    for key in sorted(first.keys(), key=lambda k: (size_key(k[0]), k[1])):
        rows = [b[1][key] for b in matrix_batches if key in b[1]]
        if not rows:
            continue
        arms = [median([r["arms"][i] for r in rows]) for i in range(4)]
        gains = [median([r["gains"][i] for r in rows]) for i in range(4)]
        out.append("| {} | {} | {} | {} | {} | {} | {:.2f}x | {:.2f}x | {:.2f}x | {:.2f}x |".format(
            key[0], key[1], fmt(arms[0]), fmt(arms[1]), fmt(arms[2]), fmt(arms[3]),
            gains[0], gains[1], gains[2], gains[3]))

    for arm_index, arm_label in enumerate(ARM_LABELS):
        out.append("")
        out.append("## 各批次原始数字（{} req/s）".format(arm_label))
        out.append("")
        out.append("| 负载 | 并发 | " + " | ".join(name for name, _ in matrix_batches) + " |")
        out.append("|---|---|" + "---|" * len(matrix_batches))
        for key in sorted(first.keys(), key=lambda k: (size_key(k[0]), k[1])):
            cells = []
            for _, matrix in matrix_batches:
                cells.append(fmt(matrix[key]["arms"][arm_index]) if key in matrix else "-")
            out.append("| {} | {} | {} |".format(key[0], key[1], " | ".join(cells)))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", help="报告文件（按批次顺序）")
    parser.add_argument("--out", help="输出文件")
    args = parser.parse_args()

    end_to_end = []
    matrices = []
    for path in args.reports:
        scenarios, matrix = parse_report(path)
        if not scenarios and not matrix:
            print(f"⚠ {path} 里没解析到性能表，跳过", file=sys.stderr)
            continue
        end_to_end.append((os.path.basename(path), scenarios))
        if matrix:
            matrices.append((os.path.basename(path), matrix))

    if not end_to_end:
        print("没有可用报告", file=sys.stderr)
        return 1

    out = []
    out.extend(render_end_to_end(end_to_end))
    if matrices:
        out.append("")
        out.extend(render_matrix(matrices))

    text = "\n".join(out) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"已写入 {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
