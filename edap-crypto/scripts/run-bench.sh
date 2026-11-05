#!/bin/bash
# edap-crypto 基准：跑 5 轮，落 before/after 两批 JSON，汇总中位数 + 变异系数。
#
# 用法：
#   ./scripts/run-bench.sh [轮数]     默认 5 轮
#
# 产出（edap-crypto/doc/bench/）：
#   before-CryptoBaseline-<ts>-r<N>.json    修前：裸 JCE，每次 getInstance
#   after-CryptoProvider-<ts>-r<N>.json     修后：走 provider（JCE 实现，实例复用）
#   after-NativeVsJce-<ts>-r<N>.json        native vs JCE
#   summary-<ts>.md                         中位数 + 最小/最大 + 变异系数
#   bench-<ts>.log                          原始日志（含 loadavg）
#
# 纪律（见方案 §3.6）：
#   - 每轮前后记录 loadavg；loadavg 超过核数一半时先等待再跑
#   - ≥5 轮，报中位数 + 最小/最大 + 变异系数，不用单轮数字下结论
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}/.."
MODULE_DIR="$(pwd)"
REPO_DIR="$(cd "${MODULE_DIR}/.." && pwd)"

ROUNDS="${1:-5}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${MODULE_DIR}/doc/bench"
LOG="${OUT_DIR}/bench-${TS}.log"

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)
fi
JAVA="${JAVA_HOME}/bin/java"
MVN="$(command -v mvn || echo /opt/homebrew/bin/mvn)"

mkdir -p "${OUT_DIR}"

CORES="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 8)"

loadavg() {
    # macOS: vm.loadavg (1/5/15 分钟)；Linux: /proc/loadavg
    if [ -r /proc/loadavg ]; then
        awk '{print $1}' /proc/loadavg
    else
        sysctl -n vm.loadavg | awk '{print $2}'
    fi
}

# 等待负载降到核数一半以下（最多等 3 × 30s）。
# 注意：开发机上 IDEA / 编辑器 / 其它后台进程会让 loadavg 长期高于阈值，
# 等再久也不会降下来 —— 所以只短暂等待，然后继续跑并把 loadavg 记录在案，
# 由 5 轮的中位数 + 变异系数来判断数据是否可信。
wait_for_quiet() {
    for _ in $(seq 1 3); do
        local l
        l="$(loadavg)"
        local limit
        limit=$(awk -v c="${CORES}" 'BEGIN{printf "%.2f", c/2}')
        if awk -v a="${l}" -v b="${limit}" 'BEGIN{exit !(a < b)}'; then
            return 0
        fi
        echo "[wait] loadavg=${l} >= ${limit}（核数 ${CORES} 的一半），等待 30s…" | tee -a "${LOG}"
        sleep 30
    done
    echo "[warn] 负载一直偏高，仍继续跑（loadavg 已记录，数据需结合 CV 判断可信度）" | tee -a "${LOG}"
    return 0
}

echo "=== edap-crypto 基准 ===" | tee -a "${LOG}"
{
    echo "date        : $(date)"
    echo "rounds      : ${ROUNDS}"
    echo "cores       : ${CORES}"
    echo "uname       : $(uname -a)"
    echo "java        : $(${JAVA} -version 2>&1 | head -1)"
    echo "openssl cli : $(openssl version 2>/dev/null || echo n/a)"
    echo "git HEAD    : $(cd "${REPO_DIR}" && git rev-parse HEAD)"
} | tee -a "${LOG}"

# --- 编译 + 拉 classpath（test scope，edap-native 是 test scope） ---
echo "--- building test classes ---" | tee -a "${LOG}"
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-crypto -am test-compile -DskipTests) >> "${LOG}" 2>&1

CP_FILE="$(mktemp)"
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-crypto dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile="${CP_FILE}") >> "${LOG}" 2>&1

CP="${MODULE_DIR}/target/test-classes:${MODULE_DIR}/target/classes:$(cat "${CP_FILE}")"

run_bench() {
    # $1 = class simple name, $2 = 输出 json
    # 结果文件由 benchmark 自己的 main 通过 -Dedap.bench.out 配置（父 JMH 进程写）
    local cls="$1" out="$2"
    echo "--- running ${cls} -> ${out} ---" | tee -a "${LOG}"
    "${JAVA}" -Dedap.bench.out="${out}" -cp "${CP}" \
        "io.edap.crypto.benchmark.${cls}" \
        2>&1 | tee -a "${LOG}" | grep -E '^(Benchmark|Result)' || true
}

BASELINE_CLS="CryptoBaselineBenchmark"
PROVIDER_CLS="CryptoProviderBenchmark"
NATIVE_CLS="NativeVsJceBenchmark"

for r in $(seq 1 "${ROUNDS}"); do
    echo "=========== round ${r}/${ROUNDS} ===========" | tee -a "${LOG}"
    wait_for_quiet
    LOAD_BEFORE="$(loadavg)"
    echo "[loadavg] before round ${r}: ${LOAD_BEFORE}" | tee -a "${LOG}"

    run_bench "${BASELINE_CLS}" \
        "${OUT_DIR}/before-CryptoBaseline-${TS}-r${r}.json"
    run_bench "${PROVIDER_CLS}" \
        "${OUT_DIR}/after-CryptoProvider-${TS}-r${r}.json"
    run_bench "${NATIVE_CLS}" \
        "${OUT_DIR}/after-NativeVsJce-${TS}-r${r}.json"

    LOAD_AFTER="$(loadavg)"
    echo "[loadavg] after  round ${r}: ${LOAD_AFTER}" | tee -a "${LOG}"
done

# --- 汇总：中位数 + 最小/最大 + 变异系数 ---
SUMMARY="${OUT_DIR}/summary-${TS}.md"
python3 "${MODULE_DIR}/scripts/summarize-bench.py" "${OUT_DIR}" "${TS}" "${ROUNDS}" > "${SUMMARY}"
cat "${SUMMARY}"

echo ""
echo "OK: ${OUT_DIR}"
echo "  before-*.json / after-*.json / summary-${TS}.md / bench-${TS}.log"
