# edap RPC 上下文（RpcContext）详细设计

> 本文档定义 gRPC 与 eRPC 共用的 RPC 上下文：身份、deadline、取消信号、属性、线程绑定，以及两端的超时传播方式。
>
> 另有**连接级**上下文 `RpcChannelContext`（§4.4）：权限校验这类「连接不断就不重复校验」的事用它 ——
> **目前只有 eRPC 在用**，gRPC 确定不接入。
>
> 另有**请求级、可上线**的元数据 `RpcMetadata`（§4.5）：分布式事务的 txId 之类要跨进程传播的东西走它 ——
> 走 eRPC 的帧内容区独立一段（§6.5），**不污染业务参数**。
>
> **目标读者**：在 `edap-rpc-api` / `edap-grpc-parent` / `edap-erpc-parent` / `edap-container-parent` 下做 RPC 调用的开发者。
>
> **一句话目标**：业务代码不再需要知道自己走的是 gRPC 还是 eRPC —— 两边都能用同一行 `RpcContext.current()` 拿到「这次调用是谁、还剩多久、要不要停」。

---

## 一、目标与范围

### 1.1 改造前的问题

| | gRPC | eRPC |
|---|---|---|
| per-call 对象 | 有（`GrpcCall` / `GrpcCallImpl`） | **没有**（只有 `ErpcRequest` / `ErpcResponse` 两个可变 POJO） |
| deadline | **没有**；`GrpcStatus.DEADLINE_EXCEEDED(4)` 定义了但全项目无人产出 | **没有**；`RpcRequest.timeoutMillis` 注释写明「不上线」 |
| 取消 | **没有** | **没有** |
| 线上超时 | 无 `grpc-timeout` 头 | 帧头 `flags` 的 bit0 已被「压缩预留」占用，只剩 7 bit |
| 线程绑定 | **没有**；业务 handler 直接跑在 HTTP/2 IO 线程上 | 业务跑在 worker 线程池上，但**没有**上下文绑定 |

于是：业务无法判断「我这个调用还剩多久」「上游是不是已经放弃了」「我是哪个租户的」。

### 1.2 本轮交付

1. 新模块 `edap-rpc-api`（package `io.edap.rpc`，零三方依赖）—— 上下文与超时编解码的公共层；
2. gRPC / eRPC 两端的**接线**：建上下文、传 deadline、绑定线程、触发取消；
3. 容器侧：ASM 生成的 handler **不需要改**（生成的字节码在框架的 attach 作用域内执行）。

### 1.3 不在本轮范围内

- gRPC 服务端业务 handler 迁出 IO 线程（本轮只加上下文，不改线程模型）；
- 拦截器链 / 名字解析 / 负载均衡；
- eRPC 线上 CANCEL 帧（v1 帧格式不变 ⇒ 只做**本地取消**）；
- ★ **属性（`RpcContext.put(Key, Object)`）跨进程传播** —— 属性是**进程内**的（不上线）；
  要跨进程传播的走 `RpcMetadata`（§4.5），它是**另一套东西**，语义不同。

---

## 二、总体结构

```
                       ┌───────────────────────────────┐
                       │      edap-rpc-api             │
                       │  io.edap.rpc                  │
                       │   ├─ RpcContext               │  上下文 + 线程绑定
                       │   ├─ RpcChannelContext        │  连接级上下文
                       │   ├─ RpcMetadata              │  请求级、可上线的元数据
                       │   ├─ CancellationToken        │  取消信号
                       │   ├─ RpcProtocol (GRPC|ERPC)  │  协议标识
                       │   ├─ RpcTimeout               │  超时编解码
                       │   └─ RpcDeadlineExceededException │
                       └───────────┬───────────────────┘
                          ▲        │        ▲
            ┌─────────────┘        │        └──────────────┐
            │                      │                       │
   ┌────────┴─────────┐   ┌────────┴────────┐   ┌──────────┴──────────┐
   │  edap-grpc-*     │   │  edap-erpc-*    │   │  edap-container-*   │
   │                  │   │                 │   │  （ASM 生成的 handler│
   │ GrpcChannel      │   │ ErpcChannel     │   │    跑在框架的 attach │
   │ GrpcClientCall   │   │ PendingCall     │   │    作用域内）        │
   │ GrpcCallImpl     │   │ ErpcServer      │   │                     │
   └──────────────────┘   │ ErpcServerDecoder│  └─────────────────────┘
                          └─────────────────┘
```

### 2.1 依赖方向

`edap-rpc-api` **不依赖任何三方库**，也不依赖 edap 的其它模块（连 `edap-common` 都不依赖）。
`edap-grpc-api` / `edap-grpc-client` / `edap-grpc-server` / `edap-erpc-api` / `edap-erpc-client` / `edap-erpc-server` 依赖它。

> `io.edap.rpc` 包名与 `edap-protobuf-wire` 里的 `io.edap.rpc.StreamObserver` 同包，但两者**互不依赖**（无环）；本仓库无 `module-info`（classpath 模式），拆包不构成 JPMS 冲突。

---

## 三、核心不变式

### 3.1 线上只传「相对时长」，本地只存「绝对 nanoTime 时刻」

`System.nanoTime()` **跨进程不可比** —— 跨机传绝对时刻没有意义；
`System.currentTimeMillis()` 会被 NTP 调整跳变，不能当 deadline 的基准。

因此两端的分工是固定的：

| | 线上 | 本地 |
|---|---|---|
| gRPC | `grpc-timeout: 2S`（还剩多久） | `deadlineNanos = System.nanoTime() + 2e9` |
| eRPC | 帧头 `flags` 的 bit1~bit7（还剩多久的 7-bit 码） | `deadlineNanos = System.nanoTime() + 解码出的纳秒` |

服务端一收到请求就换算成**自己进程的**绝对时刻 ⇒ 两端的 deadline **不受时钟偏移影响**。

### 3.2 解码值永远不短于编码前的请求值（向上取整）

如果服务端拿到的剩余时间比客户端请求的**短**，服务端就会掐掉一个「客户端仍认为有效」的调用。
所以两条编码路径都保证 `decode(encode(x)) >= x`：

- gRPC：优先选**最大的、且整除的单位**（无损）；实在不行才向上取整；
- eRPC：二分取「最小的、`decode(code) >= nanos` 的码值」。

这条不变式由 `RpcTimeoutTest` 用 10 万次随机抽样守住。

### 3.3 调用正常结束 ≠ 被取消

`CancellationToken` 有两个终态：

- `cancel(cause)` —— **被取消**（客户端取消 / 等待超时 / deadline 到期 / 对端断开）；
- `complete()` —— **正常结束**（之后再 `cancel()` 不生效）。

> ★ 为什么必须分开：业务在收尾阶段（比如已经算好了结果、正在写响应）不该误判「被取消」。
> 如果结束时用 `cancel()` 收尾，业务读到 `isCancelled() == true` 会把一次成功调用当成失败。

---

## 四、`edap-rpc-api`

### 4.1 `RpcContext` —— 一次调用的上下文

```java
RpcContext ctx = RpcContext.current();          // 当前线程绑定的上下文
long left = ctx.remainingMillis();               // 还剩多久（无 deadline ⇒ Long.MAX_VALUE）
if (ctx.cancellation().isCancelled()) { ... }    // 协作式取消
String tenant = ctx.get(TENANT);                // 强类型属性
```

| 分组 | API |
|---|---|
| 身份 | `protocol()` / `method()` / `requestId()` / `traceId()` |
| deadline | `deadlineNanos()` / `remainingNanos()` / `remainingMillis()` / `isExpired()` |
| 取消 | `cancellation()` |
| 属性 | `get(Key<T>)` / `put(Key<T>, T)` / `remove(Key<T>)` / `attributeKeys()` |
| deadline 定时器 | `onDeadline(Runnable)` / `clearDeadline()` |
| 线程绑定 | `current()` / `currentOrNull()` / `attach(ctx)` / `detach()` / `wrap(Runnable)` / `wrap(Callable)` |

**属性用 `Key<T>` 而不是 `Map<String, Object>`**：编译期类型安全、key 拼错在编译期暴露。
`Key` 是 **identity 语义**（与 grpc-java 的 `Context.Key` 一致）—— 用法是把 key 定义成 `static final` 常量。

```java
public static final RpcContext.Key<String> TENANT = RpcContext.Key.create("tenant");
```

**未绑定时** `current()` 返回一个**共享的只读 ROOT 上下文**（不抛异常），往它写属性会抛 `IllegalStateException`。
`currentOrNull()` 返回 `null`。ROOT 的 `protocol()` 是 `null` —— 业务可以据此判断「不在 RPC 上下文里」。

### 4.2 线程绑定：为什么不用 `InheritableThreadLocal`

NIO 事件循环 + 线程池场景下，父线程的上下文透传到子线程会**污染独立请求的语义**（子线程可能处理的是另一个请求）。

沿用 edap 项目 `edap-microservice-context` 的既有规约：

- 用普通 `ThreadLocal`；
- `Scope.close()` 用 `ThreadLocal.remove()` 还原，而不是 `set(null)`（避免 Entry 里残留 null value）。

**跨线程必须显式传播**：

```java
executor.submit(RpcContext.current().wrap(() -> doWork()));
```

> ★ `wrap()` 在**包装时**捕获上下文、在**执行时**绑定 ⇒ 必须在**持有上下文的线程上**调用包装。
> 在子线程里包装会捕获到空上下文。

### 4.3 `RpcTimeout` —— 超时编解码

#### gRPC：`grpc-timeout: <最多 8 位数字><单位>`

单位**大小写敏感**（与 grpc-java 一致）：`H` 时 / `M` 分 / `S` 秒 / `m` 毫秒 / `u` 微秒 / `n` 纳秒。

编码规则（顺序即优先级）：

1. **最大的、且整除的单位** —— 无损且人类友好（30 秒 → `"30S"`）。
   ★ 不能只看「数字塞得进 8 位」：那样 1_000_500 ns 会被编成 `"1H"`（超调 360 万倍）；
2. 纳秒单位 —— 总是整除，只在数字不超过 8 位时用；
3. **兜底** —— 最小的、向上取整后不超过 8 位的单位（超调 < 1 个该单位）。

解析：最多 8 位十进制 + 恰好 1 个单位字符；超 8 位 / 缺单位 / 多余字符 ⇒ 非法（`-1`）；`digits × unit` 溢出 ⇒ `Long.MAX_VALUE`。

#### eRPC：帧头 `flags` 的 bit1~bit7

```
flags 字节：  bit7 bit6 bit5 bit4 bit3 bit2 bit1 bit0
             ├──────────── 超时代码（7 bit） ────┤ └ 压缩预留（恒 0）
```

- **bit0 是「压缩预留」，任何路径都不许写**；
- 写：`flags = (code << 1) & 0xFE`；读：`code = (flags >>> 1) & 0x7F` ⇒ 与 bit0 完全隔离。

刻度是 **1/4 八度二进制**，基准 0.25 ms：

```java
code → 纳秒：q = code - 1;  e = q / 4;  m = q % 4;
             nanos = ((4 + m) << e) * 250_000
```

刻度序列：1、1.25、1.5、1.75、2、2.25、2.5、2.75、3、4、5 … 毫秒 ⇒ **最大相对超调 25%**。
范围：1 ms ~ ≈ 21.7 天。

> ★ **码值 ≠ flags 字节**：`encodeErpcTimeoutCode()` 返回的是「码值」，
> 写帧头要用 `RpcTimeout.encodeErpcFlags()`（内部做左移）。直接拿码值当 flags 写 ⇒ 码值错位一位。
> （本轮联调时踩过这个坑：码值 45 直接写进 flags，实际落在 bit0 上，把「压缩预留」位写成了 1。）

#### 通用

```java
long deadlineFromNow(long millis);        // → nanoTime 绝对时刻
long deadlineFromNowNanos(long nanos);    // → nanoTime 绝对时刻（溢出当「无 deadline」）
```

> ★ **溢出当「没有 deadline」**：`nanoTime + 时长` 溢出成负数 ⇒ 那个调用会被当成「一收到就过期」。
> 而溢出的语义其实是「无穷久」（比如 gRPC 头 `99999999H` ≈ 1100 万年）⇒ 按「无 deadline」处理。

### 4.4 `RpcChannelContext` —— 连接级上下文

`RpcContext` 是**一次调用**的；`RpcChannelContext` 是**一条连接**的。

| | `RpcContext` | `RpcChannelContext` |
|---|---|---|
| 生命周期 | 一次调用 | 一条连接（连接断了就没了） |
| 装什么 | 身份 / deadline / 取消信号 | 连接级状态：已鉴权标记、租户、协商结果…… |
| 怎么拿到 | `RpcContext.current()` | 拦截器钩子的参数 / 连接对象上的 getter |

```java
RpcChannelContext ch = ...;                 // 这条连接
ch.put(TENANT, "t-42");                    // 任意属性（键用 RpcContext.Key，identity 语义）
ch.markAuthenticated();                     // 内置的「已鉴权」标记
ch.isAuthenticated();                       // true ⇒ 同一连接的后续请求不用再校验
```

**★ 核心用法：权限校验只做一次**

```java
public class AuthInterceptor implements ErpcServerInterceptor {
    @Override
    public void beforeCall(ErpcRequest req, RpcChannelContext channel) throws Exception {
        if (!channel.isAuthenticated()) {
            verify(req);                    // 校验一次（抛异常 ⇒ 拒绝本次调用）
            channel.markAuthenticated();    // 记在连接上
        }
        // 同一连接的后续请求直接放行
    }
}
```

**★ 安全不变式：新连接 = 新上下文**

上下文是**连接对象自己持有的实例**（不是连接池持有的）⇒ 连接断了、池重建了连接 ⇒
拿到的是**新实例** ⇒ 标记不会跨连接泄漏 ⇒ 新连接上的第一个请求**一定**会重新校验。

> ★ 这条不变式靠「上下文挂在连接上」保证，**不靠应用自觉** ——
> 池重建连接时不会去「搬运」旧上下文的属性（那才是危险的）。
> `RpcChannelContextTest#flagDoesNotLeakAcrossContexts` 守住「两个上下文互不影响」。

**★ 线程安全**：同一条连接上的并发请求共享同一个实例 ⇒ 属性袋是 `ConcurrentHashMap`，
鉴权标记是 `volatile boolean`。

**★ 内置的 `authenticated` 标记是进程级的一个布尔**：容器里 gRPC / eRPC Server 是进程级共享的，
多个应用跑在同一条连接上时这个标记对它们是同一个。需要按应用区分 ⇒ 各自用自己的
`RpcContext.Key` 存标记，别用这个。

**★ 现状**：**只有 eRPC 用它**（见 §6.4）。**gRPC 确定不接入** ——
gRPC 服务端的「连接」对象在 `edap-http-server` 的 HTTP/2 层（`Http2Connection`），
不在 gRPC 模块内 ⇒ 要接入得先动 HTTP/2 那一层，收益不足 ⇒ 不做。
本类本身**与协议无关**（零依赖、不引用任何 eRPC 类型）⇒ 仍放在共用的 `edap-rpc-api` 里，
不挪进 eRPC 模块。

### 4.5 `RpcMetadata` —— 请求级、**可上线**的元数据

`RpcContext` 的属性表（`put(Key, Object)`）是**进程内**的：值不会随请求发给对端。
要跨进程传播的东西（分布式事务的 **txId**、灰度标签、链路标签……）走**另一套**：
`RpcMetadata` —— 一组「**字符串** 键 → **字符串** 值」。

| | 属性（`RpcContext.put`） | 元数据（`RpcMetadata`） |
|---|---|---|
| 会不会上线 | **不会**（纯进程内） | **会**（随请求帧发给对端） |
| 键 | `RpcContext.Key`（identity 语义、强类型） | `String`（小写 ASCII，线上可见） |
| 值 | 任意 `Object` | 字符串 |
| 典型用途 | 把「已经算好的东西」递给下游代码 | **跨进程传播**（txId…） |

**★ 为什么不灌进属性表**：属性表的键是 `RpcContext.Key`（identity 语义，每个 JVM 一个）、
值是任意 `Object` —— 而「上线」要求的是**稳定的、可被别的进程读懂的**键值对。
两者语义不同，各管各的。

**★ 键的约束**：小写 ASCII 字母开头，其余为 `[a-z0-9-_.]`（与 gRPC 的 metadata 规矩一致）——
大小写不敏感的对端才不会读错。违反约束时 `put` **fail-fast 抛异常**，不静默丢。

**★ 怎么用**：

```java
// 客户端：调用时带上
RpcMetadata meta = RpcMetadata.create().put("txid", "0f9a-42");
client.invoke(methodId, req, RespType.class, timeoutMillis, meta);

// 客户端拦截器：自动传播（下游调用前注入）
@Override public void beforeCall(ErpcRequest request) {
    request.getContext().metadata().put("txid", currentTxId());
}

// 服务端 handler：读
String txId = RpcContext.current().metadata().get("txid");
```

**★ 懒创建**：`RpcContext.metadata()` 在上下文是 ROOT（没有绑定）时返回**共享的只读空实例**
（写它抛 `IllegalStateException`）⇒ 没绑定上下文时误写会立刻暴露，而不是静默丢数据。
上下文非 ROOT 时第一次调 `metadata()` 才建一个 `ConcurrentHashMap` ⇒
不给每次调用多分配一个 map。`RpcContext.hasMetadata()` **不触发懒创建** ——
客户端出站写帧时用它判断「要不要多写一段」。

**★ 线程安全**：内部是 `ConcurrentHashMap`。典型用法是「客户端拦截器在请求发出前写、
传输层读」，两边不在一个线程上。

**★ 现状**：**只有 eRPC 上了线**（§6.5）；gRPC 侧暂不接入（gRPC 本来就有自己的 metadata 通道，
但语义与 edap 的 `RpcMetadata` 不同，硬接会误导）。本类**与协议无关** ⇒ 仍放在共用的
`edap-rpc-api` 里。

---

## 五、gRPC 侧接线

### 5.1 客户端

```
GrpcChannel.newCall(path, reqType, respType, observer, headers, timeoutMillis)
   ├─ timeoutMillis > 0 ⇒ 请求头加 grpc-timeout（RpcTimeout.encodeGrpcTimeout）
   ├─ 建 RpcContext（protocol=GRPC / method=path / requestId=streamId / deadline）
   └─ new GrpcClientCall(..., context)
```

`GrpcClientCall` 持有本次调用的 `RpcContext`：

| 时机 | 动作 |
|---|---|
| `sendMessage()` / `halfClose()` | 请求侧由调用方线程驱动 ⇒ `attach(context)` 后再写 socket |
| `onData()` 的观察者回调 | 响应侧由读线程驱动 ⇒ 回调外包 `attach(context)` |
| `cancel(cause)` | **先** `cancellation().cancel(cause)`（业务立刻看到「被取消」），**再**发 RST_STREAM |
| `complete(status)` | `cancellation().complete()`（正常结束不是取消） |
| `awaitCompletion(timeout)` | 等待上限 = `min(调用方给的, 本次调用自己的 deadline)`；超时 ⇒ `cancel()` 后抛 `GrpcException` |

### 5.2 服务端

`GrpcCallImpl` 在**构造器**里按请求头建上下文：

```java
long timeoutNanos = RpcTimeout.parseGrpcTimeoutNanos(metadata.get("grpc-timeout"));
this.context = RpcContext.builder()
        .protocol(RpcProtocol.GRPC)
        .method(method.getPath())
        .requestId(stream.getStreamId())
        .traceId(traceIdOf(metadata))          // 取 x-edap-trace-id，缺省 0
        .deadlineNanos(RpcTimeout.deadlineFromNowNanos(timeoutNanos))
        .build();
```

`invokeHandler()` 是上下文与 deadline 的总闸：

```java
void invokeHandler() {
    synchronized (requestLock) { if (handlerInvoked) return; handlerInvoked = true; }
    // 客户端给的 deadline 已经过了 ⇒ 业务根本不该跑
    if (context.isExpired()) {
        context.cancellation().cancel(new RpcDeadlineExceededException("deadline exceeded"));
        close(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
        return;
    }
    // deadline 到期 ⇒ 定时器线程回 DEADLINE_EXCEEDED 的 trailers
    context.onDeadline(() -> {
        context.cancellation().cancel(new RpcDeadlineExceededException("deadline exceeded"));
        close(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
    });
    try (RpcContext.Scope scope = RpcContext.attach(context)) {
        method.getHandler().handle(this);
    } catch (Throwable t) {
        close(GrpcStatus.UNKNOWN, messageOf(t));
    }
}
```

★ **客户端流式 / 双向流式的后续消息投递也必须 attach**：`deliverToObserver()` 由 IO 线程在
`handle()` **返回之后**才被调用，那时上下文已经解绑 ⇒ 不绑的话业务观察者里
`RpcContext.current()` 拿不到本次调用的上下文。

`close()` 末尾做两件事：撤掉 deadline 定时器（否则长连接服务会攒下一堆僵尸任务）+ `cancellation().complete()`。

### 5.3 `GrpcCall.context()` 用 default 方法

```java
public interface GrpcCall {
    /**
     * 本次调用的 RPC 上下文（deadline / 取消信号 / 属性）。
     * <p>默认返回当前线程绑定的上下文。
     */
    default RpcContext context() {
        return RpcContext.current();
    }
}
```

用 **default** 而不是抽象方法 ⇒ 仓库内的 `GrpcCallImpl` 与测试里的 `FakeCall` 都不受影响，
外部自定义实现也**源码与二进制双兼容**。

---

## 六、eRPC 侧接线

### 6.1 线格式：只动 flags 的 bit1~bit7

eRPC 的帧头是 32 字节定长布局，`flags` 只占 1 字节。本轮**只借用它的 bit1~bit7**：

| 类 | 现状 | 本轮 |
|---|---|---|
| `ErpcFrameHeader.readFrom(byte[]/FastBuf)` | 已解析 flags | 不改 |
| `ErpcFrameCodec.header()` | 调 readFrom | 不改 |
| `ErpcServerDecoder.decode()` | **flags 没复制进 `ErpcRequest`** | 复制 + 建 `RpcContext` |
| `ErpcFrameEncoder.encode(...)` | `setFlags(0)` | 新增带 `int flags` 的重载；旧签名保留并委派 `flags=0` |
| `ErpcOutboundWriter.writeFrame(...)` | `setFlags(0)`（服务端响应） | 不改（响应不回带 deadline） |
| `ErpcChannel.writeFrame(...)` | **从不调用 `setFlags`**（复用 headerBuffer，靠「一直是 0」侥幸正确） | **必须显式 `setFlags(flags)`** |

★ **帧长 / 字段偏移 / 字节序全不变** ⇒ 线上格式无破坏。
旧服务端读新请求帧 ⇒ 忽略高位 ⇒ 退化为「无超时」，不报错；旧客户端 `flags=0` ⇒ 新服务端解出 `code=0` ⇒ 无超时。

### 6.2 服务端

`ErpcServerDecoder` 在解码帧头时就建上下文（覆盖所有路径：正常 / 未知 methodId / 未知 codec / 线程池满）：

```java
request.setFlags(header.getFlags());
long nanos = RpcTimeout.decodeErpcTimeoutNanos(RpcTimeout.fromErpcFlags(header.getFlags()));
request.setContext(RpcContext.builder()
        .protocol(RpcProtocol.ERPC)
        .method("erpc:" + header.getMethodId())
        .requestId(header.getRequestId())
        .traceId(header.getTraceId())
        .deadlineNanos(RpcTimeout.deadlineFromNowNanos(nanos))
        .build());
```

`ErpcServer.invoke()`（**worker 线程**）里 attach/detach + 到期检查：

```java
private void invoke(ErpcServerSession session, ErpcRequest req, ErpcMethod method) {
    RpcContext ctx = req.getContext();
    if (ctx != null && ctx.isExpired()) {
        session.writeError(req, ErpcStatus.DEADLINE_EXCEEDED, "deadline exceeded", null);
        return;
    }
    AtomicBoolean responded = new AtomicBoolean();
    if (ctx != null) {
        ctx.onDeadline(() -> {
            if (responded.compareAndSet(false, true)) {
                session.writeError(req, ErpcStatus.DEADLINE_EXCEEDED, "deadline exceeded", null);
            }
        });
    }
    try (RpcContext.Scope scope = RpcContext.attach(ctx)) {
        method.getHandler().handle(req, resp);
    } catch (Throwable t) { ... }
    if (responded.compareAndSet(false, true)) {
        session.writeResponse(req, resp);
    }
    if (ctx != null) {
        ctx.clearDeadline();
        ctx.cancellation().complete();
    }
}
```

- `dispatch()`（IO 线程）**不 attach** —— 只路由 + 投递，没有业务代码跑；
- `inlineDispatch` 诊断开关（IO 线程直调）**自动覆盖** —— attach 放在 `invoke()` 内部，两条路径都会跑到。

### 6.3 客户端

- `ErpcClient.invoke(methodId, req, respType, timeoutMillis)` 建客户端上下文（traceId 已有），
  挂到进程内的 `RpcRequest`（`timeoutMillis` / `requestObject` 同性质，**不上线**）；
- `ErpcNodePool.invoke(...)` → `ErpcChannel.call(...)`：**保持现有 public 签名不变**（源码兼容），新增带 `RpcContext` 的重载；
- `ErpcChannel.call0(...)` 的**两条写路径都要写 flags**：
  - 对象路径：`ErpcFrameEncoder.encode(codec, type, codecId, methodId, serviceId, requestId, traceId, flags, message, sink)`；
  - 字节路径：`ErpcChannel.writeFrame(...)` 里 `headerBuffer.setFlags(flags)`（**顺带修掉「从不 setFlags」的陈旧位隐患**）；
  - `ping()` 走 `writeFrame` ⇒ flags=0 ✓；
- **本地取消**（本轮新增）：`ErpcChannel.cancel(requestId, cause)` ⇒ `calls.remove` + `future.completeExceptionally` + `ctx.cancellation().cancel(cause)`。
  ★ **线上没有 CANCEL 帧**（eRPC v1 帧格式不变）⇒ 服务端不会立刻停。

### 6.4 连接级上下文（channel context）

两端都是「**连接对象自己持有**一个 `RpcChannelContext`」：

| 端 | 持有者 | getter |
|---|---|---|
| 服务端 | `ErpcServerSession`（一条连接一个 session） | `session.channelContext()` |
| 客户端 | `ErpcChannel`（一条连接一个 channel） | `channel.channelContext()` |

**服务端接线**：`ErpcServer.invoke()` 把 session 的上下文传给拦截器钩子 ——

```java
RpcChannelContext channel = session.channelContext();
runBeforeInterceptors(req, channel);      // beforeCall(req, channel)
...
runAfterInterceptors(req, resp, error, channel);   // afterCall(req, resp, error, channel)
```

`ErpcServerInterceptor` 的两档钩子（带 `channel` 的那一档）是 **default 方法** ⇒
只实现老钩子（`beforeCall(req)`）的拦截器**照旧工作**，源码与二进制双兼容。

**客户端接线**：`ErpcNodePool.invoke()` 在**选中连接之后**才填上下文 ——

```java
ErpcChannel channel = next();
request.setChannelContext(channel.channelContext());   // ★ 选连接之后才有
```

★ **`beforeCall` 阶段拿不到它**（`request.getChannelContext()` 是 `null`）：
那个钩子跑在「选节点 / 选连接」**之前**，那时还不知道走哪条连接。
`afterCall` 阶段能拿到（`request.getChannelContext()`）。

**★ 连接重建 ⇒ 新上下文**：连接断了 ⇒ 池重建一条连接 ⇒ 新 `ErpcChannel` ⇒ 新上下文 ⇒
旧连接上的标记不沿用 ⇒ 新连接的第一个请求重新校验。

### 6.5 请求级元数据通道：帧头 `type` 的 bit7

**★ 为什么不加进 `version`**：`version` 是「协议版本」，不该兼职当「有没有上下文」的标记；
`type` 才是帧种类的字段，取值少、变动少。**帧头 32 字节一个字节都没改**
（`version` 恒为 1）。

```
type 字节：  bit7        bit0 ~ bit6
            ───────     ─────────────
            带上下文?   帧种类（1 ~ 6）
```

**帧内容区的两种形态**：

```
type = REQUEST(1)      → [32B 帧头][业务 DTO]                  ← 与改造前字节级一致
type = REQUEST|0x80    → [32B 帧头][RPC 上下文段][业务 DTO]
type = AUTH(6)         → [32B 帧头][凭据(UTF-8)]
```

**上下文段的格式**（自描述、大端、**不依赖业务序列化器**）：

```
metadataLen(4B)   ← 上下文块体的字节数（不含本 4 字节）
entryCount(4B)
[ keyLen(2B) key(UTF-8) valLen(4B) val(UTF-8) ]*
```

★ **为什么不复用业务序列化器**：上下文是**框架级**的东西（txId、灰度标签），
业务 DTO 用什么编码器跟它无关 —— 独立固定格式 ⇒ 任何 codec 下都能解析，也方便别的语言实现。

★ **长度越界 ⇒ 协议错误**（关连接），不静默截断。解码时用**减法比较**
（`valLen > end - p`）而不是 `p + valLen > end` —— 线上的长度字段是攻击面，
加法可能溢出成负数 ⇒ 越界检查被绕过。

★ **只有元数据非空才用带上下文的帧** ⇒ 默认路径（绝大多数调用）**线上字节完全不变**、
零额外开销。判定用 `RpcContext.hasMetadata()`（**不触发懒创建**）。

**服务端接线**：`ErpcServerDecoder` 在累积缓冲上**就地**解析上下文段，
业务载荷只 `Arrays.copyOfRange` 拷**一次** ⇒ 拷贝次数与不带元数据时持平。
剥下来的元数据灌进 `req.getContext().metadata()`。

**客户端接线**：`ErpcChannel.call0()` 里 `ctx.hasMetadata()` 为真 ⇒ 走
`writeFrameWithContext(...)`（锁外编码两段、锁内一次性写）。
★ **不走 `ErpcFrameEncoder`** —— 它的 `FrameSink` 接口在 benchmark 模块里也有实现
⇒ 改签名会波及那边；元数据走**独立的写路径**。

### 6.6 连接级鉴权握手：AUTH 帧

**★ 与 §6.5 是两个互相独立的功能**：

| | 连接级（Channel 级） | 请求级（Call 级） |
|---|---|---|
| 生命周期 | 一条连接 | 一次调用 |
| 机制 | **AUTH 帧**（连接级、一次） | **元数据通道**（每请求） |
| 落点 | `RpcChannelContext` | `RpcContext.metadata()` |
| 装什么 | 已鉴权标记、认证主体 | txId、灰度标签…… |

凭据走 AUTH 帧（连接级、一次），txId 走元数据（请求级、每请求）—— **两条路互不相干**。

**服务端**：

- `dispatch()` 里 `kind == AUTH` ⇒ **投 worker 线程池**（校验可能要查库/验签，
  **绝不能挡住 IO 线程**）；线程池满 ⇒ 回 `ERROR(RESOURCE_EXHAUSTED)`；
- `handleAuth()`：取配好的 `ErpcAuthenticator` ⇒ 校验 AUTH 帧载荷（UTF-8 字符串）⇒
  - **通过** ⇒ `channel.markAuthenticated()` + 记认证主体
    （`RpcChannelContext.PRINCIPAL`）⇒ 回一个**空载荷的 RESPONSE 帧**（requestId 原样回填）；
  - **失败** ⇒ 回 `ERROR(UNAUTHENTICATED)` + **关连接**（连都过不去，这条连接留着也没用）；
  - **服务端没配鉴权器 ⇒ 视为通过**（服务端处于「不校验」模式，
    不该把坚持握手的客户端拒掉）；
- `requireAuth` 开关：`setRequireAuth(true)` ⇒ 未鉴权的连接发请求 ⇒
  回 `ERROR(UNAUTHENTICATED)`（在 `dispatch()` 的**共用路径**上 ⇒ 带上下文与不带上下文的
  请求类型都受管）。

**客户端**：

- `ErpcClientOptions.credential(String)` 配凭据；
- `ErpcChannel` 构造函数：**读线程起来之后**才发 AUTH 帧（否则应答没人收）；
  用传输层分配的 `requestId` ⇒ 应答走**现有的 RESPONSE/ERROR 配对路径**
  （**不需要新的响应帧类型**）；超时用 `connectTimeoutMillis`；
- **握手失败 ⇒ 关连接 + 抛异常** ⇒ `ErpcNodePool.connect()` 抛出 ⇒ **槽位保持空**
  ⇒ 不会把坏连接留在池里，下次重试。

**容器装配**：`AppContext` 启动时把应用 bean 里的 `ErpcAuthenticator` 装到
**进程级共享**的 eRPC Server 上；停止时摘掉。★ **槽位是一个，不是「装一堆」**
⇒ 多个 app 各带一个时**先装的生效**、后装的**不覆盖**（覆盖会让先启动的 app
突然失去鉴权策略）。

---

## 七、容器层

### 7.1 ASM 生成的 handler 不需要改

已复核 `GrpcHandlerGenerator` / `ErpcHandlerGenerator` 生成的字节码：

- 生成的 `handle(GrpcCall)` / `handle(ErpcRequest, ErpcResponse)` 方法体在**框架的 attach 作用域内**执行
  ⇒ 业务方法被调用时 `RpcContext.current()` 一定非空；
- 手写 handler（用户直接 `implements GrpcHandler` / `implements ErpcHandler`）走**同一段框架代码**
  （`GrpcCallImpl.invokeHandler` / `ErpcServer.invoke`）⇒ 同样被覆盖。

容器侧只需要一个**护栏测试**：业务方法在容器生成的 handler 里跑，仍然能看到本次调用的上下文。
（`GrpcContainerContextEndToEndTest` / `ErpcContainerEndToEndTest`）

### 7.2 业务自起线程必须显式传播

```java
public ReviewReply listReviews(ReviewRequest req) {
    RpcContext ctx = RpcContext.current();          // 或 req.getContext()
    if (ctx.remainingMillis() <= 0) {
        throw new RpcDeadlineExceededException("业务超时");
    }
    String tenant = ctx.get(TENANT);
    if (ctx.cancellation().isCancelled()) {          // 协作式取消
        throw new RpcDeadlineExceededException("调用被取消");
    }
    return doListReviews(req);
}
```

业务自起线程时**必须** `RpcContext.current().wrap(r)`，否则子线程里 `current()` 返回 ROOT。

---

## 八、已知代价与边界

### 8.1 ★ gRPC 的 deadline trailers 会被业务阻塞推迟

gRPC 服务端的业务 handler 跑在 **HTTP/2 IO 线程**上（`GrpcProtocolHandler.onBody` ← `Http2Connection.processFrames` ← `decode`）。
`Http2Connection.writeFrame` 是 `synchronized`（写安全 ✓），但它只把帧**排进累积缓冲** ——
真正的 socket 写发生在 IO 线程 decode 结束后调 `nioSession.flush(bbPool)`。

⇒ **业务 handler 阻塞时，deadline 的 trailers 要等 IO 线程空闲才发出去。**

这是「本轮不挪出 IO 线程」决策的已知代价。eRPC 的 handler 跑在 worker 线程上，
它的 deadline 错误帧能及时发出去。

### 8.2 其它边界

| 项 | 说明 |
|---|---|
| 属性不跨进程 | `RpcContext.put(Key, Object)` 仍是**进程内**的；跨进程的走 `RpcMetadata`（§4.5），**目前只有 eRPC 上了线**（§6.5），gRPC 侧不接入 |
| 元数据的值只能是字符串 | `RpcMetadata` 的值一律 UTF-8 字符串（与 gRPC 的 metadata 规矩一致）；要传二进制得另设计 |
| 跨机时钟偏移 | 只传时长 ⇒ 两端各自的 deadline 不受偏移影响；不需要校正 |
| eRPC 无服务端取消 | 客户端取消只取消本地 future；服务端仍在跑（线上没有 CANCEL 帧） |
| `traceId` | gRPC 侧只读 `x-edap-trace-id` 头（edap 约定，非 gRPC 规范）；缺省 0 |
| 连接级上下文 | **只有 eRPC 在用**（gRPC 确定不接入：它的连接对象在 `edap-http-server` 的 HTTP/2 层，不值得为它动那一层）；内置的 `authenticated` 标记是**进程级**的（多应用共享一条连接时对它们是同一个） |
| 鉴权器是**一个槽位** | eRPC Server 是**进程级共享**的 ⇒ 多个 app 各带一个 `ErpcAuthenticator` 时**先装的生效**、后装的不覆盖 |
| 性能 | 每次调用多 1 个 `RpcContext` + 1 个 `CancellationToken`（属性为空时用不可变空 map，懒初始化）；ThreadLocal 读写各 1 次（纳秒级）；**带 deadline 的调用会往调度器队列排 1 个任务**（~µs），无 deadline 的调用不排队；**不带元数据的调用零额外开销**（线上帧字节级不变） |

---

## 九、测试

| 模块 | 测试类 | 断言点 |
|---|---|---|
| `edap-rpc-api` | `RpcTimeoutTest` | ① `grpc-timeout` 往返；② 单位大小写敏感；③ 非法值 ⇒ `-1`；④ **向上取整** `parse(encode(x)) >= x`；⑤ eRPC 7-bit 往返 + `decode(encode(x)) >= x`（10 万次随机抽样）；⑥ 旧帧（flags=0）解出 code=0；⑦ **bit0 恒 0** |
| `edap-rpc-api` | `RpcContextTest` | ① attach/detach 后 `current()` 变化、`currentOrNull()` 为 null；② **线程复用不泄漏**；③ `wrap(Runnable)` 跨线程可见；④ `Key<T>` 类型隔离；⑤ `CancellationToken`：首次 cancel 生效、重复无效、`complete()` 后 cancel 无效 |
| `edap-rpc-api` | `RpcChannelContextTest` | ① 属性往返（写 null = 移除）；② key 是 **identity 语义**；③ null key 被拒；④ 移除不存在的键不报错；⑤ **已鉴权标记**默认 false、标记后 true；⑥ **两个上下文的标记互不影响**（= 新连接会重新校验）；⑦ 并发写不丢更新 |
| `edap-grpc-server` | `GrpcContextEndToEndTest` | ① 客户端 deadline ⇒ 服务端 handler 里 `remainingMillis()` 落在预期区间；② `protocol()` / `method()` / `traceId` 正确；③ 没有 `grpc-timeout` ⇒ 服务端无 deadline；④ **已过期 ⇒ `grpc-status=4` 且业务未被调用**；⑤ deadline 在业务运行期间到期 ⇒ 状态码 4；⑥ **业务自起线程看不到上下文**（必须显式 `wrap`）；⑦ 调用结束后 IO 线程上 `currentOrNull()==null`（不泄漏到下一个请求） |
| `edap-grpc-client` | `GrpcClientDeadlineTest` | ① 客户端确实发出 `grpc-timeout` 头（值是 `"2S"`）；② 超时为 0 ⇒ 不带头；③ `cancel()` 后取消信号被 trip；④ 等待超时 trip 取消信号；⑤ 等待上限 = min(调用方给的, 本次调用的 deadline) |
| `edap-erpc-api` | `ErpcFrameFormatTest` | ① flags 高位不影响既有帧；② 旧帧（flags=0）与新帧都能被 `readFrom` 正确解析 |
| `edap-erpc-server` | `ErpcContextEndToEndTest` | ① 客户端写 flags ⇒ 服务端 handler 读到 deadline；② 身份字段（协议 / 方法 / traceId）传播；③ **旧客户端（flags=0）⇒ 服务端无 deadline**；④ deadline 在业务运行期间到期 ⇒ ERROR 帧 status=4；⑤ **已过期 ⇒ 业务根本不被调用**；⑥ handler 运行时上下文已绑定；⑦ 请求之间上下文不泄漏；⑧ **复用的 worker 线程每次都看到各自的上下文**；⑨ 未绑定的线程看不到上下文 |
| `edap-erpc-server` | `ErpcServerInterceptorTest` | ① **同一连接上的后续请求不重复校验**（`authCheckHappensOncePerConnection`）；② **不同连接各自校验一次**（`channelContextIsNotSharedAcrossConnections`）；③ **只实现老钩子的拦截器照旧工作**（二进制兼容） |
| `edap-erpc-client` | `ErpcClientChannelContextTest` | ① 同一条连接的两次调用拿到**同一个**上下文；② **连接重建 ⇒ 新上下文**（标记不沿用）；③ **`beforeCall` 拿不到**（还没选连接）、`afterCall` 拿得到 |
| `edap-container` | `GrpcContainerContextEndToEndTest` | ① **容器生成的 handler 里业务能看到上下文**；② 无 `grpc-timeout` ⇒ 服务端无 deadline；③ 正常结束 ⇒ 取消信号是 `complete` 不是 `cancel` |
| `edap-container` | `ErpcContainerEndToEndTest` | ① 容器生成的 eRPC handler 里业务能看到上下文（协议 = ERPC、方法 = `erpc:<methodId>`、剩余时间落在 (0, 2500]） |
| `edap-rpc-api` | `RpcMetadataTest` | ① put/get/remove/keys 往返；② **值 null = 移除**；③ **键非法 ⇒ fail-fast**；④ 只读实例写 ⇒ 抛异常；⑤ 空实例共享且不可变 |
| `edap-erpc-api` | `ErpcMetadataCodecTest` | ① 空元数据 / 中文 / 特殊字符 / 长 value 往返；② **长度越界 ⇒ 协议错误**（不是静默截断）；③ 整数溢出不能绕过越界检查 |
| `edap-erpc-api` | `ErpcFrameTypeTest` | ① bit7 置位/清位的掩码行为；② 新帧类型取值不与既有冲突（AUTH=6）；③ `of()` 自动掩标志位 |
| `edap-erpc-server` | `ErpcMetadataAuthTest` | ① **上下文段被剥下来**放进 `RpcContext.metadata()`，**业务 DTO 的字节不受影响**；② **普通请求帧行为零变化**（元数据为空）；③ 上下文段长度越界 ⇒ 协议错误（关连接）；④ AUTH 帧通过 ⇒ 那条连接 `isAuthenticated()` 为 true；⑤ **同一连接的后续请求免校验**；⑥ 握手失败 ⇒ ERROR(UNAUTHENTICATED) + 关连接；⑦ 没配鉴权器 ⇒ 握手视为通过；⑧ `requireAuth` ⇒ 未鉴权连接发请求被拒；⑨ **标记不跨连接泄漏** |
| `edap-erpc-client` | `ErpcClientMetadataAuthTest` | ① 带元数据调用 ⇒ 服务端 handler 拿到 metadata；② **不带元数据 ⇒ 线上帧 type 仍是 REQUEST**（字节级兼容）；③ 带元数据 ⇒ type 的 bit7 置位；④ 空元数据视同没有；⑤ 客户端拦截器注入的元数据能上线；⑥ 握手成功；⑦ 凭据错 ⇒ 调用失败；⑧ **坏连接不留池**；⑨ 一条连接只握一次手；⑩ 服务端不校验时放行 |
| `edap-container` | `ErpcAuthenticatorBeanTest` | ① 应用启动 ⇒ 鉴权器 bean 装到进程级共享的 eRPC Server 上；② 停止 ⇒ 摘掉；③ **槽位被别的 app 占了 ⇒ 本 app 的不覆盖**；④ 本 app stop 时不误伤别的 app 的鉴权器 |

### 跑测试

根 pom 的 `skipTests` 默认是 true ⇒ 必须显式 `-DskipTests=false`；`JAVA_HOME` 要指到 JDK 17：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$JAVA_HOME/bin:$PATH
cd /Volumes/Data/NetBeansProjects/edap

mvn -pl edap-rpc-api -am -DskipTests=false test
mvn -pl edap-erpc-parent/edap-erpc-api,edap-erpc-parent/edap-erpc-client,edap-erpc-parent/edap-erpc-server -am -DskipTests=false test
mvn -pl edap-grpc-parent/edap-grpc-api,edap-grpc-parent/edap-grpc-client,edap-grpc-parent/edap-grpc-server -am -DskipTests=false test
mvn -pl edap-container-parent/edap-container -DskipTests=false test
```

> ★ **DTO 简单名在同一个包下不能重名**：protobuf 的解码器类名是「包名 + 简单名 + Decoder」，
> 同包下两个同名 DTO 共用同一个解码器 ⇒ 解码出别的 DTO 的实例（`ClassCastException`）。
> 新增测试 DTO 前先搜简单名。
