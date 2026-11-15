# edap protobuf：标准版 vs 改进版（FAST）编解码性能对比

> 测量日期：2026-11-14
> - **基线（改前）**：改进版 UTF16 串解码走 `new String(bytes, UTF_16LE)`
> - **改后 1**：UTF16 串解码改走 `StringUtil.fastInstance`（见第四节 4.1）
> - **改后 2**：端序方案落地 ——「charset 标记携带发送方字节序」（见第六节）
> - **改后 3**：补 `expand(fieldData.length)` —— 修掉缓冲写满时的越界（见第七节 7.3）
> - **本轮（改后 4）**：**无代码改动**；负载扩到 **19 个**（新增「中文占比扫描」6 个），
>   并把「混排体积」的口径从固定比例改成**按中文占比的函数**（见 5.2.1）、
>   定位了标准版读端的「一字悬崖」（见 5.3）
>
> 被测方法：`io.edap.protobuf.ProtoBuf`
> - 编码：`toByteArray(Object)`（标准版） vs `fastToByteArray(Object)`（改进版 / `CodecType.FAST`）
> - 解码：`toObject(byte[], Class)`（标准版） vs `fastToObject(byte[], Class)`（改进版）
>
> ⚠️ **两条路径的 wire 格式不同**：标准版是 protobuf 标准格式（UTF-8 字符串 + LENGTH 分帧嵌套），
> 改进版是 edap 自己的格式（charset 标记 + 裸字节字符串 + GROUP 分帧嵌套）。
> 改进版**不能**与 protobuf-java / 标准版互相解码 ⇒ 只能做「同一语义对象上的时间与体积」对比。

---

## 一、结论摘要

| 结论 | 证据 |
|---|---|
| **改进版在「纯中文」上碾压式赢**：编码快 **7.7~9.0×**，解码快 **34.9~38.4×**，体积小 **33%** | `cn-1k` / `cn-16k` |
| **改进版在「Latin1 高位字符」上碾压式赢**：编码快 **13.0×**，解码快 **45.0×**，体积小 20% | `latin1-high-1k` |
| **改进版在「内嵌消息」上稳定赢**：编码 **1.04~1.78×**、解码 **1.19~1.49×**（内层含中文的 `nest-cn-1k-x8` 另有 7.5× / 39.5×），且完全不受长度前缀阈值影响 | `nest-*` 四个负载 |
| **改进版在「中英混排」上编码赢 4.6~10.3×、解码赢 10.8~35.6×** | `mix-cn10` ~ `mix-90` / `mix-1cn` |
| ★ **混排的体积不是固定比例，而是「中文占比」的函数，交叉点正好在 50%**：中文 <50% 时改进版更大（最多 2.00×），中文 >50% 时改进版更小（最省 0.71×） | 第五节 5.2 |
| **改进版在 ASCII 上只赢 17~27%**（多 1 字节 charset 标记） | `ascii-1k` / `ascii-16k` |
| **对照路径（packed int / bytes）完全持平**（0.99~1.01×），**没有牺牲其它路径换收益** | `packed-int-16k` / `bytes-16k` |
| **map 编码赢 11%、解码赢 32%，但体积大 9%** | `map-1k` |
| ★ **UTF16 串解码换实现带来 25~28 倍提速**，且**未动其它任何路径** | 第四节 4.1 |
| ★ **标准版读端有个「一字悬崖」**：串里只要出现一个非 Latin1 字符，JDK 会把前面那段 ASCII **重拷一遍** ⇒ 解码成本随「第一个中文的位置」线性增长 | 第五节 5.3 |
| ★ **端序问题已解决**：跨端序不再静默乱码，同端序零成本 | 第六节 |
| ★ **单测无回归**：`edap-protobuf` 1419 个测试全绿；`edap-protobuf-wire` 的失败类清单与 HEAD 干净树**逐类一致** | 第八节 |
| ★ **缓冲写满时的越界已修**（`writeFieldData` 前补 `expand`），零性能代价 | 第七节 7.3 |

一句话：**改进版的收益来自「按 String 的 coder 选编码」和「嵌套消息改用 GROUP 分帧」；
唯一实质代价是 —— 只有「英文占多数、里面混了少数中文」的串，体积才会比 UTF-8 大
（中文占比 ≥50% 时改进版反而更小，补充平面字符两边完全抵消）——
而且这类串恰恰是改进版解码优势最大的地方；
在「内网 RPC、速度优先」的定位下，这个体积代价已决策接受（见 9.1）。**

---

## 二、测量口径与方法学

| 项 | 值 |
|---|---|
| 机器 | Apple M1 Max（10 核） / macOS Darwin 27.2.0 arm64 |
| JDK | OpenJDK 17.0.20.1（Homebrew） |
| 机器负载 | 测量期间 load average 22~25（**高负载**）⇒ 必须靠下面的方法学抵消 |
| 每项测量 | 300ms × 3 轮，**取 min** |
| 隔离 | **每个 (负载, 候选) 一个独立 JVM**，共 19×4×3 = **228 个 JVM** |
| launch | 3 轮，**候选顺序按 launch 轮转**（第 l 轮候选表左移 l-1 位），最终取所有 launch 的 min |

### 为什么这么测（每条都是踩过的坑）

1. **一个候选一个 JVM** —— 多个候选同 JVM 会让调用点变成 megamorphic，且 HotSpot 会把 C2 编译成
   「针对上一个负载的分支形态」后不再为新负载重编译 ⇒ 数字里混着 JIT profile 污染。
2. **多 launch 取 min + 候选顺序轮转** —— 本机持续高负载（load 22+）且会降频/升温，
   「排在后面的候选」会被系统性压慢（实测同份代码跨位置差 1.54 倍）⇒ 每个候选都要在不同位置各测一次。
3. **JVM 必须带 `--add-opens java.base/java.lang`（+ `java.nio` / `sun.nio.ch` / `java.util`）** ——
   不开 `java.lang` ⇒ `StringUtil` 静态初始化炸、`IS_BYTE_ARRAY=false`
   ⇒ 标准版的「纯 ASCII 直拷内部 byte[]」快路径被关掉 ⇒ **标准版被测得人为偏慢**；
   不开 `java.nio` ⇒ `UnsafeUtil` 静态初始化直接抛 `AssertionError`。
4. **★ 先做 round-trip 自检再计时** —— `toByteArray`/`fastToByteArray` 把 `EncodeException` 吞掉返回 **null**，
   `toObject`/`fastToObject` 把任何异常也吞掉返回 **null**。
   ⇒ 一个坏掉的改进版路径会表现为「快得离谱」而不是报错。
5. **★ 计时结束后再验一次内容** —— 改进版的 UTF16 串走 `StringUtil.fastInstance`：
   `Unsafe.allocateInstance(String.class)` + `Unsafe.putByte(coder)` + `Unsafe.putObject(value)`，
   **绕过了 String 的 final 字段语义** ⇒ 理论上存在被 JIT 常量折叠成 null/0 的风险。
   启动时那次自检还在解释执行阶段，验不出这个问题 ⇒ 计时跑完（JIT 已编译）必须再验一次。
6. **防死代码消除** —— 所有被测结果累加进静态 `sink` 字段。
7. **预热 500 次** —— FAST 的 encoder/decoder 是首次调用时用 ASM 现场生成的。
8. ★ **探针本身也要「一个测点一个 JVM」** —— 这是本轮踩到的最隐蔽的一个坑。
   同一个 `bench()` 被多个不同 lambda 调用 ⇒ `Task.run()` 调用点变成 megamorphic
   ⇒ lambda 体被单独编译 ⇒ `String.<init>` 被内联进去 ⇒ 逃逸分析发现
   **解码出来的 String 不逃逸** ⇒ **把整段字节级解码当成无用计算优化掉**。
   实测同一个 `mix-1cn` 负载：**同 JVM 多测点报 6881 ns，一个测点一个 JVM 报 27277 ns（差 4 倍）**。
   ⇒ 所有定位用探针（`Utf8DecodeProbe` / `EscapeProbe` / `DecodePathProbe` /
   `NonLatin1PositionProbe`）都改成「一个测点一个 JVM」，并且**必须给被测的 String 一个
   逃逸锚点**（写进 `static volatile Object esc`），否则 EA 会把解码干掉。
   ★ `-XX:-DoEscapeAnalysis` 下数字不变 ⇒ 这是**调用点形态**问题，不是 EA 本身的问题。

### 复现

```bash
cd edap-protocol-parent/edap-protobuf/src/test/java/io/edap/protobuf/benchmark
./run-fast-vs-standard.sh 300 3 3          # 毫秒 / launch 轮数 / 轮内轮数
KEEP=1 ./run-fast-vs-standard.sh 300 3 3   # 保留每个 JVM 的原始输出
java ... io.edap.protobuf.benchmark.FastEndianCheck   # 端序一致性自检
```

代码：`FastVsStandardBench.java`（单候选测量器）、`BenchPayloads.java`（负载矩阵）、
`FastEndianCheck.java`（端序自检）、`BenchStr/BenchInner/BenchNest.java`（模型）、
`run-fast-vs-standard.sh`（驱动脚本）。

**定位用探针**（都在同目录，都遵循「一个测点一个 JVM」）：

| 探针 | 作用 |
|---|---|
| `Utf8DecodeProbe.java` | 拆开标准版 `readString()` 的三步（JDK 解码 / `indexOf('\uFFFD')` 全文扫描 / 严格解码）逐段计时 |
| `EscapeProbe.java` | 验证「解码成本与逃逸、与调用形式无关」—— 4 个变体（整串/带偏移 × 逃逸/不逃逸）各一个 JVM |
| `DecodePathProbe.java` | 定位 `toObject` / `readString` / `new String` 三段成本，看额外开销有多少 |
| `Mix1cnByteProbe.java` | 逐字节比对 wire payload 与 `s.getBytes(UTF_8)`，并查解码结果里有没有 U+FFFD |
| `NonLatin1PositionProbe.java` | 只挪动「中文在串里的位置」，验证标准版解码成本随位置线性增长 |

> 脚本会**一并编译 edap-common** 并把它的 `target/classes` 放在 classpath 最前面 ——
> FAST 的字符串路径依赖 `StringUtil.fastInstance` / `NATIVE_LITTLE_ENDIAN` / `swapUtf16ByteOrder`，
> 用 m2 里可能陈旧的 jar 会测到旧实现。

### 可复现性

五轮完整矩阵之间，头部数字的差异 ≤ 5%；
**最小的几个负载（几十~几百 ns）轮间能差 20%**，这是本机高负载下的噪声 ——
判据是「未被改动的路径是否同步漂移」：`ascii-1k` fast-dec（本次完全没碰的路径）
两轮之间就差了 20%（41.6 → 50.1 ns），与 `cn-1k` fast-dec 的 61.1 → 73.6 ns 同量级。

| 候选 | 改后 1（第一轮） | 改后 1（第二轮） | 改后 2（端序方案后） | 改后 3（expand 修复后） | 改后 4（本轮，19 负载） |
|---|---|---|---|---|---|
| `cn-1k` fast-enc | 126.9 ns | 123.7 ns | 126.8 ns | 124.0 ns | **123.8 ns** |
| `cn-1k` fast-dec | 61.1 ns | 61.9 ns | 73.6 ns | 65.4 ns | **63.9 ns** |
| `cn-16k` fast-dec | 979.8 ns | 981.7 ns | 1029.9 ns | 977.9 ns | **970.5 ns** |
| `mixed-16k` fast-dec | 1096.3 ns | 992.6 ns | 977.4 ns | 967.1 ns | **993.8 ns** |
| `nest-cn-1k-x8` fast-dec | 579.7 ns | 660.2 ns | 660.2 ns | 659.4 ns | **567.4 ns** |
| `nest-20k-x4` std-enc | 9828.1 ns | 9761.5 ns | 9659.3 ns | 9993.5 ns | **9668.5 ns** |
| `packed-int-16k` fast-dec | 66949.1 ns | 66340.8 ns | 66878.3 ns | 68701.4 ns | **67539.0 ns** |

---

## 三、结果（当前代码，纳秒/次，取所有 launch 的 min）

```
负载           | 编码std(ns) 编码fast(ns)   比值 | 解码std(ns) 解码fast(ns)   比值 | 字节std 字节fast   体积
------------------------------------------------------------------------------------------------------------------
ascii-1k         |        81.1        69.3    1.17x |        53.2        44.4    1.20x |      1027      1028    1.00x
ascii-16k        |      1093.3       900.3    1.21x |       999.6       786.5    1.27x |     16388     16389    1.00x
cn-1k            |       948.9       123.8    7.66x |      2454.5        63.9   38.41x |      3075      2052    0.67x
cn-16k           |     15134.2      1678.6    9.02x |     33869.4       970.5   34.90x |     49156     32773    0.67x
mixed-16k        |      9816.1      1678.1    5.85x |     18257.6       993.8   18.37x |     22942     32773    1.43x
latin1-high-1k   |       847.5        65.1   13.02x |      1958.3        43.5   45.02x |      1283      1028    0.80x
mix-cn10         |      8014.5      1647.3    4.87x |     10556.6       975.5   10.82x |     19668     32773    1.67x
mix-cn25         |      9403.4      1668.4    5.64x |     18749.3       978.7   19.16x |     24588     32773    1.33x
mix-cn50         |     12715.3      1659.0    7.66x |     30572.2       971.2   31.48x |     32788     32773    1.00x
mix-cn75         |     16621.9      1645.4   10.10x |     34595.9       970.6   35.64x |     40988     32773    0.80x
mix-cn90         |     17245.8      1669.2   10.33x |     37599.9      1080.7   34.79x |     45896     32773    0.71x
mix-1cn          |      7685.7      1661.3    4.63x |     28811.6       986.6   29.20x |     16390     32773    2.00x
nest-64-x8       |       166.8       105.7    1.58x |       184.2       149.5    1.23x |       626       635    1.01x
nest-1k-x8       |       779.8       749.1    1.04x |       468.0       392.2    1.19x |      9284      9284    1.00x
nest-20k-x4      |      9668.5      5422.1    1.78x |      6181.8      4150.1    1.49x |    102446    102441    1.00x
nest-cn-1k-x8    |      8590.0      1144.6    7.50x |     22385.8       567.4   39.45x |     27716     18500    0.67x
packed-int-16k   |     21881.8     21753.4    1.01x |     66733.7     67539.0    0.99x |     31876     31876    1.00x
map-1k           |     16495.5     14823.1    1.11x |     45150.4     34180.6    1.32x |     21780     23780    1.09x
bytes-16k        |       905.1       896.2    1.01x |       718.3       717.1    1.00x |     16388     16388    1.00x
```

**比值 = 标准版 / 改进版，>1 表示改进版更快；体积 = 改进版字节数 / 标准版字节数，<1 表示改进版更小。**

### 三条一眼可见的规律

1. **改进版的时间与内容无关**：所有 `mix-*` 负载的 `fast-enc` 都落在 **1645~1669 ns**，
   `fast-dec` 都落在 **971~1081 ns** —— 因为改进版是「整串一刀切 memcpy」，
   **工作量只取决于字节数，与字符种类无关**。
2. **标准版的时间随中文占比单调上升**：`std-enc` 8014 → 9403 → 12715 → 16621 → 17245 ns，
   `std-dec` 10556 → 18749 → 30572 → 34595 → 37599 ns ⇒
   **改进版的优势随中文占比放大**（编码 4.87× → 10.33×，解码 10.82× → 34.79×）。
3. **体积是唯一有交叉点的指标**：`mix-cn50` 处改进版与标准版**完全打平**（32788 vs 32773），
   中文占比 <50% 时改进版更大，>50% 时更小 ⇒ 见第五节 5.2 的推导与实测。

---

## 四、三次改动的前后对照

> 第四节记的是**三次代码改动**；第三节的表是**第四轮测量**（负载扩到 19 个），
> 代码与「改后 3」完全一致 ⇒ 时间列与「改后 3」可比，wire 字节列也完全一致。

### 4.1 改后 1：UTF16 串解码改用 `StringUtil.fastInstance`

唯一的代码改动在 `ByteArrayFastReader.readString` 的 **UTF16LE 分支**：

```java
// 改前：JDK 的 UTF-16 解码（解一趟 + 压缩试探 + char[] 中间分配）
s = new String(_buf, tmpPos, len - 1, StandardCharsets.UTF_16LE);

// 改后：一次 arraycopy + Unsafe 直接把字节塞回 String 的 value 字段
byte[] sdata = new byte[len - 1];
System.arraycopy(_buf, tmpPos, sdata, 0, len - 1);
s = StringUtil.fastInstance(sdata, UTF16LE_BYTE);
```

**只影响含 UTF16 串（String coder = UTF16）的负载** ⇒ 非中文类负载的解码数字必须基本不动，
否则说明改动越界或测量噪声超标。

| 负载 | fast-dec 改前 | fast-dec 改后 | 提升 | 改动是否命中 |
|---|---|---|---|---|
| `cn-1k` | 1710.1 ns | **61.1 ns** | **28.0×** | ✅ UTF16 串 |
| `cn-16k` | 27810.7 ns | **979.8 ns** | **28.4×** | ✅ UTF16 串 |
| `mixed-16k` | 27629.5 ns | **1096.3 ns** | **25.2×** | ✅ UTF16 串 |
| `nest-cn-1k-x8` | 15966.1 ns | **579.7 ns** | **27.5×** | ✅ UTF16 串 × 8 |
| `ascii-1k` | 41.9 ns | 41.6 ns | 1.01× | ❌ Latin1 串（未改） |
| `ascii-16k` | 745.5 ns | 714.6 ns | 1.04× | ❌ Latin1 串（未改） |
| `latin1-high-1k` | 37.5 ns | 38.4 ns | 0.98× | ❌ Latin1 串（未改） |
| `nest-64-x8` | 164.1 ns | 162.3 ns | 1.01× | ❌ 无 UTF16 串 |
| `nest-1k-x8` | 387.2 ns | 384.6 ns | 1.01× | ❌ 无 UTF16 串 |
| `nest-20k-x4` | 4145.1 ns | 4146.4 ns | 1.00× | ❌ 无 UTF16 串 |
| `packed-int-16k` | 67135.8 ns | 66949.1 ns | 1.00× | ❌ 无串 |
| `map-1k` | 36296.0 ns | 34277.3 ns | 1.06× | ❌ Latin1 串（未改） |
| `bytes-16k` | 720.0 ns | 721.6 ns | 1.00× | ❌ 无串 |

**编码侧全部落在 ±5% 噪声内**（只改了解码路径）：最大偏差是 `ascii-1k` fast-enc（76.6 → 67.1 ns，−12%），
该项与改动无关，属 ~70 ns 量级小负载的轮间噪声。

**为什么快 28 倍**

- **改前** `new String(bytes, UTF_16LE)` 走 JDK 的 `StringCoding.decodeUTF16LE`：
  字节 → char 解一趟，再尝试压缩成 Latin1（中文串压缩失败）⇒ **「解成 char[] → 再写回 byte[]」两趟**，
  外加一个 char[] 中间分配。
- **改后** 只有一次 `arraycopy` + `Unsafe` 写两个字段 ⇒ **一次拷贝、零解码、零中间分配**。
  中文串的内部表示本来就是 UTF-16，wire 上的字节可以**原样塞回去**。

`cn-1k`（1024 个中文字，wire 2052 字节）：1710.1 ns → 61.1 ns，
61.1 / 2052 ≈ **0.03 ns/字节** ⇒ 基本就是一次 `memcpy` 的成本，已触到这条路径的下限。

**副产品**：`mixed-16k` 解码从 27629.5 ns（比标准版**慢** 1.54×）降到 1096.3 ns（**快** 16.4×）
⇒ mixed 只剩**体积**问题（1.43×），不再是速度问题。
（体积的准确口径见 5.2：**不是固定大 43%，而是取决于中文占比，交叉点 50%**。）

### 4.2 改后 2：端序方案（见第六节）

**wire 字节数一个都没变**（第三节的「字节std / 字节fast」两列与改后 1 完全一致），
因为本机是小端 ⇒ 写端仍然发标记 1 + LE 字节 ⇒ **协议输出零变化**。
时间上的差异（`cn-1k` fast-dec 61.1 → 73.6 ns 等）**全部是机器噪声**：
对照证据是本次完全没碰的 `ascii-1k` fast-dec（41.6 → 50.1 ns，同样 +20%）。

### 4.3 改后 3：补 `expand(fieldData.length)`（见第七节 7.3）

`FastProtoBufWriter` 的两个方法在写 `fieldData` 前没有 `expand`，缓冲写满时会越界。
修复只加了两行 `expand(fieldData.length)`（`expand` 只在空间不足时才扩容 ⇒ **正常路径零成本**）。

**wire 字节数一个都没变**（第三节「字节std / 字节fast」两列与改后 2 完全一致）。
时间上也在噪声内：所有 fast 路径与改后 2 的差异 ≤ 5%
（`cn-1k` fast-dec 73.6 → 65.4 ns、`cn-16k` fast-dec 1029.9 → 977.9 ns、`nest-cn-1k-x8` fast-dec 660.2 → 659.4 ns）；
反过来本次**完全没碰**的路径也同步漂了（`packed-int-16k` fast-dec 66878 → 68701 ns，0.97×；
`bytes-16k` fast-dec 722.9 → 761.1 ns，0.96×）⇒ 是机器噪声不是回归。

---

## 五、按两处改进拆开看

### 5.1 内嵌消息：GROUP 分帧 vs 长度前缀

标准版用 LENGTH 分帧：预留 2 字节长度前缀、写完内容再回填
（`AbstractWriter.writeLenPrefixReserve`）。**载荷落在 128..16383 字节时前缀正好 2 字节、内容不用搬；
其余区间必然整块 memmove**。改进版改用 GROUP 分帧（START_GROUP/END_GROUP tag），**完全没有长度前缀**。

三个负载分别压这三档（内层长度决定嵌套载荷落在哪一档）：

| 负载 | 嵌套载荷 | 标准版前缀 | 标准版是否搬移 | 编码比值 | 解码比值 | 体积 |
|---|---|---|---|---|---|---|
| `nest-64-x8` | ~69 B | 1 字节 | **每次左移 1 字节** | **1.55×** | 1.22× | 1.01× |
| `nest-1k-x8` | ~1029 B | 2 字节 | 不搬移（标准版最优区间） | 1.09× | 1.20× | 1.00× |
| `nest-20k-x4` | ~20 KB | 3 字节 | **每次右移 1 字节** | **1.75×** | 1.49× | 1.00× |

**结论**：改进版的优势**集中在标准版要搬移的两档**（`nest-64-x8` 1.55×、`nest-20k-x4` 1.75×）；
在标准版本来就不用搬移的「甜点区间」（`nest-1k-x8`）编码几乎打平（1.09×），
但**解码仍然稳定快 20%**（GROUP 分帧不需要先读长度再设 limit，省一次跳转）。
体积上 GROUP（2 字节 tag）vs LENGTH（1~3 字节前缀）基本持平。

### 5.2 字符串编码：按 coder 选编码 vs 一律 UTF-8

标准版一律 UTF-8（`AbstractWriter.writeString0`）；改进版写 **1 字节 charset 标记 + 裸字节**
（Latin1 存 1 字节/字，UTF-16 存 2 字节/字，`FastProtoBufWriter.writeString0`）。

| 负载 | String 的 coder | 标准版 UTF-8 | 改进版 | 编码比值 | 解码比值 | 体积 |
|---|---|---|---|---|---|---|
| `ascii-1k` | Latin1 | 1 B/字 | Latin1 1 B/字 + 1 字节标记 | 1.17× | 1.20× | 1.00× |
| `ascii-16k` | Latin1 | 1 B/字 | 同上 | 1.21× | 1.27× | 1.00× |
| `cn-1k` | UTF-16 | **3 B/字** | UTF-16 **2 B/字** | **7.66×** | **38.41×** | **0.67×** |
| `cn-16k` | UTF-16 | **3 B/字** | 同上 | **9.02×** | **34.90×** | **0.67×** |
| `mixed-16k` | UTF-16 | 混排平均 **1.4 B/字** | UTF-16 2 B/字 | **5.85×** | **18.37×** | **1.43×** |
| `latin1-high-1k` | Latin1（含 0xE9） | 2 B/字（必须真做 UTF-8 编码） | Latin1 1 B/字 | **13.02×** | **45.02×** | 0.80× |

**关键机理**：

- **纯中文赢在两处**：编码不用把 UTF-16 转成 UTF-8（省掉整个编码过程），
  体积还小 33%（2 B/字 vs 3 B/字）；解码侧靠 4.1 那次优化再快 28 倍。
- **Latin1 高位字符赢得更狠**：标准版必须真做 UTF-8 编码（Latin1 的 0xE9 不是合法 UTF-8），
  改进版直接裸拷内部 byte[]；解码侧标准版走 JDK UTF-8 多字节慢路径（1280 字节要 ~1958 ns），
  改进版走 ISO-8859-1 的 1:1 直拷（1028 字节只要 ~44 ns）⇒ **45×**。
- **混排**：改进版把**每个字符**都按 2 字节写；标准版按 UTF-8 逐字符编码 ——
  ASCII 部分 1 字节、中文部分 3 字节。**改进版是「按整个 String 的 coder 一刀切」，
  标准版是「逐字符最省」** ⇒ 谁体积更优**完全由中文占比决定**，见 5.2.1。

#### 5.2.1 ★ 混排体积 = 中文占比的函数，交叉点 50%

**推导**（设 A = ASCII 字数，B = BMP 非 ASCII（中文）字数，S = 补充平面字数）：

```
标准版 UTF-8 payload  = 1·A + 3·B + 4·S
改进版 UTF-16 payload = 2·A + 2·B + 4·S
─────────────────────────────────────────────
改进版 − 标准版      = A − B
```

- ★ **补充平面字符（emoji 等）在两边完全抵消** —— UTF-8 占 4 字节，
  UTF-16 占 2 码元 × 2 字节也是 4 字节 ⇒ **混进 emoji 不会改变体积差**。
- **改进版更小 ⟺ A < B ⟺ 中文占比 > 50%** ⇒ **交叉点正好在中文占比 50%**。
- ⇒ **改进版的体积劣势只出现在「英文占多数、中文是少数」这一种内容上**：
  中文占比 50% 时两边**几乎完全相等**（实测差 15 字节 / 32788 ≈ 0.05%），
  中文占比越高改进版越省（90% 时省 29%）。
- wire 总字节差还要加上 **1 字节 charset 标记** 和 varint 长度差
  ⇒ 实测差值 = (A − B) + 1（长度 varint 只在跨 128 边界时差 1）。

**实测验证**（6 个配比负载，总字符数固定 16384，全部精确吻合）：

| 负载 | A（ASCII） | B（中文） | A − B | 理论差 | 实测 `fastBytes − stdBytes` | 吻合 |
|---|---|---|---|---|---|---|
| `mix-1cn` | 16383 | 1 | 16382 | 16383 | 32773 − 16390 = **16383** | ✅ |
| `mix-cn10` | 14744 | 1640 | 13104 | 13105 | 32773 − 19668 = **13105** | ✅ |
| `mix-cn25` | 12284 | 4100 | 8184 | 8185 | 32773 − 24588 = **8185** | ✅ |
| `mix-cn50` | 8184 | 8200 | −16 | −15 | 32773 − 32788 = **−15** | ✅ **交叉点** |
| `mix-cn75` | 4084 | 12300 | −8216 | −8215 | 32773 − 40988 = **−8215** | ✅ |
| `mix-cn90` | 1630 | 14754 | −13124 | −13123 | 32773 − 45896 = **−13123** | ✅ |

**结论修正**：混排内容上改进版的体积**不是**「固定大 43%」，而是：

| 中文占比 | 体积（改进/标准） | 谁更省 |
|---|---|---|
| ~0%（`mix-1cn`，16383 ASCII + 1 中文） | **2.00×** | 标准版（改进版最坏情况） |
| 10% | 1.67× | 标准版 |
| 25% | 1.33× | 标准版 |
| **50%** | **1.00×** | **打平** |
| 75% | 0.80× | 改进版 |
| 90% | 0.71× | 改进版 |

⇒ **「一刀切」的最坏情况是「一个中文 + 一万多个 ASCII」**（体积翻倍）；
但**同一时刻改进版的解码快 29.2×**（28811 → 987 ns）⇒ 这是**用体积换 CPU** 的取舍，不是单纯的亏。

### 5.3 ★ 标准版读端的「一字悬崖」：JDK 把 ASCII 重拷一遍

上表的 `mix-1cn` 有一处反常：它的标准版解码（28811 ns）比 `mix-cn10`（10556 ns）**慢 2.7 倍**，
尽管 `mix-1cn` 的 UTF-8 字节**更少**（16390 vs 19668）。三个 launch 完全一致
（28931 / 30267 / 28811）⇒ 不是噪声。

**根因（JDK 17 `java.base/java/lang/String.java:521-578`）**：

```java
public String(byte[] bytes, int offset, int length, Charset charset) {
    } else if (charset == UTF_8.INSTANCE) {
        if (COMPACT_STRINGS && !StringCoding.hasNegatives(bytes, offset, length)) {
            this.value = Arrays.copyOfRange(bytes, offset, offset + length);   // ★ 纯 ASCII 快路径
            this.coder = LATIN1;
        } else {
            int sl = offset + length; int dp = 0; byte[] dst = null;
            if (COMPACT_STRINGS) {
                dst = new byte[length];
                while (offset < sl) {                 // ★ 逐字节拷贝 ASCII，没有 bulk 扫描
                    int b1 = bytes[offset];
                    if (b1 >= 0) { dst[dp++] = (byte) b1; offset++; continue; }
                    if ((b1 & 0xfe) == 0xc2 && offset + 1 < sl) { ... }   // 2 字节 Latin1
                    break;                              // ★ 遇到非 Latin1 → break
                }
            }
            if (dp == 0 || dst == null) { dst = StringUTF16.newBytesFor(length); }
            else { byte[] buf = StringUTF16.newBytesFor(length);
                   StringLatin1.inflate(dst, 0, buf, 0, dp);   // ★ 已解的 ASCII 再膨胀一遍
                   dst = buf; }
            dp = decodeUTF8_UTF16(bytes, offset, sl, dst, dp, true);
            if (dp != length) { dst = Arrays.copyOf(dst, dp << 1); }   // ★ 再整体拷一遍
```

⇒ **「长 ASCII 跑 + 后面跟一个非 Latin1 字符」时，前面那段 ASCII 要被拷两遍
（Latin1 缓冲 + inflate）+ 一次最终 copyOf** ⇒
**解码成本随「第一个非 Latin1 字符在串里的位置」线性增长。**

**实测（`NonLatin1PositionProbe`：同样 16384 字、同样 UTF-8 字节数，只挪动中文的位置）**：

| 中文位置 | 0 | 1 | 10 | 100 | 1024 | 4096 | 8192 | 16383 | 纯 ASCII |
|---|---|---|---|---|---|---|---|---|---|
| 解码 ns | 5659 | 5683 | 5660 | 5764 | 6752 | 9864 | 14043 | **22223** | 974 |

⇒ 标准版读端（`ByteArrayReader.readString`）踩的就是这个坑；
**改进版完全不受影响**（按标记直接裸拷，一次 memcpy）。

**排除过的其它解释**（都做了对照实验）：

| 假设 | 排除方式 | 结果 |
|---|---|---|
| wire 字节与 `getBytes(UTF_8)` 不同 | `Mix1cnByteProbe` 逐字节比对 | **逐字节相同** |
| 触发了 `decodeUtf8Strict`（含 U+FFFD ⇒ 触发） | 解码结果里查 U+FFFD | **无 U+FFFD，未触发** |
| 逃逸分析（EA）把解码优化掉了 | `-XX:-DoEscapeAnalysis` 重跑 | **数字不变** ⇒ 与 EA 无关 |
| 探针自身的 JIT 假象 | `EscapeProbe`（4 变体各一个 JVM）/`DecodePathProbe` | **27.3k~28.5k，与真实路径 28811 ns 吻合** |

⚠ **探针方法学警告**：同一段字节、同一个 `new String(bytes, UTF_8)`，
在**同一个 JVM 里跑多个测点**时报 **6881 ns**，**一个测点一个 JVM** 报 **27277 ns**（差 4 倍）。
原因见第二节第 8 条（调用点 megamorphic ⇒ lambda 单独编译 ⇒ String 不逃逸 ⇒ EA 把解码干掉）。
⇒ **本报告所有数字都来自「一个测点一个 JVM」**；
`Utf8DecodeProbe` 重构前的那组数（6.9k）**已作废**。

---

## 六、端序方案：设计与验证 ★

### 6.1 问题

**JDK 17 的 String 内部 UTF-16 字节序 = 原生端序，不是固定 LE**：

```
java.lang.StringUTF16:
  private static native boolean isBigEndian();
  static { if (isBigEndian()) { HI_BYTE_SHIFT=8; LO_BYTE_SHIFT=0; }   // BE：高字节在前
          else               { HI_BYTE_SHIFT=0; LO_BYTE_SHIFT=8; } } // LE：低字节在前
```

（`javap -c java.lang.StringUTF16` 可复核；本机实测 `HI_BYTE_SHIFT=0` ⇒ LE。）

而改进版的写端把 String 的内部 byte[] **原样**写出去 ⇒ **wire 字节序 = 生产方机器的端序**；
charset 标记却写死 `UTF16LE_BYTE` ⇒ **标记在大端机上是假的**。

### 6.2 方案：标记携带「发送方的字节序」

新增标记 `FastProtoBufWriter.UTF16BE_BYTE = 3`：

| | 写端 | 读端 |
|---|---|---|
| 行为 | 按**本机**端序选标记（LE→1，BE→3），**字节原样拷，一次转换都不做** | 按标记知道 wire 字节序，**只有「对方端序 ≠ 自己端序」时才翻一次** |
| 成本 | 0 | 同端序 0；跨端序 1 次 `swapUtf16ByteOrder`（每 2 字节一组交换） |

四种组合的正确性：

| W（写） | R（读） | 旧方案（HEAD：显式按 LE 解） | 「按 native 塞回」 | **本方案** |
|---|---|---|---|---|
| LE | LE | ✅ | ✅ | ✅ |
| LE | BE | **✅** | **❌ 回归** | ✅ |
| BE | LE | ❌ | ❌ | ✅ |
| BE | BE | ❌ | ✅ | ✅ |

⇒ 本方案四种全对，且**同端序时两端都零成本**。
★ 特别注意中间那一列：把读端改成「按 native 塞回」会**破坏 HEAD 本来正确的「小端写 → 大端读」**，
所以端序这块不能只看同端序，**必须列 W/R 四格表**。

实现（三个文件）：

```java
// edap-common/StringUtil
public static final boolean NATIVE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

public static void swapUtf16ByteOrder(byte[] b, int off, int len) {   // BE ⇄ LE，每 2 字节一组
    for (int i = off, end = off + (len & ~1); i < end; i += 2) {
        byte t = b[i]; b[i] = b[i + 1]; b[i + 1] = t;
    }
}

// FastProtoBufWriter.writeString0
bs[_pos++] = NATIVE_LITTLE_ENDIAN ? UTF16LE_BYTE : UTF16BE_BYTE;   // 字节照拷，不翻

// ByteArrayFastReader.readString
} else if (charsetByte == UTF16LE_BYTE || charsetByte == UTF16BE_BYTE) {
    byte[] sdata = new byte[len - 1];
    System.arraycopy(_buf, tmpPos, sdata, 0, len - 1);
    boolean wireLittleEndian = (charsetByte == UTF16LE_BYTE);
    if (wireLittleEndian != NATIVE_LITTLE_ENDIAN) {
        StringUtil.swapUtf16ByteOrder(sdata, 0, len - 1);   // ★ 只有跨端序才翻
    }
    s = StringUtil.fastInstance(sdata, UTF16LE_BYTE);
}
```

顺带把读端对**未知 charset 标记**改成 fail-fast（抛 `ProtoException`），不再当 UTF-8 静默解 ——
这既是「旧读端收到新标记」时的响亮报错，也是把静默乱码变成可观测故障。

### 6.3 验证（`FastEndianCheck`）

本机没有大端 JVM ⇒ 大端侧是**模拟**的：构造「大端写端写出的字节」（= LE 字节翻成 BE + 标记换 3），
喂给真实的读端代码路径。

```
本机原生端序 = 小端 (LE)
  ✔ 写端标记 = 1(LE) 或 3(BE)
  ✔ 标记与本机端序一致
  ✔ payload 长度正确
  ✔ wire 字节序 = 本机端序
  ✔ 本机 round-trip 内容相等
  ✔ 大端写端（新协议）产出 BE 字节
  ✔ ★ 跨端序读端能正确还原（大端写 + 小端读）
  ✔ 旧大端写端（标记1+BE字节）解出乱码（已知缺陷，记录在案）
  ✔ 未知 charset 标记 ⇒ 解码失败（不再静默乱码）
  ✔ swapUtf16ByteOrder 是对合的
  ✔ Latin1 串 round-trip
★ 全部通过
```

> ⚠ **尚未覆盖**：真实大端 JVM 上的端到端跑（没有 BE 机器/qemu）。
> 结构性论证是充分的（读端的变换 = 「按 wire 字节序换成本机 native 布局」，
> 而大端机上的 native 布局就是 BE），但**建议在 SPARC 上跑一次 `FastEndianCheck`** 收口。

### 6.4 上线顺序（这次有约束）

- **小端平台**：写端输出完全没变（还是标记 1 + 同样的字节）⇒ 随时可上。
- **大端平台**：新写端会开始发标记 3，**旧读端不认这个标记** ⇒ 会落进 UTF-8 分支静默乱码。
  ⇒ **大端平台必须先升读端（认标记 3）、再升写端。**
- 新读端读旧写端的存量数据：小端写的照常对；大端写的（标记 1 + BE 字节）新旧读端都解不对 ——
  与 HEAD 同级，不算回归。
- **同一端序两端之间**（大端↔大端）：改前就坏（HEAD 显式按 LE 解）、改后正确 ⇒ 属于修复。

---

## 七、缺陷清单

| # | 缺陷 | 状态 |
|---|---|---|
| 7.1 | 改进版在「集合字段为 null」时静默失败 | **已修**（`FastProtoBufWriter.writeMessages` 的 List 重载补了 `if (vs == null) return;`） |
| 7.2 | 四个公开方法出错都返回 null 不抛异常 | 未修（设计如此，用之前必须判 null） |
| 7.3 | `writeMessage`/`writeMessages` 写 fieldData 与 END_GROUP tag 时没有 `expand` | **已修**（`writeMessage(byte[],int,T,codec)` 与 `writeMessage0(...)` 补 `expand(fieldData.length)`） |
| 7.4 | `readString` 的 UTF16 分支漏 `pos += len` ⇒ 读指针不前进 ⇒ 后续字段整体错位 | **已修**（去掉提前 `return`，让三个分支统一落到 `pos += len`） |
| 7.5 | UTF16 串的端序不可移植 ⇒ 跨端序静默乱码 | **已修**（标记携带发送方字节序，见第六节） |

### 7.1 集合字段为 null 时静默失败（已修）

原 `FastProtoBufWriter.writeMessages(byte[], int, List, ProtoBufEncoder)`
（`FastProtoBufWriter.java:154`）直接 `int size = vs.size();`，**漏掉了标准版
`StandardProtoBufWriter.writeMessages` 里的 `if (CollectionUtils.isEmpty(vs)) return;`**。
叠加 `ProtoBuf.fastToByteArray` 把 `EncodeException` 吞掉返回 null
⇒ **消息里任何一个 `List` 字段是 null，改进版编码就静默返回 null**，而标准版完全正常。

现补上 `if (vs == null) { return; }`（数组重载与 `writeMap` 本来就有判断）。

### 7.2 失败静默

`toByteArray` / `fastToByteArray` / `toObject` / `fastToObject` 四个公开方法
**出错时都返回 null 而不抛异常**。用它们之前必须显式判 null。

### 7.3 缓冲写满时越界（已修）

`FastProtoBufWriter.writeMessage` / `writeMessages` 写 fieldData 时**没有 `expand`**，
而标准版 `StandardProtoBufWriter.writeMessage0` 有 `expand(MAX_VARINT_SIZE + NESTED_LEN_RESERVE)`。

`AbstractWriter.writeFieldData`（`AbstractWriter.java:98`）是**裸写**（直接 `_bs[_p++] = fieldData[0]`，
一个字节的容量检查都没有），而 `expand(minLength)`（`AbstractWriter.java:1202`）的语义是
「保证 `bs.length - pos >= minLength`」⇒ **`writeFieldData` 之前必须保证剩余空间 ≥ `fieldData.length`**。

`fieldData` = 字段 tag 的 varint 字节（`ProtoUtil.buildFieldData` → `varIntEncode`），长度 1~5 字节。
触发路径是**可达的**：`writeString0` 的 `expand(length + 6)` 恰好等于它实际写入的字节数
（varint ≤5 + 标记 1 + payload length）⇒ payload 长度取某些值时正好把缓冲写满；
紧接着的下一个字段若是 message 类型且走 `writeMessage(fieldData, ...)` ⇒ `bs[pos]` 越界。

**修法**（与标准版 `AbstractWriter` 里所有 `writeFieldData` 调用点的写法一致 —— 那里 20 处调用**全部**都有前置 `expand`）：

```java
// FastProtoBufWriter.writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec)
expand(fieldData.length);
writeFieldData(fieldData);

// FastProtoBufWriter.writeMessage0(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec, int end)
expand(fieldData.length);
writeFieldData(fieldData);
```

`writeMessages(byte[],int,T[],...)` 委派给 `writeMessage0` ⇒ 一并覆盖。

其余两处 `writeFieldData` 调用点**经核对是安全的**（不需要改）：
`writeMessages(..., List, ...)` 与 `writeObject(...)` 前面的 `expand(MAX_VARINT_SIZE)` = 5 字节，
恰好覆盖 `fieldData.length ≤ 5`（int varint 上限）⇒ 但这依赖「fieldData 恒 ≤ 5 字节」这个隐含前提。

`expand` 只在空间不足时才扩容，**对正常路径零成本**。

---

## 八、回归验证（单测）★

改动涉及 3 个生产文件（`edap-common/StringUtil`、`edap-protobuf/…/FastProtoBufWriter`、
`edap-protobuf/…/ByteArrayFastReader`）+ `ProtoBuf` 新增两个公开方法 ⇒ 必须跑现有单测排除回归。

### 8.1 判定方法

`edap-protobuf-wire` 在 HEAD 上就有**预存在失败**（javabuilder 的 checked-in 夹具陈旧），
所以「有没有失败」本身不能判定回归 ⇒ **必须与 HEAD 干净树逐类对比**：

```bash
git worktree add /tmp/edap-head HEAD --detach
cd /tmp/edap-head/edap-protocol-parent
mvn -o test -DskipTests=false -pl edap-protobuf,edap-protobuf-wire -am
# 再在主树跑同样的命令，比对 surefire-reports 里的失败类清单
```

> ⚠ **踩到的坑：HEAD 的 `edap-protobuf-wire` 直接编译不过。**
> `JavaBuilder`（已提交）引用了 `io.edap.protobuf.annotation.ProtoGrpc`，
> 而 `ProtoGrpc.java` / `ProtoErpc.java` 是**工作区里的未跟踪文件**（`git status` 里是 `??`），
> 从没进过 git ⇒ 干净 worktree 里这两个类不存在 ⇒ `找不到符号: 类 ProtoGrpc`。
> ⇒ 干净树跑测试前必须先把这两个文件拷进去。
> （顺带说明：这两个未跟踪文件是 `edap-protobuf-wire` 能编译的必要条件，别误删。）

### 8.2 结果

| 模块 | HEAD 干净树 | 当前树 | 判定 |
|---|---|---|---|
| `edap-protobuf` | **1419 个测试，0 失败** | **1419 个测试，0 失败** | ✅ 一致，无回归 |
| `edap-protobuf-wire` | 246 个测试 / **74 个失败 / 38 个失败类** | 246 个测试 / **74 个失败 / 38 个失败类** | ✅ **失败类清单逐类完全一致** |

失败类清单 diff（HEAD vs 当前树）**为空**：

```
36 个 io.edap.protobuf.wire.test.javabuilder.*  ← 夹具缺头部注释，与本次改动无关
 1 个 io.edap.protobuf.wire.test.TestExtend     ← 期望抛 ProtoParseException 实际没抛
 1 个 io.edap.protobuf.wire.test.TestParseEnum  ← 期望 (custom_option) 实际 custom_option
```

当前树比 HEAD 多 7 个测试，来自 2 个**未跟踪的新测试类**
（`io.edap.protobuf.test.builder.GeneratedSourceCompileTest`、`ServiceCodeGenTest`）⇒ **全部通过**。

> 上表在**第三轮改动（7.3 的 `expand` 修复）之后重跑过一遍**，数字不变。

### 8.3 其它自检

| 检查 | 结果 |
|---|---|
| `FastEndianCheck`（端序一致性，含模拟大端写端） | ✅ 10 项全过 |
| 基准的 round-trip 自检（计时前 + 计时后各验一次） | ✅ **19 个负载全过** |
| 基准的 wire 字节数自检 | ✅ 与改前基线逐字节一致（三轮改动都没动过 wire） |
| 完整矩阵重跑（19 负载 × 4 候选 × 3 launch = 228 个 JVM） | ✅ 见第三节，fast 路径差异 ≤ 5%，且未改动的路径同步漂移 ⇒ 噪声 |

---

## 九、给使用者的选型建议

| 场景 | 建议 |
|---|---|
| 报文以**中文为主**（占比 >50%） | 用改进版：编码快 8~10×，**解码快 33~39×**，体积小 20~33% |
| 报文以**ASCII 为主**（纯 ASCII，无中文） | 用改进版：编码快 17~21%，解码快 20~27%，体积基本持平（多 1 字节/串） |
| 报文是**中英混排** | 用改进版：**速度全面赢**（编码 4.6~10.3×、解码 10.8~35.6×）。<br>体积按中文占比走：**<50% 时改进版更大**（10%→1.67×、25%→1.33×；极端「1 个中文 + 16383 个 ASCII」→2.00×），**<br>50% 打平，>50% 时改进版更小**。⇒ **内网 RPC 场景下这个体积代价已决策接受、不修**（见 9.1） |
| 报文**含 Latin1 高位字符**（西文、姓名等） | 用改进版：编码快 13×，解码快 45×，体积小 20% |
| 报文**嵌套多且内层大小忽大忽小** | 用改进版：编码快 1.6~1.8×，且**没有长度前缀阈值带来的性能悬崖** |
| 需要与 **protobuf-java / 其它标准实现互通** | **只能用标准版** —— 改进版的 wire 不是 protobuf 格式 |
| 部署在**混用端序**的机器上（如 Solaris SPARC + x86） | 用改进版没问题（端序已由标记解决），但**大端侧要先升读端再升写端** |
| 任何场景 | 记得判 null；并确保集合字段非 null（否则改进版静默返回 null） |

### 9.1 「混排体积变大」这个短板：已决策**不修**

改进版是**按整个 String 的 coder 一刀切** ⇒ 一个字符串里哪怕只有 1 个中文，
整串就按 UTF-16 存 ⇒ 最坏情况体积翻倍（`mix-1cn`：2.00×）。

**决策（2026-11-15，用户定）：不改。** 理由：**RPC 基本都在内网跑，速度是关键，体积大点没关系。**
⇒ FAST 写端**保持「一刀切」**，不做「非 ASCII 占比 <50% 就改用 UTF-8 编码」的规则
（wire 上的 `UTF8_BYTE=2` 标记保留但写端不产出）。

**为什么这个决策在当前场景下是对的**：混排负载上改进版解码快 **10.8~35.6×**、
编码快 **4.6~10.3×**；而多出来的字节只在内网链路上多传一次。
按 16KB 报文算，最坏情况多传 16KB ≈ 内网千兆 0.13 ms，
而省下的解码时间是 **19~28 µs/次** —— 换的是「带宽」换「CPU」，在 CPU 紧张的内网 RPC 场景下这笔交易划算。

> 若将来场景变成**公网 / 带宽计费 / 大报文**，再回头评估这条规则：
> 代价是要付一次完整的 `getBytes(UTF_8)` 编码（会吃掉大部分编码速度优势），
> 或在**服务边界按字段/按消息**选 codec（两条路径 wire 不同，不能在同一消息里混）。
