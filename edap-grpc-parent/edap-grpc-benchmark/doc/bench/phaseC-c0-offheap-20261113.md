# Phase C（C-0）收益实测 —— 「protobuf 编码器直写 HTTP/2 堆外缓冲」的值多少

生成时间: 2026-11-13 11:40 ~ 13:10 CST

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核（load 5~11）

探针源码: `src/main/java/io/edap/grpc/benchmark/OffHeapCopyBenchmark.java`

---

## 〇、一句话结论

**★ NO-GO —— Phase C 不做。**

不是「收益小所以先放着」，而是**「Phase C 在物理上不可能比现状快」**：
编码器是**逐字节产出**的，而把 64KB 从堆内搬进堆外唯一高效的手段就是**整块 bulk copy**
（`Unsafe.copyMemory`，实测 834ns）——**那正是现状**。
把编码器改成直接写堆外 ⇒ 变成逐字节 `Unsafe.putByte(long,byte)` ⇒ **慢 8.1 倍**（6749ns）。

---

## 一、要消除的是哪一次拷贝

帧头内联之后，edap 一条响应只剩 **1 次**整块拷贝：

```
protobuf 写入堆内 bs(byte[])  →  Http2FrameWriter.writeFrame:146  buf.write(payload,off,len)
                                  = UnsafeUtil.copyMemory(byte[], int, long, int)   ★这一次
```

grpc-java 是 0 次（netty ByteBuf 一次到位）⇒ 微观差 1.78x（64KB：edap 2375ns vs google 1338ns）。

Phase C 想做的是「编码器直接写堆外」⇒ 这一次拷贝消失。

---

## 二、微观实测（4 条路径，同一段载荷，隔离 JVM 取 min）

| 路径 | 做法 | 1KB | 16KB | 64KB | vs A |
|---|---|---|---|---|---|
| **A 现状** | `UnsafeUtil.copyMemory(byte[], int, long, int)` 一次整块搬 | **21** | **215** | **834** | 1.00x |
| **B 逐字节直写** | `UnsafeUtil.writeByte(long, byte)` 循环 | 104 | 1492 | 6749 | **8.1x 更差** |
| **C 8 字节批量直写** | 拼成 long 再 `putLong` | 411 | 6357 | 25156 | **30x 更差** |
| **D 分块 bulk copy** | 4KB 一块逐块搬（保留拷贝、只改落点） | 21 | 221 | 956 | 1.15x 更差 |

（单位 ns；0B 全为 0。）

### 2.1 怎么读这张表

| Phase C 的可能形态 | 对应路径 | 结果 |
|---|---|---|
| **① 编码器逐字节直接写堆外**（唯一能真正消掉拷贝的形态） | **B** | **慢 8.1 倍** ⇒ 净亏损 |
| ② 编码器写进堆内小块，再逐块搬到堆外（拷贝还在） | D | 慢 15% ⇒ **省 0** |
| ③ 编码器把 8 字节拼起来批量写堆外 | C | 慢 30 倍（而且拼装本身是 Java 级开销） |

⇒ **A（一次整块 bulk copy）就是搬运这件事的最优解，而它已经是现状。**
⇒ Phase C 唯一的「真正消掉拷贝」的实现（B）**比现状慢一个数量级**。

### 2.2 为什么「直写」在原理上吃亏

`AbstractWriter` 有 **72 处逐字节写**（`bs[pos++]`）。protobuf 编码器的产出是**一个字节一个字节**长出来的
（长度前缀、varint、嵌套消息的长度回填……），**没有「一次产出 8 字节」的天然边界**
⇒ 想批量写堆外就得先在堆内拼 ⇒ 那还是一次拷贝。

而 `Unsafe.putByte(long, byte)` 是**逐字节 native 调用**（无向量化、无 bounds check 消除）
⇒ 64KB = 65536 次 native 调用 ⇒ 实测 6749ns，而一次 `copyMemory` 只要 834ns（**8.1 倍**）。

---

## 三、端到端实测（多一次拷贝的边际成本）

「拷贝耗时 ÷ 往返耗时」只是**上界估计**（忽略了缓存效应、锁持有时间）。
更准的做法是**把这次拷贝多做一遍**，量端到端吞吐掉了多少 ——
去掉它就能赚回多少。

做法（**临时 hack，测完已删除**）：在 `Http2FrameWriter.writeFrame` 里，
载荷拷进累积缓冲之后再**多拷一遍**到一块没人读的暂存缓冲（`-Dedap.http2.extraCopy=true`）。

64KB @ 32 并发，4 组交替 A/B（每组 = 4 轮 × 4 臂拉丁方，取中位数）：

| 组 | base E→E | extra E→E | Δ | base G→E | extra G→E | Δ |
|---|---|---|---|---|---|---|
| 1 | 20,543 | 20,175 | −1.8% | 22,123 | 20,827 | −5.9% |
| 2 | 20,331 | 20,602 | +1.3% | 21,520 | 20,278 | −5.8% |
| 3 | 20,882 | 20,738 | −0.7% | 20,873 | 21,408 | +2.6% |
| 4 | 20,977 | 20,816 | −0.8% | 22,024 | 21,397 | −2.8% |
| **中位数** | **20,712** | **20,670** | **−0.2%** | **21,772** | **21,403** | **−1.7%** |

⇒ **一次 64KB 堆内→堆外拷贝的端到端边际成本：E→E 臂 −0.2%（落在噪声地板内）、G→E 臂 −1.7%。**
⇒ 逐对看：8 个配对里 6 个是负的，幅度 0.7% ~ 5.9%。
⇒ **收益上限 ≈ 1~2%，与 §四 的「微观 834ns ÷ 48.05µs = 1.7%」互相印证。**

### 3.1 ★ 为什么这么小：64KB@32 的瓶颈在**客户端**，不在服务端

同一批基准的 2×2 矩阵：

| 臂 | E→E | E→G | G→E | G→G | 换客户端 | 换服务端 |
|---|---|---|---|---|---|---|
| 64KB@32 | 20,712 | ~15,900 | 21,772 | ~16,150 | **1.05x** | **0.77x** |

- **换客户端**（G→E/E→E = 1.05x）：把 edap 客户端换成 grpc-java 的**快 5%**
  ⇒ **edap 的客户端（阻塞 socket + 单读线程）是这一档的瓶颈**。
- **换服务端**（E→G/E→E = 0.77x）：把 edap 服务端换成 grpc-java 的**慢 23%**
  ⇒ **edap 的服务端明显领先**。

⇒ 服务端省下来的那 834ns，有一部分被客户端的瓶颈吃掉了 ⇒ 端到端只看到 0.2%~1.7%。

> ⚠ 这条不构成「去优化客户端」的结论 ——
> README §23.3 已经记过：「把 edap 客户端改成 NIO + 多读线程」这条路线**不成立**。
> 这里只是解释**为什么服务端侧再省一次拷贝收益这么小**。

---

## 四、闸门判定

方案的 go/no-go 规则（写在计划里）：

> **若 C-0 的实测收益 < 2% 且改动面横跨 http-server/nio/protobuf 三个模块
> ⇒ 建议停在 C-1（出结论、不实施）。**

| 判据 | 实测 | 结论 |
|---|---|---|
| 端到端收益 | **0.2% ~ 1.7%**（上限 1.7%） | **< 2% ✗** |
| 改动面 | protobuf-api + grpc-api + http-server + nio **4 个模块** | **✗** |
| 方案里列的硬约束 | **10 条**（流控挂起、异常回滚、锁序、缓冲稳定性、模块依赖、TLS 下收益归零……） | **✗** |
| 实现是否可能更快 | **不可能**（见 §二：直写 = 慢 8.1 倍；分块 = 省 0） | **✗✗** |

⇒ **NO-GO。C-1 设计评审也不做了** —— 不是「设计起来麻烦所以不做」，
而是「设计出来也没有收益」。

---

## 五、那剩下的差距怎么办

真正能根治同一类问题（少一次整块搬运）的方向是
**「嵌套 / map / packed 的长度前缀 size 预计算」**：

- 现在：`pos += 1` 预留长度前缀 → 写内容 → `writeLenMoveBytes` 把整块内容**右移** 1~5 字节
  ⇒ 内容 ≥128 字节就整块搬一次 = **O(n) 额外拷贝**；
- protobuf-java：先算 size 再写长度，**零搬移**。

⇒ 根治要给 ASM 生成的编码器加 size 计算（protobuf-java 有 `getSerializedSize`，edap 没有）
⇒ **跨模块大改，但这是唯一有实质收益的方向** ⇒ 单独立项，不在本轮。

★ 顺带一提：**字符串侧已经在第二轮用「预留 varint」解决了同一类问题**
（`AbstractWriter.writeStringLenPrefix`）—— 中文编码从 1.32x 降到 1.02x。
嵌套 / map / packed 侧还没做 ⇒ **同一思路可以推广**，只是工程量大。

---

## 六、明确不做

- **不做 Phase C 的实施**（NO-GO）。
- **不做 Phase C 的设计评审**（收益已被证明不存在）。
- **不改 `Http2FrameWriter`**（临时 hack 已删除，`edap-http-server` 95 例全绿）。
- **不做 writev / 多段 iovec**（要给 JNI 加 writev，5 个平台 .o 都要重编，收益为负）。
- **不把累积缓冲从堆外改成堆内**（socket 写路径 `NioServerSession.writeInternal:358` → `write0(fd,pos,len)`
  是**从堆外绝对地址直接 write(2)**；改成堆内就得走 JNI 的 `GetPrimitiveArrayCritical`，收益归零、风险上升）。

---

## 七、验证与复现

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap/edap-grpc-parent/edap-grpc-benchmark
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
$JAVA_HOME/bin/java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
  -cp "target/classes:$(cat target/cp.txt)" io.edap.grpc.benchmark.OffHeapCopyBenchmark 400 5
```

输出：

```
载荷               A 现状copy        B 逐字节直写         C 8字节批量         D 分块直写        B/A
------------------------------------------------------------------------------------------
0B                      0              0              2              2       0.0x
1KB                    21            104            411             21       5.0x
16KB                  215           1492           6357            221       6.9x
64KB                  834           6749          25156            956       8.1x
```

**端到端 A/B**（需要临时 hack，见 §三；本轮已删除，改动不在树里）：

```bash
# base
$JAVA_HOME/bin/java $OPENS -Dedap.grpc.connWindow=8388608 -Dbench.google.connWindow=8388608 \
  -cp "target/classes:$(cat target/cp.txt)" io.edap.grpc.benchmark.BenchMain \
  --blob 65536 --conc 32 --rounds 4 --warmup 1000 --measure 3000 --out /tmp/pc-base.md

# extra（多一次拷贝）
$JAVA_HOME/bin/java $OPENS -Dedap.grpc.connWindow=8388608 -Dbench.google.connWindow=8388608 \
  -Dedap.http2.extraCopy=true \
  -cp "target/classes:$(cat target/cp.txt)" io.edap.grpc.benchmark.BenchMain \
  --blob 65536 --conc 32 --rounds 4 --warmup 1000 --measure 3000 --out /tmp/pc-extra.md
```

> ★ 跑基准期间**不能中途 `mvn install`**（基准 classpath 指向 `~/.m2` 的 jar）。
> ★ 端口没释放会导致下一次启动失败 —— 脚本里要 `sleep` + 重试。
> ★ **绝对值受机器负载影响**，只看同批次内的相对关系（本报告用的是交替 A/B + 4 组中位数）。
