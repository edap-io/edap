#!/bin/bash
# 用部署版 EAR 里的真实 WechatPayNotifyCrypto 校验 wx_notify.py 产物的签名/密文格式。
#
# 前提：
#   1. EAR 已解压，com/estylr/** 的 class 在 EAR_ALL 指向的目录下
#   2. edap-common jar 存在（io.edap.util.StringUtil）
#
# 用法: ./run-self-test.sh [EAR_ALL 目录]
set -e
export JAVA_HOME=${JDK17_HOME:-/opt/homebrew/opt/openjdk@17}
HERE="$(cd "$(dirname "$0")" && pwd)"
EAR_ALL="${1:-/private/tmp/earreal/all}"
EDAP_COMMON=$(ls "$HOME"/.m2/repository/io/edap/edap-common/0.1.3-SNAPSHOT/edap-common-0.1.3-SNAPSHOT.jar 2>/dev/null | head -1)
CP="$EAR_ALL:$EDAP_COMMON:$HERE"
cd "$HERE"

python3 wx_notify.py --emit-java --timestamp 1762300800 --nonce mocknonce123456 > pieces.txt

"$JAVA_HOME/bin/javac" -cp "$CP" -d . CryptoSelfTest.java
"$JAVA_HOME/bin/java" \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  -cp "$CP" CryptoSelfTest pieces.txt
