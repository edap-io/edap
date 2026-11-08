#!/bin/sh
# 半开连接回收的端到端验证。
#
# 场景：客户端在独立 network namespace 里，通过 veth 连服务端。
# 1) 建连后把客户端的 veth 置 down —— 客户端的网卡没了，FIN/RST 发不出去
# 2) 再 SIGKILL 客户端
# 3) 服务端这边就是「ESTABLISHED 但永远收不到任何数据」的半开连接
#    （老版本 edap 只有 EOF / 读异常两条关闭路径，这种连接永久泄漏 fd）
# 4) NioSessionIdleReaper 应当按 idleTimeout 把它收掉
#
# 用法: run_halfopen.sh <连接数> <idleTimeoutMs> [观察秒数]
#       idleTimeoutMs 传一个很大的值 = 对照组（不配回收器）
N=$1
IDLE=$2
OBS=${3:-30}
PORT=19099
CP="/opt/bench/classes:/opt/bench/lib/*"
OPENS="--add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"

cleanup() {
    ip netns del cli 2>/dev/null
    ip link del veth0 2>/dev/null
    kill $CPID $SPID 2>/dev/null
}
trap cleanup EXIT

pkill -9 -f HalfOpen 2>/dev/null
sleep 1

# --- 建 netns + veth ---
ip netns del cli 2>/dev/null
ip link del veth0 2>/dev/null
ip netns add cli
ip link add veth0 type veth peer name veth1
ip link set veth1 netns cli
ip addr add 10.99.0.1/24 dev veth0
ip link set veth0 up
ip netns exec cli ip addr add 10.99.0.2/24 dev veth1
ip netns exec cli ip link set veth1 up
ip netns exec cli ip link set lo up

rm -f /tmp/hopen_server.log /tmp/hopen_client.log

/opt/jdk/bin/java $OPENS -Dfile.encoding=UTF-8 -Xmx2g -cp "$CP" \
    io.edap.nio.bench.HalfOpenServer 10.99.0.1 $PORT $N $IDLE > /tmp/hopen_server.log 2>&1 &
SPID=$!
sleep 3

ip netns exec cli /opt/jdk/bin/java $OPENS -Dfile.encoding=UTF-8 -Xmx1g -cp "$CP" \
    -Dbench.holdMs=600000 io.edap.nio.bench.HalfOpenClient 10.99.0.1 $PORT $N > /tmp/hopen_client.log 2>&1 &
CPID=$!

# 等连上
i=0
while [ $i -lt 60 ]; do
    grep -q CONNECTED /tmp/hopen_client.log 2>/dev/null && break
    sleep 1
    i=$((i+1))
done
sleep 1

echo "--- 连接建立后服务端 socket 状态（应为 ESTAB）："
echo "  计数: $(ss -tan state established 2>/dev/null | grep -c ":$PORT")"
ss -tan state established 2>/dev/null | grep ":$PORT" | head -2

# --- 拔网线：客户端网卡 down，FIN/RST 发不出去 ---
ip netns exec cli ip link set veth1 down
echo "--- 客户端网卡已 down，现在 SIGKILL 客户端"
kill -9 $CPID
sleep 2

echo "--- 杀掉客户端 + 网卡 down 后，服务端 socket 状态（应仍是 ESTAB = 半开）："
echo "  计数: $(ss -tan state established 2>/dev/null | grep -c ":$PORT")"

# --- 观察窗口：每秒打一次服务端还挂着多少条 ESTAB ---
i=0
while [ $i -lt $OBS ]; do
    if grep -q ALL_CLOSED /tmp/hopen_server.log 2>/dev/null; then
        break
    fi
    i=$((i+1))
    sleep 1
done
echo "--- 观察 ${i}s 后的服务端 socket 计数: $(ss -tan state established 2>/dev/null | grep -c ":$PORT")"

wait $SPID
echo "===== SERVER ====="
cat /tmp/hopen_server.log
echo "===== CLIENT ====="
cat /tmp/hopen_client.log
echo "===== END ====="
