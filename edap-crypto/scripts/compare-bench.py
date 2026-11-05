#!/usr/bin/env python3
"""before vs after 对比：修前（每次 getInstance）vs 修后（provider 复用实例）。

用法：compare-bench.py <bench-dir> <timestamp> [rounds]

读 <bench-dir>/before-CryptoBaseline-<ts>-r*.json
  与 <bench-dir>/after-CryptoProvider-<ts>-r*.json，
按「方法名 + 参数」配对，输出中位数对比 + 加速比。

加速比 = before 中位数 / after 中位数（>1 表示修后更快）。
"""

import glob
import json
import os
import statistics
import sys
from collections import defaultdict


def load(bench_dir, ts, prefix):
    """返回 {(方法名, 参数串): [各轮 score]}"""
    out = defaultdict(list)
    pattern = os.path.join(bench_dir, f"{prefix}-{ts}-r*.json")
    for path in sorted(glob.glob(pattern)):
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
            score = (entry.get("primaryMetric") or {}).get("score")
            if not name or score is None:
                continue
            method = name.rsplit(".", 1)[-1]
            params = ",".join(
                f"{k}={v}" for k, v in sorted((entry.get("params") or {}).items())
            )
            out[(method, params)].append((rnd, score))
    return out


def med(values):
    return statistics.median(values)


def fmt(value):
    if value is None:
        return "-"
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 1:
        return f"{value:,.2f}"
    return f"{value:.3f}"


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    bench_dir, ts = sys.argv[1], sys.argv[2]
    rounds = int(sys.argv[3]) if len(sys.argv) > 3 else None

    before = load(bench_dir, ts, "before-CryptoBaseline")
    after = load(bench_dir, ts, "after-CryptoProvider")

    print(f"# 修前 vs 修后对比（{ts}）")
    print()
    print("修前 = 裸 JCE，每次 `getInstance` 新建实例；修后 = 走 `CryptoProvider`（实例按线程复用）。")
    print("加速比 = 修前中位数 / 修后中位数（>1 表示修后更快）。")
    print()
    print("| arm | payload | 修前中位数 (ns/op) | 修后中位数 (ns/op) | 加速比 | 修后 CV% |")
    print("|---|---|---|---|---|---|")

    keys = sorted(set(before) & set(after))
    for method, params in keys:
        b = [s for _, s in sorted(before[(method, params)])]
        a = [s for _, s in sorted(after[(method, params)])]
        bm, am = med(b), med(a)
        cv = ""
        if len(a) > 1 and statistics.fmean(a):
            cv = f"{statistics.stdev(a) / statistics.fmean(a) * 100:.2f}"
        payload = params.replace("payloadSize=", "")
        print(
            f"| `{method}` | {payload} | {fmt(bm)} | {fmt(am)} | "
            f"{bm / am:.3f}x | {cv} |"
        )

    only_before = sorted(set(before) - set(after))
    only_after = sorted(set(after) - set(before))
    if only_before or only_after:
        print()
        if only_before:
            print(f"> 只有 before 的 arm：{only_before}")
        if only_after:
            print(f"> 只有 after 的 arm：{only_after}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
