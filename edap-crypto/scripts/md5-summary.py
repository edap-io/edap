#!/usr/bin/env python3
"""汇总 MD5 的 native vs JCE 多轮结果。

用法：md5-summary.py <bench-dir> [文件通配符]

★ 为什么单独一个脚本（而不是复用 native-vs-jce-summary.py）：
  那个脚本按方法名里的 `Digest` / `Mac` 分类原语 —— MD5 的 arm 叫 `nativeMd5_*` /
  `jceMd5_*`（不含 `Digest`）⇒ 会被误判成 `mac` ⇒ 这里按 `Md5` 单独认。

★ 口径（与 DESIGN.md §10.14 同一套纪律）：
  - JMH 是 Mode.AverageTime ⇒ score 是 ns/op，**越大越慢**；
  - 比值取 jce 中位数 / native 中位数 ⇒ >1 表示 native 更快；
  - 跨 launch 取**中位数**（不取 min —— min 等于挑机器最闲的那一轮）；
  - 同时报 CV%：本机是开发机，CV 大的格子不可外推。
"""

import glob
import json
import os
import statistics
import sys
from collections import defaultdict


def parse_name(full):
    """io.edap.crypto.benchmark.Md5VsJceBenchmark.nativeMd5_16t -> ("native", 16)"""
    simple = full.rsplit(".", 1)[-1]
    arm = "native" if simple.startswith("native") else "jce"
    threads = 1
    for t in ("1t", "4t", "16t"):
        if simple.endswith("_" + t):
            threads = int(t[:-1])
            break
    return arm, threads


def load(bench_dir, pattern):
    """返回 {(arm, threads, payload): [score, ...]}"""
    scores = defaultdict(list)
    files = sorted(glob.glob(os.path.join(bench_dir, pattern)))
    for path in files:
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
            arm, threads = parse_name(name)
            payload = str((entry.get("params") or {}).get("payloadSize", "?"))
            scores[(arm, threads, payload)].append(score)
    return scores, files


def stats(values):
    median = statistics.median(values)
    mean = statistics.fmean(values)
    cv = (statistics.stdev(values) / mean * 100.0) if len(values) > 1 and mean else 0.0
    return median, cv


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    bench_dir = sys.argv[1]
    pattern = sys.argv[2] if len(sys.argv) > 2 else "*.json"
    scores, files = load(bench_dir, pattern)

    if not files:
        print("没有读到任何 json 文件：%s" % os.path.join(bench_dir, pattern), file=sys.stderr)
        return 1

    print("# MD5：native(JNI) vs JCE（%d 轮）" % len(files))
    print("")
    print("★ JMH 是 Mode.AverageTime ⇒ score 是 ns/op，**越大越慢**。")
    print("  比值 = jce 中位数 / native 中位数 ⇒ **>1 表示 native 更快**。")
    print("★ 跨 launch 取**中位数**（不取 min）—— 本机是开发机，min 等于挑运气。")
    print("★ 并发档（4t/16t）本机是超载 + 常驻外部负载 ⇒ **CV 大的格子不可外推**。")
    print("")
    print("| payload | 线程 | native 中位数 | jce 中位数 | **native/jce 倍数** | native CV% | jce CV% |")
    print("|---|---|---|---|---|---|---|")

    payloads = sorted({k[2] for k in scores}, key=lambda x: int(x) if x.isdigit() else 0)
    for payload in payloads:
        threads_list = sorted({k[1] for k in scores if k[2] == payload})
        for threads in threads_list:
            nv = scores.get(("native", threads, payload))
            jv = scores.get(("jce", threads, payload))
            if not nv or not jv:
                continue
            n_med, n_cv = stats(nv)
            j_med, j_cv = stats(jv)
            ratio = j_med / n_med if n_med else 0.0
            print("| %s | %dt | %.1f | %.1f | **%.2fx** | %.2f | %.2f |"
                  % (payload, threads, n_med, j_med, ratio, n_cv, j_cv))

    print("")
    print("并发劣化倍数（同 arm，16t 中位数 / 1t 中位数）：")
    print("")
    print("| payload | arm | 1t | 16t | 劣化 |")
    print("|---|---|---|---|---|")
    for payload in payloads:
        for arm in ("native", "jce"):
            one = scores.get((arm, 1, payload))
            many = scores.get((arm, 16, payload))
            if not one or not many:
                continue
            m1, _ = stats(one)
            m16, _ = stats(many)
            print("| %s | %s | %.1f | %.1f | %.2fx |" % (payload, arm, m1, m16, m16 / m1 if m1 else 0.0))

    return 0


if __name__ == "__main__":
    sys.exit(main())
