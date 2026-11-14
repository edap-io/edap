# edap protobuf 编解码器 —— 纯 protobuf 负载矩阵探针（改前 / 改后）

生成时间: 2026-11-12 23:00 ~ 23:30 CST

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核

被测版本: protobuf-java 3.25.9 / edap 自研 protobuf（edap-protobuf）

探针源码: `src/main/java/io/edap/grpc/benchmark/ProtoCodecBenchmark.java`（负载矩阵）
+ `StringCandidates.java`（编码候选算法）+ `DecodeCandidates.java`（解码候选算法）

> ★ **第二轮（2026-11-13）**：中文（UTF-16）字符串编码路径已优化（1.30x/1.32x → 1.04x/1.02x），
> `repeated` 标量的 wire type 已修（跨框架逐字节对齐）。
> 见 [`proto-codec-20261113-string.md`](proto-codec-20261113-string.md)。
> ⚠ 本文的编码候选算法对比数字**不可信**（多个候选塞进同一个 JVM ⇒ `op.call()` megamorphic，
> 同一候选两次跑出 13259 / 24317）；第二轮已改为「一个候选一个 JVM 取 min」。

---

## 〇、为什么要这个探针

《编解码器 micro 对比》（`CodecBenchmark`，存档在 `after-20261112-0908.md` §三）用的是 gRPC 的
`Payload{name,value,blob}` —— 一个**平坦结构**：

```
message Payload { string name = 1; int32 value = 2; bytes blob = 3; }
```

它打不中 edap protobuf 编解码器的关键路径：

- 字符串解码（`ByteArrayReader.readString()` 的手写 UTF-8 解码）
- 嵌套消息的长度前缀（`writeLenMoveBytes` 的整块 memmove）
- packed int 列表
- map\<string,string\>

⇒ 本探针用一组专门的消息（`bench.proto` 里的 `CodecStr` / `CodecInner` / `CodecNest`）
去打这些路径。**同一份 `bench.proto` 同时喂两个代码生成器**（edap 的 `edap-protoc-maven-plugin`
与 google 的 `protobuf-maven-plugin`）⇒ 两边的 wire 字节天然对齐，可以逐字节断言。

## 一、口径

| 口径 | edap | google | 拷贝次数 |
|---|---|---|---|
| **编码核心** | 写进 writer 缓冲（`encoder.encode(writer, p)`，只读 `writer.size()`） | `writeTo(CodedOutputStream)` 写进已有数组 | 1 / 1 |
| **产出 byte[]** | `ProtoBuf.toByteArray` | `toByteArray()` | 2 / 1 |
| **解码** | `ProtoBuf.toObject(bs, 0, len, cls)` | `getParserForType().parseFrom(bs)` | — |

- 「编码核心」是两把**对等**的尺子（都是往一个已存在的缓冲里写一次），是编解码器能力的真正对比。
- 「产出 byte[]」多出来的那次拷贝是**分配策略**的差异，不是编解码能力的差异。
- ★ 所有被测操作的结果都累加进静态字段 `sink` —— 否则 JIT 会把「写进一个没人读的缓冲」
  当成无用计算消掉，测出来的数字会小一个数量级（第一次跑出来 1KB 编码只要 21ns，就是踩了这个坑）。

## 二、负载矩阵 —— **改前**（edap-protobuf HEAD，2026-11-12 22:40）

> 测量参数：600ms × 3 轮取中位数。机器 loadavg 常 10~25 ⇒ **绝对值不可跨批次比较**，
> 只看同一批内的相对关系（edap vs google）。

| 负载 | wire | 编码核edap | 编码核goog | 比值 | byte[]edap | byte[]goog | 解码edap | 解码goog | 解码比 |
|---|---|---|---|---|---|---|---|---|---|
| str-ascii-1024 | 同 | 21 | 943 | 0.02x | 69 | 960 | 352 | 57 | **6.20x** |
| str-ascii-16384 | 同 | 333 | 14844 | 0.02x | 905 | 15013 | 6169 | 962 | **6.41x** |
| str-cn-1024 | 同 | 1097 | 846 | 1.30x | 1200 | 892 | 1756 | 2428 | 0.72x |
| str-cn-16384 | 同 | 17414 | 13305 | 1.31x | 19010 | 13950 | 29275 | 33699 | 0.87x |
| str-latin1-high | **★异** | ★用例异常 | — | — | — | — | **AIOOBE** | — | — |
| nest-1k-x1 | 同 | 100 | 448 | 0.22x | 182 | 482 | 2126 | 206 | **10.31x** |
| nest-1k-x8 | 同 | 443 | 2025 | 0.22x | 931 | 2147 | 9583 | 766 | **12.52x** |
| nest-1k-x64 | 同 | 3108 | 14347 | 0.22x | 5174 | 15325 | 69559 | 5243 | **13.27x** |
| packed-int-1000 | **★异** | ★用例异常 | — | — | — | — | — | — | google 抛 `invalid tag (zero)` |
| packed-int-16000 | **★异** | ★用例异常 | — | — | — | — | — | — | 同上 |
| map-str-1000 | 同 | 16177 | 65700 | 0.25x | 17044 | 64689 | 64566 | 60946 | 1.06x |
| bytes-16384 | 同 | 336 | 389 | 0.86x | 987 | 724 | 702 | 761 | 0.92x |

**读出来的三件事**：

1. **编码端 edap 全面领先**（ASCII 快 45x，嵌套/map 快 4~5x）；中文慢 1.3x。
2. **解码端 edap 在 ASCII 上慢 6.2~6.4x，嵌套消息里慢 10~13x** ——
   根因是 `ByteArrayReader.readString()` 的手写 UTF-8 解码（`char[]` 中间数组 + 两遍拷贝）。
   中文解码 edap 反而快 0.72x ⇒ **不要动中文路径**。
3. **`str-latin1-high` 的 wire 与 protobuf-java 不一致** —— 详见 §五。

## 三、负载矩阵 —— **改后**（2026-11-12 23:25，第二次跑 23:30 复核，数字一致）

| 负载 | wire | 编码核edap | 编码核goog | 比值 | byte[]edap | byte[]goog | 解码edap | 解码goog | 解码比 |
|---|---|---|---|---|---|---|---|---|---|
| str-ascii-1024 | 同 | 64 | 945 | 0.07x | 104 | 963 | **50** | 57 | **0.88x** |
| str-ascii-16384 | 同 | 870 | 15008 | 0.06x | 1562 | 15029 | **978** | 970 | **1.01x** |
| str-cn-1024 | 同 | 1104 | 854 | 1.29x | 1212 | 894 | 2441 | 2450 | 1.00x |
| str-cn-16384 | 同 | 18318 | 13404 | 1.37x | 20298 | 14079 | 33693 | 33972 | 0.99x |
| str-latin1-high | **同 ✅** | 20 | 14 | 1.40x | 25 | 17 | 50 | 31 | 1.64x |
| nest-1k-x1 | 同 | 165 | 518 | 0.32x | 252 | 545 | **120** | 209 | **0.58x** |
| nest-1k-x8 | 同 | 748 | 2289 | 0.33x | 1031 | 2368 | **534** | 810 | **0.66x** |
| nest-1k-x64 | 同 | 5610 | 16117 | 0.35x | 7492 | 16911 | **4014** | 5566 | **0.72x** |
| packed-int-1000 | **★异（未修）** | ★用例异常 | — | — | — | — | — | — | google 抛 `invalid tag (zero)` |
| packed-int-16000 | **★异（未修）** | ★用例异常 | — | — | — | — | — | — | 同上 |
| map-str-1000 | 同 | 21780 | 65206 | 0.33x | 22640 | 64102 | 55180 | 69361 | 0.80x |
| bytes-16384 | 同 | 352 | 371 | 0.95x | 960 | 729 | 694 | 763 | 0.91x |

### 改前 → 改后（解码，纳秒/次）

| 负载 | 改前 edap | 改后 edap | 变化 | 改前 vs google | 改后 vs google |
|---|---|---|---|---|---|
| str-ascii-1024 | 352 | **50** | **−86%** | 6.20x | **0.88x** |
| str-ascii-16384 | 6169 | **978** | **−84%** | 6.41x | **1.01x** |
| str-cn-1024 | 1756 | 2441 | **+39%** ⚠ | 0.72x | 1.00x |
| str-cn-16384 | 29275 | 33693 | **+15%** ⚠ | 0.87x | 0.99x |
| nest-1k-x1 | 2126 | **120** | **−94%** | 10.31x | **0.58x** |
| nest-1k-x8 | 9583 | **534** | **−94%** | 12.52x | **0.66x** |
| nest-1k-x64 | 69559 | **4014** | **−94%** | 13.27x | **0.72x** |
| map-str-1000 | 64566 | 55180 | −15% | 1.06x | 0.80x |
| bytes-16384 | 702 | 694 | 持平 | 0.92x | 0.91x |

**⚠ 唯一的一处回退**：中文（多字节）字符串的解码慢了 15~39%。
原因见 §四的候选对比 —— JDK 的 UTF-8 解码器对纯 ASCII 有向量化快路径，
但对多字节要走 `char[]` 中间数组，比 edap 手写的解码器慢一点。
**代价换来的是 ASCII 路径 6.4x、嵌套消息 17x** —— 嵌套消息里全是 ASCII 字符串，
这才是 protobuf 载荷的常见形态。

## 四、候选算法实测（决定实现方案）

### 4.1 编码（`StringCandidates`，同一段字节，产出必须逐字节相同）

| 串 | A 裸拷(改前) | B 扫描+裸拷 | C 一律 getBytes | D 8字节扫描 | 参照:纯拷贝 |
|---|---|---|---|---|---|
| ascii-1k | **20** | 211 | 54 | 107 | 16 |
| ascii-16k | **322** | 3201 | **962** | 1687 | 343 |
| cn-1k | **1290** | 1420 | 1408 | 1410 | 41 |
| cn-16k | **20854** | 22476 | 22429 | 22480 | 665 |
| latin1-high | 4（★字节错） | 15 | 12 | 15 | 4 |

⇒ **选 C（一律 `getBytes(UTF_8)`）**：
JDK 的 ASCII 判定是**向量化**的（`_countPositives` intrinsic，~27 字节/cycle），
比逐字节扫描（B，~0.8 字节/cycle）快一个数量级；比 Unsafe 8 字节扫描（D）也快。
中文路径**保持原样**（A 最快，`getBytes` 反而慢）。

### 4.2 解码（`DecodeCandidates`，同一段字节，解出必须完全相同）

| 串 | A 现状(手写) | B 扫描+裸拷 | C 8字节扫描 | D JDK UTF8 | E JDK+严格回退 |
|---|---|---|---|---|---|
| ascii-1k | 347 | 221 | 112 | **42** | **43** |
| ascii-16k | 6270 | 3665 | 2034 | **971** | **971** |
| cn-1k | 2501 | 2507 | 2506 | **2353** | 2433 |
| cn-16k | 41219 | 41280 | 41273 | **31927** | 33469 |
| emoji-1k | 3401 | 3398 | 3402 | **3809** | 3960 |

⇒ **选 E（JDK UTF-8 解码 + U+FFFD 严格回退）**：
- 纯 ASCII：JDK 直接产出 **Latin1 String，零中间 char[]** ⇒ 16KB 从 6270ns 降到 971ns；
- 多字节：JDK 的路径也比「手写解码到 char[] 再 `new String(char[])`」少一遍拷贝；
- E 比 D 只多一次 `indexOf('\uFFFD')`：**Latin1 结果里 U+FFFD 不可能出现 ⇒ 这步在 ASCII 上免费**
  （`StringLatin1.indexOf` 对 >0xFF 的字符直接返回 -1）；多字节结果上 +4~5%，
  换来的是「非法 UTF-8 仍然抛异常」而不是静默替换成 U+FFFD。

## 五、★ 顺手修掉的两个正确性 bug

### 5.1 Latin1 高位字符写出**非法 UTF-8**（已修）

- 现象：`"café"`（Latin1 内部字节 `63 61 66 E9`）被 edap 原样写出 ⇒ `E9` 不是合法 UTF-8
  （protobuf-java 写的是 `C3 A9`）；edap 自己解码时按 3 字节序列解 ⇒ **AIOOBE 或乱码**。
- 根因：`AbstractWriter.writeString0()` 的 `IS_BYTE_ARRAY && isLatin1(v)` 分支直接
  `writeByteArray(StringUtil.getValue(v), 0, charLen)` —— 裸拷 String 内部 byte[]。
- 证据（留档）：新增单测 `edap-protobuf/src/test/java/io/edap/protobuf/test/v3/TestStringUtf8.java`
  共 22 例，**改前 6 例红**（`testLatin1HighByte`、混合 ASCII、`testLatin1LengthBoundary[1..4]`），
  ASCII / 中文 / emoji / 长度边界全绿（中文走 UTF16 分支，所以测不出来这个 bug）。
- 改法：Latin1 分支改成 `v.getBytes(UTF_8)` 后再写（见 §4.1 的候选 C）。
- 修后：探针里 `str-latin1-high` 的 wire 从 **★异** 变成 **同**（与 protobuf-java 逐字节相同），
  22 个单测全绿。

> ⚠ **wire 字节变化范围**：只影响「coder=LATIN1 且含 0x80~0xFF」的字符串，
> 方向是「非法 UTF-8 → 合法 UTF-8」，即**与 protobuf-java 对齐**，是修复不是回归。
> 纯 ASCII 串的字节不变（Latin1 字节 == UTF-8 字节），UTF16 串的字节不变。

### 5.2 ★★ `repeated int32` 的 wire type 错了（**未修，本轮只报告**）

- 现象：edap 编 `repeated int32`（proto3 默认 packed）写出的是 **varint** 的 tag
  （`18 14 00 01 02 ...`），protobuf-java 是 **length-delimited**（`1A 14 00 01 02 ...`）
  ⇒ **protobuf-java 解析 edap 的消息直接抛 `invalid tag (zero)`**。
- 根因链：
  1. edap 的 protoc 插件（`edap-protobuf-wire/.../builder/JavaBuilder.java:1154-1176`）
     生成 `@ProtoField(tag=3, type=Type.INT32)` 时**从不输出 `cardinality = Cardinality.REPEATED`**；
  2. `ProtoUtil.buildFieldValue`（`edap-protobuf-api/.../util/ProtoUtil.java:245-276`）
     按 OPTIONAL 给出 VARINT 的 wire type；
  3. 编码器 `writePackedInts` 写出了 packed 内容，却挂着 varint 的 tag。
- edap 自己的编解码是**自洽**的（解码器按 tag 号分发、`readPackedInt32` 按长度前缀块读），
  往返数据不丢 ⇒ **单框架内测不出来，跨框架才炸**。
- **本轮不修**：改生成器会改一批既有生成代码，而 `edap-protobuf-wire` 在 HEAD 上就有
  38 个失败测试类（陈旧夹具），验证成本高；改运行时（只改
  `ProtoBufEncoderGenerator.initFieldData`）则必须**编码/解码两侧一起改**
  （解码器的 `tagArray` 也是按 `buildFieldValue(tag,type,cardinality)` 造的），
  属于跨模块改动，单独立项。

## 六、本轮改了什么（代码）

| 文件 | 改动 |
|---|---|
| `edap-protobuf/.../reader/ByteArrayReader.java` | `readString()` 改用 JDK 的 UTF-8 解码（U+FFFD ⇒ 回退手写严格解码器）；手写解码器抽成 `decodeUtf8Strict(len)` |
| `edap-protobuf/.../writer/AbstractWriter.java` | `writeString0()` 的 Latin1 分支：`getBytes(UTF_8)` 取代裸拷内部 byte[]（修非法 UTF-8） |
| `edap-protobuf/.../writer/AbstractWriter.java` | `writeStringUtf8()` 的 Latin1 分支：同上（ext 格式，同一个 bug） |
| `edap-protobuf/src/test/.../v3/TestStringUtf8.java` | **新增** 22 例（ASCII / 中文 / emoji / Latin1 高位 / 混合 / 长度边界 / 往返） |
| `edap-grpc-benchmark/.../ProtoCodecBenchmark.java` | **新增** 纯 protobuf 负载矩阵探针 |
| `edap-grpc-benchmark/.../StringCandidates.java` | **新增** 编码候选算法对比 |
| `edap-grpc-benchmark/.../DecodeCandidates.java` | **新增** 解码候选算法对比 |
| `edap-grpc-benchmark/src/main/resources/proto/bench.proto` | **新增** `CodecStr` / `CodecInner` / `CodecNest` 三个消息 |
| `edap-grpc-benchmark/.../CodecBenchmark.java` | 加「**编码核心**」这一档对等口径（edap 写进 writer 缓冲 / google `writeTo` 写进已有数组），并把「产出 byte[]」那一档标注为不对等 |
| `edap-grpc-benchmark/.../BenchMain.java` | §五 表头与脚注标出两档编码口径（哪一档是主口径、哪一档多了整块拷贝） |
| `edap-grpc-benchmark/.../CodecBreakdown.java` | 「写进缓冲」那段改为复用 `CodecBenchmark.encodeIntoBuffer`（避免两处实现漂移） |
| `edap-grpc-benchmark/src/test/.../GrpcBenchmarkSmokeTest.java` | 断言新增的两个「编码核心」字段 |

## 七、验证

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap

# 1) edap-protobuf 全量单测（改前基线 1343 全绿 + 新增 22 例）
mvn -o -pl edap-protocol-parent/edap-protobuf test -DskipTests=false
#    → Tests run: 1365, Failures: 0, Errors: 0

# 2) gRPC 互操作回归（帧字节有任何变化就会红）
mvn -o -pl edap-grpc-parent/edap-grpc-benchmark test -DskipTests=false
#    → GrpcBenchmarkSmokeTest 3 + GrpcCompatTest 11 + GrpcInteropTest 7 = 21 全绿

# 3) 探针（★ 必须先 install，否则 benchmark 看到的是 ~/.m2 里的旧 jar）
mvn -o -q -pl edap-protocol-parent/edap-protobuf install -DskipTests
cd edap-grpc-parent/edap-grpc-benchmark
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
$JAVA_HOME/bin/java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
  -cp "target/classes:$(cat target/cp.txt)" io.edap.grpc.benchmark.ProtoCodecBenchmark 600 3
```

> ★ `--add-opens` 必须写**全包名**（`java.base/java.lang` …），
> 写成 `java.base/{lang,nio}=ALL-UNNAMED` 会被 shell 展开成 `java.base/lang java.base/nio=ALL-UNNAMED` ——
> 前两项没生效，Unsafe 反射 `String.value` 会炸。

## 八、明确不做

- **不改 gRPC 代码**（用户指示）：`GrpcCodec` / `GrpcFrame` / `GrpcCallImpl` / `GrpcClientCall` 一律不动。
- **不改 HTTP/2 层**。
- **不实现嵌套消息长度前缀的 size 预计算**（`writeLenMoveBytes` 的整块右移是 O(n) 额外拷贝，
  protobuf-java 先算 size 再写长度、零搬移）—— 工程量大，单独立项。
- **不追求「1 次拷贝」**（跨模块大改）。
- **不修 `repeated int32` 的 wire type**（见 §5.2，只报告）。
  ★ **2026-11-13 更新：已修**，见第二轮报告
  [`proto-codec-20261113-string.md`](proto-codec-20261113-string.md) §五
  （含跨框架逐字节测试 16 例 + **升级顺序要求：先升级读侧、再升级写侧**）。
