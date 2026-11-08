# 定时器 / 空闲回收的高并发基准

`doc/TIMER_DESIGN.md` §9.5 的实测代码。**不在任何模块的构建里**（`doc/` 下不参与 Maven 编译），
需要手工编译运行。

## 为什么单独放这里

「扫描式 vs 每连接一个超时任务」「100 万连接能不能扛」这类问题在 macOS 上答不了 ——
`kern.maxfilesperproc = 245,760`，单进程最多约 12 万真实连接。
所以 §9.5 的数据是在 Linux 容器（Apple `container`，内核 6.12 aarch64）里跑的。

## 文件

| 文件 | 干什么 |
|---|---|
| `TraversalBench.java` | 裸遍历：每 key 占 1 个 fd（DatagramChannel），量「遍历 `selector.keys()` + `attachment()` + `isValid()`」的每 key 成本 |
| `RealPathServer.java` | **真实代码路径**：unix domain socket accept → `IoSelectorManager.registerNioSession` → `NioSessionIdleReaper.scan()`；分「全部活跃（纯扫描）」和「全部空闲（批量关闭）」两阶段 |
| `RealPathClient.java` | 真实路径基准的客户端，独立进程持有 N 条连接（把客户端那 N 个 fd 挪出服务端的 fd 额度） |
| `HalfOpenServer.java` / `HalfOpenClient.java` | 半开连接回收的端到端验证：走平台时间轮 `Timers.get()`，等回收器把静默连接关掉 |
| `ReArmBench.java` | **决定性对比**：扫描式（只更新一个 volatile long）vs 每连接一个超时任务（cancel + schedule）的每次 IO 成本 |
| `WheelVsHeapBench.java` | 100 万个定时任务：分层时间轮 vs `ScheduledThreadPoolExecutor` 的内存与操作成本 |
| `BenchUtil.java` | fd 数 / 堆 / 进程 CPU 时间的读数工具 |
| `run_realpath.sh` | 容器内跑真实路径基准（服务端 + 客户端双进程） |
| `run_halfopen.sh` | 容器内跑半开连接验证（netns + veth + 拔网线） |

## 跑法（Linux 容器内）

```sh
# 1) 容器
container system start
container create --name edap-bench -m 32G -c 8 --cap-add ALL \
    --ulimit nofile=1048576:1048576 debian:13-slim sh -c 'while true; do sleep 3600; done'
container start edap-bench

# 2) JDK 17（Debian 13 源里没有 openjdk-17；官方 GitHub 下载极慢，走 TUNA 镜像）
container exec edap-bench sh -c '
  apt-get -qq update && apt-get -qq install -y --no-install-recommends curl ca-certificates
  curl -sSL -o /tmp/jdk17.tar.gz \
    https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.21_9.tar.gz
  mkdir -p /opt/jdk && tar xzf /tmp/jdk17.tar.gz -C /opt/jdk --strip-components=1'

# 3) classpath：edap 的 jar 是宿主用 JDK 17 编的，字节码与平台无关，直接拷进去
#    （edap-nio-server + dependency:build-classpath 给出的那批 jar）

# 4) 编译
container exec edap-bench sh -c 'cd /opt/bench && javac -encoding UTF-8 -cp "lib/*" -d classes $(find src -name "*.java")'

# 5) 跑
container exec edap-bench sh -c 'cd /opt/bench && java -Xmx8g -cp "classes:lib/*" \
    io.edap.nio.bench.TraversalBench 1000000 20'
container exec edap-bench sh -c '/opt/bench/run_realpath.sh 1000000 8 3 2g'
container exec edap-bench sh -c '/opt/bench/run_halfopen.sh 500 5000 30'
```

## JVM 参数（缺了会炸）

```
--add-opens java.base/sun.nio.ch=ALL-UNNAMED   # FastSelectorProvider 反射 sun.nio.ch 的 selectedKeys
--add-opens java.base/java.nio=ALL-UNNAMED     # UnsafeUtil 直接反射 java.nio.Buffer.address
```

`javac` 还要 `-encoding UTF-8`（容器默认 US-ASCII）。

## 这个内核缺的东西

`netem` qdisc / `clsact` qdisc 都不可用，`iptables` 的 nf_tables 后端也用不了
⇒ **不能靠 tc/iptables 丢包来造半开连接**。
改用「拔网线」：客户端跑在 `ip netns` 里，veth 连服务端，建连后
`ip netns exec cli ip link set veth1 down` 再 `SIGKILL` 客户端 ——
客户端的 FIN/RST 因为没有路由发不出去，服务端保持 ESTAB，就是真半开。

## 结果（2026-11-07 实测，详见 `doc/TIMER_DESIGN.md` §9.5）

- 裸遍历：10 万 key **52.4 ns/key**；100 万 key **83.8 ns/key**（一轮 83.8 ms）
- 真实路径 `scan()`：100 万连接 **114~135 ns/key**（一轮 114~135 ms），常驻 **0.76% 单核**（15s 扫描周期）
- 批量关闭：**6.16 µs/连接**（关 100 万条要 6.2 秒）
- re-arm 成本：扫描式 **100 ns/IO** vs 每连接一个超时任务 **717 ns/IO**（**7.2 倍**）
- 100 万定时任务：轮子插入 294 ms / 128 MB / 取消 12 ns；`ScheduledThreadPoolExecutor` 插入 117 ms / 116 MB / 取消后队列仍占 100 万
- 半开连接回收：正向 `idleTimeout=5000ms` → 500 条全收（`elapsed_ms=5053`）；对照（≈不启用）→ 30 s 后仍是 500 条 ESTAB
