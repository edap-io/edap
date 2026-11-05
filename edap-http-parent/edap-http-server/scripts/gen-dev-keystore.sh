#!/usr/bin/env bash
#
# 生成开发/测试用的自签证书 keystore（PKCS12）。
#
#   ./scripts/gen-dev-keystore.sh [输出路径] [口令] [别名]
#
# 默认输出 edap-http-server/src/test/resources/edap-test-keystore.p12，
# 口令 edap-test，别名 edap-test —— 与 TlsEndToEndTest 里的配置一致。
#
# ★ 生产环境不要用这个脚本：证书是自签的、有效期 100 年、口令是公开的。
#   生产请用正规 CA 签发的证书，或者至少把口令换成从环境变量注入的。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$DIR/../src/test/resources/edap-test-keystore.p12}"
PASS="${2:-edap-test}"
ALIAS="${3:-edap-test}"

if ! command -v keytool >/dev/null 2>&1; then
    echo "找不到 keytool —— 请确认 JDK 已安装且 JAVA_HOME 指向 JDK 17" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"

if [ -f "$OUT" ]; then
    echo "keystore 已存在，先删除：$OUT" >&2
    rm -f "$OUT"
fi

keytool -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 36500 \
    -storetype PKCS12 \
    -keystore "$OUT" \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -dname "CN=localhost, OU=edap, O=edap, L=Shanghai, ST=Shanghai, C=CN" \
    -ext "SAN=dns:localhost,ip:127.0.0.1"

echo "keystore 已生成：$OUT"
echo "  别名：$ALIAS   口令：$PASS"
echo
echo "服务端配置（系统属性）："
echo "  -Djavax.net.ssl.keyStore=$OUT"
echo "  -Djavax.net.ssl.keyStorePassword=$PASS"
echo "  -Djavax.net.ssl.keyStoreType=PKCS12"
echo "  -Djavax.net.ssl.key.alias=$ALIAS"
