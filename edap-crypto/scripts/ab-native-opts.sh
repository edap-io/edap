#!/bin/bash
# A/B 对照：native 三项优化（小载荷 region / 上下文池 / SHA256_CTX 8 字节对齐）。
#
# 用法：
#   ./scripts/ab-native-opts.sh [轮数]     默认 5 轮
#
# 产出（edap-crypto/doc/bench/）：
#   ab-<ts>-before-r<N>.json     修前：HEAD 的 C 源码编译出的 .o（流式化，但三项优化都没有）
#   ab-<ts>-after-r<N>.json      修后：工作树 C 源码编译出的 .o（三项优化都在）
#   bench-ab-<ts>.log            原始日志（含 loadavg / git HEAD / 两个产物的 sha256）
#   summary-ab-<ts>.md           汇总（before/after 中位数 + 相对变化 + JCE 对照臂）
#
# ★ 为什么不用「跑一批 before 再跑一批 after」：
#   本机是开发机（常驻外部负载、突发型干扰）⇒ 跨批次的**绝对值**不可比，
#   只有**比值**可比。而 before/after 隔几十分钟跑 ⇒ 中间的机器状态会变。
#   ⇒ 这里**每轮内交替跑 before → after**，让两臂共享同一段时间窗的机器状态。
#
# ★ JCE 对照臂：JCE arm 完全不受换 .o 影响 ⇒ 它的 before/after 差值就是
#   「这段时间里机器漂移了多少」⇒ 用来判断 native 臂的差值是不是真收益。
#
# ★ 换 .o 的办法：native 侧从**类路径**里的资源读 .o（io.edap.jni.Native），
#   没有路径开关 ⇒ 靠类路径顺序：把 edap-native/target/classes 放在 jar 之前，
#   每轮把目标 .o 拷进 target/classes ⇒ 首个命中即生效。
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}/.."
MODULE_DIR="$(pwd)"
REPO_DIR="$(cd "${MODULE_DIR}/.." && pwd)"

ROUNDS="${1:-5}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${MODULE_DIR}/doc/bench"
AB_DIR="${MODULE_DIR}/target/native-ab"
LOG="${OUT_DIR}/bench-ab-${TS}.log"

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)
fi
JAVA="${JAVA_HOME}/bin/java"
MVN="$(command -v mvn || echo /opt/homebrew/bin/mvn)"

mkdir -p "${OUT_DIR}" "${AB_DIR}"

CORES="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 8)"
loadavg() {
    if [ -r /proc/loadavg ]; then
        awk '{print $1}' /proc/loadavg
    else
        sysctl -n vm.loadavg | awk '{print $2}'
    fi
}

log() { echo "$@" | tee -a "${LOG}"; }

# --- 编译参数（与 edap-native/scripts/build-native.sh 保持一致）---
JNI_INCLUDE="-I${JAVA_HOME}/include -I${JAVA_HOME}/include/darwin"
OPENSSL_INCLUDE="-I/opt/homebrew/opt/openssl@3/include"
OPENSSL_LIB="-L/opt/homebrew/opt/openssl@3/lib"
CC_BIN="${CC:-clang}"
LIB_NAME="edap-native-macos_aarch64.o"

compile_variant() {
    # $1 = 变体名（before/after），$2 = 源目录
    local variant="$1" src="$2"
    local out="${AB_DIR}/${variant}/${LIB_NAME}"
    mkdir -p "${AB_DIR}/${variant}"
    # shellcheck disable=SC2086
    "${CC_BIN}" -shared -fPIC -O2 -Wno-deprecated-declarations \
        ${JNI_INCLUDE} ${OPENSSL_INCLUDE} ${OPENSSL_LIB} -lcrypto \
        "${src}"/*.c -o "${out}"
    echo "${out}"
}

# --- 准备 before 源（HEAD 的 C 源码）与 after 源（工作树）---
prepare_sources() {
    local before_src="${AB_DIR}/before-src"
    local after_src="${AB_DIR}/after-src"
    rm -rf "${before_src}" "${after_src}"
    mkdir -p "${before_src}" "${after_src}"

    # before = HEAD 的 C 源码（流式化已落地，但三项优化都还没有）
    #   ★ 不用 git checkout 改工作树（会毁掉未提交改动）—— 只把文件**取出来**。
    local f
    for f in $(git -C "${REPO_DIR}" ls-tree --name-only HEAD edap-native/src/main/c/); do
        git -C "${REPO_DIR}" show "HEAD:${f}" > "${before_src}/$(basename "${f}")"
    done
    # after = 工作树现状（三项优化都在）
    cp "${REPO_DIR}"/edap-native/src/main/c/* "${after_src}/"

    echo "before 源文件：$(ls "${before_src}" | tr '\n' ' ')"
    echo "after  源文件：$(ls "${after_src}" | tr '\n' ' ')"
    # 护栏：before 必须**没有** edap_jni_array.h（小载荷分流），after 必须有
    if [ -e "${before_src}/edap_jni_array.h" ]; then
        log "FATAL: before 源里出现了 edap_jni_array.h ⇒ 不是「优化前」"
        exit 1
    fi
    if [ ! -e "${after_src}/edap_jni_array.h" ]; then
        log "FATAL: after 源里缺少 edap_jni_array.h"
        exit 1
    fi
}

log "=== native 三项优化 A/B ==="
{
    echo "date        : $(date)"
    echo "rounds      : ${ROUNDS}"
    echo "cores       : ${CORES}"
    echo "uname       : $(uname -a)"
    echo "java        : $(${JAVA} -version 2>&1 | head -1)"
    echo "git HEAD    : $(git -C "${REPO_DIR}" rev-parse HEAD)"
    echo "git status  : $(git -C "${REPO_DIR}" status --porcelain edap-native/src/main/c/ | tr '\n' ' ')"
} | tee -a "${LOG}"

prepare_sources 2>&1 | tee -a "${LOG}"

log "--- compiling before / after ---"
BEFORE_O="$(compile_variant before "${AB_DIR}/before-src" | tail -1)"
AFTER_O="$(compile_variant after "${AB_DIR}/after-src" | tail -1)"
log "before : ${BEFORE_O}  sha=$(shasum -a 256 "${BEFORE_O}" | awk '{print $1}')"
log "after  : ${AFTER_O}  sha=$(shasum -a 256 "${AFTER_O}" | awk '{print $1}')"

# --- 编译 Java + 拉 classpath ---
log "--- building test classes ---"
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-crypto -am test-compile -DskipTests) >> "${LOG}" 2>&1

CP_FILE="$(mktemp)"
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-crypto dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile="${CP_FILE}") >> "${LOG}" 2>&1

# ★ edap-native/target/classes 放在 jar 之前 ⇒ 换掉里面的 .o 就是换掉 native 实现
CP="${MODULE_DIR}/target/test-classes:${MODULE_DIR}/target/classes:${REPO_DIR}/edap-native/target/classes:$(cat "${CP_FILE}")"
TARGET_O="${REPO_DIR}/edap-native/target/classes/${LIB_NAME}"
cp "${AFTER_O}" "${TARGET_O}"

for r in $(seq 1 "${ROUNDS}"); do
    for arm in before after; do
        if [ "${arm}" = "before" ]; then
            SRC_O="${BEFORE_O}"
        else
            SRC_O="${AFTER_O}"
        fi
        cp "${SRC_O}" "${TARGET_O}"
        OUT="${OUT_DIR}/ab-${TS}-${arm}-r${r}.json"
        LOAD="$(loadavg)"
        log "--- round ${r}/${ROUNDS} arm=${arm} (loadavg=${LOAD}) -> $(basename "${OUT}") ---"
        "${JAVA}" -Dedap.bench.out="${OUT}" -cp "${CP}" \
            io.edap.crypto.benchmark.NativeVsJceBenchmark >> "${LOG}" 2>&1
        # native arm 必须真的参与了（不然 JSON 里只有 jce 的结果）
        if ! grep -q '"benchmark" *: *"io.edap.crypto.benchmark.NativeVsJceBenchmark.native' "${OUT}"; then
            log "FATAL: ${OUT} 里没有 native arm 的结果 ⇒ 这一轮作废"
            exit 1
        fi
        log "    loadavg after = $(loadavg)"
    done
done

# 收尾：把目标 .o 换回 after（= 工作树产物）
cp "${AFTER_O}" "${TARGET_O}"

SUMMARY="${OUT_DIR}/summary-ab-${TS}.md"
python3 "${MODULE_DIR}/scripts/ab-summary.py" "${OUT_DIR}" "${TS}" "${ROUNDS}" > "${SUMMARY}"
cat "${SUMMARY}"

echo ""
echo "OK: ${OUT_DIR}"
echo "  ab-${TS}-{before,after}-r<N>.json / summary-ab-${TS}.md / bench-ab-${TS}.log"
