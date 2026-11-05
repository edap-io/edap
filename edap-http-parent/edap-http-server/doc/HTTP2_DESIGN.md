# edap HTTP/2 支持设计

> RFC 9113（HTTP/2）+ RFC 7540（HPACK）+ RFC 7541（HPACK 编码）+ RFC 9112（HTTP/1.1）
>
> 覆盖：h2c 明文（prior knowledge + Upgrade）、h2 over TLS（ALPN）、多路复用、流控、
> HPACK 静态/动态表与 Huffman、伪头映射、与 HTTP/1.1 的共存与 golden 比对。

---

## 一、总体形态

### 1.1 端口模型

| 端口 | 协议 | 进入方式 |
|---|---|---|
| 8080（明文） | HTTP/1.1 | 常规请求行 |
| 8080（明文） | h2c | ① prior knowledge（直接发 24 字节连接前言）；② Upgrade（`Upgrade: h2c`） |
| 8443（TLS） | h2 或 http/1.1 | ALPN 协商（`h2` / `http/1.1`） |

**不做同端口 mux**（不在一个端口上同时用 `PRI` 嗅探和 TLS 探测）—— 运维简单、行为可预测。

### 1.2 会话模型：单物理 session + 协议策略对象

edap **没有**为 HTTP/2 新建 listener，也没有给 session 加 `isHttp2` 布尔。
物理连接仍然是唯一的 `HttpServerNioSession`（持有 `socketChannel` / `writeBufs` /
`selectionKey`），协议差异全部收敛到一个策略接口：

```java
public interface HttpConnection {
    boolean decode(FastBuf buf) throws IOException;
    void flush(Pool<FastBuf> bbPool) throws IOException;
    default void onWriteBufDrained() { }
}
```

| 实现 | 位置 | 职责 |
|---|---|---|
| `Http1Connection` | `edap-http-server/io.edap.http.server` | 复用既有的 `RangeHttpRequestDecoder` |
| `Http2Connection` | `edap-http-server/io.edap.http.server.http2` | RFC 9113 全套 |

`HttpServerNioSession` 只做两件事（`HttpServerNioSession.java:158-166`、`:198-203`）：

```java
// decode
if (connection == null) {
    connection = createConnection(fastBuf);   // 嗅探 + 分派
    if (connection == null) return false;     // 数据不足，等下次 read
}
return connection.decode(fastBuf);

// flush
if (connection == null) { super.flush(bbPool); return; }
connection.flush(bbPool);
```

**收益**：`flush` / `writeBufs` / `OP_WRITE` / 直写 socket 这些机制零改动复用；
协议差异 100% 隔离在 `http2/` 包里；WebSocket 与 HTTP/2 天然正交；
`HttpConnection` 可以喂假 `FastBuf` 做单测，不需要真 socket。

### 1.3 模块布局

```
edap-http-core/
└── io/edap/http/
    ├── ResponseEncoder.java            响应编码策略接口
    ├── Http1ResponseEncoder.java       HTTP/1.1（字节级保持现状）
    ├── Http2ResponseEncoder.java       HTTP/2（:status + HEADERS 帧）
    └── http2/
        ├── Http2FrameType.java  Http2Flags.java  Http2ErrorCode.java
        ├── Http2FrameHeader.java  Http2FrameCodec.java  Http2Settings.java
        ├── Http2Exception.java
        └── hpack/
            ├── HpackStaticTable.java   61 项静态表
            ├── HpackDynamicTable.java  动态表（索引 62 = 最新）
            ├── HpackHeader.java  HpackDecoder.java  HpackEncoder.java
            └── HuffmanTable.java       257 个码长 + 规范构造 + Kraft 自检

edap-http-server/
├── io/edap/http/server/
│   ├── HttpConnection.java  Http1Connection.java
│   ├── HttpConnectionFactory.java    嗅探 + 分派
│   └── HttpProtocolSniffer.java      RFC 9113 §3.5
├── io/edap/http/server/http2/
│   ├── Http2Connection.java      帧循环 + 控制帧 + 多路复用
│   ├── Http2Stream.java          流状态 + 伪头映射 + stream 级 body
│   ├── Http2FlowController.java   连接级 + 流级窗口
│   ├── Http2FrameWriter.java      原子帧写出
│   └── Http2ResponseEncoder.java
└── io/edap/http/server/tls/
    ├── TlsConfig.java  TlsContext.java
    ├── TlsHttpServer.java
    └── TlsHttpServerNioSession.java   覆写 fastRead / writeToChannel
```

---

## 二、协议嗅探（RFC 9113 §3.5）

HTTP/2 连接前言是 24 字节固定串 `"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"`。
前 3 字节 `"PRI"` **不是任何已注册的 HTTP/1.1 method**（`HttpConsts` 里只有
GET/PUT/HEAD/POST/TRACE/DELETE/CONNECT/OPTIONS 八个）⇒ 只看 3 字节就能无歧义区分。

### 2.1 三条实现约束

1. **不能假设一次 read 拿全数据**。`DisruptorReadDispatcher` 每次 read 前都 `buf.reset()`，
   所以「数据不足」时必须把已读到的字节**拷出来暂存**（`HttpProtocolSniffer.carry`），
   否则连 `"PR"` 都会被下一次 read 冲掉。
2. **判定为 HTTP/1.1 时不能消费缓冲里的任何字节** —— 对 HTTP/1.1 路径字节级零行为变更。
   暂存的那 1~2 个字节由 `Http1Connection` **回放**给 HTTP/1.1 解码器
   （`HttpConnectionFactory.detect` 里 `sniffer.getCarry()` 传给两个实现）。
3. **判定为 HTTP/2 时暂存的字节是前言的开头**，必须交给 `Http2Connection` 继续逐字节比对
   （`prefacePos` 游标）。

### 2.2 TLS 下的嗅探

ALPN 协商结果直接决定协议（`engine.getApplicationProtocol()`），
**不**走嗅探。但握手期间读到的字节可能已经包含 HTTP 请求 ⇒
握手完成后必须把残留明文交给嗅探器（`TlsHttpServerNioSession` 的 `pendingPlaintext`）。

---

## 三、HPACK

### 3.1 静态表

RFC 7541 Appendix A 的 61 项完整实现（`HpackStaticTable`），
索引 1..61，索引 0 保留（Dynamic Table Size Update 标记）。

### 3.2 动态表

- `ArrayDeque<Entry>`，`addLast` 最新 / `removeFirst` 最旧；
- 占用大小 = `name.length + value.length + 32`；
- 索引 62 = 最新，62 + size - 1 = 最旧；
- `maxSize` 由**对端**的 `SETTINGS_HEADER_TABLE_SIZE` 设定；
- 收到 `Dynamic Table Size Update (0x20)` **立即**生效（RFC 7541 §4.2 要求它先于该 header block）；
- 超限从最旧开始 evict。

### 3.3 编码器策略（v1）

| 前缀 | 类型 | 服务端策略 |
|---|---|---|
| `1xxxxxxx` | Indexed Header Field | 用 |
| `01xxxxxx` | Literal with Incremental Indexing | **不用**（不污染对端动态表） |
| `0000xxxx` | Literal without Indexing | **默认** |
| `0001xxxx` | Literal Never Indexed | 敏感头用 |
| `001xxxxx` | Dynamic Table Size Update | 解码器必须支持 |

编码器 v1 一律 `Literal without Indexing`；**响应头不输出 Huffman**
（解码器必须完整支持 Huffman —— 对端可能用 Huffman 编码请求头）。

### 3.4 Huffman 表：只存 257 个码长

**绝不凭记忆手写 257 个码字。** 只存 257 个**码长**（sym 0..255 + EOS=256），
码字按 RFC 7541 的规范构造规则唯一还原：

1. 257 个符号按 `(码长, 符号序号)` 升序排序；
2. 第一个码字 = 0；下一个码字 = 上一个 + 1；
3. 码长增加 1 时，码字 = `(上一个 + 1) << 1`；
4. 码长相同的符号码字依次 +1。

**四条强制自检**（`HuffmanTableTest`）：

1. **Kraft 等式**：`Σ_{i=0..256} 2^(30 − len[i]) == 2^30`
   —— 码长表完整且正确的**充分必要**条件，O(257)，防抄写错误最有效的闸；
2. 前缀无关（任两码字互不为前缀）；
3. 14 个锚点逐个断言（sym 0 = `0x1ff8`/13、sym 1 = `0x7fffd8`/23、
   `' '` = `0x14`/6、`'0'`..`'9'` = `0x0`..`0x9`/5、`'a'`=`0x3`、`'c'`=`0x4`、
   `'e'`=`0x5`、`'i'`=`0x6`、`'o'`=`0x7`、`'s'`=`0x8`、`'t'`=`0x9`/5、
   EOS = `0x3fffffff`/30）；
4. **RFC 7541 官方向量**：C.3.1 / C.3.2 / C.4.1 / C.4.2 四组。

### 3.5 header 名大小写

h2 强制 header 名小写（RFC 9113 §8.2.1）。但 HTTP/1.1 侧存在**大小写敏感**的调用
（`ValueHttpRequest.java:114,126,138`），简单存小写会让它们失效。

**做法**：`putHeader` 保留原始 key 写入 map（HTTP/1.1 兼容），
**另外**维护 `TreeMap<String, HeaderValue>`（`String.CASE_INSENSITIVE_ORDER`）作为查找加速结构；
`getHeaderValue` 先查 case-insensitive map。h2 侧写入前强制 `toLowerCase`，
HPACK 解码器侧再归一一次。

---

## 四、帧层与流控

### 4.1 帧

| 帧 | 类型码 | 服务端行为 |
|---|---|---|
| DATA | 0x0 | 收：累积到 stream body；发：受流控约束 |
| HEADERS | 0x1 | 收：解 HPACK；发：响应头 |
| CONTINUATION | 0x9 | 收：拼接上一 HEADERS 尾部；发：v1 不产生 |
| RST_STREAM | 0x3 | 收：立即关闭该流；发：出错时关闭流 |
| SETTINGS | 0x4 | 收：应用参数 + 回 ACK；发：发本地参数 |
| PING | 0x6 | 收：回 PING ACK；发：保活 |
| GOAWAY | 0x7 | 收：停止建新流；发：连接级错误 / 优雅关闭 |
| WINDOW_UPDATE | 0x8 | 收：增加发送窗口；发：接收窗口释放时发 |
| PRIORITY | 0x2 | **收即忽略** |
| PUSH_PROMISE | 0x5 | **收即连接错误 PROTOCOL_ERROR**（服务端不启用 push） |

未知帧类型 ⇒ **忽略**（RFC 9113 §4.1）。

### 4.2 SETTINGS

6 参数：`HEADER_TABLE_SIZE(0x1)` / `ENABLE_PUSH(0x2)` / `MAX_CONCURRENT_STREAMS(0x3)` /
`INITIAL_WINDOW_SIZE(0x4)` / `MAX_FRAME_SIZE(0x5)` / `MAX_HEADER_LIST_SIZE(0x6)`。

- 服务端：`ENABLE_PUSH = 0`（不启用 push）；并发流数与头列表长度有限制；
- 收到对端 `INITIAL_WINDOW_SIZE` 变更 ⇒ **所有现存流的发送窗口都要调整**（差值法，RFC 9113 §6.9.2）；
- 收到 SETTINGS ⇒ 必须回 `SETTINGS` ACK。

### 4.3 流控

- 连接级 + 流级窗口，初始 65535；
- **连接级窗口初值不受 `SETTINGS_INITIAL_WINDOW_SIZE` 影响**；
  流级发送窗口初值 = **对端**通告的值，流级接收窗口初值 = **本端**通告的值；
- 收 DATA：连接窗口和流窗口**同时**减；减到负数 ⇒ **连接级** `FLOW_CONTROL_ERROR` + GOAWAY；
- 发 DATA：`chunk = min(bodyRemaining, streamSendWindow, connSendWindow, peerMaxFrameSize)`；
- 窗口耗尽 ⇒ 剩余数据进 `Http2Stream.pendingOutbound`，等 `WINDOW_UPDATE` 再发；
- 收满窗口 ⇒ 发 `WINDOW_UPDATE` 归还（**攒到初始窗口的一半**才归还，避免大量小帧）。

### 4.4 ★ 帧写出必须原子

**绝不复用 `HttpResponse.write0`** —— 它的 `write0` 在 buffer 满时会**中途**调
`nioSession.writeToChannel(buf)`，把一帧劈成两半 ⇒ 对端 `FRAME_SIZE_ERROR`。

h2 帧编码器自己管缓冲（`Http2FrameWriter`），并且：
`FastBuf.write(byte[],off,len)` **静默截断**（`FastBuf.java:215-223`）
⇒ 每段都要检查写入长度，不等则报错而不是继续。

**单帧上限取 `min(对端 MAX_FRAME_SIZE, 本端写缓冲能装下的最大值)`** ——
对端可以通告到 16MB，但本端写缓冲只有 32KB，装不下超大的帧。

---

## 五、伪头 → `HttpRequest` 映射

| 伪头 | 映射到 | 做法 |
|---|---|---|
| `:method` | `request.methodInfo` | **必须** `MethodCache.instance().getMethodInfo(name)`，保证 methodIndex 与 `HttpServerBuilder.addPathHandler` 的 `httpHandlers[]` 索引一致，否则 `HttpServerNioSession` 取 handler 时会 AIOOBE |
| `:path` | `request.path` / `pathInfo` | **复用 `PathDecoder`**：`:path` 字节 + 一个 `' '` 哨兵拷进 `FastBuf` → `pathDecoder.decode(tmp, pathRange, request)`。哨兵让它在 `' '` 处必然切出完整 path，同时白送 `+`→空格、`%XX` 解码、FNV hash、精确 cache 命中、wildcard 回退 |
| query | `request.queryInfo` | 拷 `'?'` + query 字节 + **`' '` 哨兵** → `QueryStringDecoder.decode`（它要求 `'?'` 开头、`' '` 结尾） |
| `:authority` | `request.host` | 小写存储 |
| `:scheme` | 独立字段 | **不进** headers map |
| 其它 header | `request.putHeader(name, value)` | 强制小写 |

**★ 每条流必须持有自己的**：`ValueHttpRequest`、`HttpFastBufDataRange`（path/query 各一）、
**stream 级 body 累积缓冲**、自己的 `HttpResponse`。

不能复用 HTTP/1.1 的 `ThreadLocal<ValueHttpRequest>` 复用模式
（`RangeHttpRequestDecoder` 复用单个 request + 单个 response），
也不能用 session 级 body 累积器（`HttpNioSession.getOrCreateBodyAccum`）—— 那样会串流。

---

## 六、响应侧策略化

```java
public interface ResponseEncoder {
    void writeHead(HttpResponse response, int contentLength) throws IOException;
    void writeBody(HttpResponse response, byte[] data, int offset, int length) throws IOException;
    void end(HttpResponse response) throws IOException;
}
```

- `Http1ResponseEncoder` —— **产出与改造前字节级完全一致的输出**（golden 比对，见 §九）；
- `Http2ResponseEncoder` —— 构造时已绑定 `connection + stream`
  （**这是不引入 `isHttp2()` 布尔的关键**）。

### 6.1 `HttpResponse` 的改造

| 改动 | 理由 |
|---|---|
| 新增 `statusCode` + `setStatusCode/getStatusCode` | 原来状态码硬编码在各 `write` 方法里，策略化后必须由 response 持有 |
| 新增 `encoder` + `setEncoder/getEncoder` | 编码策略挂载点。**默认** `Http1ResponseEncoder`，保持既有行为 |
| `header(String,String)` **真的输出** | 原来只 put 不输出 ⇒ h2 无法带自定义头 |
| `header(Header)` / `write(ByteData)` 不再空实现 | 同上 |
| 新增 `getHeaders()` 等遍历接口 | h2 编码器要遍历全部响应头 |
| `setSimpleResponse` 补 Content-Length | HTTP/1.1 侧原来不写，JDK HttpClient 会 read-until-close 死等 |

**明确不引入**：`isHttp2()` 布尔。streamId 是**连接内**概念，
`Http2ResponseEncoder` 构造时已绑定 `connection + stream`；
再加布尔就变成第二个 `upgraded` 式协议开关。

**明确不改**：`HttpVersion.HTTP_2_0` 的名字（public API，`HttpVersionDecoder` 依赖）。
新增 `HTTP_2("HTTP/2", 2)`；`HTTP_2_0` 标 `@Deprecated` 保留；
`fromVersion(String)` 接受 `"HTTP/2"`；新增 `public boolean isHttp2()`。

### 6.2 `:status` 编码

`ResponseStatusCode.get(code)` 返回 `"200 OK\r\n"`，h2 不能用。
新增 `public static byte[] getCodeBytes(int code)`（3 字节，无尾随 CRLF）。

---

## 七、h2c Upgrade（RFC 9113 §3.2）

### 7.1 ★ 关键事实：客户端不会重发 HEADERS

**curl 与 JDK HttpClient 在 h2c upgrade 场景下都不会再发送 h2 的 HEADERS 帧。**

RFC 7540 §3.2：升级请求本身就是 stream 1 的请求（客户端方向隐式半关闭），
**服务端必须自己用 stream 1 给出响应**。

原先的实现「等客户端重发 HEADERS」⇒ 双方互等到超时（表现为 request timeout 不触发）。
现在：`Http2Connection.createUpgradeStream()` 建 stream 1，
`startAfterUpgrade()` 立刻发出服务器连接前言（SETTINGS 帧）。

### 7.2 流程

1. HTTP/1.1 请求带 `Upgrade: h2c` + `HTTP2-Settings`；
2. 服务端回 `101 Switching Protocols` + `Connection: Upgrade` + `Upgrade: h2c`；
3. 该 HTTP/1.1 请求的语义**转交**给 stream 1（伪头从 HTTP/1.1 请求行/头映射过来）；
4. 切换到 h2 帧处理。

**前置事实**：`RawHttpTest.java:129-135` 已记录 —— JDK HttpClient 默认先发 `Upgrade: h2c`，
在 Upgrade 实现完成前 edap 不回 101 ⇒ **JDK HttpClient 在 h2c preface 死等，
request timeout 都不触发**。所以 Phase 1 只能用 `curl --http2-prior-knowledge` 验证，
Upgrade 完成后才能用 JDK HttpClient。

---

## 八、TLS + ALPN

### 8.1 落在 HTTP 模块内

`TlsHttpServerNioSession extends HttpServerNioSession`，覆写 `fastRead` / `writeToChannel`。

理由：`NioServerSession.fastRead`（`:224-241`）和 `writeToChannel`（`:100-111`）
都是 **public 非 final** ⇒ **对 edap-nio-parent 及其它协议模块零影响**。

### 8.2 实现要点

- `FastBuf.syncToByteBuffer()` 是**空实现** ⇒ TLS 路径**不能**用 ByteBuffer 直通；
- `SSLEngine.wrap/unwrap` 用 `byte[]` 中转，再搬进 `FastBuf`；
- **绕过 `channelFd` 直写**：TLS 路径用 `socketChannel.write(buf.byteBuffer())`；
- **ALPN**：`SSLParameters.setApplicationProtocols(new String[]{"h2", "http/1.1"})`；
  握手完成后 `engine.getApplicationProtocol()` 决定走哪个 `HttpConnection`；
- **握手残留字节**：握手期间读到的字节可能已含 HTTP 请求 ⇒ 交给协议嗅探器。

### 8.3 ★ TLS + HTTP/2 大响应体的三个叠加缺陷（已修）

1. `TlsHttpServerNioSession.writeToChannel` 的密文重试路径**提前清**了 `cipherInQueue`；
2. `Http2FrameWriter.flushToSocket()` 帧写完就 `disableWriteInterest()`；
3. `removeWriteBuf` 里 TLS backlog（更旧的字节）还没排空就发新帧。

修复：`HttpServerNioSession.removeWriteBuf` 新增 `protected void onWriteBufRemoved(FastBuf wbuf)` 钩子；
TLS 的 `writeToChannel` 重试路径改 `return super.writeToChannel(buf)`；
TLS 覆写 `onWriteBufRemoved` 与 `disableWriteInterest`。

### 8.4 密钥来源

**主路径**：用户提供的 keystore —— JVM 标准属性 `javax.net.ssl.keyStore` /
`javax.net.ssl.keyStorePassword`（TLS 是进程级配置，推荐）。
辅助：`edap.http.tls.*`（`Props` 的 `child("http.tls")`）。

**明确不做**：运行时反射 `sun.security.x509` 生成证书、引入 BouncyCastle、子进程调 keytool。

---

## 九、与 HTTP/1.1 的共存

### 9.1 golden 比对

`edap-http-server/src/test/resources/golden/` 下保存了改造前的响应字节，
改造后逐字节比对 —— **除刻意变更外，HTTP/1.1 输出必须完全不变**。

刻意变更清单（独立 commit）：

| 变更 | 影响 |
|---|---|
| `header(String,String)` / `header(Header)` / `write(ByteData)` 真正输出 | 之前只 put 不输出，现在会出现在响应里 |
| `setSimpleResponse` 补 `Content-Length` | 之前不写 ⇒ 客户端 read-until-close 死等。1xx/204/304 不输出 |
| URL 编码路径命中精确 cache | 之前 URL 编码路径永远命中不了精确 cache（hash 原始字节 vs 解码后字节）。改成双 key 注册（原始优先 + 解码别名） |

### 9.2 已知限制（必须写进文档，不能装作已解决）

**> 32KB 的 h2 响应体会被截断** —— 与 HTTP/1.1 一样的既有问题：
`HttpResponse.write0` 在 socket 满时截断并 return false，
根因是仓库已识别的 task #20/#21/#22。

h2 侧的做法是把未写完的数据留在 `pendingOutbound`，等 `OP_WRITE` 继续写，
但**根因未修之前**，两条路径都存在截断风险。

---

## 十、明确不做的事

| 项 | 原因 |
|---|---|
| **WS over h2（RFC 8441）** | 服务端不广告 `SETTINGS_ENABLE_CONNECT_PROTOCOL`；收到 `CONNECT` 帧 ⇒ `RST_STREAM(PROTOCOL_ERROR)`；h2 路径下若命中 WS 路径 ⇒ 直接回 `501 Not Implemented`（不走 `handeshake`）；`upgraded` 布尔在 h2 下**永不**置 true |
| 同端口 mux | 明文/TLS 两个端口 |
| 服务端 push | `ENABLE_PUSH = 0`；收到 PUSH_PROMISE ⇒ 连接级 PROTOCOL_ERROR |
| 响应头 Huffman 编码 | v1 只做 Literal without indexing；解码器仍必须完整支持 Huffman |
| HTTP/2 客户端 | 本次只做服务端 |

---

## 十一、验证

### 11.1 手工验证矩阵

**一键跑完**（脚本会自己起服务、跑 curl、收服务）：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./scripts/verify-http2.sh 18080 18443
```

本机（macOS / curl 8.7.1 + nghttp2 1.69.0）**实测全部通过**：

```
=== 验证矩阵 ===
  [ OK ] h2c prior knowledge                            2 200
  [ OK ] h2c Upgrade (curl --http2)                     2 200
  [ OK ] HTTP/1.1 明文（回归）                          1.1 200
  [ OK ] HTTP/1.1 路径/方法可见                         /echo|GET
  [ OK ] h2 over TLS (ALPN)                             2 200
  [ OK ] HTTP/1.1 over TLS (ALPN)                       1.1 200
```

> curl 的 `%{http_version}` 对 HTTP/2 返回 **`2`**（不是 `HTTP/2`），对 HTTP/1.1 返回 `1.1`。

| 场景 | 命令 | 期望 | 状态 |
|---|---|---|---|
| h2c prior knowledge | `curl --http2-prior-knowledge -sv http://127.0.0.1:8080/` | HTTP/2 200 | ✅ |
| h2c Upgrade | `curl --http2 -sv http://127.0.0.1:8080/` | 101 → HTTP/2 200 | ✅ |
| h2 over TLS | `curl --http2 -k -sv https://127.0.0.1:8443/` | HTTP/2 200（ALPN 协商 h2） | ✅ |
| HTTP/1.1 over TLS | `curl --http1.1 -k -sv https://127.0.0.1:8443/` | HTTP/1.1 200 | ✅ |
| HTTP/1.1 明文（回归） | `curl --http1.1 -sv http://127.0.0.1:8080/` | HTTP/1.1 200，**字节级与 golden 一致** | ✅ |
| 多路复用 | `nghttp -v -n 20 https://127.0.0.1:8443/` | 20 条流全部 200 | ⚠️ 本机**没装 nghttp**；多路复用由 `Http2MultiplexingEndToEndTest` 覆盖（3 例，进程内 20 条并发流） |
| JDK 客户端 | `java.net.http.HttpClient`（默认 HTTP_2）明文 | 成功 | ✅（`TlsEndToEndTest` 用 JDK HttpClient 覆盖 TLS 侧；明文侧的 Upgrade 由 `Http2UpgradeEndToEndTest` 覆盖） |

### 11.2 自动化测试

| 测试 | 内容 |
|---|---|
| `HuffmanTableTest` | Kraft 等式；前缀无关；14 锚点；RFC 7541 C.3.1/C.3.2/C.4.1/C.4.2 |
| `HpackDecoderTest` / `HpackEncoderTest` | 静态表索引、literal 四种形式、动态表增删、size update、round-trip |
| `Http2FrameCodecTest` | 帧 round-trip；长度编码；截断必须报错而不是静默截断 |
| `Http2SettingsTest` | 6 参数解析；INITIAL_WINDOW_SIZE 变更对现存流的传播 |
| `Http2FlowControllerTest` | 连接级/流级窗口双减；负数 ⇒ FLOW_CONTROL_ERROR；WINDOW_UPDATE 恢复 |
| `HttpProtocolSnifferTest` | `"PRI * HTTP/2.0..."` ⇒ h2；`"GET / HTTP/1.1..."` ⇒ h1；分 1/2/3 字节到达也正确 |
| `Http2EndToEndTest` | 内存 `FastBuf` 喂 preface + SETTINGS + HEADERS + DATA，断言收到 HEADERS + DATA |
| `Http2MultiplexingEndToEndTest` | 多条流并发 |
| `Http2FlowControlEndToEndTest` | 大响应体（> 64KB） |
| `Http2ControlFrameEndToEndTest` | PING / GOAWAY / RST_STREAM / WINDOW_UPDATE |
| `Http2UpgradeEndToEndTest` | h2c Upgrade 全流程 |
| `TlsEndToEndTest` | TLS + ALPN（h2 与 http/1.1 两条） |
| `UrlEncodedPathCacheTest` | URL 编码路径命中精确 cache |

---

## 十二、与密码学线的边界

**edap-http-parent 不依赖 edap-crypto**，两条线完全解耦：

- HPACK 的 Huffman 是**固定查表**，不是密码学；
- TLS 用 JDK 自带 `SSLEngine`（接管它就得实现 `javax.net.ssl` SPI，工作量巨大且削弱兼容性）；
- edap-crypto 只依赖 edap-log-api，与本模块无交集。

见 `edap-crypto/doc/DESIGN.md`。
