# edap gRPC 一条响应的拷贝链 —— 「编解码器 micro 对比」的口径说明

生成时间: 2026-11-12 23:35 CST

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

探针源码: `src/main/java/io/edap/grpc/benchmark/CodecBreakdown.java`

---

## 〇、这份文档回答什么

《编解码器 micro 对比》（`after-20261112-0908.md` §三）里有一行：

| 负载 | edap 编码 ns | google 编码 ns | 编码比 |
|---|---|---|---|
| 16KB | 1024 | 737 | **1.39x** |
| 64KB | 2884 | 2011 | **1.43x** |

「> 1 表示 edap 更慢」—— 用户自己写的对比验证认为 edap 的编解码应当更快，
于是要「找到原因」。**结论：那两行的口径不对，两边量的不是同一件事。**

## 一、根因：一次多余的整块拷贝

《编解码器 micro 对比》的口径是：

| | edap | google |
|---|---|---|
| 编码 | `GrpcCodec.encode` = 写进 writer 缓冲 **+ 拷成新 byte[]** | `toByteArray()` = **直接写进最终数组，零中间数组** |
| 解码 | `GrpcCodec.decode` | `Bench.Payload.parseFrom` |

⇒ 编码这一列，**edap 多做了一次整块拷贝**，而 google 没有。

`CodecBreakdown` 探针把「编码」拆成两档（都是往一个已存在的缓冲里写一次）：

| 负载 | edap 写缓冲 | google 写缓冲 | edap byte[] | google byte[] | 多出来的那次拷贝 | `System.arraycopy` 参照 |
|---|---|---|---|---|---|---|
| 0B | 16 | 17 | 25 | 19 | 9 ns | 3 ns（23 字节） |
| 1KB | 28 | 35 | 61 | 56 | 33 ns | 16 ns（1050 字节） |
| 16KB | 362 | 380 | 1047 | 732 | **686 ns** | 208 ns（16411 字节） |
| 64KB | 1249 | 1279 | 2906 | 2073 | **1656 ns** | 1335 ns（65563 字节） |

**对账**：64KB 行 edap 慢 833ns（2884 vs 2011 的口径差），而「多出来的那次拷贝」是 **1656ns**，
一次 64KB `System.arraycopy` 实测 **1335ns** —— 量级吻合。
⇒ **「edap 编码慢 1.4x」是口径问题，不是编解码器能力问题**：
按对等口径（都写进已有缓冲），edap 的编码核心**比 protobuf-java 快**（0.94x~0.98x）。

> ★ 这套口径拆分已经进到主基准里：`CodecBenchmark` 现在**同时测两档编码口径**
> （「编码核心」= 主口径，两边都是 1 次拷贝；「编码(产出 byte[])」= edap 多一次整块拷贝，不对等），
> `BenchMain` §五 的表头与脚注都标了口径。重跑主基准即可拿到这两列，
> 不必再单独跑本探针。

## 二、真实链路：edap 一条响应被拷 4 次，grpc-java 只拷 1 次

```
edap  : payload →[1] writer 缓冲 →[2] byte[] →[3] gRPC 帧数组 →[4] HTTP/2 累积缓冲(堆外)
google: message →[1] 帧缓冲（netty ByteBuf，一次到位）
```

`CodecBreakdown` 的「真实链路」一档（帧 = 帧头 + 载荷）：

| 负载 | edap 现状 | edap 省掉一次拷贝（纸面） | google | 比值 |
|---|---|---|---|---|
| 0B | 24 ns | 15 ns | 17 ns | 1.45x |
| 1KB | 96 ns | 57 ns | 40 ns | 2.38x |
| 16KB | 1592 ns | 693 ns | 398 ns | **4.00x** |
| 64KB | 6157 ns | 2460 ns | 1376 ns | **4.47x** |

⇒ 64KB 真实链路 edap 6157ns vs google 1376ns = **4.47x**。
每条响应还要多分配两个 64KB 数组（64KB@32 并发 ⇒ ~2.4GB/s 垃圾）。

### ★★ 已实施（2026-11-13）：帧头内联，4 拷 → 2 拷

新增 `io.edap.grpc.GrpcFrameEncoder`（`edap-grpc-api`），把 5 字节帧头**内联进 protobuf 的编码缓冲**：

```
edap 改前: payload →[1] writer 缓冲 →[2] byte[] →[3] gRPC 帧数组 →[4] HTTP/2 累积缓冲(堆外)   （4 拷）
edap 改后: payload →[1] writer 缓冲（帧头内联） →[2] HTTP/2 累积缓冲(堆外)                （2 拷）
google   : message →[1] 帧缓冲（netty ByteBuf，一次到位）                                  （1 拷）
```

改后 `CodecBreakdown` 的「真实链路」一档（**同一批**实测，2026-11-13 00:48，loadavg ~8）：

| 负载 | edap 老路径 | edap 新路径（帧头内联） | google | 老路径比 | **新路径比** |
|---|---|---|---|---|---|
| 0B | 26 ns | 25 ns | 17 ns | 1.59x | 1.52x |
| 1KB | 96 ns | 54 ns | 35 ns | 2.78x | **1.55x** |
| 16KB | 1583 ns | 585 ns | 401 ns | 3.95x | **1.46x** |
| 64KB | 6143 ns | 2375 ns | 1338 ns | 4.59x | **1.78x** |

⇒ 64KB 真实链路 **6143ns → 2375ns（-61%）**，与 google 的差距从 **4.59x 收窄到 1.78x**。
每条响应少分配两个大数组（16KB/64KB 负载 ⇒ 32 并发时少 ~2.4GB/s 垃圾）。

**端到端也验证到了**（3 批中位数，见
[`matrix-summary-20261113-frameinline.md`](./matrix-summary-20261113-frameinline.md)）：

| 负载 | 并发 | edap 基线 | edap 新 | Δ | 与 grpc-java 的比值 |
|---|---|---|---|---|---|
| 64KB | 1 | 6,875 | 7,665 | **+11.5%** | 1.10x → **1.20x** |
| 64KB | 8 | 17,666 | 18,781 | **+6.3%** | 1.16x → **1.23x** |
| 64KB | 32 | 19,207 | 20,811 | **+8.4%** | 1.23x → **1.35x** |
| 16KB | 1 | 12,516 | 13,396 | +7.0% | 1.45x → 1.52x |
| 0B / 1KB | 全部 | — | — | ±2%（噪声内） | 无变化 |

2×2 矩阵显示**收益来自 edap 服务端**（G→E +11.7% @64KB/32，E→G +0.2%）
—— 一条 64KB 响应原来要拷 4 次，现在 2 次。
0B / 1KB 没有变化是对的：帧头才 5 字节，被省掉的整块拷贝本来就小。

> ★ 老路径这一列现在只是**对照基准**（生产路径已不再走它）——
> `GrpcFrame.encode(GrpcCodec.encode(...))` 这条链现在只留在测试里当「逐字节相等」的基准。

**改动的落点**：

| 文件 | 改动 |
|---|---|
| `edap-grpc-api/.../GrpcFrameEncoder.java` | **新增** —— 帧头内联编码（`FrameSink` 接口 + `encode(type, message, sink)`） |
| `edap-grpc-api/.../GrpcFrame.java` | 新增 `writeHeader(buf, offset, payloadLength)`（写 flag=0 + 4 字节大端长度） |
| `edap-grpc-server/.../GrpcCallImpl.java` | `sendOne` 改走 `GrpcFrameEncoder.encode(...)` → `response.chunk(buf, offset, length)` |
| `edap-grpc-client/.../GrpcClientCall.java` | `sendMessage` 改走 `GrpcFrameEncoder.encode(...)` → `channel.writeData(...)`，**且整段编码挪进 `synchronized(sendLock)`**（原来锁外编码、锁内写 ⇒ 消息序有竞争） |
| `edap-protobuf/.../ProtoBufWriter.java` | 新增 `byte[] buffer()`（拿到底层数组；`expand` 可能换数组，用完必须重取） |
| `edap-protobuf/.../writer/AbstractWriter.java` | 实现 `buffer()` |

> ⚠ **一处行为变化**：发送侧现在会调 `GrpcFrame.checkLength(payloadLen)`
> （上限 4MB，与 grpc-java / edap 解码侧的默认值一致）。
> 改前**发送侧从来没检查过长度**（`checkLength` 只在解码侧被调用），
> 发一条 >4MB 的消息改前能发出去（对端解码时才会拒），改后**在发送侧就 fail-fast**。
> 这是补上了一个漏掉的检查，不是功能回退。

### ★ 修法的可行性评审（实施前做的，结论全部成立）

针对「把帧头内联进编码缓冲（偏移 5 字节）」这个方案做过一轮源码级评审，三个关键问题的结论都是**安全**：

| 问题 | 结论 | 依据 |
|---|---|---|
| 偏移 5 字节后，写路径还依赖绝对位置吗？ | **安全**，写出字节逐字节相同 | 写路径全部相对 `pos`：`AbstractWriter.expand()` 扩容时 `arraycopy(bs,0,res,0,pos)` 会把已写前缀（含 [0,5)）整体搬过去；`writeLenMoveBytes()`(`AbstractWriter.java:780`) 的 memmove 以 `p` 为锚点；生成的顶层编码器只调 `writer.writeXxx(...)`，不碰 `pos`/`bs`。`ProtoBufOut.hasBuf()` 恒为 false ⇒ 那条 flush 分支不可达 |
| 编码缓冲的生命周期够长吗？ | **安全**，两条路径都当场拷贝 | 服务端 `GrpcCallImpl.sendOne` → `HttpResponse.chunk` → `Http2ResponseEncoder.writeBody` → `Http2Connection.writeBody`：正常路径 `Http2FrameWriter.writeFrame` 当场 `buf.write(payload,...)`；流控挂起路径 `Http2Stream.appendPendingBody` 会**拷一份**（`new byte[length]` + arraycopy）再排队。客户端 `GrpcChannel.writeData` → `appendFrameLocked` 同样当场拷贝，**没人持有调用方的数组** |
| `ProtoBuf.THREAD_WRITER` 是线程共享的，重入安全吗？ | **安全**，前提是「encode + copy」在**同一个方法里**完成 | `ProtoBuf.THREAD_WRITER` 与 `HttpResponse.write(Object)`（`application/x-protobuf`）共用；传输路径不回调用户代码也不做 protobuf 编码，客户端读线程的 `onNext` 在另一个线程、另一个 thread-local writer 上 |

**实施时必须满足的硬要求**（评审查出来的坑，逐条对照现有代码）：

1. 编码前 `writer.reset()` **和** `out.reset()` 都要调（否则残留旧字节）—— 同 `CodecBreakdown.encodeIntoBuffer`(`CodecBreakdown.java:144`)。
2. 发送路径要调 `GrpcFrame.checkLength(payloadLen)`：现在它**只在解码侧**被调用（`GrpcFrameDecoder.java:57`），发送侧从来没调过；还要拒掉 `payloadLen > 0x7FFFFFFF`（int 溢出会写坏 4 字节长度）。
3. 第 0 个字节必须显式写 0（compressed flag = identity），与 `GrpcFrame.encode`(`GrpcFrame.java:72`) 一致。
4. 客户端要把 encode 挪进 `synchronized (sendLock)` —— 现在 `GrpcClientCall.sendMessage` 在锁外编码(`:117`)、锁内写(`:123`)，本身就有个消息序的竞争；回调式编码必须整段在锁内。
5. 保留 `GrpcCodec` 的语义：`validate(type)`(`:123`) + 按**运行时类**取编码器 `getEncoder(message.getClass())`(`:86`) + null 消息抛 `GrpcException`。
6. `Sink.accept` 要声明 `throws IOException`（服务端 `response.chunk` 会抛）。
7. 编码后 **`bs` 和 `size()` 都要重新读**（`size()` 是绝对 `pos`；`expand` 可能换掉 `bs`）。
8. 用 `GrpcFrame.HEADER_LENGTH`，不要写字面量 5。
9. 必须加「与 `GrpcFrame.encode(GrpcCodec.encode(...))` 逐字节相等」的测试，且要覆盖：空消息 / 嵌套消息 >128 字节（触发多字节 `writeLenMoveBytes`）/ >16KB（触发 `expand` 重新分配）/ 字符串 >3072 字符（触发 `writeString0` 的 memmove）—— **后两个不覆盖，偏移 5 字节的 bug 会是静默的**。

### ★ 实施后的验证

| 验证项 | 结果 |
|---|---|
| 逐字节相等测试 `GrpcFrameEncoderTest`（15 例，覆盖上面第 9 条列的全部场景） | **全绿** |
| `edap-grpc-api` 全部测试 | **全绿**（44 例） |
| `edap-grpc-server` 端到端（4 种调用形态 + 错误 + 未实现） | **全绿**（11 例） |
| `edap-grpc-client` 端到端 + 流控 + 窗口协商 + RST_STREAM | **全绿**（15 例） |
| `edap-grpc-benchmark` 互操作（`GrpcInteropTest` 7 + `GrpcCompatTest` 11）与基准冒烟 | **全绿**（21 例） |
| `edap-protobuf` 全部测试 | **全绿**（1365 例） |

> 互操作那 18 个用例是**最硬的守门**：wire 字节有任何变化就会红。

> 附注：服务端会把帧切成 ≤16KB 的 DATA 帧（`Http2Connection.java:285`）⇒ FastBuf 那次拷贝是分片的，
> 但**总字节数不变**。

## 三、解码侧：口径是对的，数字也是对的

| 负载 | edap 解码 | edap(去掉 validate) | google 解码 | 比值 |
|---|---|---|---|---|
| 0B | 18 ns | 16 ns | 20 ns | 0.92x |
| 1KB | 46 ns | 50 ns | 53 ns | 0.87x |
| 16KB | 714 ns | 723 ns | 743 ns | 0.96x |
| 64KB | 1634 ns | 1563 ns | 1579 ns | 1.03x |

解码两边基本持平（0.87x~1.03x）。0B 那一档在《编解码器 micro 对比》里显示 1.44x/1.45x，
但那是 **36ns vs 25ns 的绝对差**（同一份代码两次跑出过 1.12x / 1.09x）⇒ **小基数放大，不是系统性差异**。

## 四、结论

1. 《编解码器 micro 对比》里「edap 编码慢 1.39x~1.43x」**是口径问题**（两把不对等的尺子），
   按对等口径 edap 的编码核心**比 protobuf-java 快**。
2. edap 真实链路的差距是**拷贝次数**（4 vs 1）⇒ **已实施帧头内联（4 → 2 拷）**：
   64KB 编码 6143ns → 2375ns，与 google 的差距从 **4.59x 收窄到 1.78x**。
   剩下的 1 次拷贝差距要靠「protobuf 编码器堆外化」才能抹平 —— 那是跨模块大改，本轮不做。
3. edap 的**编解码器本体**有没有可优化点 —— 见
   [`proto-codec-20261112.md`](./proto-codec-20261112.md)（纯 protobuf 负载矩阵 + 改前/改后）。

## 五、跑法

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap/edap-grpc-parent/edap-grpc-benchmark
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
$JAVA_HOME/bin/java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
  -cp "target/classes:$(cat target/cp.txt)" io.edap.grpc.benchmark.CodecBreakdown
```
