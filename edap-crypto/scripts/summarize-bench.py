#!/usr/bin/env python3
"""汇总 JMH 多轮结果：中位数 + 最小/最大 + 变异系数。

用法：summarize-bench.py <bench-dir> <timestamp> <rounds>

读 <bench-dir>/{before,after}-*-<timestamp>-r*.json，
按 benchmark 名聚合各轮 score，输出 markdown 表格。

变异系数 CV = 标准差 / 均值 × 100%，衡量多轮之间的稳定程度。
"""

import glob
import json
import os
import statistics
import sys
from collections import defaultdict


def load_rounds(bench_dir, ts):
    """返回 {benchmark 名: {轮次: score}} 与 {benchmark 名: 参数串}"""
    scores = defaultdict(dict)
    params = {}
    pattern = os.path.join(bench_dir, f"*-{ts}-r*.json")
    for path in sorted(glob.glob(pattern)):
        # 文件名里 r<N>.json
        base = os.path.basename(path)
        try:
            rnd = int(base.rsplit("-r", 1)[1].split(".")[0])
        except (IndexError, ValueError):
            continue
        with open(path, "r", encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                continue
        if not isinstance(data, list):
            data = [data]
        for entry in data:
            name = entry.get("benchmark")
            metric = entry.get("primaryMetric") or {}
            score = metric.get("score")
            if not name or score is None:
                continue
            scores[name][rnd] = score
            p = entry.get("params") or {}
            params[name] = ",".join(f"{k}={v}" for k, v in sorted(p.items()))
    return scores, params


def fmt(value):
    if value is None:
        return "-"
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 1:
        return f"{value:,.2f}"
    return f"{value:.3f}"


def main():
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    bench_dir, ts, rounds = sys.argv[1], sys.argv[2], int(sys.argv[3])
    scores, params = load_rounds(bench_dir, ts)

    print(f"# edap-crypto 基准汇总（{ts}）")
    print("")
    print(f"轮数：{rounds}；变异系数 CV = 标准差 / 均值 × 100%。")
    print("")
    print("| benchmark | 参数 | 轮数 | 中位数 (ns/op) | 最小 | 最大 | 均值 | CV% |")
    print("|---|---|---|---|---|---|---|---|")

    # 按 benchmark 名前缀分组（同一个类的 arm 放在一起）
    def group_of(name):
        return name.rsplit(".", 1)[0]

    groups = defaultdict(list)
    for name in scores:
        groups[group_of(name)].append(name)

    for group in sorted(groups):
        for name in sorted(groups[group]):
            per_round = scores[name]
            values = [per_round[r] for r in sorted(per_round)]
            if not values:
                continue
            median = statistics.median(values)
            mean = statistics.fmean(values)
            cv = (statistics.stdev(values) / mean * 100.0) if len(values) > 1 and mean else 0.0
            print(
                f"| `{name.split('.')[-1]}` | {params.get(name, '')} | {len(values)} | "
                f"{fmt(median)} | {fmt(min(values))} | {fmt(max(values))} | {fmt(mean)} | {cv:.2f} |"
            )
        print("| | | | | | | | |")

    return 0


if __name__ == "__main__":
    sys.exit(main())
