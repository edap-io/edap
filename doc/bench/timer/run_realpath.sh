#!/bin/sh
# 真实路径基准的容器内运行脚本：服务端 + 客户端双进程
# 用法: run_realpath.sh <连接数> <selector 数> <轮数> [客户端堆]
N=$1
IO=$2
ROUNDS=$3
CH=${4:-2g}
SOCK=/tmp/edap-bench-$N.sock
CP="/opt/bench/classes:/opt/bench/lib/*"
OPENS="--add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"

rm -f "$SOCK" /tmp/server.log /tmp/client.log

/opt/jdk/bin/java $OPENS -Dfile.encoding=UTF-8 -Xmx12g -Xms1g -cp "$CP" \
    io.edap.nio.bench.RealPathServer "$SOCK" "$N" "$IO" "$ROUNDS" > /tmp/server.log 2>&1 &
SPID=$!
sleep 2
/opt/jdk/bin/java $OPENS -Dfile.encoding=UTF-8 -Xmx$CH -Xms512m -cp "$CP" \
    -Dbench.holdMs=1800000 io.edap.nio.bench.RealPathClient "$SOCK" "$N" > /tmp/client.log 2>&1 &
CPID=$!

# 等服务端跑完
wait $SPID
kill $CPID 2>/dev/null
echo "===== SERVER ====="
cat /tmp/server.log
echo "===== CLIENT ====="
cat /tmp/client.log
echo "===== END ====="
