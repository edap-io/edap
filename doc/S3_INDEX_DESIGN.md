# edap-s3 磁盘存储索引化设计

> 目标：把 edap-s3 磁盘后端的元数据从「散落的 per-object `.meta` sidecar」收敛成
> 「per-bucket 的按 key 有序索引」，一次同时解决 LIST 的 O(N) 扫描和 4 个正确性 bug。

---

## 1. 起点：为什么改

用户问「S3 每个桶都存成单一目录，文件多了性能是不是比较差」。

### 1.1 实测结论：单目录布局本身不是瓶颈

APFS 本地盘 / JDK 17 / 1KB 对象 / 直接打 `DiskObjectStore`：

| N | 目录项 | put(ms) | head(ms) | get(ms) |
|---|---|---|---|---|
| 1,000 | 2,000 | 0.294 | 0.037 | 0.077 |
| 10,000 | 20,000 | 0.345 | 0.037 | 0.060 |
| 50,000 | 100,000 | 0.257 | 0.032 | 0.167 |
| 100,000 | 200,000 | 0.204 | 0.035 | 0.160 |

10 万对象内点查**基本是常数**（目录索引是平衡树）。「文件多了性能差」的直觉方向对、
归因错。

### 1.2 真正的热点：LIST 是唯一 O(N) 路径

> **口径说明**：本节是第一会话的探索性数据（单轮、机器负载未记录、含 JIT 预热）。
> §8 是同机 5 轮复测（修前 vs 修后），带 loadavg 标注。**两组数字不可混用**：
> §1.2 的绝对值只用于说明「趋势是 O(N)」，倍数结论一律以 §8 为准。

| N | LIST(maxKeys=1000) | LIST(前缀不匹配⇒全扫) | LIST(maxKeys=0 全量) |
|---|---|---|---|
| 1,000 | 47.8 ms¹ | 2.8 ms | 36.7 ms |
| 10,000 | 30.4 ms | 26.6 ms | 366.6 ms |
| 50,000 | 30.3 ms | 94.1 ms | 1,772 ms |
| 100,000 | 33.7 ms | 215.9 ms | **14,902 ms**(冷) / 3,251 ms(热) |

¹ 含 JIT 预热。

100k 冷启动的成本拆解：扫 20 万目录项 217ms + 10 万次 `Files.exists` 535ms
+ 10 万次 `.meta` 读解析 ~10s + 排序 52ms。

`maxKeys` 只在**已经扫过**的对象上截断，**不减少扫描量**。

> 注意 `LIST(maxKeys=1000)` 一列**不随 N 增长** —— 因为它截断得早、只扫前 ~1000 个
> 就停了。真正暴露 O(N) 的是「前缀不匹配」和「不限条数」两列。

### 1.3 哈希分片目录实测零收益

单目录扫 10 万项 **216.9ms** vs 256 片（每片 834 项）**220.6ms**。
⇒ **不做目录分片**。真正的解法是「元数据收敛成按 key 有序的索引」。

---

## 2. 挖出来的 4 个正确性 bug（比性能严重）

| # | bug | 实测证据 | 后果 |
|---|---|---|---|
| 1 | **分页完全失效** | `continuationToken` 在 `DiskObjectStore.list()` 里从未被引用；`ListObjectsV2Handler` 把 `isTruncated` 写死 `false`、`nextContinuationToken` 传 `null`。两次 LIST（第二次带任意 token）返回**完全相同的 1000 个 key** | 10 万对象的桶里 99,000 个对客户端**静默隐形** |
| 2 | **带 `/` 的 key 在 LIST 里完全不可见** | `Files.newDirectoryStream(dir, "*.meta")` 不递归。4 个对象（`flat.txt`/`dir/a.txt`/`dir/b.txt`/`dir/deep/c.txt`）**只列出 1 个** | S3 语义里 key 里的 `/` 只是普通字符 |
| 3 | **删光对象后 `isEmpty()` 仍 false** | 残留空目录 `dir/`、`dir/deep/` | DeleteBucket 永远 BucketNotEmpty |
| 4 | **`.acl` 让桶永远非空** | 「建桶 → PUT 桶 ACL → DELETE 桶」⇒ BucketNotEmpty（`Files.delete` 抛 `DirectoryNotEmptyException`） | 同上 |

附带问题：结果在**截断之后**才排序 ⇒ 第一页不是字典序第一页
（实测首 key `obj-00000492.dat`、末 key `obj-00099903.dat`）。

**根因**：`.acl` / `.multipart` / key 含 `/` 造成的子目录这些「非对象项」
没有和对象分开记账。

---

## 3. 目标布局

```
<root>/<bucket>/
├── .acl                    ← 不变
├── .index/log              ← ★新增：索引 log（元数据唯一真源）
├── .multipart/<uploadId>/  ← 不变
└── <key...>                ← body，不变
```

- **元数据唯一真源 = per-bucket 的 append-only 索引 log**，取代 `.meta` sidecar。
- body 路径**完全不变**（仍 `<bucket>/<key>`，key 含 `/` 仍建子目录）。

---

## 4. 测试基建与基线（Step 1）

### 4.1 基建

仓库根 pom 在 `<build><plugins>` 里把 surefire 的 `skipTests` 写死成 `true`
（不是 `pluginManagement`）⇒ **所有子模块的测试都不跑**。

- `edap-s3-parent/pom.xml`：加 surefire 块覆盖回来（`<skipTests>false</skipTests>`
  + `<includes>` 显式写 `**/*Test.java` 和 `**/*IT.java` —— surefire 默认 includes 不含 `*IT.java`）
- `edap-s3-parent/edap-s3-server/pom.xml`：**删掉**那整块 `<skipTests>true</skipTests>`
  （子模块 configuration 优先于父模块，不删则父 pom 改了也没用）
- `edap-http-parent/pom.xml`：同样的 surefire 覆盖（为了验证 http-server 的改动）

**不动根 pom**。

### 4.2 打开测试后暴露的既有问题（本次一并修掉）

| 问题 | 位置 | 现象 | 处理 |
|---|---|---|---|
| `HttpServerBuilder.addPathHandler` 越界崩溃 | `edap-http-server` | `handlers.add(methodIndex, handler)` 作用在空 ArrayList 上，而 `methodIndex` 是 `MethodCache` 的**全局**递增索引（GET=0、POST=1…）⇒ 第二次注册就 `IndexOutOfBounds: Index: 1, Size: 0` | 改成按 method index 分配定长数组（`new HttpHandler[methodIndex+1]`，空槽留 null），写法对齐 `AppContext.buildPathTable` |
| 同一个 path 注册多个 method 会互相覆盖 | `edap-http-server` | wildcard 路由按 path 字符串去重（`PostfixWildcardPathRouter.registerPathInfo`），精确路径走 `mapping.put` 整体覆盖 ⇒ S3 注册 `GET/PUT/POST/DELETE/HEAD` 到 `/*` 时只有第一个存活 | `HttpServerBuilder` 按 path 缓存 `PathInfo`，重复注册只补 method→handler 槽位 |
| `?acl` 签名算错 | `edap-s3-server`（测试） | `S3BucketAclIT` 把 `?acl` 写进 path，客户端 canonicalUri 变成 `/bucket?acl`、canonicalQuery 为空，与服务端（canonicalUri=`/bucket`、canonicalQuery=`acl=`）不一致 ⇒ `SignatureDoesNotMatch` | 测试改成把 `acl` 作为 query 参数单独传。**服务端是对的**（AWS SDK 也是这么签的） |
| multipart `initiated` 时间戳精度只到秒 | `edap-s3-disk` | 同一秒内发起的两个 upload 落盘后 `initiated` 相同 ⇒ `listUploads` 按 initiated 排序时并列、顺序退化成目录遍历序 ⇒ 随机 | `UploadMetaFile` 的时间戳改用 `DateTimeFormatter.ISO_INSTANT`（纳秒级；老文件秒级格式仍是合法 ISO instant，照样能读） |
| 并发上传测试 contentLength 写错 | `edap-s3-core`（测试） | `streamOf("SECOND_VERSION")` 是 14 字节，却传 `contentLength=13` ⇒ body 被截成 `SECOND_VERSIO`，断言必挂（哪个线程最后写决定成败 ⇒ 50/50 flaky） | 改成 14 |

### 4.3 基线

`mvn -pl edap-s3-parent -amd test`（`JAVA_HOME=/opt/homebrew/opt/openjdk@17`）：

| 模块 | 测试数 | 失败 | 耗时 |
|---|---|---|---|
| edap-s3-core | 103 | 0 | ~0.7 s |
| edap-s3-server | 48 | 0 | ~4.3 s（`RawHttpTest` 占 3.4 s） |
| edap-s3-disk | 43 | 0 | ~12.2 s（`DiskMultipartStoreIT` 写 500 MB） |
| edap-s3-starter | 0 | — | ear 打包阶段失败（见下） |

**连跑 4 次全绿、0 flaky。**

**已知既有问题（不追修）**：
- `edap-s3-starter` 的 `io.edap:edap-app-plugin:package` 报 `java.io.IOException: Is a directory`
  —— 用**原始 pom**（未加 surefire 覆盖）复现过，同样失败 ⇒ 与本次改动无关。
  单模块 `mvn -pl edap-s3-parent/edap-s3-starter test` 能过（走 ~/.m2 的已装依赖），reactor 里走 target/classes 就炸。
- `edap-http-core` 的 `HttpFastBufDataRangeTest.testFrom` 失败、`edap-http-server` 的
  `PathDecoderTest.testDecode` / `HttpGetParseTest.testParse` 失败（`pathInfoMatcher` 为 null）
  —— 回退 `HttpServerBuilder` 的改动后同样失败 ⇒ 既有问题，与本次无关。

---

## 5. 核心设计

### 5.1 索引 log 格式（自包含，不引新依赖）

```
#EDAPIDX1\n
<op>\t<field>\t<field>...\t<crc32hex8>\n
```

- `op`：`P`=PUT（key/etag/size/contentType/lastModifiedMillis/umCount/(umKey,umValue)*）、
  `D`=DELETE（key）
- 字段一律 **percent-encoding**（转义 `<0x20`、`%`、`0x7F`；`%` 自身也转义 ⇒ 单射可逆）。
  分隔符是 TAB ⇒ 值里含 `=`/换行都安全 ⇒ 一条记录严格一行。
- `crc32hex8` = payload 的 CRC32（`java.util.zip.CRC32`）。**尾部半条（无 `\n`）或
  CRC 不匹配的记录一律丢弃**；CRC 失败则该行及其后全部丢弃（位置不可信），
  用 `FileChannel.truncate()` 截回。

### 5.2 内存索引

per-bucket 一个 `ConcurrentSkipListMap<String, ObjectMeta>`，comparator = `Utf8KeyOrder`
（**UTF-8 字节序 == code point 序**，与 `String.compareTo` 在代理对上不一致，故不用后者）。
跳表天然按键序 ⇒ `tailMap(boundary,false)` 直接拿续查区间，LIST 零磁盘 IO。

### 5.3 锁

只有 **log append 串行化**需要锁 ⇒ per-bucket 一把 `ReentrantLock`（不是全局一把）。
`put`/`delete` 各持一把，**`log.append` 和 `map.put` 必须在同一把锁内**
（否则「刚 PUT 完立刻 LIST 少一个」）；`head`/`list`/`isEmpty` 零把锁。
满足「每方法最多一把锁、锁细分到最小单位」。

### 5.4 崩溃一致性：**索引是提交点**

- **PUT**：写 body 到 `.tmp` → `ATOMIC_MOVE` rename 成 `<key>`（body 就位）
  → **append 索引记录** → 更新内存索引。顺序不可颠倒：先 body 后索引，崩溃只留
  「body 有、索引无」的孤儿（LIST 看不到、GET 404 ⇒ 语义自洽）；反过来会留
  「索引有、body 无」的不一致。
- **DELETE**：**先 append DELETE 记录**（提交「对象消失」）→ 再删 body → 再清空父目录。
  孤儿 body 不影响正确性。
- fsync：**v1 不引入**（现有 `.meta` 方案也没 fsync，保持一致），代码留 `fsyncOnWrite` 开关。

### 5.5 LIST 语义

- **排序**：索引本身有序 ⇒ 天然「先排序后截断」。
- **delimiter 聚合**：`idx = key.indexOf(delimiter, prefix.length())`；命中则该 key
  **不进 contents**，只贡献一个 CommonPrefix（group 成员在字典序里连续 ⇒
  与上一个 group 比较即可去重，不需要 Set）。
- **maxKeys 同时计数 contents + commonPrefixes**（S3 规范）。`maxKeys=0` ⇒ 返回空且
  `IsTruncated=false`（**破坏性语义变更**：现有 `list(...,0,...)` 表示「不限」，
  改用 `ListOptions.MAX_KEYS_UNLIMITED`）。协议层 maxKeys 上限 1000。
- **continuationToken 编码「边界」而非「最后一个输出的 key」**：
  - 边界 = `(KEY, k)` ⇒ 续查所有 `K > k` 的 key。
  - 边界 = `(COMMON_PREFIX, p)` ⇒ 续查所有 `K > p` **且 `!K.startsWith(p)`** 的 key
    （跳过整个 group）。
  - **必须区分两种边界**：若统一用「最后一个输出的 key」，`a/1,a/2,b,c` + delimiter=`/`
    + maxKeys=1 会让第 2 页**重复输出** `a/`；若统一排除 `startsWith`，则 key `a`
    与 `a/1` 共存时会漏掉 CommonPrefix `a/`。
  - token 编码 = `version|kind|boundary|requestFingerprint(CRC32(prefix\0delimiter))|crc32`，
    外层 Base64 URL-safe。fingerprint 让「A 的 token 用到 B 的 prefix」返回
    `InvalidArgument` 而不是语义错乱的结果。
- **start-after**：只在没有 continuation-token 时生效，`K > startAfter`。
  `S3RequestParser` 白名单要补 `start-after`。
- **IsTruncated 判定**：页满即视为可能截断（不做「向前多看一个」）—— AWS 自己就这么干
  （文档明说可能返回空列表但仍有更多）；且 CommonPrefix 边界要判断是否还有更多就得
  扫完整个 group，代价不可接受。客户端按标准流程多查一次空页即可。

### 5.6 SPI 改造

`ObjectStore`（edap-s3-core）：

```java
ListResult list(String bucket, ListOptions options) throws IOException;   // 签名变更
default void init() throws IOException {}          // 启动期：加载索引 + 迁移 + 修 log 尾巴
default void forgetBucket(String bucket) {}         // 删桶后释放该桶内存索引
```

用 `default` ⇒ `DiskMultipartStoreIT` 里的 `BlockingObjectStore` **不用改**。

新增（edap-s3-core）：`model/ListOptions`、`model/ListResult`（`keyCount()` =
contents + commonPrefixes）、`store/ListToken`、`store/ListPaging`
（共享分页算法，`ListPaging.paginate(NavigableMap<K,?>, ListOptions, Function<K,ObjectMeta>)`
—— 用 extractor 避免 InMemory 侧每次 LIST 分配 N 个包装对象）、`store/Utf8KeyOrder`。

`InMemoryObjectStore` 换成 `ConcurrentSkipListMap` + 走同一个 `ListPaging`
（顺带修它自己的 delimiter 空实现、截断在排序前、token 未用三个缺陷，
并加 `forgetBucket` 释放 body 内存）。

`XmlResponseBuilder.listBucketResultV2` 换新签名（收 `ListResult`），
补 `<Delimiter>`/`<ContinuationToken>` 回显/`<StartAfter>` 回显，
`<KeyCount>` 改用 `result.keyCount()`，删掉死代码 `computeCommonPrefixes`。

### 5.7 isEmpty / 删桶

`isEmpty()` ⇒ **索引里有没有对象条目** ⇒ 天然排除 `.acl` / `.index` / `.multipart` /
`.meta` 残留 / 空目录 / 孤儿 body ⇒ bug #3 和 #4 同时解决。

连带必须改：`DiskBucketStore.delete()` 现在是 `Files.delete(dir)`，目录非空就抛
`DirectoryNotEmptyException` ⇒ 改成**递归删**（`DiskMultipartStore.deleteRecursive`
改成包级 `static` 复用）。`DeleteBucketHandler` 在 `bucketStore.delete()` 后调
`objectStore.forgetBucket(name)`。

### 5.8 key 保留前缀

`.index/` / `.multipart/` / `.acl` / `.index` / `.multipart` 是保留名 ——
`put`/`delete` 拒绝（`INVALID_ARGUMENT`），`get`/`head` 拒绝（`NO_SUCH_KEY`，
**否则 `GET /bucket/.index/log` 会泄露整个索引**）。现状 `PUT /bucket/.acl` 本来就能
覆盖 ACL 文件，顺手堵上。

`DELETE` 后 best-effort 清理空的父目录（止步于桶目录）—— S3 语义里目录不存在。

### 5.9 迁移

入口是 `DiskObjectStore.openIndex(bucket)`：**有 `.index/log` ⇒ 回放；没有 ⇒ 走迁移**。
迁移由 `LegacyMetaMigrator` 做：`Files.walk` 递归找所有 `*.meta` sidecar
（跳过 `.index`/`.multipart`/`.acl` 下的）→ 解析（复用老 `MetaFile.read`）→ 按 key 排序
→ 生成 log 内容 → 写 `.index/log.tmp` → `ATOMIC_MOVE` 覆盖
⇒ 迁移幂等、崩溃安全。**不删任何 `.meta`**。

触发点：**`init()`（预热 + 全量迁移）+ 懒加载兜底**（任何针对该桶的操作第一次碰到时
`indexFor(bucket)`）—— 测试里不调 `init()` 也能正确。

log 损坏（magic 不对）时：`DiskObjectStore.openIndexRebuild()` 归档成
`log.corrupt-<ts>`，再用 `LegacyMetaMigrator.rebuildFromBodies()` 从 body 重建
（etag 现算 MD5，老 sidecar 不作数）。

已知限制（写进文档）：key 以 `.meta` 结尾的对象在老布局下本身就与 sidecar 冲突，
迁移时保守跳过（宁可漏迁不可错迁）。

### 5.10 log 压缩

**v1 未实现任何压缩**（计划里写的「启动期压缩」本次没做，代码里没有这段逻辑）。

- 现状：log 只追加不回收，体积 = O(总写入次数)，不是 O(存活对象数)。
  反复覆盖同一个 key 会让 log 线性增长。
- 后果：启动回放时间随 log 体积线性增长。10 万对象的一次性迁移后，
  log ≈ 6.8 KB/48 对象量级（见 §9 实测），正常写入增速约
  「每次写一条记录」的体量。
- 计划中的形态（未落地）：回放后若 log > 64MB 或记录数 > 2×存活对象数 ⇒
  重写一份只含存活对象的 log，tmp + atomic rename。
- 兜底：log 尾部半条 / CRC 失配会被丢弃并 truncate（见 §5.1），
  所以「写坏了」不会导致索引不可用 —— 最坏是丢掉最后几条记录。

---

## 6. 风险

- **内存**：索引是 O(存活对象数) 不可压缩，约 328 B/对象 ⇒ 100 万对象 ≈ 330 MB。
  超过百万级应改磁盘 B-tree/LSM。
- **迁移成本**：10 万对象的桶首次迁移 ≈ 10 万次 `.meta` 读解析（约 10 s，一次性，启动时发生）。
- **`.meta` 保留的磁盘浪费**：老桶的 sidecar 一直占着（100 万对象 ≈ 100~200 MB）。
  v1 不自动删；另给离线清理工具。**但删是安全的** —— §9 实测：迁移后删掉全部 `.meta`，
  新实现照常工作（索引是唯一真源）；老实现则会「全部对象隐形 + head 报 NoSuchKey」。
- **log 增长**：O(总写入次数)。**启动期压缩本次未实现**（见 §5.10），
  目前唯一的缓解是 §5.1 的尾部截断。
- **key 冲突**：S3 允许同时存在 key `dir` 和 `dir/a.txt`，文件系统布局下不可能 ⇒
  `PUT /bucket/dir/a.txt` 会失败。本次不修（修需要改 body 路径），但把 500 改成友好的
  `INVALID_ARGUMENT`。

---

## 7. 明确不在本次范围

- key 编码（解决文件/目录名冲突）—— 会改 body 路径，需单独立项
- 孤儿 body GC（启动期全量遍历 + 误删风险，收益有限）
- 完整 LSM 快照+尾巴压缩（v2）
- `GET /{bucket}` 不带 `list-type` 的 V1 语义（现在被判成 V2，已知偏差）
- `encoding-type` / `fetch-owner` 参数

---

## 8. 落地状态（改了什么）

### 8.1 新增类

| 模块 | 类 | 作用 |
|---|---|---|
| edap-s3-core | `model/ListOptions` | LIST 参数（prefix / delimiter / maxKeys / continuationToken / startAfter） |
| edap-s3-core | `model/ListResult` | LIST 结果（contents / commonPrefixes / truncated / nextContinuationToken / keyCount） |
| edap-s3-core | `store/ListToken` | continuationToken 编解码（边界 + 请求指纹 + CRC32） |
| edap-s3-core | `store/ListPaging` | **共享分页算法**（磁盘侧和内存侧走同一份代码） |
| edap-s3-core | `store/Utf8KeyOrder` | 按 UTF-8 字节序（== code point 序）比较 key |
| edap-s3-disk | `IndexRecord` | 一条索引记录（PUT / DELETE） |
| edap-s3-disk | `IndexCodec` | 记录 ↔ 一行文本（percent-encoding + CRC32） |
| edap-s3-disk | `IndexLog` | append-only log 的读写、尾部修复、损坏归档 |
| edap-s3-disk | `BucketIndex` | per-bucket 内存索引（跳表）+ log 追加 + 分页查询 |
| edap-s3-disk | `LegacyMetaMigrator` | 老 `.meta` sidecar → 索引 log（含「从 body 重建」） |

### 8.2 SPI 变更

```java
// io.edap.s3.store.ObjectStore
ListResult list(String bucket, ListOptions options) throws IOException;  // 签名变更
default void init() throws IOException {}            // 启动期：加载索引 + 迁移 + 修 log 尾巴
default void forgetBucket(String bucket) {}           // 删桶后释放该桶内存索引

// io.edap.s3.store.MultipartStore
default void startupRecovery() throws IOException {}  // 启动期：恢复崩溃残留的 multipart upload
```

三个 `default` 方法 ⇒ 现有实现（`InMemoryObjectStore`、测试里的
`BlockingObjectStore`）**不需要改就能编译**。

`MultipartStore.startupRecovery()` 是新增的 —— 顺带补上了**生产代码从来没调过的
multipart 崩溃恢复**（`DiskMultipartStore` 里一直有实现，但没有任何调用方）。

### 8.3 关键实现点

- `DiskObjectStore` 重写：`put`/`head`/`get`/`delete`/`list`/`isEmpty` 全部走
  `BucketIndex`；新增 `checkKeyWritable` / `checkKeyReadable`（保留名）、
  `pruneEmptyParents`（删完清空父目录）。
- `DiskBucketStore.delete()` 从 `Files.delete(dir)` 改成**递归删**
  （目录非空会抛 `DirectoryNotEmptyException`）。
- `DeleteBucketHandler` 在 `bucketStore.delete()` 之后调 `objectStore.forgetBucket(name)`。
- `S3ServerBuilder.build()` 新增 `startupStores()`：调 `objectStore.init()` +
  `multipartStore.startupRecovery()`，IOException 包成 `RuntimeException`
  （启动期失败要立刻暴露，不能拖到首个请求）。
- `S3UrlMapping.urlMappings()` 同样调这两个启动期钩子。
- `IndexLog.open()`：非新文件时 `writePos = ch.size()`
  （修「不调 `replay()` 直接 append 会从 0 覆盖写坏 log」的隐患）。

### 8.4 新增测试

| 测试 | 位置 | 覆盖 |
|---|---|---|
| `ListPagingTest` | core | 分页边界语义（含「group 不重复」「Contents key 是 group 前缀时不漏」两个关键用例） |
| `InMemoryObjectStoreTest`（扩） | core | 内存侧 LIST / 分页 / delimiter（13 例） |
| `InMemoryBucketStoreTest`、`InMemoryMultipartStoreTest` | core | 新增，覆盖内存侧桶/多段语义 |
| `IndexCodecTest` | disk | 往返、特殊字符、CRC 篡改、截断尾巴、坏 magic |
| `IndexLogTest` | disk | log 追加 / 尾部修复 / 损坏归档 |
| `DiskObjectIndexTest` | disk | 4 个 bug 逐个覆盖 + 保留名 + 孤儿 body |
| `LegacyMetaMigrationTest` | disk | 老布局迁移（12 例） |
| `DiskObjectStoreCrashTest` | disk | 重启后数据完整、崩溃点语义（10 例） |
| `S3TestClient` | server | 测试用 SigV4 签名 / 匿名 HTTP 客户端（从 `S3BucketAclIT` 抽出） |
| `S3ListObjectsIT` | server | 真实 HTTP server + 真实磁盘 store 的端到端（11 例，覆盖 4 个 bug 的 wire 层） |
| `ListObjectsV2HandlerTest` | server | handler 层（12 例） |

### 8.5 测试数（改造前基线 → 改造后）

| 模块 | 基线（§4.3） | 改造后 | 失败 |
|---|---|---|---|
| edap-s3-core | 103 | **116** | 0 |
| edap-s3-disk | 43 | **102** | 0 |
| edap-s3-server | 48 | **71** | 0 |
| edap-s3-starter | 0 | 0 | ear 打包阶段失败（既有问题，见 §4.3） |

### 8.6 需要改的既有断言

| 文件:行 | 现状 | 改法 |
|---|---|---|
| `DiskObjectStoreTest.java:69-70` | 断言 `<bucket>/k.meta` 存在 | 断言 `<bucket>/k` 存在 **且** `<bucket>/.index/log` 存在；`.meta` 断言**不存在** |
| `DiskObjectStoreTest.java:131,140` | `list(...,0,...)` 期望 3 / 1 | `0` 语义变了 ⇒ 改 `ListOptions.MAX_KEYS_UNLIMITED` |
| `InMemoryObjectStoreTest.java:111,124,136` | 同上 | 同上 |
| `XmlResponseBuilderTest.java:53-54,94-113` | 旧 7 参签名 + `computeCommonPrefixes` 2 个测试 | 改新签名；**删**那 2 个测试（方法已删） |
| `DiskMultipartStoreIT.java:242-243` | 断言 `<key>` 和 `<key>.meta` 存在 | 断言 `<key>` 和 `.index/log` 存在；`.meta` 改为断言**不存在** |
| `DiskMultipartStoreIT.java:400-409` | `diskUsage()` 用 `Files.walk` 求和 | 排除 `.index/**`，保持断言口径 |
| `DiskMultipartStoreIT.java:521-523` | `BlockingObjectStore.list(...)` 转发 | 改新签名 |

---

## 9. 性能复测（修前 vs 修后）

### 9.1 口径

- **同一台机器**（APFS 本地盘）、**同一份基准逻辑**、**同一组 N**。
- 修前代码跑在 `git worktree /tmp/s3old`（HEAD = 改造前的提交）里单独编译，
  **不污染 `~/.m2`**；修后跑当前工作区。
- 基准：`/tmp/s3bench/S3Bench.java`（老 API）与 `/tmp/s3bench/S3BenchAfter.java`
  （新 API，**逐行等价**，只有 `list()` 的调用方式不同）。
  原始数据已留档到仓库：[`doc/S3_INDEX_BENCH_RAW.txt`](S3_INDEX_BENCH_RAW.txt)
  （42 行 = 5 轮 OLD + 5 轮 NEW，每行 8 列：
  `版本 N 目录项 LIST1k LISTmiss LISTall put head get`）。
- **各跑 5 轮**（OLD r1/r2 + 交替 r3/r4/r5；NEW r1/r2 + 交替 r3/r4/r5），
  下面报 min / 中位 / max。
- **loadavg 必须带上**：OLD 各轮跑前 loadavg 区间 `{7.29 … 57.91}`，
  NEW `{25.87 … 42.74}`。**NEW 跑在更高的机器负载下仍全面更快 ⇒ 结论保守。**

### 9.2 修前（OLD，5 轮 min/中位/max，单位 ms）

| N | 目录项 | LIST1k | LISTmiss | LISTall | put | head | get |
|---|---|---|---|---|---|---|---|
| 1,000 | 2,000 | 34.836/37.290/45.998 | 2.212/2.388/2.496 | 31.040/32.435/33.460 | 0.2456/0.2564/0.2673 | 0.0341/0.0350/0.0370 | 0.0624/0.0684/0.0726 |
| 10,000 | 20,000 | 25.524/27.940/28.791 | 21.680/21.969/23.985 | 257.862/264.115/301.672 | 0.2005/0.2052/0.2334 | 0.0247/0.0294/0.0342 | 0.0497/0.0517/0.0593 |
| 50,000 | 100,000 | 24.197/25.733/33.323 | 98.887/101.460/107.201 | 1529.5/1641.8/2825.6 | 0.2092/0.2220/0.3131 | 0.0300/0.0318/0.0336 | 0.0575/0.1646/0.1716 |
| 100,000 | 200,000 | 23.322/25.148/26.250 | 193.186/206.050/210.830 | 10896.8/12612.3/15265.0 | 0.2048/0.2132/0.2518 | 0.0314/0.0343/0.0357 | 0.1590/0.1669/0.1704 |

### 9.3 修后（NEW，5 轮 min/中位/max，单位 ms）

| N | 目录项 | LIST1k | LISTmiss | LISTall | put | head | get |
|---|---|---|---|---|---|---|---|
| 1,000 | 1,001 | 0.486/0.537/0.608 | 0.006/0.006/0.007 | 0.444/0.456/0.481 | 0.1669/0.1765/0.2112 | 0.0058/0.0064/0.0080 | 0.0370/0.0378/0.0388 |
| 10,000 | 10,001 | 0.329/0.356/0.411 | 0.005/0.005/0.005 | 3.472/3.657/3.838 | 0.1386/0.1493/0.1574 | 0.0027/0.0037/0.0045 | 0.0280/0.0319/0.0398 |
| 50,000 | 50,001 | 0.318/0.325/0.347 | 0.005/0.005/0.005 | 13.111/13.837/14.088 | 0.1456/0.1484/0.1572 | 0.0025/0.0026/0.0035 | 0.0322/0.0340/0.0369 |
| 100,000 | 100,001 | 0.193/0.195/0.200 | 0.004/0.005/0.005 | 25.189/25.739/26.599 | 0.1530/0.1586/0.1771 | 0.0024/0.0028/0.0032 | 0.0325/0.1461/0.1537 |

**列的含义**：`LIST1k` = `list(prefix="", maxKeys=1000)`；
`LISTmiss` = `list(prefix="zzz/")`（一个都不匹配 ⇒ 老实现被迫全目录扫描）；
`LISTall` = `list(maxKeys=不限)`。

### 9.4 结论（N = 100,000）

**倍数口径**：`中位` = 修前中位 ÷ 修后中位；`最好÷最好` = 修前最好值 ÷ 修后最好值
（两边都取各自最好的一轮 ⇒ 对「修后更快」这个结论最不利的一种读法）。

| 指标 | 修前中位 | 修后中位 | 倍数（中位） | 倍数（最好÷最好） |
|---|---|---|---|---|
| 桶目录项数 | 200,000 | 100,001 | — | 目录项**减半**（sidecar 没了） |
| LIST1k | 25.148 ms | 0.195 ms | **~129×** | ~121× |
| LISTmiss | 206.050 ms | 0.005 ms | **~4×10⁴** | ~5×10⁴ |
| LISTall | 12,612.3 ms | 25.739 ms | **~490×** | ~423× |
| put | 0.2132 ms | 0.1586 ms | 1.34× | 1.31× |
| head | 0.0343 ms | 0.0028 ms | ~12× | ~10× |
| get | 0.1669 ms | 0.1461 ms | 1.14× | 1.04× |

- **目录项减半**：`.meta` sidecar 不再写 ⇒ 桶目录里只剩 body。
- **LIST 三个口径全部从 O(N) 变成 O(页大小)**：`LISTmiss` 从 206ms 到 5µs，
  说明「扫全目录」这一整段已经不在路径上了。
  ⚠️ 这一行的倍数写两位有效数字就够：修后值只有几微秒，取 min(0.004) 还是
  中位(0.005) 会让倍数差 25%。**看绝对值更可靠**：206ms → 5µs。
- **LISTall 仍随 N 线性**（100k → 25.7ms，10k → 3.7ms）：这是**构造 10 万个
  `ObjectMeta` 包装对象的成本**，不是磁盘 IO。内存侧实现用 extractor 避免了同样的分配
  （见 §5.6），磁盘侧的 `ObjectMeta` 是 API 返回值，必须物化。
- put / head / get 的改善主要来自「少一次 `.meta` 的 open+read+parse」（head 尤其明显，
  10×）；get 只 1.04~1.14×，因为它的成本大头是**读 body**，那部分改不了。

### 9.5 受控 IO 对比（排除 page cache 冷热这个混淆变量）

显式把 body（+ 修前的 `.meta`）全部读一遍焐热 page cache，再测 head / get，各 3 轮交替。
数值按 `min / 中位 / max` 排：

| | 修前 | 修后 | 倍数（最好÷最好） | 倍数（中位） |
|---|---|---|---|---|
| head | 0.0638 / 0.1842 / 0.1994 ms | 0.0079 / 0.0084 / 0.0086 ms | **8.1×** | ~22× |
| get | 0.0794 / 0.2137 / 0.2109 ms | 0.0384 / 0.0384 / 0.0395 ms | **2.1×** | ~5.6× |
| 焐热耗时 | 5,139 / 10,249 / 11,644 ms（114.5 MB） | 2,593 / 2,595 / 2,729 ms（102.4 MB） | 2.0× | ~4.0× |

- head 快是因为**不再 open+read+parse `.meta`**，直接查内存跳表。
- get 只快 2.1×（最好÷最好）—— 因为 get 的主要成本是**读 body**（127 KB～4.8 MB），
  那部分改不了。
- 焐热读到的字节数从 114.5MB 降到 102.4MB —— 差的 12MB 就是那 48 个 `.meta` sidecar。

### 9.6 分页修好的判据

基准里每次 LIST 后拿第 1 页 + 它的 token 去查第 2 页，比对两页的 key 集合：

| | 第 2 页 vs 第 1 页 |
|---|---|
| 修前 | 5 轮**全部** `SAME`（两页完全相同 ⇒ token 根本没被引用） |
| 修后 | 5 轮**全部** `ovl=0`（零重叠） |

### 9.7 一处需要单独说明的现象

修后 N=100,000 的 `get` 从第 3 轮起从 0.037ms 变成 0.15ms，而 N=50,000 稳定在
0.032ms ⇒ 是 **page cache 工作集装不下 100MB**（macOS 默认动态管理），
**与实现无关** —— 修前同样受影响（修前 N=100k 的 get 也是 0.16ms 量级）。
所以 get 的结论以 §9.5 的受控焐热对比为准。

---

## 10. 真实桶验证：`user-uploads` / `photos`

用户在 `edap-container-parent` 下观察到的现象：`photos` 里所有文件平铺在一个目录。
那不是「桶」—— `S3UrlMapping` 里 `root = Path.of("./")`、桶名 `user-uploads`，
`photos` 是**桶内的 key 前缀**（`photos/<uuid>.jpg`）。

真实布局（改造前）：

```
edap-container-parent/user-uploads/
├── .acl
├── hello.txt
├── hello.txt.meta
└── photos/
    ├── <uuid>.jpg        ← 47 个 body
    └── <uuid>.jpg.meta   ← 47 个 sidecar（每个 ~120B）
```

把这份真实桶**复制一份**到 `/tmp`（不动原数据），分别用修前 / 修后实现跑：

### 10.1 修前：47 张照片**全部隐形**

```
OLD list(all, maxKeys=1000)      ->   1 keys    0.386 ms      ← 只有 hello.txt
OLD list(prefix=photos/)          ->   0 keys    0.168 ms
OLD 可见的 key:  hello.txt
```

根因就是 bug #2：`Files.newDirectoryStream(dir, "*.meta")` **不递归** ⇒
`photos/` 子目录里的 47 个 sidecar 根本不在扫描范围内。
**这 47 张照片在 S3 LIST 里从来就没出现过。**

点查本身是好的（body 在磁盘上）：

```
OLD head(photo)   -> 0.1758 ms
OLD get(photo)     -> 0.3264 ms
```

### 10.2 修后：迁移一次，全部可见

```
NEW init(迁移+回放)              ->    45.4 ms     ← 48 个 .meta 读解析 + 建索引
NEW list(all, maxKeys=1000)      ->   1 contents, 1 commonPrefixes, keyCount=2   0.050 ms
NEW list(prefix=photos/)          ->  48 keys      0.141 ms
NEW head(photo)                   ->  0.0175 ms
NEW get(photo, 127KB)              ->  0.1268 ms
```

- `list(all, delimiter="/")` ⇒ contents=[`hello.txt`]、commonPrefixes=[`photos/`]、
  keyCount=2 —— 这是**标准 S3 语义**：`photos/` 下面的对象被聚合成一个公共前缀。
- `list(prefix="photos/")` ⇒ 48 个 key 全在（47 jpg + 1 jpeg）。
- 迁移耗时 45.4ms（48 个对象）—— 10 万对象的桶约 10s，一次性。

### 10.3 `.meta` sidecar 现在可以安全删掉了

删掉全部 48 个 `.meta` 之后，同一份目录：

| | 修前实现 | 修后实现 |
|---|---|---|
| `list(all)` | 0 keys | 1 contents + 1 commonPrefixes |
| `list(prefix=photos/)` | **0 keys** | **48 keys** |
| `head(photo)` | **抛 NoSuchKey** | 0.019 ms，正常 |
| `photos/` 目录项数 | 48（只删了 sidecar 后） | 48 |

修前实现删了 `.meta` 就**彻底看不见对象**（元数据就在 sidecar 里）——
这就是 §5.1「元数据唯一真源 = 索引 log」的直接后果：
**sidecar 从「必需」变成「冗余」**。

### 10.4 对「不分文件夹效率低」这个直觉的结论

- **点查（GET / HEAD）不受目录项数影响**：实测 head 在 47 个对象的目录里
  0.0175ms；§9.4 里 10 万对象的桶里 head 也是 0.0028ms —— 目录索引是平衡树，
  点查是常数。
- **真正随目录项数线性增长的是 LIST**（扫目录项）。现在 LIST 走内存索引，
  不扫目录 ⇒ 与目录项数无关。
- **「分文件夹」不是解法**：§1.3 实测哈希分片零收益（单目录 216.9ms vs
  256 片 220.6ms）。而且 key 里的 `/` 在 S3 语义里只是普通字符，
  不代表目录结构 —— 强行按 `/` 分目录会把 LIST 的语义搞坏。
- **该做的是**：① 元数据收敛到索引（已做，LIST 变 O(页)）；
  ② 不再写 sidecar（已做，目录项减半）；③ 老桶的 sidecar 离线清理（可安全删，见 §10.3）。

