#!/usr/bin/env python3
"""汇总 native 三项优化（小载荷 region / 上下文池 / 8 字节对齐）的 A/B 结果。

用法：ab-summary.py <bench-dir> <ts> [轮数]

读 ab-<ts>-before-r<N>.json 与 ab-<ts>-after-r<N>.json，
逐格给出 before / after 的中位数、相对变化与变异系数。

★ 为什么相对变化用中位数算：JMH 是 Mode.AverageTime（score = ns/op，越大越慢），
  本机是开发机（常驻外部负载 + 突发干扰）⇒ 单轮数字不可信，只有中位数可用。
★ 为什么要带 JCE 对照臂：JCE arm 完全不受换 .o 影响 ⇒ 它的 before/after 差值
  就是「这两批之间机器漂了多少」⇒ 用来判断 native 臂的差值是不是真收益。
"""

import glob
import json
import os
import statistics
import sys
from collections import defaultdict


def parse_name(full):
    """io.edap.crypto.benchmark.NativeVsJceBenchmark.nativeDigest_16t
       -> ("digest", "native", 16)"""
    simple = full.rsplit(".", 1)[-1]
    arm = "native" if simple.startswith("native") else "jce"
    kind = "digest" if "Digest" in simple else "mac"
    threads = 1
    for t in ("1t", "4t", "16t"):
        if simple.endswith("_" + t):
            threads = int(t[:-1])
            break
    return kind, arm, threads


def load(bench_dir, ts, arm):
    """返回 {(kind, arm, threads, payload): [score, ...]}"""
    scores = defaultdict(list)
    pattern = os.path.join(bench_dir, f"ab-{ts}-{arm}-r*.json")
    for path in sorted(glob.glob(pattern)):
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
            kind, bench_arm, threads = parse_name(name)
            payload = str((entry.get("params") or {}).get("payloadSize", "?"))
            scores[(kind, bench_arm, threads, payload)].append(score)
    return scores


def median(values):
    return statistics.median(values) if values else 0.0


def cv(values):
    if len(values) < 2:
        return 0.0
    mean = statistics.fmean(values)
    return (statistics.stdev(values) / mean * 100.0) if mean else 0.0


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    bench_dir, ts = sys.argv[1], sys.argv[2]
    before = load(bench_dir, ts, "before")
    after = load(bench_dir, ts, "after")

    if not before or not after:
        print("没有读到 before/after 的 json：%s" % bench_dir, file=sys.stderr)
        return 1

    print("# native 三项优化 A/B（%s）" % ts)
    print("")
    print("★ JMH Mode.AverageTime ⇒ score 是 ns/op，**越大越慢**。")
    print("  相对变化 = (before − after) / before ⇒ **正数 = 优化后更快**。")
    print("  JCE 对照臂完全不受换 .o 影响 ⇒ 它的相对变化就是「机器漂移」⇒")
    print("  native 臂的相对变化要显著大于对照漂移，才算真收益。")
    print("")
    print("| 原语 | payload | 线程 | before 中位数 | after 中位数 | **相对变化** | before CV% | after CV% | JCE 对照漂移 |")
    print("|---|---|---|---|---|---|---|---|---|")

    kinds = sorted({k[0] for k in before})
    for kind in kinds:
        payloads = sorted({k[3] for k in before if k[0] == kind}, key=lambda x: int(x) if x.isdigit() else 0)
        for payload in payloads:
            threads_list = sorted({k[2] for k in before if k[0] == kind and k[3] == payload})
            for threads in threads_list:
                nb = before.get((kind, "native", threads, payload))
                na = after.get((kind, "native", threads, payload))
                if not nb or not na:
                    continue
                mb, ma = median(nb), median(na)
                rel = ((mb - ma) / mb * 100.0) if mb else 0.0
                jb = before.get((kind, "jce", threads, payload))
                ja = after.get((kind, "jce", threads, payload))
                if jb and ja:
                    mj_b, mj_a = median(jb), median(ja)
                    drift = ((mj_b - mj_a) / mj_b * 100.0) if mj_b else 0.0
                    drift_s = "%+.2f%%" % drift
                else:
                    drift_s = "n/a"
                print("| %s | %s | %dt | %.1f | %.1f | **%+.2f%%** | %.2f | %.2f | %s |"
                      % (kind, payload, threads, mb, ma, rel, cv(nb), cv(na), drift_s))
        print("| | | | | | | | | |")

    return 0


if __name__ == "__main__":
    sys.exit(main())
