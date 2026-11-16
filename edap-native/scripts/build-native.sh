#!/bin/bash
# 编译 edap-native .o 文件
#
# 用法：
#   ./scripts/build-native.sh [输出目录]   默认 target/native-build（不碰已提交产物）
#   ./scripts/build-native.sh --commit      输出到 src/main/resources/（更新已提交产物）
#
# 输出：<输出目录>/edap-native-{os}_{arch}.o
# 编译 src/main/c/ 下的全部 .c（一次编译成一个共享库 —— System.load 只能加载一个文件）
# 同时输出 target/openssl_version.h（OpenSSL 版本，供 C 侧 #if 分支用）
#
# 支持的平台：macOS / Linux / Solaris（含 SPARC）。
#   Solaris 上通常要显式指定 OpenSSL 位置，例如：
#     OPENSSL_INCLUDE=-I/opt/csw/include OPENSSL_LIB="-L/opt/csw/lib" ./scripts/build-native.sh --commit
#   产物名由「JVM 的 os.name / os.arch」决定（与运行时 Native.java 的取名规则一致），
#   所以在目标机上跑本脚本、在目标机上加载，才是同一份产物。
set -e

cd "$(dirname "$0")/.."

COMMIT=0
OUT_DIR="target/native-build"
for arg in "$@"; do
    case "$arg" in
        --commit) COMMIT=1 ;;
        *) OUT_DIR="$arg" ;;
    esac
done

# --- JAVA_HOME ---
# macOS 有 /usr/libexec/java_home；Solaris 没有（/usr/java/jdk*）⇒ 逐个来源兜底。
if [ -z "$JAVA_HOME" ]; then
    if [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=$(/usr/libexec/java_home)
    elif command -v java >/dev/null 2>&1; then
        JAVA_HOME=$(dirname "$(dirname "$(command -v java)")")
    fi
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
    echo "找不到可用的 JDK：请先 export JAVA_HOME=<JDK17 路径>" >&2
    echo "  (macOS: /usr/libexec/java_home -v 17；Solaris: /usr/java/jdk17)" >&2
    exit 1
fi
JAVA="${JAVA_HOME}/bin/java"
JAVAC="${JAVA_HOME}/bin/javac"
if [ ! -x "${JAVAC}" ]; then
    echo "需要 JDK（javac 缺失）：${JAVAC}" >&2
    exit 1
fi

# --- 平台识别：直接问 JVM ---
# ★ 唯一事实源 = io.edap.jni.Platform（运行时 Native 用的就是它）：
#   这里用 javac 把它编译出来再运行，拿到与运行时**逐字节一致**的 os / arch / 产物名。
#   ★ 为什么不能问 uname：Solaris/SPARC 上 `uname -m` 恒为 sun4v，
#     而 JVM 的 os.arch 是 sparcv9（64 位 JDK）—— 两者不是一回事，
#     按 uname 取名 ⇒ 运行时按 os.arch 找文件必然找不到 ⇒ native 静默不加载，
#     所有加密悄悄退回 JVM 内置实现。
#   （历史教训：本脚本曾用 awk 解析 `-XshowSettings:properties`，但 $1 带前导空格
#     永远匹配不上 key ⇒ 一直静默退回 uname，macOS 上碰巧对、Solaris 上直接错。）
PLATFORM_SRC="src/main/java/io/edap/jni/Platform.java"
PLATFORM_CLASSES="target/platform-classes"
rm -rf "${PLATFORM_CLASSES}"
mkdir -p "${PLATFORM_CLASSES}"
"${JAVAC}" -d "${PLATFORM_CLASSES}" "${PLATFORM_SRC}"
PLATFORM_INFO=$("${JAVA}" -cp "${PLATFORM_CLASSES}" io.edap.jni.Platform)

platform_field() {
    printf '%s\n' "${PLATFORM_INFO}" | sed -n "s/^$1=//p" | head -1
}

RAW_OS_NAME=$(platform_field os.name)
RAW_OS_ARCH=$(platform_field os.arch)
OS=$(platform_field os)
ARCH=$(platform_field arch)
LIB_NAME=$(platform_field name)

if [ -z "${LIB_NAME}" ]; then
    echo "无法从 JVM 解析平台信息（Platform 没有输出）：" >&2
    printf '%s\n' "${PLATFORM_INFO}" >&2
    exit 1
fi

case "${OS}" in
    macos|linux|sunos) ;;
    *)
        echo "unsupported OS: ${RAW_OS_NAME}（归一化结果 '${OS}'）" >&2
        echo "  本脚本只支持 macos / linux / sunos" >&2
        exit 1
        ;;
esac

# --- 编译器 ---
# Solaris 上 gcc 与 Sun Studio cc 的共享库参数不同：
#   gcc → -shared -fPIC；cc(Sun Studio) → -shared -KPIC（不认 -fPIC）
CC_BIN=""
# ★ 保留调用方通过环境变量传入的 CC_FAMILY（Solaris 上区分 gcc 与 Sun Studio cc 需要它）：
#   这里若直接清空，CC_FAMILY=studio 会被静默丢掉 ⇒ 用错共享库参数（-fPIC vs -KPIC）。
CC_FAMILY="${CC_FAMILY:-}"
case "$OS" in
    sunos)
        if [ -n "${CC:-}" ]; then
            CC_BIN="$CC"; CC_FAMILY="${CC_FAMILY:-custom}"
        elif command -v gcc >/dev/null 2>&1; then
            CC_BIN="gcc"; CC_FAMILY="gcc"
        else
            CC_BIN="cc"; CC_FAMILY="studio"
        fi
        ;;
    macos) CC_BIN="${CC:-clang}"; CC_FAMILY="${CC_FAMILY:-clang}" ;;
    linux) CC_BIN="${CC:-gcc}"; CC_FAMILY="${CC_FAMILY:-gcc}" ;;
esac

case "$CC_FAMILY" in
    studio) SHARED_FLAGS="-shared -KPIC"; WARN_FLAGS="" ;;
    *)      SHARED_FLAGS="-shared -fPIC";  WARN_FLAGS="-Wno-deprecated-declarations" ;;
esac

# 头文件路径（各平台 jni_md.h 位置不同）
JNI_INCLUDE="-I${JAVA_HOME}/include"
case "$OS" in
    macos) JNI_INCLUDE="${JNI_INCLUDE} -I${JAVA_HOME}/include/darwin" ;;
    linux) JNI_INCLUDE="${JNI_INCLUDE} -I${JAVA_HOME}/include/linux" ;;
    sunos) JNI_INCLUDE="${JNI_INCLUDE} -I${JAVA_HOME}/include/solaris" ;;
esac

# OpenSSL 头文件 + lib 路径
#   显式环境变量优先 —— Solaris 上 OpenSSL 常装在 /opt/csw 或自编译目录，
#   不在默认搜索路径里（例如只有 libcrypto.so.1.1、没有 libcrypto.so 软链）。
#   例：OPENSSL_INCLUDE=-I/opt/csw/include OPENSSL_LIB="-L/opt/csw/lib"
OPENSSL_INCLUDE="${OPENSSL_INCLUDE-}"
OPENSSL_LIB="${OPENSSL_LIB-}"
if [ -z "$OPENSSL_INCLUDE" ] && [ -z "$OPENSSL_LIB" ]; then
    if [ -d "/opt/homebrew/opt/openssl@3/include" ]; then
        OPENSSL_INCLUDE="-I/opt/homebrew/opt/openssl@3/include"
        OPENSSL_LIB="-L/opt/homebrew/opt/openssl@3/lib"
    elif [ -d "/opt/homebrew/opt/openssl/include" ]; then
        OPENSSL_INCLUDE="-I/opt/homebrew/opt/openssl/include"
        OPENSSL_LIB="-L/opt/homebrew/opt/openssl/lib"
    fi
fi
# Solaris：CSW 的 OpenSSL 不在默认搜索路径
if [ "$OS" = "sunos" ] && [ -z "$OPENSSL_LIB" ] && [ -d "/opt/csw/lib" ]; then
    OPENSSL_LIB="-L/opt/csw/lib"
fi

# 链接期附加库：pthread（Linux 需要，Solaris 有 libpthread）、
# socket/nsl（Solaris 上 libc 之外的网络库）
LINK_EXTRA=""
case "$OS" in
    linux) LINK_EXTRA="-lpthread" ;;
    sunos) LINK_EXTRA="-lpthread -lsocket -lnsl" ;;
esac

# 编译 src/main/c/ 下的全部 .c（一次编译成一个共享库）
SOURCES=(src/main/c/*.c)
if [ ! -e "${SOURCES[0]}" ]; then
    echo "no C source found under src/main/c/" >&2
    exit 1
fi

mkdir -p "${OUT_DIR}" target

echo "platform   : ${RAW_OS_NAME} / ${RAW_OS_ARCH}  ->  ${LIB_NAME}"
echo "compiler   : ${CC_BIN} (${CC_FAMILY})"
echo "flags      : ${SHARED_FLAGS} ${WARN_FLAGS} ${JNI_INCLUDE} ${OPENSSL_INCLUDE} ${OPENSSL_LIB} ${LINK_EXTRA}"
echo "compiling ${#SOURCES[@]} source file(s) -> ${OUT_DIR}/${LIB_NAME}"
for f in "${SOURCES[@]}"; do echo "  - ${f}"; done

# 编译为 shared library（macOS file 命令的输出会是 "dynamically linked shared library"，
# 这是预期的 —— System.load() 接受 .dylib 也接受 .o；命名沿用 .o 只是历史约定）
# 注意：不能加 -c（compile only），否则输出是 relocatable object，System.load() 会失败
"${CC_BIN}" ${SHARED_FLAGS} -O2 \
    ${WARN_FLAGS} \
    ${JNI_INCLUDE} \
    ${OPENSSL_INCLUDE} \
    ${OPENSSL_LIB} \
    -lcrypto \
    ${LINK_EXTRA} \
    "${SOURCES[@]}" \
    -o "${OUT_DIR}/${LIB_NAME}" || exit 1

# --- OpenSSL 版本头（C 侧可用 #if EDAP_OPENSSL_VERSION_NUMBER >= 0x30000000L 分支）---
# 版本必须取「编译时用的头文件」的版本，而不是 PATH 上的 openssl CLI ——
# 两者可能不是同一个安装（例如 CLI 是 4.x、头文件是 3.x）。
OPENSSL_VERSION="unknown"
OPENSSL_VERSION_NUMBER="0x00000000L"
for hdr in ${OPENSSL_INCLUDE#-I}/openssl/opensslv.h \
           /opt/homebrew/opt/openssl@3/include/openssl/opensslv.h \
           /opt/homebrew/opt/openssl/include/openssl/opensslv.h \
           /opt/csw/include/openssl/opensslv.h \
           /usr/include/openssl/opensslv.h; do
    if [ -f "$hdr" ]; then
        OPENSSL_VERSION=$(sed -n 's/^# *define OPENSSL_VERSION_TEXT *"\(.*\)"/\1/p' "$hdr" | head -1)
        [ -z "$OPENSSL_VERSION" ] && OPENSSL_VERSION="unknown"
        OPENSSL_VERSION_NUMBER=$(sed -n 's/^# *define OPENSSL_VERSION_NUMBER *0x\([0-9a-fA-F]*\).*/0x\1L/p' "$hdr" | head -1)
        [ -z "$OPENSSL_VERSION_NUMBER" ] && OPENSSL_VERSION_NUMBER="0x00000000L"
        break
    fi
done
mkdir -p target
cat > target/openssl_version.h <<EOF
/* 由 scripts/build-native.sh 生成，勿手改 */
#ifndef EDAP_OPENSSL_VERSION_H
#define EDAP_OPENSSL_VERSION_H
#define EDAP_OPENSSL_VERSION_STRING "${OPENSSL_VERSION}"
#define EDAP_OPENSSL_VERSION_NUMBER ${OPENSSL_VERSION_NUMBER}
#endif
EOF

# --- 自检：符号 / 文件类型 / 摘要 ---
echo "--- self check ---"
EXPECTED_SYMBOLS=(
    Java_io_edap_jni_crypto_NativeHmacSha256_sign0
    Java_io_edap_jni_crypto_NativeDigest_digest0
    Java_io_edap_jni_crypto_NativeMac_hmacSha2560
    Java_io_edap_jni_crypto_NativeMac_hmacSha10
)

# nm 的参数各家不同：GNU 认 -g / -D，Solaris 的 nm 不认 -g（-g 在那边是别的意思，
# 裸 nm 与 -D 才是对的）。逐个尝试、合并有输出的结果；
# 若 nm 完全不可用 ⇒ 只跳过符号自检，绝不误判成「符号缺失」把构建打断。
NM_OUT=""
for nm_opt in "" "-D" "-g"; do
    # shellcheck disable=SC2086
    nm_out=$(nm ${nm_opt} "${OUT_DIR}/${LIB_NAME}" 2>/dev/null || true)
    if [ -n "${nm_out}" ]; then
        NM_OUT="${NM_OUT}${nm_out}"$'\n'
    fi
done

# 判断符号是否「已定义」：nm 输出里未定义符号的类型字母是 U。
# 兼容 GNU（"0000000000001159 T sym"）与 Solaris（"    3e0 T sym"）两种格式。
symbol_defined() {
    printf '%s\n' "${NM_OUT}" \
        | grep -F -- "$1" \
        | awk '{
              line = $0
              sub(/^[ \t]+/, "", line)
              sub(/^[0-9a-fA-F]+[ \t]+/, "", line)
              t = substr(line, 1, 1)
              if (t != "U" && t != "u") { print "yes"; exit }
          }' \
        | grep -q "yes"
}

if [ -z "${NM_OUT}" ]; then
    echo "  warn: nm 在本平台不可用，跳过符号自检"
else
    MISSING=0
    for sym in "${EXPECTED_SYMBOLS[@]}"; do
        if symbol_defined "${sym}"; then
            echo "  ok   ${sym}"
        else
            echo "  MISS ${sym}" >&2
            MISSING=1
        fi
    done
    if [ "$MISSING" != "0" ]; then
        echo "missing JNI symbols — aborting" >&2
        exit 1
    fi
fi

file "${OUT_DIR}/${LIB_NAME}"
# 摘要：macOS 用 shasum，Linux 用 sha256sum，Solaris 用 digest
shasum -a 256 "${OUT_DIR}/${LIB_NAME}" 2>/dev/null \
    || sha256sum "${OUT_DIR}/${LIB_NAME}" 2>/dev/null \
    || digest -a sha256 "${OUT_DIR}/${LIB_NAME}" 2>/dev/null \
    || echo "(本平台没有可用的 sha256 命令)"
echo "openssl: ${OPENSSL_VERSION}"

if [ "$COMMIT" = "1" ]; then
    # 显式要求更新已提交产物
    mkdir -p src/main/resources
    cp "${OUT_DIR}/${LIB_NAME}" "src/main/resources/${LIB_NAME}"
    echo "committed: src/main/resources/${LIB_NAME}"
    echo "下一步: mvn -pl edap-native package 让 jar 包含新 .o"
else
    echo "OK: ${OUT_DIR}/${LIB_NAME}"
    echo "  （没有动 src/main/resources/ —— 要更新已提交产物请加 --commit）"
    echo "下一步: mvn -pl edap-native package -DskipTests"
    echo "然后 mvn -pl edap-crypto test -DskipTests=false -Dtest='io.edap.crypto.**'"
fi
