#!/usr/bin/env python3
"""汇总 NativeVsJceBenchmark 的多轮结果：native vs jce 配对比率。

用法：native-vs-jce-summary.py <bench-dir> [文件通配符]

默认读 <bench-dir>/*.json，按文件名里的 -r<N>.json 识别轮次，
把 native<jce> 配成一行，输出 markdown 表格。

★ 为什么要单独一个脚本（而不是直接用 summarize-bench.py）：
  summarize-bench.py 按「benchmark 名 + 参数串」聚合，输出的是每个 arm 自己的统计量；
  而这一轮真正要回答的问题是**同一个 (payload, 线程数) 下 native 与 jce 的比值**，
  比值才是跨机器、跨批次可比的读数（绝对值不可外推）。

★ 参数名必须进 key：JMH 的 JSON 里 benchmark 名只到方法名，
  同一方法的多个 @Param 取值共用一个名字 —— 只按方法名聚合会把几组参数**静默合并**。
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


def load(bench_dir, pattern):
    """返回 {(kind, arm, threads, payload): {轮次: score}}"""
    scores = defaultdict(dict)
    files = sorted(glob.glob(os.path.join(bench_dir, pattern)))
    for path in files:
        base = os.path.basename(path)
        rnd = None
        if "-r" in base:
            try:
                rnd = int(base.rsplit("-r", 1)[1].split(".")[0])
            except ValueError:
                rnd = None
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
            kind, arm, threads = parse_name(name)
            payload = (entry.get("params") or {}).get("payloadSize", "?")
            scores[(kind, arm, threads, str(payload))][rnd if rnd is not None else 0] = score
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
    rounds = len(files)
    print("# native vs jce（%s，%d 轮）" % (bench_dir, rounds))
    print("")
    print("★ JMH 是 Mode.AverageTime ⇒ score 是 ns/op，**越大越慢**。")
    print("  所以比值取 jce 中位数 / native 中位数，>1 表示 native 更快（口径同 DESIGN.md）。")
    print("")

    header = ("| 原语 | payload | 线程 | 轮数 | native 中位数 | jce 中位数 | **native/jce 倍数** "
              "| native CV% | jce CV% |")
    print(header)
    print("|---|---|---|---|---|---|---|---|---|")

    kinds = sorted({k[0] for k in scores})
    for kind in kinds:
        payloads = sorted({k[3] for k in scores if k[0] == kind}, key=lambda x: int(x) if x.isdigit() else 0)
        for payload in payloads:
            threads_list = sorted({k[2] for k in scores if k[0] == kind and k[3] == payload})
            for threads in threads_list:
                nv = scores.get((kind, "native", threads, payload))
                jv = scores.get((kind, "jce", threads, payload))
                if not nv or not jv:
                    continue
                n_vals = list(nv.values())
                j_vals = list(jv.values())
                n_med, n_cv = stats(n_vals)
                j_med, j_cv = stats(j_vals)
                ratio = j_med / n_med if n_med else 0.0
                print("| %s | %s | %dt | %d | %.1f | %.1f | **%.2fx** | %.2f | %.2f |"
                      % (kind, payload, threads, min(len(n_vals), len(j_vals)),
                         n_med, j_med, ratio, n_cv, j_cv))
        print("| | | | | | | | | |")

    # 并发劣化：同一 arm 下 16t / 1t
    print("")
    print("并发劣化倍数（同 arm，16t 中位数 / 1t 中位数）：")
    print("")
    print("| 原语 | arm | payload | 1t | 16t | 劣化 |")
    print("|---|---|---|---|---|---|")
    for kind in kinds:
        payloads = sorted({k[3] for k in scores if k[0] == kind}, key=lambda x: int(x) if x.isdigit() else 0)
        for payload in payloads:
            for arm in ("native", "jce"):
                one = scores.get((kind, arm, 1, payload))
                many = scores.get((kind, arm, 16, payload))
                if not one or not many:
                    continue
                m1, _ = stats(list(one.values()))
                m16, _ = stats(list(many.values()))
                print("| %s | %s | %s | %.1f | %.1f | %.2fx |"
                      % (kind, arm, payload, m1, m16, m16 / m1 if m1 else 0.0))

    return 0


if __name__ == "__main__":
    sys.exit(main())
