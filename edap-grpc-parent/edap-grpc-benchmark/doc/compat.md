# edap gRPC ↔ google grpc-java —— 跨框架兼容性矩阵

> 目的：回答「edap 的 gRPC 实现和 grpc-java **说的是不是同一种语言**」。
>
> 性能对比在 [`bench/`](bench/)；本文只管**对不对** —— 通不通、通得对不对、
> 边界情况会不会翻车。

---

## 一、被测对象

同一份 `src/main/resources/proto/bench.proto` 喂给**两个生成器**，得到两套 Java 代码：

| | 代码生成器 | 消息类 | 服务端 stub |
|---|---|---|---|
| edap 侧 | `edap-protoc-maven-plugin`（自研） | `io.edap.grpc.benchmark.Payload` | `io.edap.grpc.benchmark.BenchService` |
| google 侧 | `protoc` + `protoc-gen-grpc-java` 1.85.1 | `io.edap.grpc.benchmark.Bench.Payload` | `io.edap.grpc.benchmark.BenchServiceGrpc` |

> google 系依赖（grpc-java / protobuf-java）**只存在于基准模块内**，
> 不进入任何 edap 运行时产物。

---

## 二、测试在哪

| 文件 | 定位 |
|---|---|
| `src/test/java/io/edap/grpc/benchmark/GrpcInteropTest.java` | **地基**：能不能互相调用、wire 字节是否一致 |
| `src/test/java/io/edap/grpc/benchmark/GrpcCompatTest.java` | **矩阵**：通得对不对 —— 负载尺寸 / 并发 / 流式形态 / 状态码 / 元数据 |

跑：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn -o -pl edap-grpc-parent/edap-grpc-benchmark -DskipTests=false \
    -Dtest='GrpcInteropTest,GrpcCompatTest' test
```

---

## 三、兼容性矩阵（11 个用例，双向）

`GrpcCompatTest` 的每个用例都是**双向**跑一遍：edap 客户端打 google 服务端，
**以及** google 客户端打 edap 服务端 —— 只测一个方向证明不了「两种实现说的是同一种语言」。

| # | 用例 | 覆盖点 | edap 客户端 → google 服务端 | google 客户端 → edap 服务端 |
|---|---|---|---|---|
| 1 | `edapClientCallsGoogleServerAtEverySize` | 负载尺寸扫描（0 / 1 / 127 / 1KB / 16KB / **65535** / **65536** / 512KB） | ✅ | ✅ |
| 2 | `googleClientCallsEdapServerAtEverySize` | 同上，反方向 | ✅ | ✅ |
| 3 | `requestWireBytesAreIdentical` | **请求的 wire 字节逐字节相同** | ✅（同一份消息两边编出来的字节一样） | |
| 4 | `concurrentStreamsDoNotCrossTalkAgainstGoogleServer` | **32 路并发多路复用**不串流 | ✅ | ✅ |
| 5 | `concurrentStreamsDoNotCrossTalkAgainstEdapServer` | 同上，反方向 | | ✅ |
| 6 | `serverStreamWorksBothDirections` | **服务端流式**（1 请求 → 3 响应） | ✅ | ✅ |
| 7 | `clientStreamWorksBothDirections` | **客户端流式**（3 请求 → 1 响应，求和语义） | ✅ | ✅ |
| 8 | `bidiWorksBothDirections` | **双向流式**（3 请求 → 3 响应） | ✅ | ✅ |
| 9 | `unknownMethodReturnsUnimplemented` | 未知方法必须回 `UNIMPLEMENTED`（gRPC 规范，不是 HTTP 404） | ✅ | ✅ |
| 10 | `grpcStatusArrivesInTrailers` | `grpc-status` 必须落在 **trailers** 里 | ✅ | |
| 11 | `metadataRoundTripsBothDirections` | 自定义元数据从请求头原样回到响应头 | ✅ | ✅ |

**载荷尺寸为什么挑这几个**：65535 是老连接级窗口的上限，65536 刚好越界，
512KB 用来验证窗口扩容后的流控是否正确。

---

## 四、结论

### ④.1 wire 格式是兼容的

- **非空负载下，两边编出来的请求字节逐字节相同**（用例 3 断言）——
  这是「两个框架说的是同一种语言」最直接的证据；
- 负载尺寸覆盖 0B ~ 512KB，包括流控窗口的边界值，双向全部通过；
- 32 路并发多路复用下响应不串流（每条流的内容各不相同，任何流 id 分配错误都会被抓出来）。

### ④.2 唯一已知的表示差异：空 bytes 字段

| | 空 blob 的 wire 字节 | 解码后的表示 |
|---|---|---|
| edap 编码器 | 写出字段（`1a 00`，共 25 字节） | `getBlob()` → **`null`**（用 null 表示「字段不存在」） |
| protobuf-java | 按 proto3 默认值规则**省略**该字段（共 23 字节） | `getBlob()` → **空 `ByteString`** |

⇒ **语义完全相同**（blob 都是空），不影响互操作，但**字节不同**。
这是两个实现的**表示法差异**，不是协议不兼容。

`GrpcCompatTest` 里对 size=0 的 blob 断言因此放宽为「null 或空数组都算一致」
（`assertBlobEquals`），并在 `GrpcInteropTest.emptyBlobIsSemanticallyEqualOnBothSides`
里单独把这个差异钉死。

### ④.3 三种流式形态完全互通

服务端流式 / 客户端流式 / 双向流式，双向全部通过，语义逐条对齐
（客户端流式两边都是「求和 + 回显最后一条请求的 name / blob」）。

> ★ 注意 **grpc-java 的阻塞 stub 只支持一元与服务端流式**，
> 客户端流式与双向流式必须走**异步 stub**（`ClientResponseObserver.beforeStart`
> 才能拿到请求观察者）。这是 grpc-java 的 API 限制，不是 edap 的兼容性问题。

### ④.4 状态码与元数据符合 gRPC 规范

- 未知方法 → `UNIMPLEMENTED`（不是 HTTP 404）；
- `grpc-status` 落在 **trailers** 里（这是 gRPC 与普通 HTTP 的分界线）；
- 自定义元数据（`x-bench-echo`）从请求头原样回到响应头，双向都通。

---

## 五、踩过的坑（实现 gRPC 服务端时才会遇到）

1. **grpc-java 的服务端拦截器里不能提前 `call.sendHeaders(...)`** ——
   `ServerCalls` 在 `onNext` 时还会自动发一次头，直接撞
   `IllegalStateException: sendHeaders has already been called`。
   正确做法是包一层 `ForwardingServerCall`，把要加的头**合并**进框架真正发的那一次
   （`GoogleBenchServer` 里就是这么写的）。
2. **grpc-java 1.85 的 `Metadata.Key` 没有 `get()` 方法** ——
   服务端读请求元数据要在拦截器里读 `headers`。
3. **`MetadataUtils.newCaptureMetadataInterceptor` 的签名**是
   `(AtomicReference<Metadata>, AtomicReference<Metadata>)` —— 头一个、尾一个。
4. **edap 的解码器用 `null` 表示「字段不存在」**，protobuf-java 用空 `ByteString` ——
   见 §④.2。

---

## 六、复现

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap
mvn -o -pl edap-grpc-parent/edap-grpc-benchmark -DskipTests=false \
    -Dtest='GrpcInteropTest,GrpcCompatTest' test
```
