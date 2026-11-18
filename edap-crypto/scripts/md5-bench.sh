#!/bin/bash
# MD5 的 native（JNI）vs JCE 对比基准。
#
# 用法：
#   ./scripts/md5-bench.sh [轮数]      默认 5 轮
#
# 产出（edap-crypto/doc/bench/）：
#   md5-<ts>-r<N>.json          第 N 轮的 JMH 原始输出
#   bench-md5-<ts>.log          原始日志（含 loadavg / java 版本 / native 是否启用）
#   summary-md5-<ts>.md         汇总表（native/jce 比值 + CV）
#
# ★ 为什么「多轮取中位数」而不是「一轮取 min」：
#   本机是开发机（常驻外部负载 + 突发干扰，§10.14）⇒ 绝对值不可外推，
#   **min 等于挑运气**（专挑机器最闲的那一轮）⇒ 只能取中位数并同时报 CV。
# ★ 为什么并发档（4t/16t）的数不能下结论：
#   本机 10 核跑 16 线程是超载 + 常驻外部负载 ⇒ 并发档的 CV 2~8%，
#   落在噪声里（§10.14.8 的同一结论）。
# ★ 跑之前必须确认 native arm 真参与了：
#   NativeDigestProvider.tryCreate 会吞掉 Throwable ⇒ `.o` 过期时静默退回 JCE，
#   那样测的是「JCE vs JCE」（比值恒 1.00 却看不出来）⇒ 脚本每轮都 grep 一遍。
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}/.."
MODULE_DIR="$(pwd)"
REPO_DIR="$(cd "${MODULE_DIR}/.." && pwd)"

ROUNDS="${1:-5}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${MODULE_DIR}/doc/bench"

# ★ 可参数化：同一套跑法既能跑 MD5，也能跑 SHA-256 作**同批对照**
#   （同批 = 同一段机器状态窗口 ⇒ 两个原语的比值可以横向比）。
#   用法示例（SHA-256 对照，只跑摘要臂）：
#     BENCH_CLASS=NativeVsJceBenchmark BENCH_TAG=sha256ctl \
#     SUMMARY_PY=native-vs-jce-summary.py BENCH_INCLUDE='.*Digest.*' \
#     ./scripts/md5-bench.sh 5
BENCH_CLASS="${BENCH_CLASS:-Md5VsJceBenchmark}"
BENCH_TAG="${BENCH_TAG:-md5}"
SUMMARY_PY="${SUMMARY_PY:-md5-summary.py}"
BENCH_INCLUDE="${BENCH_INCLUDE:-}"

LOG="${OUT_DIR}/bench-${BENCH_TAG}-${TS}.log"

# ★ 必须锁死 JDK 17：
#   本机默认 JAVA_HOME 指向 JavaAppletPlugin（坏掉的 JDK 8），
#   而 `/usr/libexec/java_home -v 17` 在本机**返回 JDK 11**（只有 11/1.8 三套），
#   ⇒ 只能按「版本号必须是 17」逐个候选地挑，不能信 java_home 的 fallback。
#   JAVA_HOME 还要 export 出去：下面的 mvn 也吃它（mvn 不认 JAVA 参数）。
#   候选按平台排：macOS（homebrew / java_home）→ Solaris（/build/jdk-17.0.19、/usr/java/jdk17）。
pick_java_home() {
    local c v
    for c in "${JAVA_HOME:-}" \
             /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk \
             "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
             /build/jdk-17.0.19 /usr/java/jdk17; do
        [ -n "${c}" ] && [ -x "${c}/bin/java" ] || continue
        # ★ 不能用 `grep -E`：Solaris 的 grep 是老版本，**不认 -E**（报 illegal option）
        #   ⇒ 版本判定改用纯 bash 的 case（不调外部命令，跨平台最稳）。
        #   匹配 `"17`：JDK 1.7 的 banner 是 `"1.7.0_80"`（没有 `"17`）⇒ 不会误判。
        v="$("${c}/bin/java" -version 2>&1 | head -1)"
        case "${v}" in
            *'"17'*) echo "${c}"; return 0 ;;
        esac
    done
    return 1
}
JAVA_HOME="$(pick_java_home)" || {
    echo "FATAL: 找不到 JDK 17（试过 \$JAVA_HOME / homebrew openjdk@17 / java_home" \
         "/ /build/jdk-17.0.19 / /usr/java/jdk17）" >&2
    exit 1
}
export JAVA_HOME
JAVA="${JAVA_HOME}/bin/java"
MVN="$(command -v mvn || echo /opt/homebrew/bin/mvn)"
# 汇总脚本要 python3（md5-summary.py 用了 statistics 模块 ⇒ py2 跑不了）
PY="$(command -v python3 || true)"

mkdir -p "${OUT_DIR}"

# ★ 三平台的核数与 loadavg —— 目标机是 **Solaris/SPARC（build-01）**，
#   那儿**没有 sysctl，也没有 /proc/loadavg** ⇒ 只能走 kstat / uptime。
#   ★ `set -e` 下命令替换失败会让整个脚本退出 ⇒ 每个探测都要 `|| true` 兜住。
detect_cores() {
    if command -v sysctl >/dev/null 2>&1; then
        local n; n="$(sysctl -n hw.ncpu 2>/dev/null || true)"
        [ -n "${n}" ] && { echo "${n}"; return 0; }
    fi
    if command -v nproc >/dev/null 2>&1; then
        local n; n="$(nproc 2>/dev/null || true)"
        [ -n "${n}" ] && { echo "${n}"; return 0; }
    fi
    if command -v kstat >/dev/null 2>&1; then
        # Solaris：system_misc 里的 cpu_info 每核一行 ⇒ 数行数就是核数（zone 视角）
        local n; n="$(kstat -p system_misc:0:system_misc 2>/dev/null |
                      awk '/cpu_info/ {c++} END {print c+0}' || true)"
        [ -n "${n}" ] && [ "${n}" -gt 0 ] && { echo "${n}"; return 0; }
    fi
    echo 8
}
CORES="$(detect_cores)"

# ★ 1 分钟 loadavg：Linux /proc → macOS sysctl → Solaris kstat → uptime 兜底
loadavg() {
    local v=""
    if [ -r /proc/loadavg ]; then
        v="$(awk '{print $1}' /proc/loadavg 2>/dev/null || true)"
    elif command -v sysctl >/dev/null 2>&1; then
        v="$(sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}' || true)"
    elif command -v kstat >/dev/null 2>&1; then
        v="$(kstat -p system_misc:0:system_misc 2>/dev/null |
              awk '/loadavg/ {for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+\.[0-9]+$/) {print $i; exit}}' || true)"
    fi
    if [ -z "${v}" ]; then
        v="$(uptime 2>/dev/null | sed -n 's/.*load average[s]*: *\([0-9.]*\).*/\1/p' || true)"
    fi
    echo "${v}"
}
log() { echo "$@" | tee -a "${LOG}"; }

log "=== ${BENCH_TAG}: native vs JCE（class=${BENCH_CLASS}）==="
{
    echo "date        : $(date)"
    echo "rounds      : ${ROUNDS}"
    echo "cores       : ${CORES}"
    echo "uname       : $(uname -a)"
    echo "java        : $(${JAVA} -version 2>&1 | head -1)"
    echo "git HEAD    : $(git -C "${REPO_DIR}" rev-parse HEAD)"
} | tee -a "${LOG}"

log "--- building test classes ---"
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-crypto -am test-compile -DskipTests) >> "${LOG}" 2>&1

CP_FILE="$(mktemp)"
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-crypto dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile="${CP_FILE}") >> "${LOG}" 2>&1

# ★ edap-native/target/classes 排在 jar 之前 ⇒ 拿到的是当前源码编出来的 .o
CP="${MODULE_DIR}/target/test-classes:${MODULE_DIR}/target/classes:${REPO_DIR}/edap-native/target/classes:$(cat "${CP_FILE}")"

for r in $(seq 1 "${ROUNDS}"); do
    OUT="${OUT_DIR}/${BENCH_TAG}-${TS}-r${r}.json"
    LOAD="$(loadavg)"
    log "--- round ${r}/${ROUNDS} (loadavg=${LOAD}) -> $(basename "${OUT}") ---"
    if [ -n "${BENCH_INCLUDE}" ]; then
        "${JAVA}" -Dedap.bench.out="${OUT}" -Dedap.bench.include="${BENCH_INCLUDE}" -cp "${CP}" \
            "io.edap.crypto.benchmark.${BENCH_CLASS}" >> "${LOG}" 2>&1
    else
        "${JAVA}" -Dedap.bench.out="${OUT}" -cp "${CP}" \
            "io.edap.crypto.benchmark.${BENCH_CLASS}" >> "${LOG}" 2>&1
    fi
    # native arm 必须真的参与了（否则 JSON 里只有 jce 的结果）
    # ★ 只用 BRE（Solaris 的老 grep 也不认 -E）—— 匹配 JMH JSON 里 benchmark 字段的值
    if ! grep -q "${BENCH_CLASS}\.native" "${OUT}"; then
        log "FATAL: ${OUT} 里没有 native arm 的结果 ⇒ 这一轮作废"
        exit 1
    fi
    log "    loadavg after = $(loadavg)"
done

SUMMARY="${OUT_DIR}/summary-${BENCH_TAG}-${TS}.md"
if [ -z "${PY}" ]; then
    log "FATAL: 找不到 python3 ⇒ 没法汇总（原始 JSON 已落在 ${OUT_DIR}/${BENCH_TAG}-${TS}-r*.json）"
    exit 1
fi
"${PY}" "${MODULE_DIR}/scripts/${SUMMARY_PY}" "${OUT_DIR}" "${BENCH_TAG}-${TS}-r*.json" > "${SUMMARY}"
cat "${SUMMARY}"

echo ""
echo "OK: ${OUT_DIR}"
echo "  ${BENCH_TAG}-${TS}-r<N>.json / summary-${BENCH_TAG}-${TS}.md / bench-${BENCH_TAG}-${TS}.log"
