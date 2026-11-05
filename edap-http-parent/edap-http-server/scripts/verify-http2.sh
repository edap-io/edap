#!/bin/bash
# edap HTTP/2 手工验证矩阵 —— 对应 doc/HTTP2_DESIGN.md §11.1。
#
# 用法：
#   ./scripts/verify-http2.sh [明文端口] [TLS端口]
#
# 依赖：curl（需支持 --http2 / --http2-prior-knowledge）、jq 可选。
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"     # edap-http-server
REPO_DIR="$(cd "${MODULE_DIR}/../.." && pwd)"    # 仓库根

PLAIN_PORT="${1:-18080}"
TLS_PORT="${2:-18443}"

# curl 单次请求的超时上限 —— 没有它，一个卡住的连接会让整个脚本挂死。
CURL_MAX_TIME="${CURL_MAX_TIME:-15}"

if [ -z "${JAVA_HOME}" ] || [ ! -d "${JAVA_HOME}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)
fi
JAVA="${JAVA_HOME}/bin/java"
MVN="$(command -v mvn || echo /opt/homebrew/bin/mvn)"

CP_FILE="$(mktemp)"
SERVER_LOG="$(mktemp)"
SERVER_PID=""

cleanup() {
    if [ -n "${SERVER_PID}" ] && kill -0 "${SERVER_PID}" 2>/dev/null; then
        kill "${SERVER_PID}" 2>/dev/null
        # 等一会儿还不死就 -9 —— 服务是常驻进程，不能让脚本挂在这儿
        for _ in $(seq 1 20); do
            kill -0 "${SERVER_PID}" 2>/dev/null || break
            sleep 0.25
        done
        kill -9 "${SERVER_PID}" 2>/dev/null
        wait "${SERVER_PID}" 2>/dev/null
    fi
    rm -f "${CP_FILE}" "${SERVER_LOG}"
}
trap cleanup EXIT

if ! command -v curl >/dev/null 2>&1; then
    echo "找不到 curl —— 本脚本依赖 curl 的 HTTP/2 支持" >&2
    exit 2
fi

# 前置检查：端口必须空闲。
# 否则本脚本会「连到上一轮遗留的旧服务进程」上测出一堆看似正常的假结果
# （或者在旧服务已经半死的情况下直接挂死）。
for p in "${PLAIN_PORT}" "${TLS_PORT}"; do
    if (exec 3<>"/dev/tcp/127.0.0.1/${p}") 2>/dev/null; then
        exec 3<&- 3>&-
        echo "端口 ${p} 已被占用 —— 上一轮的 verify server 可能还在运行。" >&2
        echo "先把它杀掉再跑（例如 lsof -nP -iTCP:${p} -sTCP:LISTEN）。" >&2
        exit 3
    fi
done

echo "--- building + installing test classes ---"
# install 而不是只 test-compile：下面的 classpath 是从本地仓库解析的，
# 依赖模块（edap-http-core 等）必须是**刚编译出来的**那份，否则会用到旧 jar。
(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-http-parent/edap-http-server -am install -DskipTests) || exit 1

(cd "${REPO_DIR}" && "${MVN}" -q -pl edap-http-parent/edap-http-server dependency:build-classpath \
    -DincludeScope=test -Dmdep.outputFile="${CP_FILE}") || exit 1

CP="${MODULE_DIR}/target/test-classes:${MODULE_DIR}/target/classes:$(cat "${CP_FILE}")"

echo "--- starting server (plain=${PLAIN_PORT} tls=${TLS_PORT}) ---"
# 与根 pom 的 surefire argLine 保持一致（StringUtil / UnsafeUtil 依赖反射访问）
JAVA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.nio=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED"

# 用 exec 让子 shell 被 java 进程替换掉 —— 这样 $! 就是 java 本身的 PID，
# cleanup 里的 kill 才杀得掉它（否则杀的是包装用的子 shell，java 会变孤儿继续占着端口）。
# 输出重定向到文件而不是继承脚本的 stdout —— 否则孤儿 java 会一直攥着管道不放，
# 下游（比如 `| tail`）永远等不到 EOF，整个脚本看起来像卡死了。
(cd "${MODULE_DIR}" && exec "${JAVA}" ${JAVA_OPTS} -cp "${CP}" \
    io.edap.http.server.test.http2.Http2VerifyServer "${PLAIN_PORT}" "${TLS_PORT}") \
    >"${SERVER_LOG}" 2>&1 &
SERVER_PID=$!

# 等端口就绪；顺带盯住服务进程 —— 它要是自己挂了（比如端口被抢、keystore 缺失），
# 上面那个 connect 循环会一直空转到超时，必须在这里就停掉。
SERVER_UP=0
for _ in $(seq 1 60); do
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
        echo "verify server 进程已退出（端口被抢？keystore 缺失？）" >&2
        wait "${SERVER_PID}" 2>/dev/null
        exit 1
    fi
    if (exec 3<>"/dev/tcp/127.0.0.1/${PLAIN_PORT}") 2>/dev/null; then
        exec 3<&- 3>&-
        SERVER_UP=1
        break
    fi
    sleep 0.5
done

if [ "${SERVER_UP}" -ne 1 ]; then
    echo "verify server 在 30s 内没有开始监听 ${PLAIN_PORT}" >&2
    exit 1
fi

FAILED=0
run() {
    local name="$1"; shift
    local expect="$1"; shift
    local out
    out="$("$@" 2>&1)"
    if printf '%s' "${out}" | grep -q "${expect}"; then
        printf '  [ OK ] %-46s %s\n' "${name}" "${expect}"
    else
        printf '  [FAIL] %-46s 期望含 %s\n' "${name}" "${expect}"
        printf '%s\n' "${out}" | head -20 | sed 's/^/         | /'
        FAILED=$((FAILED + 1))
    fi
}

echo ""
echo "=== 验证矩阵 ==="

# --- 明文 ---
run "h2c prior knowledge"        "2 200" curl -s --max-time "${CURL_MAX_TIME}" --http2-prior-knowledge -o /dev/null -w '%{http_version} %{http_code}\n' "http://127.0.0.1:${PLAIN_PORT}/hello"
run "h2c Upgrade (curl --http2)"  "2 200" curl -s --max-time "${CURL_MAX_TIME}" --http2 -o /dev/null -w '%{http_version} %{http_code}\n' "http://127.0.0.1:${PLAIN_PORT}/hello"
run "HTTP/1.1 明文（回归）"       "1.1 200"    curl -s --max-time "${CURL_MAX_TIME}" --http1.1 -o /dev/null -w '%{http_version} %{http_code}\n' "http://127.0.0.1:${PLAIN_PORT}/hello"
run "HTTP/1.1 路径/方法可见"      "/echo|GET"   curl -s --max-time "${CURL_MAX_TIME}" --http1.1 "http://127.0.0.1:${PLAIN_PORT}/echo"

# --- TLS ---
run "h2 over TLS (ALPN)"         "2 200" curl -s --max-time "${CURL_MAX_TIME}" -k --http2 -o /dev/null -w '%{http_version} %{http_code}\n' "https://127.0.0.1:${TLS_PORT}/hello"
run "HTTP/1.1 over TLS (ALPN)"    "1.1 200"    curl -s --max-time "${CURL_MAX_TIME}" -k --http1.1 -o /dev/null -w '%{http_version} %{http_code}\n' "https://127.0.0.1:${TLS_PORT}/hello"

echo ""
if [ "${FAILED}" -eq 0 ]; then
    echo "全部通过。"
else
    echo "${FAILED} 项失败。"
    echo ""
    echo "--- verify server 日志 ---"
    tail -40 "${SERVER_LOG}" | sed 's/^/  | /'
fi
exit "${FAILED}"
