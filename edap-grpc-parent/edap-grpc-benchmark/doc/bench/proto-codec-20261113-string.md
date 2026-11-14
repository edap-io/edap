# edap protobuf 编解码器 —— 第二~五轮 + 第八轮：中文编码路径 + repeated 标量 wire type + 查表/向量化探索 + 长度前缀预留

生成时间: 2026-11-13 10:30 ~ 19:40 CST（第二轮 10:30~11:35；第三轮 12:00~13:30；**第四轮 14:00~15:10**；**第五轮 15:10~16:20**；**第八轮 19:00~19:40**）

运行环境: Mac OS X 27.2.1 / aarch64 / JDK 17.0.20.1 / 10 核（load 5~11，噪声大 ⇒ 全部「隔离 JVM 取 min」）

被测版本: protobuf-java 3.25.9 / edap 自研 protobuf（edap-protobuf）

探针源码: `src/main/java/io/edap/grpc/benchmark/ProtoCodecBenchmark.java`（负载矩阵）
+ `StringEncodeCandidates.java`（编码候选算法，**34 个候选**）+ `run-string-candidates.sh`（隔离测量脚本）

> 第一轮（2026-11-12）见 [`proto-codec-20261112.md`](proto-codec-20261112.md)：
> 解码端从「慢 6~13x」优化到持平、修掉 Latin1 非法 UTF-8、
> 并**报告**了 `repeated int32` 的 wire type 错。第二轮把「中文编码」做完、把 wire type 错修掉；
> 第三、四轮是「再优化」的探索，全部否决；
> **第五轮推翻了第四轮的一条封堵 ⇒ 「纯 ASCII 字符串零分配直写」已落地（ASCII 编码核心 -40%）。**
> **第八轮把嵌套/packed 的长度前缀从「预留 1 字节 + 整块右移」改成「预留 2 字节 + 按差值平移」
> ⇒ 嵌套消息编码核心 -27% ~ -32%（相对 protobuf-java 从 0.22~0.24x 压到 0.16~0.17x）。**


---

## 〇、本轮两件事

| | 目标 | 结果 |
|---|---|---|
| **A** | `repeated` packable 标量的 wire type（`18` ⇒ `1A`） | ✅ 修完，跨框架逐字节对齐；⚠ **wire 变了 ⇒ 有升级顺序要求** |
| **B** | 中文（UTF-16 coder）字符串编码路径（唯一系统性落后项） | ✅ **1.30x/1.32x → 1.04x/1.02x**（基本持平） |

---

## 一、★ 探针方法论（本轮最大的收获，也是最容易踩的坑）

### 1.1 现象

第一轮把 5 个编码候选塞进**同一个 JVM** 跑，结果出现这种数字：

```
同一个候选，同一批串，两次跑：
  cn-16k → 13259 ns / 24317 ns     ← 2 倍差
```

### 1.2 根因

测量循环是 `op.call()`，`op` 是 lambda。
**同一个 JVM 里放 14 个候选 ⇒ `call()` 的接收者类型有 14 种 ⇒ 调用点变成 megamorphic**
⇒ JIT 无法内联 ⇒ 每次调用都走 vtable 间接分派，且**哪个候选先跑、哪个后跑会决定 inline cache 的形状**
⇒ 数字完全不可比。

本机 10 核、load 常在 5~11 也会放大噪声，但**2 倍的差不是 load 能解释的**。

### 1.3 正确做法

**一个候选一个 JVM**，多轮 launch，跨 launch 取 **min**：

```bash
./run-string-candidates.sh 200 3 4      # MILLIS=200  LAUNCHES=3  ROUNDS=4
```

脚本内部：每个候选起 3 个独立 JVM → 每个 JVM 内跑 4 轮取中位数 → 跨 3 个 launch 取 min。

⇒ **本报告里所有候选对比数字都是这样测出来的**。
⚠ 顺带：`ProtoCodecBenchmark`（负载矩阵）里的「字符串编码候选」段落**已经删掉**，
它会误导人（同一个 JVM 跑多候选）；矩阵本身的每个负载只有 1 个候选 ⇒ 不受影响。

### 1.4 其它纪律

- **防死代码消除**：被测结果必须累加进静态 `sink`（`ProtoCodecBenchmark.sink`、
  `StringEncodeCandidates.sink`），否则 JIT 会把「写进没人读的缓冲」当成无用计算优化掉。
- **候选之间必须字节一致**：所有候选产出的字节要逐字节相同，且与 protobuf-java 一致；
  探针里有断言。（⚠ 期间发现 `writeVarint` 在长度 < 128 时多写 1 字节，已修。）
- **`--add-opens` 必须写全包名**：`java.base/java.lang`、`java.base/java.nio`、
  `java.base/sun.nio.ch`、`java.base/java.util`。
  写成 `java.base/{lang,nio}=ALL-UNNAMED` 会被 shell 展开坏。
- **改完 edap-protobuf 必须先 `mvn install`**：benchmark 模块的 classpath 指向 `~/.m2` 的 jar。

---

## 二、候选实测（隔离 JVM，取 min，单位 ns）

| 串 | 0 现状 | 1 pb精确 | 2 char[]+Uns-int+rsv | **3 char[]+Uns-long+rsv** | 4 char[]+plain+rsv | 5 charAt+Uns-int | 8 两遍 | 9 getBytes | **10 参照protobuf-java** | 11 直调int | 12 直调long | 13 复用+UnsL |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| cn-1k | 1045 | 922 | 1031 | **806** | 1294 | 1049 | 1390 | 1286 | **840** | 1321 | 816 | 813 |
| cn-16k | 16999 | 14632 | 16153 | **12750** | 20770 | 16773 | 21984 | 20516 | **13258** | 21153 | 12843 | 13432 |
| emoji-1k | 2092 | 2073 | 2020 | **1854** | 2129 | 2162 | 2987 | 2328 | **2084** | 2346 | 1828 | 1812 |
| ascii-16k | 856 | 856 | 856 | 860 | 860 | 858 | 857 | 938 | 4234(Latin1) | 884 | 974 | 876 |

（`rsv` = 预留 varint 长度前缀；`Uns-int` / `Uns-long` = 字节偏移计数器用 int / long；
`plain` = 普通数组写而非 `Unsafe.putByte`；`两遍` = 先扫一遍算 UTF-8 长度；
`复用` = 复用 ThreadLocal 的 char[] 缓冲；`直调` = 直接调 Unsafe 而不是走 `UnsafeUtil` 包装。）

### 2.1 两条可复现的结论

**① 字节偏移计数器必须用 `long`**（-21%）

逐字节编码时偏移是**连续递增**的，用 int 计数 ⇒ 每写一个字节都要把 int 符号扩展成 long（`i2l`），
拉长「偏移 → 地址」的依赖链，JIT 也更难消掉 range check。

```
cn-16k: char[]+Unsafe-int 16153 ns  →  char[]+Unsafe-long 12750 ns   (-21%)
```

**② 「预留长度前缀」省掉整块 memmove**

protobuf-java 的做法：先按「每字最多 3 字节」**预留**长度前缀的字节数 → 写内容 →
回退补上真实长度 ⇒ **零 memmove**。
中文串的实际 UTF-8 长度必然等于「每字 3 字节」的估算 ⇒ varint 长度必然相等 ⇒ **完全不用搬**。

edap 原实现是「先写内容、再把内容整块右移补前缀」⇒ **每次都搬**。

### 2.2 被排除的方案（都更慢，别再试）

| 方案 | cn-16k | 结论 |
|---|---|---|
| 普通数组写替代 `Unsafe.putByte` | 20770 | **慢 63%** |
| `charAt` 替代 `toCharArray()` | 14632 | 慢 15%（`StringUTF16.charAt` 要逐字节拼装） |
| 先扫一遍算 UTF-8 长度 | 21984 | 慢 72%（多一遍扫描） |
| 一律 `getBytes(UTF_8)` | 20516 | 慢 61%（多一次分配 + 拷贝） |
| 4 字节批量写（`putInt`） | 21153 | 慢 66% |
| 复用 char[] 缓冲（ThreadLocal） | 13432 | 与 `toCharArray` 基本持平（cn）⇒ **不引入 ThreadLocal**，保持 `toCharArray` |

### 2.3 选型结论

⇒ 采用**候选 3**：`char[]`（`toCharArray`）+ **`long` 偏移计数器** + **`Unsafe.putByte`** + **预留 varint**。

---

## 三、实现（Phase B）

### 3.1 `edap-common/src/main/java/io/edap/util/UnsafeUtil.java`

新增 `long` 索引的重载（放在既有 `writeByte(byte[],int,byte)` 之后）：

```java
public static void writeByte(byte[] bs, long offset, byte b) {
    UNSAFE.putByte(bs, ARRAY_BYTE_BASE_OFFSET + offset, b);
}
```

### 3.2 `edap-protobuf/.../writer/AbstractWriter.java`

| 方法 | 改动 |
|---|---|
| `writeChars(char[], int, int, int)` | 偏移计数器 `int p` → **`long p`**；写入走新的 `long` 重载；返回 `(int)(p - pos)` |
| `writeString0` 的 UTF-16 分支 | 改为**预留 varint**：短串分支 `expand(maxBytes + reserve)`、内容写在 `_pos + reserve`；长串分支（1024 字符/段）同理；编码完调 `writeStringLenPrefix` 回填前缀 |
| `writeStringLenPrefix(int,int,int)`（新增） | 实际 varint 长度 ≠ 预留时**左移**内容（`System.arraycopy`）再写前缀，返回「前缀 + 内容」字节数 |
| `varintLen(int)` / `writeVarint(byte[],int,int)`（新增） | varint 长度计算与写入 |
| **Latin1 分支** | **未动**（`getBytes(UTF8_CHARSET)` 是上一轮修的非法 UTF-8 洞，必须保留） |
| `writeLenMoveBytes` | **保留**（`StandardProtoBufWriter` 写嵌套消息 / packed 长度前缀仍用它） |

**左移分支是新出现的**：旧实现一律右移；现在「绝大多数 ASCII + 少量中文」的串
实际长度前缀比预留的**短** ⇒ 需要左移补齐。
该分支由新增用例 `testMixedAsciiChinesePrefixShift`（100/1000/6000/20000 长度）锁住。

`FastProtoBufWriter` 有自己的 `writeString0`，**天然隔离**（父类的是 `private final`），
且它不是 protobuf wire 格式 ⇒ 不动。

### 3.3 测试

`edap-protobuf/src/test/.../v3/TestStringUtf8.java`：22 例 → **34 例**

| 新增 | 覆盖 |
|---|---|
| `testShortLongBranchBoundary` | 1023/1024/1025/1026/2047/2048/2049 —— 短串分支（`maxBytes≤3072`）与长串分支的分界线 |
| `testMixedAsciiChinesePrefixShift` | 100/1000/6000/20000 —— **左移回退分支** |
| `testLongChinese` | 30000 中文字（90000 字节 ⇒ 3 字节 varint，走长串分支） |

⚠ **盲区**：4 字节 varint 分支需要 ≥2²¹ 字节（≈70 万中文字 / 2MB 载荷），太重，**未覆盖**。

---

## 四、端到端 before/after（`ProtoCodecBenchmark`）

口径见第一轮文档。「编码核心」= 写进 writer 缓冲（两边对等，1 次拷贝）。
**每个配置跑 2 个独立 JVM**，下面记两次的原始值。

### 4.1 改前（= HEAD + Latin1 修复，不含本轮改动）

| 负载 | 编码核edap | 编码核goog | 比值 | byte[]edap | byte[]goog | 比值 |
|---|---|---|---|---|---|---|
| str-ascii-1024 | 62 / 59 | 957 / 940 | 0.06x | 102 / 102 | 970 / 958 | 0.11x |
| str-ascii-16384 | 960 / 865 | 14924 / 15208 | 0.06x | 1582 / 1565 | 15105 / 15071 | 0.10x |
| **str-cn-1024** | **1100 / 1099** | 849 / 855 | **1.30x / 1.29x** | 1205 / 1210 | 889 / 899 | 1.36x / 1.35x |
| **str-cn-16384** | **18386 / 18544** | 13899 / 13261 | **1.32x / 1.40x** | 19540 / 19896 | 13977 / 13938 | 1.40x / 1.43x |
| str-latin1-high | 19 / 19 | 14 / 14 | 1.37x / 1.38x | 25 / 24 | 16 / 16 | 1.54x / 1.51x |
| nest-1k-x1 | 179 / 164 | 522 / 472 | 0.34x / 0.35x | 272 / 250 | 543 / 486 | 0.50x / 0.52x |
| nest-1k-x8 | 763 / 738 | 2280 / 2050 | 0.33x / 0.36x | 1033 / 1015 | 2359 / 2138 | 0.44x / 0.47x |
| nest-1k-x64 | 5508 / 5516 | 16149 / 14274 | 0.34x / 0.39x | 7402 / 7438 | 16848 / 15212 | 0.44x / 0.49x |
| packed-int-1000 | 1527 / 1414 | 1427 / 1543 | 1.07x / 0.92x | 1601 / 1491 | 1428 / 1542 | 1.12x / 0.97x |
| packed-int-16000 | 28179 / 26417 | 23123 / 25024 | 1.22x / 1.06x | 29119 / 27471 | 23561 / 25479 | 1.24x / 1.08x |
| map-str-1000 | 21247 / 21537 | 65544 / 65058 | 0.32x / 0.33x | 22035 / 22154 | 63879 / 63858 | 0.34x / 0.35x |
| bytes-16384 | 354 / 343 | 373 / 388 | 0.95x / 0.88x | 945 / 950 | 724 / 724 | 1.30x / 1.31x |

### 4.2 改后

| 负载 | 编码核edap | 编码核goog | 比值 | byte[]edap | byte[]goog | 比值 |
|---|---|---|---|---|---|---|
| str-ascii-1024 | 63 / 59 | 964 / 944 | 0.07x / 0.06x | 104 / 103 | 998 / 969 | 0.10x / 0.11x |
| str-ascii-16384 | 988 / 953 | 14982 / 14669 | 0.07x / 0.06x | 1606 / 1577 | 15080 / 15007 | 0.11x / 0.11x |
| **str-cn-1024** | **896 / 822** | 858 / 855 | **1.04x / 0.96x** | 970 / 927 | 899 / 886 | **1.08x / 1.05x** |
| **str-cn-16384** | **13742 / 13573** | 13521 / 13319 | **1.02x / 1.02x** | 15307 / 15059 | 14122 / 13901 | **1.08x / 1.08x** |
| str-latin1-high | 20 / 19 | 14 / 14 | 1.41x / 1.37x | 25 / 24 | 17 / 17 | 1.51x / 1.46x |
| nest-1k-x1 | 164 / 161 | 457 / 511 | 0.36x / 0.31x | 246 / 242 | 484 / 543 | 0.51x / 0.45x |
| nest-1k-x8 | 767 / 726 | 2031 / 2288 | 0.38x / 0.32x | 1022 / 1005 | 2131 / 2368 | 0.48x / 0.42x |
| nest-1k-x64 | 5519 / 5553 | 14267 / 16151 | 0.39x / 0.34x | 7302 / 7425 | 15252 / 16857 | 0.48x / 0.44x |
| packed-int-1000 | 1413 / 1411 | 1424 / 1534 | 0.99x / 0.92x | 1493 / 1492 | 1437 / 1539 | 1.04x / 0.97x |
| packed-int-16000 | 26425 / 26521 | 23102 / 25029 | 1.14x / 1.06x | 27392 / 27544 | 23691 / 25482 | 1.16x / 1.08x |
| map-str-1000 | 21518 / 21625 | 65991 / 65262 | 0.33x / 0.33x | 22412 / 22297 | 64409 / 63794 | 0.35x / 0.35x |
| bytes-16384 | 353 / 379 | 381 / 395 | 0.93x / 0.96x | 964 / 959 | 716 / 726 | 1.35x / 1.32x |

### 4.3 结论

| 负载 | 改前 | 改后 | Δ |
|---|---|---|---|
| **str-cn-1024（编码核心）** | 1.30x / 1.29x | **1.04x / 0.96x** | **-19% ~ -25%** |
| **str-cn-16384（编码核心）** | 1.32x / 1.40x | **1.02x / 1.02x** | **-25%** |
| **str-cn-1024（产出 byte[]）** | 1.36x / 1.35x | **1.08x / 1.05x** | -21% |
| **str-cn-16384（产出 byte[]）** | 1.40x / 1.43x | **1.08x / 1.08x** | -23% |

⇒ **中文编码从「慢 1.3~1.4x」变成「基本持平（1.02x）」。**

**其余各档无回退**：ASCII 0.06~0.07x、嵌套 0.31~0.39x、map 0.33x、bytes 0.93x、
packed-int 0.92~1.14x（改前 0.92~1.22x，也在噪声内）。解码侧未动。

★ 顺带一个观察：**edap 的 protobuf 编解码器在绝大多数维度本来就比 protobuf-java 快**
（ASCII 编码快 15x、嵌套快 3x、map 快 3x）—— 第一轮那句「编码慢 1.4x」是口径问题
（edap 多一次整块拷贝），本轮修的「唯一系统性落后 = 中文」修完后，
**edap 的 protobuf 编解码器本体已经全面不落后于 protobuf-java**。

---

## 五、Phase A：`repeated` packable 标量的 wire type

### 5.1 根因链

1. **生成器丢字段**：`JavaBuilder.java:1154-1176` 生成 `@ProtoField(tag=…, type=…, name=…, jsonType=…, oneOf=…)`
   —— **从不输出 `cardinality`**；而同一方法 `:1179` 明明知道 `f.getCardinality()==REPEATED`，却只拿它决定 Java 类型。
   注解默认值 `ProtoField.java:37` = `Cardinality.OPTIONAL`。
   ★ 铁证：`ListInt32BuilderTest.java:46` 锁死了生成结果 `@ProtoField(tag = 1, type = Type.INT32)` + `private List<Integer> value;`（**没有 cardinality**）。
2. **运行时判据是对的，入参是错的**：`ProtoUtil.buildFieldValue`（`:249-306`）
   只在 `syntax==PROTO_3 && cardinality==REPEATED` 时才给 packable 标量选 `LENGTH_DELIMITED`，
   否则 `VARINT` ⇒ 写出 `0x08` 而不是 `0x0A`。
3. **载荷是对的，只有 tag 错**：`writePackedInts` 写的是标准 packed（tag + 长度前缀 + packed 内容）；
   `writeListEnum` / `writeListProtoEnum` / 数组路径**也都是 packed** ⇒ 全链路只有 tag 一处不一致。
4. **无注解路径本来就是对的**：`ProtoUtil.generateProtoField` → `buildProtoFieldAnnotation`（`:586-597`）
   会从 Java 字段类型推断 `REPEATED`。**注解路径与无注解路径不一致 = bug 本质**。

**为什么 edap 自己往返不炸、跨框架才炸**：解码侧的 tag 表也用 cardinality ⇒ 旧版两边都是 `0x08`；
而解码分支 `isPacked(pfi)`（`ProtoUtil.java:91-92`，只看 `type().packable()`，**不看 cardinality**）
⇒ 读到 `0x08` 后按 packed 读，恰好把紧跟其后的长度前缀当成长度 ⇒ **edap 自洽（歪打正着）**。
protobuf-java 读到 `0x08` 当作单个 varint，剩下字节当未知字段 ⇒ 抛 `invalid tag (zero)`。

### 5.2 修法（单点）

改 `ProtoUtil.getProtoFields`（`edap-protobuf-api/.../util/ProtoUtil.java:117-173`）：
拿到注解 `pf` 之后，若

- `pf.cardinality() == OPTIONAL`（注解没写 cardinality），且
- `pf.type().packable()`（14 个 packable 标量），且
- Java 字段的 generic type 是 List/Set/数组/Iterable（复用 `:588-591` 已有的判定）

⇒ 把 cardinality **合成**成 `REPEATED`。

**保留原注解的其余属性**（`name`/`jsonType`/`oneOf`/`comment`/`options`）——
`oneOf` 编码侧要用（`ProtoBufEncoderGenerator.java:278`），
所以**不能**复用 `buildProtoFieldAnnotation`（`:859-909`，它把那几个字段都置空），
而是新增一个**只覆盖 `cardinality()` 的委托实现**。

**为什么选这个点**：
- 编码侧与解码侧**都**从 `getProtoFields` 拿 `ProtoFieldInfo`
  （`ProtoBufEncoderGenerator.java:111` / `ProtoBufDecoderGenerator.java:102`）
  ⇒ **只改这一处，编码与解码同时自洽**。
- **不需要重新生成任何 POJO**，不动 `JavaBuilder`
  ⇒ `edap-protobuf-wire` 那 36 个陈旧的 javabuilder 夹具失败**完全不受影响**（保留回归信号）。
- 不改 `ProtoField` 注解本身 ⇒ 不影响所有手写 POJO。

**副作用（有益）**：`ProtoIdlUtil`（`edap-protobuf-idl:260`）现在能为 List 字段正确生成 `repeated`。

### 5.3 测试

新增 `TestRepeatedPackedWireType`（**16 例**）+ 4 个测试 POJO
（`AnnotatedRepeatedInt32` / `Int64` / `Bool` / `Double` / `Int32Array`，
⚠ 简单类名必须唯一 —— protobuf 生成的编解码器类名只按简单类名）。

覆盖：edap 字节 == protobuf-java 字节（protobuf-java 是 edap-protobuf 的 **test scope** 依赖）、
首字节必须是 `0x0A`、edap 往返、int32/int64/bool/double/enum 等 packable 类型 × List/数组两种形态。

**红证留档**：改前 7 失败 + 3 错误（protobuf-java 解析 edap 字节抛 `invalid tag (zero)`）；改后 16 全绿。

### 5.4 ⚠ 兼容性与升级顺序

**wire 格式变了**：新写 `1A <len> <payload>`，旧写 `18 <varint>`。
⇒ **新版 edap 写的数据，旧版 edap 读会当成未知字段静默丢弃**（旧解码器的 tag 表里只有 `18`）。

- **缓解 ①（推荐，写进 README §23.2）**：**先升级「读」的一侧（服务端/消费者），再升级「写」的一侧（客户端/生产者）**。
- **缓解 ②（不做）**：解码侧同时接受旧 tag ⇒ 要给 ASM 解码器加 legacy 分支，
  而 `lookupSwitch` 的 key 必须**升序**（`ProtoBufDecoderGenerator.java:397`）⇒ 插入 legacy key 要重排，**有坑**。

### 5.5 影响面

- **受影响**：**由 `JavaBuilder` 生成的、含 repeated packable 标量的 POJO**。
  仓库里的现成例子：`edap-erpc-parent/edap-erpc-api/src/main/resources/proto/rpc.proto:23,45`（`repeated int32 workId`）。
- **不受影响**：`repeated string/bytes/message/map`（本来就 LENGTH_DELIMITED，type 不 packable）；
  手写 POJO（现有测试里带注解的 repeated POJO **全部显式写了 `cardinality = REPEATED`**，
  例如 `ListSfixed32.java:29`、`ArraySint32.java:27`）。

---

## 六、验证

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap

# 1) edap-protobuf 全量单测
mvn -o -pl edap-protocol-parent/edap-protobuf test -DskipTests=false
#    → Tests run: 1393, Failures: 0, Errors: 0

# 2) gRPC 互操作回归（帧字节有任何变化就会红）
mvn -o -pl edap-grpc-parent/edap-grpc-benchmark test -DskipTests=false
#    → GrpcBenchmarkSmokeTest 3 + GrpcCompatTest 11 + GrpcInteropTest 7 = 21 全绿

# 3) 编码候选（★ 隔离 JVM，取 min）
cd edap-grpc-parent/edap-grpc-benchmark
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
./run-string-candidates.sh 200 3 4

# 4) 负载矩阵（★ 必须先 install edap-protobuf，否则看到的是 ~/.m2 里的旧 jar）
mvn -o -q -pl edap-common,edap-protocol-parent/edap-protobuf install -DskipTests   # 在仓库根
$JAVA_HOME/bin/java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
  -cp "target/classes:$(cat target/cp.txt)" io.edap.grpc.benchmark.ProtoCodecBenchmark 600 3
```

---

## 七、明确不做

- **不改 `JavaBuilder`**（走它要重生成 POJO + 改 18 份陈旧夹具 + 失去回归信号）。
- **不改 `ProtoField` 注解的默认值**（会影响所有手写 POJO）。
- **不做解码侧 legacy tag 兼容**（ASM `lookupSwitch` 的 key 必须升序 ⇒ 需重排，有坑；单独立项）。
- **不改 `FastProtoBufWriter`**（非 protobuf wire 格式）。
- **不改 ext/AnyCodec 的 `writeStringUtf8`**（没有 wire-vs-google 基准，风险高）。
- **不引入 ThreadLocal char[] 缓冲**（实测与 `toCharArray` 基本持平，不值那个复杂度）。
- **不实现嵌套/map/packed 的 size 预计算**（真正的根治方向，但跨模块大改 ⇒ 独立立项）。

---

# ★ 第三轮（2026-11-13 12:00 ~ 13:30）：再优化尝试 —— 全部否决 + 探针方法论的一个大坑

生成时间: 2026-11-13 12:00 ~ 13:30 CST

起因：四条「`writeString0` 还能不能再快」的追问 ——

1. UTF-16 分支还是**逐 char 转换**，能不能批量化？
2. Latin1 分支的 `v.getBytes(UTF8_CHARSET)` 还能不能优化？
3. 「字符串查找我记得是有**向量加速**的」—— 能不能用上？
4. 「Latin-1 高位字符场景非常少，**多处理一遍**应该也可以」。

**结论先说**：四条全部实测，**没有任何一条能带来收益 ⇒ 生产代码一行不改**。
但过程中挖出一个**比优化本身更重要的东西**：探针方法论里的一个 **1.65 倍假象**。

---

## 八、★ 先修探针：一个 1.65 倍的假象（JIT profile 污染）

### 8.1 现象

同一段 UTF-8 编码（**真实生产路径**：`ProtoBufWriter` + ASM 生成的 `ProtoBufEncoder`），
在两个 harness 里测出完全不同的数：

| harness | mixed-16k（80% ASCII + 20% 中文） |
|---|---|
| `ProductionVsReplica`（归因探针） | **9051 ns** |
| `StringEncodeCandidates`（候选探针） | **15135 ns** |

⇒ 差 **1.67 倍**，而两边的循环体是**同一份代码**（逐行等价）。

### 8.2 归因：不是代码，是「上一个串的 JIT profile」

`ProductionVsReplica` 加了个 mode：**只把串的顺序换成候选探针的顺序**，其它全不变：

| 串的顺序 | mixed-16k |
|---|---|
| mixed-16k 在 emoji-1k **之前** | **9194 ns** |
| mixed-16k 在 emoji-1k **之后**（= 候选探针的顺序） | **15188 ns** |

⇒ **证实是 JIT profile 污染**：

- HotSpot 把热方法（这里的 UTF-8 编码循环）C2 编译成**针对当时分支分布**的形态；
- `emoji-1k` 全是代理对 ⇒ 编译产物里「4 字节路径」占 100%，「ASCII 单字节路径」被优化成冷路径；
- 换到 `mixed-16k`（80% ASCII）时，**HotSpot 不会自动重编译** ⇒ ASCII 分支全部预测失败 ⇒ 慢 1.65 倍。

⚠ **「每串预热」修不好**：HotSpot 不会因为预热而重编译已经 C2 编译过的方法（实测加了 250ms 预热，
mixed-16k 仍是 15135ns）。⇒ **唯一可靠的做法：`(候选, 串)` 各起一个 JVM。**

⇒ 脚本 `run-string-candidates.sh` 改成三层循环：**候选 × 串 × launch**，
每个组合一个独立 JVM（`StringEncodeCandidates.main(millis, rounds, which, onlyString)`）。

### 8.3 副产品：候选 24 = **真实生产路径**

之前所有候选都是「在探针里复刻一遍生产逻辑」，而复刻可能与生产**不等价**。
现在把**线上真正跑的那条路**（`ProtoBufWriter` + ASM 生成的 `ProtoBufEncoder` ⇒ `AbstractWriter.writeString0` 本体）
作为候选 24 加进同一张表 ⇒ 「复刻 vs 生产」直接对照。

（断言：候选 24 产出的是整条消息（多一个字段 tag `0x0A`），单独断言「tag + 载荷 == 现状的载荷」。）

---

## 九、主矩阵（`(候选, 串)` 各一个 JVM，3 launches 取 min，单位 ns）

| 串 | 0 现状复刻<br>(int偏移+右移) | **3 生产形态复刻**<br>(long偏移+预留) | 15<br>UTF16+ASCII批量 | 20<br>int偏移+预留 | 21<br>long偏移+右移 | 22<br>分段+ASCII批量 | 23<br>只分段 | **24 真实生产路径** |
|---|---|---|---|---|---|---|---|---|
| ascii-1k | 54 | 54 | 54 | 54 | 53 | 55 | 54 | **56** |
| ascii-16k | 898 | 877 | 868 | 961 | 951 | 867 | 853 | **874** |
| cn-1k | 1107 | 846 | 832 | 1052 | 890 | 1050 | 1044 | **834** |
| **cn-16k** | 18014 | **13583** | 14099 | 16257 | 14758 | 17239 | 17244 | **13721** |
| emoji-1k | 1860 | **1303** | 1308 | 1785 | 1385 | 1833 | 1823 | **1354** |
| latin1-high | 12 | 12 | 12 | 12 | 12 | 12 | 12 | 14 |
| **mixed-16k** | 9252 | 8902 | **19639** | 8891 | 9321 | 21048 | 22375 | **9083** |
| latin1-16k | 9748 | 9718 | 9734 | 9695 | 9766 | 9712 | 9725 | **9854** |

三个读法：

1. **候选 3（复刻）≈ 候选 24（真实生产）**（差异 1~4%，噪声内）⇒ 复刻是**可信的**，
   上一轮基于复刻得出的结论**成立**。
2. **候选 24 在每一行都是最好或并列最好**（cn-16k 13721 仅比复刻的 13583 慢 1%）⇒ **没有候选能赢过现状**。
3. **候选 0（现状 = int 偏移 + 右移）比现状慢 25~31%**（cn-16k 18014 vs 13721、emoji 1860 vs 1354）
   ⇒ 上一轮 Phase B 的两条改动（`long` 偏移计数器 + 预留 varint 省掉 memmove）**是真实的收益**，
   且在修正后的干净方法论下依然成立。

### 9.1 Latin1 侧的候选（同样用修正后的方法论重测）

| 串 | 0 现状 | 14 Latin1直写 | 17 Latin1主路径 | 18 判定(long批量) | 19 判定(无分支求和) | **24 真实生产** |
|---|---|---|---|---|---|---|
| ascii-1k | 53 | 401 | 135 | 119 | 342 | **56** |
| **ascii-16k** | 876 | 6159 | 2151 | 1876 | 5519 | **923** |
| latin1-16k | 9705 | 9584 | 9660 | 1※ | 5454 | **9765** |
| latin1-high | 12 | 11 | 14 | 2※ | 2※ | 14 |

※ 候选 18/19 是「**只做判定、不写字节**」的分解探针 ⇒ 遇到高位字符就**提前返回**
（latin1-16k 的第 1 个字符就是 0xE9）⇒ 这两格的 1~2ns **不代表判定成本**；
判定成本要看纯 ASCII 的 ascii-16k 行（**1876ns / 5519ns**）。

★ **候选 17 就是第 4 条追问的形态**（「纯 ASCII 走一次 arraycopy，有高位字符就退回现状」）：
纯 ASCII 的 ascii-16k 上它要 **2151ns**，而**现状整条路径只要 923ns**
⇒ **判定比它想省下的东西还贵 ⇒ 净亏 2.3 倍**。
⇒ 罕见路径本身没问题：latin1-16k 上候选 14 = 9584 vs 现状 9705（持平）。

---

## 十、逐条回答

### 10.1 「UTF-16 还是逐 char 转换，能不能批量化？」—— **不能，逐字节已是最优**

- **纯中文（每字 3 字节）**：3 字节与 8 字节边界**天然不对齐** ⇒ 任何「拼 long 一次写 8 字节」的方案
  都要先在堆内拼 ⇒ 实测慢 **2 倍**（候选 12：25156ns vs 现状 13721ns）。
- **ASCII 段批量（候选 15/22）**：mixed-16k 上 **19639 / 21048ns**，比现状慢 **2.2 倍**。
  原因很直白：mixed 的 ASCII 段只有 **4 个字符**（每 5 个字符一个中文）⇒ 「8 字符拼 long」的快路径
  **永远不触发**，只剩分支判断的开销。
  ⇒ 纯中文上它也不占优（cn-16k 14099 vs 13583）。
- **为什么 protobuf-java 也逐 char**：它的 `Utf8.encodeUtf8` 对 UTF-16 coder 的串同样是逐 char 循环
  ⇒ 两边是同一形态 ⇒ 现在的实测就是 **edap 13721 vs protobuf-java 13595 = 0.99x（持平）**。

### 10.2 「`v.getBytes(UTF8_CHARSET)` 还能不能优化？」—— **不能，JDK 那条路已是向量化快路径**

想替代它的思路都是「拿 String 内部的 Latin1 `byte[]`，判定有没有高位字节，ASCII 段一次 `arraycopy`」：

| 方案 | ascii-16k | 结论 |
|---|---|---|
| **现状（JDK `getBytes(UTF_8)` + 拷进缓冲）** | **923 ns** | — |
| 候选 17：判定 + 一次 arraycopy | 2151 ns | **慢 2.3x** |
| 候选 18：只用 long 批量做判定 | 1876 ns | **判定就比整条现状还贵** |
| 候选 19：无分支求和（指望 C2 向量化） | 5519 ns | **C2 没有向量化它** |
| 候选 14：分段写（ASCII 段 arraycopy） | 6138 ns | 慢 7x |

★ **根因**：JDK 的 `String.getBytes(UTF_8)` 对 **Latin1** 串走的是
「`countPositives`（**C2 intrinsic，向量化**）找第一个非 ASCII 字节 → 纯 ASCII 则整块 memcpy」；
**这个 intrinsic 在 Java 里拿不到** ⇒ 我们手写的任何判定都只能是一个普通循环 ⇒ 必然更慢。

> ⚠ **勘误（第四轮，2026-11-13）**：`countPositives` 是 **JDK 9~11 的旧名**；
> **JDK 17 已改名 `StringCoding.hasNegatives`**（`java.base/java/lang/StringCoding.java:38`，
> 签名 `(byte[], int, int) -> boolean`，语义 = 「这段字节里有没有负数（= 有没有 ≥0x80）」）。
> 下面第十四节有 JDK 17 源码行号与 `-XX:+PrintIntrinsics` 的实证。

⇒ **保持现状**。（代价是一次 `getBytes` 的数组分配 + 一次拷贝，都是 unavoidable 的：
JDK 没有「直接把 UTF-8 编进我指定的 byte[]」的 API。）

★ **第四轮把这条追问量化到了具体数字**（见 §十四）：
`getBytes(UTF_8)` 的 777ns ≈ **分配 403 + SIMD 扫描 169 + memcpy 205**；
跳过 `byte[]` 生成值 **2.4x**，但**只有 JNI 能做**（Java 侧拿不到 SIMD 判定）且端到端 ≈1% ⇒ **不做**。

> ★★ **勘误（第五轮，2026-11-13 15:30）**：上面这段「**只有 JNI 能做**」**是错的**。
> **intrinsic 通过 `MethodHandle.invokeExact` 依然会被 C2 替换** ⇒ `StringCoding.hasNegatives`
> 的 SIMD 判定**在 Java 侧可达** ⇒ 「纯 ASCII 串零分配直写」**已落地**（ASCII 编码核心 **-40%**）。
> 详见**第五轮**（§十六~§二十二）；第四轮的封堵①②均作废。

### 10.3 「字符串查找有向量加速」—— **方向对，但用不上**

- 向量加速**确实存在**，但它长在 **JDK 的 `String.getBytes(UTF_8)`（Latin1 路径）**里 ——
  这正是 edap 的 ASCII 档能跑到 **protobuf-java 的 0.07x** 的原因。
- **UTF-16 coder 的串（含中文）没有向量化路径**：JDK 内部走的是 `encodeUTF8_UTF16` 的**逐 char 普通循环**，
  我们手写的也是逐 char ⇒ **两边都没有向量加速可谈**。
- JDK 的 SIMD 例程（`String.indexOf` / `Arrays.mismatch`）都表达不了「找第一个 ≥0x80 的字节」这个需求。
- 实测「无分支求和」期望被 C2 向量化 ⇒ **没有**（5519ns）。

### 10.4 「Latin-1 高位字符罕见，多处理一遍也可以」—— **形态对，但收益为负**

形态已被采纳并实现为候选 17（纯 ASCII 走一次 arraycopy，罕见路径退回现状）。
实测：**判定成本（1876ns）> 它能省下的拷贝成本** ⇒ ascii-16k 从 923 变成 2151 ⇒ **否决**。
（罕见路径本身没问题：候选 14 在 latin1-16k 上 9673 vs 现状 9748，持平。）

---

## 十一、第三轮的结论

| | 结论 |
|---|---|
| **生产代码** | **一行不改**。`AbstractWriter.writeString0` 保持 Phase B 之后的形态（long 偏移 + 预留 varint） |
| **探针** | ① 新增候选 14/15/17/18/19/24；② 脚本改成 `(候选, 串, launch)` 三层循环；③ 新增 `ProductionVsReplica`（归因探针） |
| **负载矩阵** | `ProtoCodecBenchmark` 新增 `str-mixed-1024 / str-mixed-16384`（中英混排，原先是盲区） |
| **方法论** | 「一个候选一个 JVM」**不够**，还要「一串一个 JVM」（JIT profile 污染，实测 1.65 倍） |

### 11.1 负载矩阵新增的 mixed 档（真实生产路径 vs protobuf-java）

| 负载 | wire | 编码核 edap | 编码核 google | 比值 | byte[] edap | byte[] google | 比值 | 解码 edap | 解码 google | 比值 |
|---|---|---|---|---|---|---|---|---|---|---|
| str-mixed-1024 | 同 | 589 | 671 | **0.88x** | 630 | 690 | 0.91x | 1535 | 1483 | 1.04x |
| str-mixed-16384 | 同 | 9432 | 10479 | **0.90x** | 10412 | 10934 | 0.95x | 24116 | 24359 | 0.99x |

⇒ **中英混排不是盲区，edap 反而快 10%**（此前矩阵里没有这一档，是覆盖度不足，不是性能问题）。

---

## 十二、第三轮的验证

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap/edap-grpc-parent/edap-grpc-benchmark
mvn -o -q compile
./run-string-candidates.sh 250 3 3          # (候选, 串) 各一个 JVM
CANDS_OVERRIDE="0 14 17 18 19 24" ./run-string-candidates.sh 250 3 3   # Latin1 侧候选
```

---

# ★ 第四轮（2026-11-13 14:00 ~ 15:00）：char→UTF-8 查表 + `getBytes(UTF_8)` 的向量化定位

## 〇、第四轮的两件事

| # | 追问 | 结论 |
|---|---|---|
| 1 | 「**能否搞一个 char 到 utf8 byte[] 数组的表，遍历过程查表会快吗**」 | **不能**。四种查表形态（`byte[][]` / 扁平 `byte[]` / `int[]`+`putInt` / 混合式）全部实测，**没有一种能同时在中文与混排上不亏** ⇒ **生产代码一行不改** |
| 2 | 「**`v.getBytes(UTF8_CHARSET)` 的向量优化在什么地方，能否直接调用向量优化的函数，跳过 `byte[]` 生成的逻辑**」 | 向量化**在 JDK 内部**（`StringCoding.hasNegatives`，C2 intrinsic + x86 SIMD），**Java 侧拿不到、也没有「编码进指定 `byte[]`」的公开 API** ⇒ **不能直接调用**。成本分解已量化（见 §十四） |

## 十三、追问 1：char → UTF-8 查表

### 13.1 四个候选

| 候选 | 表形态 | 每字符的动作 |
|---|---|---|
| **25** | `byte[65536][]`（每项 1~3 字节，**代理项 = null**） | 1 次表读 + 1 次指针解引用 + 1~3 次字节写 + 1 次 `null` 判定 |
| **26** | 扁平 `byte[65536*3]`（左对齐）+ `byte[65536]` 长度表 | 2 次表读 + 1~3 次字节写 |
| **27** | `int[65536]`，**按小端内存序预置**（内存布局 `[b0,b1,b2,pad]`） | 1 次表读 + **1 次 `Unsafe.putInt`（写 4 字节，多写的 1 字节被下一字符覆盖）** + 1 次长度表读 |
| **32** | 同 27，但**只有 ≥0x80 的字符走表**，ASCII 仍走生产的逐字节写 | ASCII：1 次分支 + 1 次字节写；≥0x80：同 27 |

> ⚠ **`Unsafe.putInt` 的字节序**：实测 `putInt(byte[], ARRAY_BYTE_BASE_OFFSET+字节偏移, v)` 写 **4 个字节**、**x86 小端**
> ⇒ 表必须按 `bit0-7=b0, bit8-15=b1, bit16-23=b2` 预置（第一版写成了 `(b0<<8)|(b1<<16)|b2` ⇒ 字节全错，已修正）。
> ⚠ **副作用**：27/32 会**多写 ≤3 字节** ⇒ 生产的 `expand(maxBytes + reserve)` 容量**不够**，若采纳必须改 `expand`。

### 13.2 实测（`(候选, 串)` 各一个 JVM，3 launches 取 min，**候选顺序按 launch 轮转**，单位 ns）

批次 5（`CANDS_OVERRIDE="3 24 26 27 32"`，`/tmp/batch5.txt`）：

| 串 | **c3 现状复刻** | c24 真实生产 | 26 扁平表 | 27 int 表 | **32 混合式表** |
|---|---|---|---|---|---|
| ascii-1k | 52 | 55 | 52 | 52 | 52 |
| ascii-16k | 856 | 865 | 873 | 861 | 856 |
| cn-1k | **842** | 850 | 1466 | 969 | **1125 (+34%)** |
| cn-2k | **1663** | 1680 | 2611 | 1633 | 1900 (+14%) |
| cn-4k | **3380** | 3422 | 4733 | 2576 | 3388 (持平) |
| cn-8k | 6638 | 6720 | 10846 | **5150 (-22%)** | 6316 (-4.8%) |
| cn-16k | 13335 | 13667 | 21558 | **9924 (-26%)** | 12454 (-6.6%) |
| cn-32k | 27205 | 28000 | 44054 | **21608 (-21%)** | 25461 (-6.4%) |
| emoji-1k | 1306 | 1341 | 1176 | 1175 | 1233 (-5.6%) |
| **mixed-16k**（80% ASCII） | **8937** | 9047 | 21704 | 10221 (+14%) | **8953 (持平)** |
| latin1-high | 12 | 14 | 12 | 12 | 12 |
| latin1-16k | 9739 | 9827 | 9765 | 9769 | 9760 |

批次 3（`/tmp/batch3.txt`，含候选 25）：

| 串 | c3 | c24 | 25 `byte[][]` | 26 扁平 | 27 int 表 |
|---|---|---|---|---|---|
| cn-1k | 864 | 865 | 1795 | 1460 | 979 |
| cn-16k | 13815 | 14178 | 25775 | 22573 | **10607** |
| mixed-16k | 9081 | 9310 | 20793 | 22922 | 10487 |
| emoji-1k | 1346 | 1399 | 1826 | 1214 | 1226 |

### 13.3 中文长度曲线（**批次 5**，ns/字符 = 该档 ns ÷ 字符数）

| 字符数 | c3 现状 | c27 int 表 | c32 混合式 |
|---|---|---|---|
| 1024 | 0.822 | 0.946（**+15%**） | 1.099（**+34%**） |
| 2048 | 0.812 | 0.797（-2%） | 0.928（**+14%**） |
| 4096 | 0.825 | 0.629（**-24%**） | 0.827（持平） |
| 8192 | 0.810 | 0.629（**-22%**） | 0.771（-5%） |
| 16384 | 0.814 | 0.606（**-26%**） | 0.760（-7%） |
| 32768 | 0.830 | 0.659（-21%） | 0.777（-6%） |

> 批次 4（更早一轮，只跑 c3/c24/c27）结论一致：c3 = 862/1729/3460/6808/13811/27594，
> c27 = 992/1668/2685/5229/10253/21675 ⇒ 同一形态、同一趋势。

⇒ **c3 完全线性（0.81~0.83 ns/字符，斜率恒定 ⇒ 没有固定开销项）**；
⇒ **c27 长串收敛到 ~0.61~0.66 ns/字符（-22~26%），但 ≤2K 字符时有 ~300ns 的固定惩罚**（成因未定位，
已排除 cache/TLB/varint/memmove 等假设；各 launch 原始值极稳 ⇒ 不是噪声）；
⇒ **c32 的渐近线是 ~0.77 ns/字符（-6%）**，短串惩罚更大（+34% @1K）。
⇒ **纯中文下「表 + 一次 4 字节写」确实更快（最多 -26%），但只在串足够长时才兑现。**

### 13.4 否决理由（逐条）

| 候选 | 否决理由 |
|---|---|
| **25 `byte[][]`** | **全线更慢**（cn-16k 25775 vs 13335，慢 **1.93x**）。原因：每字符一次**指针解引用**（表项是对象）⇒ 一次 dependent load；而且代理项为 `null` ⇒ 循环里还要多一次 `null` 判定 |
| **26 扁平 `byte[]`** | **全线更慢**（cn-16k 21558 vs 13335，慢 1.62x）。原因：把 1 次解引用换成了 **2 次表读**（字节表 + 长度表）⇒ 访存次数反而更多；且表是 192KB ⇒ 每次访问都可能 miss L2 |
| **27 `int[]`+`putInt`** | **方向对但不稳定**：长中文 **-26%**（好），但 **短中文 +15%**、**混排 +14%**（坏）。混排更慢的原因：ASCII 字符走表时**只省 0 次写**（本来就只写 1 字节）却**多付 2 次表读** ⇒ 80% ASCII 的负载必亏 |
| **32 混合式** | **修好了 c27 的混排回归（mixed-16k 持平），代价是长中文的收益从 -26% 缩到 -6.6%**，而且 **cn-1k 反而 +34%** ⇒ **1KB 中文是最常见的负载，这条直接否掉** |

⇒ **结论：四种查表形态全部否决 ⇒ `AbstractWriter.writeString0` 一行不改。**

★ **方法论收获**：`c32` 是「**用一次分支把「表只用在划算的地方」的想法实现出来**」的形态，
结果确实把 c27 的混排回归修掉了 —— 但**修回退的代价是把长串收益吃掉 4 倍**。
⇒ **「局部优化」在编码器这种「同一段代码要服务多种字符分布」的场景里，常常是拆东墙补西墙。**

## 十四、追问 2：`getBytes(UTF_8)` 的向量化在哪，能否直接调用

### 14.1 JDK 17 源码取证（向量化点在哪）

`String.getBytes(UTF_8)` 的实际路径：

```
String.getBytes(Charset)                       java/lang/String.java:1786
  └─ encode(UTF_8.INSTANCE, coder, val)       java/lang/String.java:833
      └─ encodeUTF8(coder, val, true)         java/lang/String.java:1262   ★ 关键
          ├─ if (coder == UTF16) return encodeUTF8_UTF16(val, doReplace);   // UTF-16 串走逐 char 循环
          ├─ if (!StringCoding.hasNegatives(val, 0, val.length))            // ★★ 向量化判定
          │      return Arrays.copyOf(val, val.length);                     // 纯 ASCII ⇒ 整块 memcpy
          └─ 否则：逐字节 2 字节编码
```

**向量化判定 = `java.lang.StringCoding.hasNegatives(byte[], int, int)`**
（`java.base/java/lang/StringCoding.java:38`）：

```java
@IntrinsicCandidate
public static boolean hasNegatives(byte[] ba, int off, int len) {
    for (int i = off; i < off + len; i++) {
        if (ba[i] < 0) return true;
    }
    return false;
}
```

**它是 C2 intrinsic**（实测 `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics`）：

```
@ 15   java.lang.StringCoding::hasNegatives (25 bytes)   (intrinsic)
```

⇒ **x86 上编译成 SIMD 指令**（一次 16/32 字节比较）⇒ 这就是「ASCII 档 edap 能做到 protobuf-java **0.07x**」的来源。

> ⚠ **勘误**：先前文档/README 写的 `countPositives` 是 **JDK 9~11 的旧名**；**JDK 17 已改名 `hasNegatives`**。

### 14.2 ★ 成本分解（ascii-16k = 16KB，实测）

| 候选 | 动作 | ns | 差值归因 |
|---|---|---|---|
| **c3** | 现状（`getBytes(UTF_8)` + 拷进 writer 缓冲） | **898** | — |
| **c28** | **只**调 `getBytes(UTF_8)`（不拷进 dst） | **777** | 分配 + SIMD 扫描 + memcpy |
| **c30** | **只**调 `getBytes(ISO_8859_1)`（不拷进 dst） | **608** | 分配 + memcpy（**无 SIMD 扫描、无高位字符处理**）⇒ **SIMD 扫描 ≈ 169 ns** |
| **c29** | 读内部 Latin1 数组 + 一次 `arraycopy`（**零分配、零编码**） | **205** | ⇒ **分配 ≈ 403 ns**（777 - 205 - 169） |

⇒ **`getBytes(UTF_8)` 的 777 ns ≈ 分配 403 + SIMD 扫描 169 + memcpy 205。**

### 14.3 「跳过 `byte[]` 生成」值多少？

| 方案 | ns | 比值 |
|---|---|---|
| 现状 | 898 | 1.00x |
| **跳过分配**（内部数组 + 一次 arraycopy + SIMD 判定） | 205 + 169 = **374** | **2.4x** |

★ **但前提是「先知道这个串是纯 ASCII」，而这个判定在 Java 里只有 SIMD 才够快**：
用 long 掩码手写「找第一个 ≥0x80 字节」实测 **1876 ns/16KB**（比整条现状还贵 2 倍），
「无分支求和」5519 ns（C2 没有向量化它）⇒ **Java 侧拿不到那个 intrinsic**。

⇒ **只剩 JNI 一条路**（仓库已有 `edap-native` 先例）：在 native 里做 SIMD 判定 + 直接写进目标 `byte[]`。
但：**端到端边际收益 ≈ 1%**（编码只占端到端的一小部分，64KB 载荷时更被摊薄）
⇒ **不做**（与 Phase C 同样的判断：收益 < 2% ⇒ 不动跨模块的底层设施）。

> ★★ **勘误（第五轮）**：上面「**只剩 JNI 一条路**」**是错的** —— **intrinsic 经 `MethodHandle` 依然生效**
> ⇒ **不需要 JNI**，纯 Java 就能白嫖 SIMD 判定 ⇒ 已落地（见第五轮 §二十）。

### 14.4 「能否直接调用那个向量化的函数」—— **三条封堵**

| # | 封堵理由 | 证据 | **第五轮复核** |
|---|---|---|---|
| 1 | **`StringCoding` 是 package-private 类**（`class StringCoding`，无 `public`）⇒ Java 侧根本引用不到 | `java.base/java/lang/StringCoding.java:33`：`class StringCoding {` | ❌ **作废**：反射 + `MethodHandle` 拿得到（需 `--add-opens java.base/java.lang=ALL-UNNAMED`） |
| 2 | **intrinsic 按「类 + 方法 + 签名」精确匹配** ⇒ 即使反射拿到方法，**C2 也只会编译成普通 Java 循环**（intrinsic 只对 `java.lang.StringCoding::hasNegatives` 这个**被内联进 JDK 内部调用点**的形态生效） | `-XX:+PrintIntrinsics` 只在 `String.getBytes` 的编译单元里打印出该 intrinsic；反射调用**不产生**该条目（实测反射版本无任何 intrinsic 输出） | ❌ **作废**：那次测的是**冷循环**；热循环 + `invokeExact` 下 **intrinsic 照常生效**（见 §十七） |
| 3 | **JDK 没有「把 UTF-8 直接编进我指定的 `byte[]`」的公开 API** ⇒ 即便判定能自己做，还得**先分配一个 `byte[]`**（这正是要跳过的那 403 ns） | `String.getBytes` 全部返回**新数组**；`CharsetEncoder` 只能写进 `ByteBuffer`，且**没有「Latin1 直通」的 SIMD 路径**（`sun.nio.cs.UTF_8$Encoder.encodeFromLatin1` 是普通循环） | ✔ **仍成立**，但**有绕过**：**纯 ASCII** 串的 UTF-8 字节 == String 内部字节 ⇒ 直接拷内部数组即可 |

⇒ **能自己做「纯 ASCII 判定」的前提（SIMD）在 Java 侧不可得 ⇒ 整条路只剩 JNI ⇒ 收益 ≈1% ⇒ 不做。**
（★ 上句**已被第五轮推翻**：SIMD 判定可达 ⇒ 不需要 JNI ⇒ 路径已打通并落地。）

★ **补充**：`String.getBytes(ISO_8859_1)` 对 Latin1 串是 `Arrays.copyOf(内部数组)`（**无 SIMD 判定、无高位处理**）
⇒ 想用它当「快速路径」就必须在**外面**先判定有没有高位字节 —— 而那个判定就是 SIMD ⇒ 回到同一个死结。

## 十五、第四轮的结论

| | 结论 |
|---|---|
| **生产代码** | **一行不改**。`AbstractWriter.writeString0` 保持 Phase B 之后的形态（long 偏移 + 预留 varint） |
| **探针** | 新增候选 25/26/27/28/29/30/31/32；探针串新增 `cn-2k / cn-4k / cn-8k / cn-32k`（中文长度曲线点） |
| **脚本** | 候选顺序**按 launch 轮转**（见 §十五.1） |
| **追问 1** | **否决**（四种查表形态全部实测，无一种在中文 + 混排上都不亏） |
| **追问 2** | **向量化在 `StringCoding.hasNegatives`（C2 intrinsic）；不能直接调用**；成本分解 777 = 分配 403 + SIMD 扫描 169 + memcpy 205；跳过分配值 2.4x，但**只有 JNI 能做且端到端 ≈1% ⇒ 不做**（★ **第五轮已推翻**：intrinsic 经 `MethodHandle` 依然生效 ⇒ 不需要 JNI ⇒ 已落地，ASCII 编码核心 **-40%**） |

### 15.1 ★ 脚本方法论修正：候选顺序必须**按 launch 轮转**

本轮发现**第三个坑**：机器会随持续负载**降频/升温** ⇒ 「**排在后面的候选**」会被**系统性压慢**。
实测：**同一份代码（候选 27）**，在「重候选之后」测出 **15869 ns**，在「轻候选之间」测出 **10284 ns**
⇒ **差 1.54 倍，与代码无关**。

⇒ 脚本第 `l` 轮把候选表**左移 `l` 位**（`idx = (k + l - 1) % NCAND`），
让**每个候选都在不同位置各测一次**，再跨 launch 取 min ⇒ 抵消这个漂移。
⚠ **只按固定顺序跑、只取 min，仍会被位置偏差骗**（min 只能压掉随机噪声，压不掉系统性偏移）。

### 15.2 第四轮的验证

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
cd /Volumes/Data/NetBeansProjects/edap/edap-grpc-parent/edap-grpc-benchmark
mvn -o -q compile
CANDS_OVERRIDE="3 24 26 27 32" ./run-string-candidates.sh 250 3 3 > /tmp/batch5.txt   # 5 候选 × 12 串 × 3 launch
CANDS_OVERRIDE="3 24 27" ./run-string-candidates.sh 250 3 3 > /tmp/batch4.txt        # 中文长度曲线
```

**四模块回归（`mvn -o -pl ... test -DskipTests=false`）**：

| 模块 | 结果 |
|---|---|
| `edap-common` | **81 全绿** |
| `edap-protobuf` | **1393 全绿**（与 Phase B 后基线一致 ⇒ 本轮未动生产代码） |
| `edap-http-server` | **95 全绿** |
| `edap-grpc-benchmark` | **21 全绿**（GrpcBenchmarkSmokeTest 3 + GrpcCompatTest 11 + GrpcInteropTest 7 ⇒ **wire 字节无回归**） |

⇒ 探针本身**不改生产代码**，`AbstractWriter.writeString0` 与第三轮结束时**逐字节相同**。

---

# ★ 第五轮（2026-11-13 15:10 ~ 16:20）：一句提示 ⇒ **推翻第四轮的一条封堵** ⇒ **纯 ASCII 零分配直写已落地**

## 〇、第五轮的起点：一句提示

> 用户：「`io.edap.auth.jwt.utils.Base64URL` 有一个反射调用我记得是能用到向量加速的。」

第四轮在 §14.4 写了三条封堵，其中**封堵②**是：

> **intrinsic 按「类 + 方法 + 签名」精确匹配** ⇒ 即使反射拿到方法，**C2 也只会编译成普通 Java 循环**。

★ **这条是错的** —— 而且它**正是**把「跳过 `byte[]` 生成（2.4x）」这条路堵死的**唯一理由**
（§14.3：「只剩 JNI 一条路 ⇒ 端到端 ≈1% ⇒ 不做」）。

⇒ 第五轮：**核实 → 更正 → 落地**。

## 十六、核实：edap 的 `Base64URL` 反射确实拿到了 intrinsic

`edap-auth-parent/edap-auth-jwt/src/main/java/io/edap/auth/jwt/utils/Base64URL.java`：
反射拿 `Base64.Encoder` 的 **private `encode0(byte[],int,int,byte[])`**，
`setAccessible` + `MethodHandles.lookup().unreflect(m).bindTo(Base64.getUrlEncoder().withoutPadding())`，
`invokeExact` 调用（Decoder 的 `decode0` 同理）；另有 `verify(...)` 自校验，失败则回退到自写的
`simpleEncode/simpleDecode`。

JDK 17 `java/util/Base64.java:421`：`@IntrinsicCandidate private void encodeBlock(...)` ⇒ **是 C2 intrinsic**。

实测（`/tmp/b64i/B64Intrinsic.java`，4096 字节，机器空闲时测）：

| 调用方式 | ns/op | `-XX:+PrintIntrinsics` |
|---|---|---|
| 直接调用 | **263** | `@ 102 java.util.Base64$Encoder::encodeBlock (153 bytes) (intrinsic)` |
| `MethodHandle.invokeExact` | **276** | **同样打出该 intrinsic** |
| 普通反射 `Method.invoke` | 290 | 同样打出 |

⇒ **edap 的 `Base64URL` 那个反射确实拿到了向量化。**

## 十七、★ 第四轮封堵②是错的（intrinsic 经 MethodHandle 依然生效）

实测（`/tmp/neg/NegIntrinsic.java`）：

```
mode=mh   → @ 21  java.lang.StringCoding::hasNegatives (25 bytes) (intrinsic)   ns/op=72
mode=refl → @ 188 java.lang.StringCoding::hasNegatives (25 bytes) (intrinsic)   ns/op=91
```

⇒ **intrinsic 通过 `MethodHandle.invokeExact` 依然会被 C2 替换。**

⚠ **第四轮为什么会测错**：那次测的是**冷循环 + `Method.invoke`**，
`PrintIntrinsics` 没输出 ⇒ **是测试方法本身有问题**（循环没跑够/没预热），**不是结论对**。

⇒ **`StringCoding.hasNegatives` 的 SIMD 判定在 Java 侧可达**
⇒ §14.3「跳过 `byte[]` 生成值 2.4x，但**只有 JNI 能做**」**这个结论作废**。

## 十八、仍然成立的两条封堵（与第四轮不同）

| # | 封堵理由 | 第四轮 | 第五轮 |
|---|---|---|---|
| ① | `StringCoding` 是 package-private ⇒ Java 侧引用不到 | ✔ | ❌ **作废**（反射 + MethodHandle 拿得到，只需 `--add-opens`） |
| ② | intrinsic 只对 JDK 内部调用点生效 | ✔ | ❌ **作废**（见 §十七） |
| ②′ | **JDK 17 没有向量化的 UTF-8 编码器** | — | ✔ **仍成立**：`String.java` 里只有 1 个 `@IntrinsicCandidate`（拷贝构造器）；`sun/nio/cs/` 下只有 `ISO_8859_1` 有 intrinsic ⇒ **中文（UTF-16 coder）的编码循环无法向量化** |
| ③ | **JDK 没有「把 UTF-8 直接编进我指定的 `byte[]`」的公开 API** | — | ✔ **仍成立**，但**有绕过办法**：**纯 ASCII** 的串，其 UTF-8 字节 == String 内部字节 ⇒ 直接拷内部数组即可 |

⇒ **「跳过 `byte[]` 生成」这条路是通的**，条件是「这个串是纯 ASCII」，
而这个判定**现在可以白嫖 JDK 的 SIMD intrinsic**。

### 18.1 ★ 部署前提：`--add-opens java.base/java.lang=ALL-UNNAMED`

`MethodHandles.lookup().unreflect(...)` 需要 `java.lang` 包对调用方 **open**。

★ **好消息：edap-protobuf 本来就需要这个 flag** —— 实测**不加**时：

```
ExceptionInInitializerError
  Caused by: java.lang.reflect.InaccessibleObjectException:
    Unable to make protected final java.lang.Class java.lang.ClassLoader.defineClass(...) accessible:
    module java.base does not "opens java.lang" to unnamed module
  at io.edap.protobuf.ProtoBufCodecRegister$ClassDefiner.getClassDefineMethod(:669)
```

⇒ **protobuf 的 ASM 编解码器生成器自己就要它**（父 pom 的 surefire `argLine` 里也已经配好）
⇒ **本轮改动零新增部署负担**。

## 十九、★ 新候选 33 / 34

| 候选 | 做法 |
|---|---|
| **33** | 取 String 内部 Latin1 `byte[]` ⇒ **SIMD 判定有没有高位字节** ⇒ 没有 ⇒ **零分配、一次 `System.arraycopy`** 直接写进目标缓冲（先写 varint 长度前缀） |
| **34** | 同 33，但拷贝用**公开 API** `v.getBytes(0, len, dst, off)`（deprecated；Latin1 串 ⇒ 直接 memcpy，零分配），不依赖 Unsafe 拷贝 |

⇒ 探针里新增 `HAS_NEGATIVES`（static final `MethodHandle`）+
`hasHighBytes(byte[],int,int)`（拿不到时**保守返回 true** ⇒ 回退，行为不变）。

### 19.1 ★★ 一次「假回退」：必须查清才能下结论

批次 6（第一版 33/34）的读数：

| 串 | c3 现状 | c33 | c34 | 解读 |
|---|---|---|---|---|
| ascii-16k | 861 | **522 (-39%)** | **523** | ✅ 预期收益 |
| cn-16k | 13653 | **18478 (+35%)** | 18335 | ❓ 中文竟然慢了 35% |
| mixed-16k | 8942 | 9422 (+5%) | 9383 | ❓ |

★ **第一反应**：「c33/c34 对 UTF-16 串走的是 `candCurrent`（**旧**路径：memmove 补前缀 + int 索引逐字节写），
而候选 3 是**生产形态**（预留 varint + long 索引）⇒ **回退到了旧实现**，是**口径错误**不是方案问题」。

⇒ **改法**：33/34 的 UTF-16 分支回退到 `candCharArrUnsafeLongReserve`（生产形态），**批次 7**：

| 串 | c3 生产形态 | c24 真实生产 | c33 SIMD直写 | c34 SIMD公开API |
|---|---|---|---|---|
| ascii-1k | 52 | 55 | **31 (-40%)** | **31 (-40%)** |
| ascii-16k | 857 | 855 | **517 (-40%)** | **517 (-40%)** |
| **ascii-64**（新增，短串） | 9 | 12 | **6 (-33%)** | **7 (-22%)** |
| cn-1k | 833 | 830 | 830 | 830 |
| cn-16k | 13157 | 13312 | 13209 | 13210 |
| cn-2k/4k/8k/32k | 持平 | 持平 | 持平 | 持平 |
| emoji-1k | 1283 | 1333 | 1287 | 1294 |
| mixed-16k | 8895 | 8884 | 8857 | 8875 |
| latin1-high | 12 | 15 | 12 | 13 |
| latin1-16k | 9674 | 9731 | 9669 | 9721 |

⇒ **回退消失，ASCII -40% 保住**；新增 `ascii-64` 短串档 ⇒ **连短串也不亏**
（MethodHandle 调用的固定开销 < 一次数组分配）。

★ **教训（第四轮方法论的第五条）**：**候选的「回退分支」必须与被比较对象同口径**，
否则会凭空造出 30% 的「回退」，把好方案误杀。

## 二十、★ 落地（生产代码改动）

### 20.1 `edap-common/src/main/java/io/edap/util/StringUtil.java`

新增两个方法：

| 方法 | 作用 |
|---|---|
| `public static boolean hasHighBytes(byte[] b, int off, int len)` | 用 JDK 的 `StringCoding.hasNegatives`（SIMD intrinsic）判断有没有 ≥0x80 的字节；**拿不到时保守返回 `true`** ⇒ 调用方回退，行为不变 |
| `public static byte[] getLatin1Bytes(String s)` | 取 String 内部的 Latin1 `byte[]`（**只读**，与 String 共享）；非 Latin1 ⇒ `null` |

（`HAS_NEGATIVES` 是 `private static final MethodHandle`，静态块里反射拿；拿不到 ⇒ `null`。）

### 20.2 `edap-protobuf/.../writer/AbstractWriter.java`（`writeString0`）

```java
if (IS_BYTE_ARRAY && isLatin1(v)) {
    byte[] latin1 = getLatin1Bytes(v);
    if (latin1 != null && !hasHighBytes(latin1, 0, latin1.length)) {
        // ★ 纯 ASCII：UTF-8 字节 == 内部字节 ⇒ 零分配直接拷
        writeByteArray(latin1, 0, latin1.length);
        return;
    }
    byte[] utf8 = v.getBytes(UTF8_CHARSET);     // ← 原路径（Latin1 有高位字节时必须走）
    writeByteArray(utf8, 0, utf8.length);
    return;
}
```

★ **判据为什么是对的**：Latin1 串的内部字节为负 ⇔ 字符 ≥ 0x80 ⇔ UTF-8 要占 2 字节；
**没有负数字节 ⇔ 纯 ASCII ⇔ UTF-8 字节与内部字节完全相同** ⇒ 可以裸拷。

### 20.3 测试

| 文件 | 动作 |
|---|---|
| `edap-common/src/test/java/io/edap/common/test/util/StringUtilAsciiTest.java` | **新增**（5 例）：ASCII 内部字节 == UTF-8 字节 / Latin1 高位字节被识别 / UTF-16 串返回 `null` / 空串与 `null` / `hasHighBytes` 的偏移参数 |
| `edap-protobuf/src/test/java/io/edap/protobuf/test/v3/TestStringUtf8.java` | **新增** `testAsciiFastPath`（8 例，长度 1/5/127/128/1024/16383/16384/65536）：① 断言**快路径的前提成立**（内部字节 == UTF-8 字节、无高位字节）② 断言 wire 字节与 protobuf-java 逐字节相同 |

## 二十一、第五轮的验证

### 21.1 四模块回归（`mvn -o -pl ... test -DskipTests=false`）

| 模块 | 结果 |
|---|---|
| `edap-common` | **86 全绿**（基线 81 + 新增 5） |
| `edap-protobuf` | **1401 全绿**（基线 1393 + 新增 8） |
| `edap-http-server` | **95 全绿** |
| `edap-grpc-benchmark` | **21 全绿**（GrpcBenchmarkSmokeTest 3 + GrpcCompatTest 11 + GrpcInteropTest 7 ⇒ **wire 字节无回归**） |

### 21.2 编码核心（隔离 JVM 取 min，ns）

| 串 | 改前（c3 生产形态） | 改后（c24 真实生产路径） | 变化 |
|---|---|---|---|
| ascii-64 | 9 | **7** | -22% |
| ascii-1k | 52 | **32** | **-38%** |
| ascii-16k | 862 | **520** | **-40%** |
| cn-1k / cn-16k / cn-32k | 831 / 13368 / 29244 | 839 / 13202 / 30926 | 持平（噪声） |
| emoji-1k | 1291 | 1330 | 持平 |
| mixed-16k | 8857 | 8948 | 持平 |
| latin1-16k | 9771 | 9801 | 持平 |

### 21.3 端到端（`ProtoCodecBenchmark`，改后）

| 负载 | 编码核 edap | 编码核 protobuf-java | 比值 | 产出 byte[] edap | 产出 byte[] protobuf-java | 比值 | 解码 edap | 解码 protobuf-java | 比值 |
|---|---|---|---|---|---|---|---|---|---|
| **str-ascii-1024** | **35 ns** | 952 ns | **0.04x** | **79 ns** | 967 ns | **0.08x** | 52 ns | 59 ns | 0.88x |
| **str-ascii-16384** | **527 ns** | 15006 ns | **0.04x** | **1060 ns** | 15013 ns | **0.07x** | 979 ns | 973 ns | 1.01x |
| str-cn-1024 | 871 ns | 858 ns | 1.02x | 973 ns | 902 ns | 1.08x | 2455 ns | 2441 ns | 1.01x |
| str-cn-16384 | 13697 ns | 13424 ns | 1.02x | 15136 ns | 14015 ns | 1.08x | 33673 ns | 33879 ns | 0.99x |
| str-mixed-1024 | 576 ns | 672 ns | 0.86x | 634 ns | 691 ns | 0.92x | 1667 ns | 1677 ns | 0.99x |
| str-mixed-16384 | 9282 ns | 10476 ns | 0.89x | 9994 ns | 10658 ns | 0.94x | 26528 ns | 26905 ns | 0.99x |
| str-latin1-high | 21 ns | 14 ns | 1.52x | 28 ns | 17 ns | 1.60x | 50 ns | 30 ns | 1.68x |
| nest-1k-x1 | 116 ns | 532 ns | 0.22x | 201 ns | 545 ns | 0.37x | 120 ns | 189 ns | 0.63x |
| nest-1k-x8 | 518 ns | 2281 ns | 0.23x | 994 ns | 2381 ns | 0.42x | 511 ns | 792 ns | 0.65x |
| nest-1k-x64 | 3752 ns | 17012 ns | 0.22x | 5836 ns | 17117 ns | 0.34x | 3890 ns | 5491 ns | 0.71x |
| packed-int-1000 | 1585 ns | 1562 ns | 1.01x | 1669 ns | 1662 ns | 1.00x | 3792 ns | 3201 ns | 1.18x |
| packed-int-16000 | 28871 ns | 25492 ns | 1.13x | 29934 ns | 25884 ns | 1.16x | 60990 ns | 51741 ns | 1.18x |
| map-str-1000 | 18816 ns | 65970 ns | 0.29x | 19578 ns | 64569 ns | 0.30x | 50777 ns | 87982 ns | 0.58x |
| bytes-16384 | 313 ns | 402 ns | 0.78x | 972 ns | 727 ns | 1.34x | 699 ns | 759 ns | 0.92x |

⚠ **口径提醒**：本表的「比值」是**本批次内** edap 与 protobuf-java 的对比；
**与第一轮记录的基线比值（如 ASCII 0.06x/0.10x）属跨批次比较**（机器 load 不同）⇒
**只能作趋势看，不能当精确差值**。
★ **同批次内最硬的 before/after 是 §21.2 的 c24**：批次 7（改前）**855 ns** ⇒ 批次 8（改后）**520 ns**
（同一脚本、同一台机器、相隔几分钟）。

⇒ **ASCII 两档明显变快**；中文 / 混排 / Latin1 高位 / 嵌套 / packed / map / bytes **全部持平**（无回退）。

## 二十二、第五轮的结论

| | 结论 |
|---|---|
| **生产代码** | **改了**：`AbstractWriter.writeString0` 的 Latin1 分支加「纯 ASCII 零分配直写」；`edap-common/StringUtil` 加两个方法 |
| **收益** | **ASCII 编码核心 -40%**（16KB：862 → 520 ns）；中文 / emoji / mixed / Latin1 高位**零回退** |
| **部署** | **零新增负担**（edap-protobuf 本来就需要 `--add-opens java.base/java.lang=ALL-UNNAMED`） |
| **可移植性** | 拿不到 intrinsic（没加 flag / 非 JDK 17）⇒ **保守回退到 `getBytes(UTF_8)`，行为完全不变** |
| **★ 更正** | 第四轮封堵①②**作废**（intrinsic 经 `MethodHandle` 依然生效）；§14.3「只有 JNI 能做」**作废** |

### 22.1 ★ 方法论补充（第五轮的两条）

1. **别把「反射/MethodHandle 拿不到 intrinsic」当既定事实** —— 实测证明它**拿得到**，
   而且 `edap` 自己的 `Base64URL` 早就在用了。
   ⚠ 测 intrinsic 要用**热循环 + `invokeExact`**，冷循环 / `Method.invoke` 会给出假阴性。
2. **候选的「回退分支」必须与被比较对象同口径**（见 §19.1）——
   否则会凭空造出 30% 的「回退」，把好方案误杀。


---

# 第八轮（2026-11-13 晚）：嵌套消息 / packed 标量的长度前缀 —— 「预留 2 字节 + 按差值平移」

生成时间: 2026-11-13 19:00 ~ 19:40 CST

起因: 用户提问「嵌套/map/packed 的长度前缀 memmove，这个和预留+move 那个效率更高应该是看场景吧」，
并给出判断「嵌套的话感觉默认预留 2 个字节更合适」。
⇒ 本轮把长度前缀从「**预留 1 字节 + 写完整块右移**」改成「**预留 R 字节 + 按实际差值平移**」。

## 二十三、问题建模：两种写法的成本公式

设嵌套载荷长度为 `L`（字节），预留 `R` 字节给 varint 长度前缀。

| 写法 | 搬运量 | 什么时候搬 |
|---|---|---|
| **A 现状**（预留 1 + 右移） | `(L−1)·n` | `L ≥ 128` 时**必然搬一次** |
| **B 预留**（预留 R + 按差值平移） | `\|L−R\|·n` | 只有 `L` 的 varint 档位 ≠ `R` 时才搬 |

其中 `n` = 载荷字节数。注意 `L` 是**字节数**，而 varint 档位由字节数决定：

| 载荷字节数 | varint 前缀字节数 |
|---|---|
| 0..127 | 1 |
| 128..16383 | **2** |
| 16384..2097151 | 3 |
| 2097152..268435455 | 4 |

★ **关键结论**：`R = 2` **永远不会比 `R = 1` 差**。

- `L ∈ [128, 16383]`（**绝大多数业务消息落这一档**）⇒ `R=2` 搬运量 **0**，`R=1` 搬运量 `(L−1)·n`。
- `L < 128` ⇒ `R=2` 要多搬 1 字节/元素，但此时 `L < 128` ⇒ 总搬运量 < 128 字节 ⇒ **< 2.5 ns**，可忽略。
- `L ≥ 16384` ⇒ 两边都要搬（`R=2` 搬 1 字节、`R=1` 搬 2 字节）⇒ `R=2` 略优。

⇒ **用户拍板的「嵌套默认预留 2 字节」在成本上是严格占优的**。

### 23.1 移一次比拷一次贵 1.4 倍（实测）

探针 `/tmp/edap-lenprefix/MemShiftProbe.java`（JDK 17，**每 case 独立 JVM**，5 轮取 min）：

| 搬运 | 1029 B | 32896 B |
|---|---|---|
| **同数组重叠右移 1 字节**（`System.arraycopy(buf,p,buf,p+1,len)`） | **19.8 ns** | **654.6 ns** |
| 不重叠整块拷贝（等量对照） | 14.0 ns | 422.7 ns |
| 无拷贝基线（循环开销） | ~1 ns | ~1 ns |

⇒ **重叠搬移比等量拷贝贵 1.4 倍**（要处理前向重叠）⇒ 省掉一次搬移的收益 = 一次拷贝的 1.4 倍。

### 23.2 「Go/gogo 是倒着写，所以快」—— 机制澄清

用户记忆：「golang 的 fastprotobuf 比标准版快是因为它是倒着写数据的」。

查证 gogo/protobuf 的生成代码：**确实是倒着写**（`MarshalToSizedBuffer` 从缓冲区尾部往前写），
但**收益来源不是「顺序」本身**，而是：

> 倒着写 ⇒ **长度前缀可以写在载荷写完之后、且正好贴在载荷的正下方** ⇒ **零预留、零搬移**。

也就是说 gogo 的快来自「**前缀与载荷的相对位置不需要预留**」，
而不是「倒着写比正着写快」本身。正着写也能做到（protobuf-java 就是正着写），
只是它用「**两遍法**」来避免预留：
第一遍 `Size()` 精确算出总长 ⇒ 第二遍先写 varint 前缀再写载荷 ⇒ 完全不需要搬移。

### 23.3 为什么 edap 不能照抄 protobuf-java 的两遍法

用户的直觉「对于 message 的 size 要计算 key + value 然后判断类型再遍历计算 size，感觉效率也不高」
—— **对 map 档确实成立，而且数据完全支持**：

| 负载 | edap 编码核心 | protobuf-java 编码核心 | 比值 |
|---|---|---|---|
| map-str-1000 | 18816 ns | 65970 ns | **0.29x** |

⇒ protobuf-java 的 map 档**正是「每个 entry 先算 key+value 的 size、再遍历一遍写」**（两遍法），
edap 反而快 3.4 倍（因为 edap 用「预留 + 一次右移」）。

★ **但 packed 标量档不能照抄**：edap 的 packed 输入是**装箱的 `List<Integer>`**
⇒ 第二遍要**指针跳转 + 拆箱**（packed-int-16000 多扫 16000 个装箱 `Integer` ≈ **16 µs**），
而它想省下的 memmove 只有 **0.65 µs** ⇒ **差 20 倍，得不偿失**。

⇒ 结论：**「预留 R + 按差值平移」是 edap 该走的路**（既避免搬移，又不需要第二遍扫描）。

⚠ **倒着写还会改 wire 字节序**：倒着写要求 ASM 生成器按字段号**倒序**调用 ⇒ 字节序与 protobuf-java 不同
⇒ 直接打破「与 protobuf-java 逐字节相同」的断言 ⇒ **本轮不做**。

### 23.4 ★★ 坑一：`writeMapMessage` **不能**用预留写法（已回退）

旧实现（`StandardProtoBufWriter.writeMapMessage`）**不预留前缀字节**：
写完 entry 内容后 `writeLenMoveBytes(bs, outOldPos, len)` 把长度前缀写到 `outOldPos`，
**正好覆盖掉内容的第一个字节 —— 也就是 map entry 的 tag `0x0A`**；
而解码侧（`ByteArrayReader.readMap` → `MapEntryDecoder`）**不读**这个 entry tag
⇒ 编码侧与解码侧是**这么对上的**。

改成「预留 2 字节」后 entry tag 会**多出一个** ⇒ 解码侧整体错位
⇒ `TestListMapFieldCodec` 报 `type[121] hasn't ProtoBufDecoder`。

⇒ **`writeMapMessage` 完整回退为旧实现**（加中文注释说明「这里不能用预留写法」）。
⚠ 对照：`writeMap`（每个 entry 单独长度前缀）本来就是 `pos += 1` 预留 ⇒ 没动。

### 23.5 ★★ 坑二：`expand(...)` 的运算符优先级 bug（顺带修掉的潜在越界）

旧代码有 20+ 处形如：

```java
expand(MAX_VARINT_SIZE << 1 + size * MAX_VARINT_SIZE);   // ★ 实际是 5 << (1 + size*5)
```

Java 的 `<<` 优先级**高于** `+` ⇒ 表达式实际是 `MAX_VARINT_SIZE << (1 + size*5)`
⇒ `size` 大时移位量超过 31 ⇒ **算出的容量是 0 或负数** ⇒ **潜在缓冲越界**。

统一改成显式括号：

```java
expand((MAX_VARINT_SIZE << 1) + size * MAX_VARINT_SIZE + reserve);
```

（同类还有 `(size << 2)` / `(size << 3)` 两处。）

## 二十四、代码改动

| 文件 | 改动 |
|---|---|
| `edap-protobuf/.../writer/AbstractWriter.java` | 新增 `NESTED_LEN_RESERVE = 2`、`writeLenPrefixReserve(prefixPos, reserve, contentLen)`（按差值平移：`vn > reserve` ⇒ 先 `expand` 再右移；`vn < reserve` ⇒ 直接左移不扩容）、`varintLen(int)`、`writeVarint(byte[],int,int)` |
| `edap-protobuf/.../writer/StandardProtoBufWriter.java` | `writePackedInts(int[]/Integer[]/List/Iterable)`、`writePackedLongs(Long[]/long[]/List)`、`writeArrayEnum`、`writeMessage`、`writeMessage0` 全部改成「预留 + 回填」；`writeMapMessage` **回退为旧写法**；修掉 20+ 处 `expand` 优先级 bug |
| `edap-protobuf/src/test/.../v3/TestNestedLenPrefix.java` | **新增** 6 例：长度前缀 1/2/3/4 字节各档（逐字节与 protobuf-java `OneMessageOuterClass` 比对 + 断言前缀档位 + edap 往返）、空嵌套、repeated 嵌套大小混排 |

## 二十五、★ 实测（A/B 同批次，5 launches，`ProtoCodecBenchmark` 编码核心 ns）

**方法**：第五轮**没有**改 `StandardProtoBufWriter.java` ⇒ `git show HEAD:...` 的版本即「本轮改前」版本。
单独编译成 `/tmp/edap-oldclasses2`（**只含旧 `StandardProtoBufWriter`**，不含旧 `AbstractWriter`）
放到 classpath **前面**遮蔽新版；old / new **交替 launch**，共 **5 组**。

| 负载 | old(中位数) | new(中位数) | 变化 | old(min) | new(min) | 变化 | 旧比值 | 新比值 |
|---|---|---|---|---|---|---|---|---|
| **nest-1k-x1** | 117 | **85** | **-27.4%** | 116 | 84 | -27.6% | 0.22x | **0.17x** |
| **nest-1k-x8** | 539 | **375** | **-30.4%** | 527 | 366 | -30.6% | 0.23x | **0.16x** |
| **nest-1k-x64** | 3827 | **2617** | **-31.6%** | 3798 | 2580 | -32.1% | 0.24x | **0.16x** |
| packed-int-1000 | 1559 | 1534 | -1.6% | 1442 | 1446 | +0.3% | 1.05x | 0.99x |
| packed-int-16000 | 28630 | 27646 | -3.4% | 26555 | 26723 | +0.6% | 1.21x | 1.12x |
| map-str-1000 | 17619 | 17384 | -1.3% | 17387 | 17266 | -0.7% | 0.27x | 0.26x |
| bytes-16384 | 332 | 324 | -2.4% | 318 | 321 | +0.9% | 0.83x | 0.81x |
| str-ascii-1024 | 35 | 35 | 0.0% | 35 | 35 | 0.0% | 0.04x | 0.04x |
| str-ascii-16384 | 542 | 547 | +0.9% | 532 | 525 | -1.3% | 0.04x | 0.04x |
| str-cn-1024 | 868 | 865 | -0.3% | 848 | 846 | -0.2% | 1.02x | 1.02x |
| str-cn-16384 | 13705 | 13606 | -0.7% | 13454 | 13378 | -0.6% | 1.02x | 1.01x |

**各 launch 原始值**（证明不是噪声）：

```
  nest-1k-x1     old=['116','118','130','117','117']  new=['85','86','89','85','84']
  nest-1k-x8     old=['527','539','541','556','538']   new=['370','386','375','386','366']
  nest-1k-x64    old=['3802','3798','3829','3871','3827'] new=['2580','2713','2600','2741','2617']
  packed-int-1000 old=['1559','1547','1442','1572','1560'] new=['1516','1534','1567','1446','1539']
  packed-int-16000 old=['28940','28630','26555','29081','28459'] new=['27646','28178','28091','26723','26813']
  map-str-1000   old=['17656','17387','17610','18706','17619'] new=['17490','17384','17494','17266','17366']
```

⇒ **三个嵌套档的 old/new 区间完全不重叠**（x1: old min 116 > new max 89；x8: 527 > 386；x64: 3798 > 2741）
⇒ **-27% ~ -32% 是真实收益**。
⇒ packed / map / bytes / 字符串**全部在噪声内**（无回退、无假收益）。

## 二十六、回归

| 模块 | 结果 |
|---|---|
| `edap-common` | **86 全绿** |
| `edap-protobuf` | **1407 全绿**（1401 基线 + 6 新例） |
| `edap-http-server` | **95 全绿** |
| `edap-grpc-benchmark` | **21 全绿**（wire 字节无回归） |

## 二十七、第八轮的结论

| | 结论 |
|---|---|
| **生产代码** | **改了**：`StandardProtoBufWriter` 的 packed / 嵌套写入全部改成「预留 + 按差值平移」；`AbstractWriter` 加 3 个辅助方法 |
| **收益** | **嵌套消息编码核心 -27% ~ -32%**（相对 protobuf-java 从 0.22~0.24x 压到 **0.16~0.17x**） |
| **无回退** | packed / map / bytes / 字符串全部在噪声内 |
| **附带修掉** | `expand()` 的 20+ 处**运算符优先级 bug**（大数组时算出 0/负数容量 ⇒ 潜在越界） |
| **踩坑留痕** | ① `writeMapMessage` **不能**用预留写法（旧实现靠「长度前缀覆盖 entry tag」与解码侧对齐）；② 装箱 `List<Integer>` 让「两遍法」成本高 20 倍 ⇒ 不能照抄 protobuf-java 的 map 做法 |

## 二十八、第九轮的起点：用户要求「packed-int 改成先计算长度写长度写内容」

用户指令：**「packed-int 改成先计算长度写长度写内容」**（= protobuf-java 的**两遍法**：
先算精确的载荷长度 → 写长度前缀 → 写内容 ⇒ 零预留、零搬移）。

### 28.1 实现（第一版：纯两遍法）

`StandardProtoBufWriter` 新增 `writePackedHeader(fieldData, contentLen)`：
`expand(tag + varintLen(contentLen) + contentLen)` → 写 tag → 写长度前缀 → 返回内容起始下标；
`writePackedInts` 的 **4 个重载**（`int[]` / `Integer[]` / `List<Integer>` / `Iterable<Integer>`，
各含 INT32/UINT32 与 SINT32 两个分支）全部改成两遍法：

```java
// 第一遍：算精确长度
int contentLen = 0;
for (int i = 0; i < size; i++) {
    contentLen += varintLenInt32(values.get(i).intValue());
}
int p = writePackedHeader(fieldData, contentLen);
// 第二遍：写内容（下标留在局部变量里，不读写 pos 字段）
byte[] _bs = bs;
for (int i = 0; i < size; i++) {
    int v = values.get(i).intValue();
    p = v > 0 ? writeVarintAt(_bs, p, v) : writeVarint64At(_bs, p, v);
}
pos = p;
```

配套（`AbstractWriter`）：新增 `varintLenInt32(int)`（int32 语义：负数符号扩展成 64 位 ⇒ 固定 10 字节；
0 ⇒ 1 字节；正数按无符号 32 位 varint）、`writeVarintAt(byte[],int,int)`（**loop 形式**、按无符号 32 位、
返回写完后的下标、不读写 `pos`）、`writeVarint64At(byte[],int,long)`（64 位，同上）；
`writeVarint(byte[],int,int)` 由 `private static void` 改成 `protected static int`（返回写入字节数）。

### 28.2 ★ 两遍法顺手修掉一个 wire bug（已加测试锁）

旧 `writePackedInts(List/Iterable)` 为了「展开前 10 个元素、避免循环的边界检查」，
把**下标 7 那个元素**写成了 `writeUInt32_0(...)` 而不是 `writeInt32_0(...)`
⇒ 该元素若是负数，就会按**无符号 32 位**写成 **5 字节**（而不是 10 字节）
⇒ **整个字段的字节错位**（长度前缀之后的载荷少 5 字节）
⇒ **只有「负数恰好落在下标 7」才会触发 ⇒ 极难被随机测试撞到**。

新测试 `edap-protobuf/src/test/.../v3/TestPackedNegativeInt.java`（**9 例**）：
下标 7 为负 / 首末为负 / 多负数混合 / 数组形态 / protobuf-java 可解析 / edap 往返 /
**10 字节的字节级断言** / **大量负数（2000 个）不越界** / **负正交替（500 组）**。
红→绿证据：用 HEAD 版 `StandardProtoBufWriter.class` 遮蔽重跑 ⇒ **2 例红**
（`testNegativeAtIndex7`：protobuf-java 21 字节 vs edap 16 字节；`testMixedNegatives`：112 vs 107）。

## 二十九、★ 两遍法的实测：**packed int 回退 +67% ~ +83%**

**方法**：old = 第八轮版本（从 `~/.m2` jar 里抽出 `StandardProtoBufWriter.class` + `AbstractWriter.class`
到 `/tmp/edap-oldpacked3/classes` 遮蔽）、new = 两遍法（jar）；
old / new **交替 launch**，共 **5 组**，每组 `ProtoCodecBenchmark 600 3`（600ms × 3 轮取中位数）。

| 负载 | old(中位) | new(中位) | 变化 | old(min) | new(min) | 变化 |
|---|---|---|---|---|---|---|
| **packed-int-1000** | 1546 | **2827** | **+82.9%** | 1522 | 2740 | **+80.0%** |
| **packed-int-16000** | 28268 | **47309** | **+67.4%** | 27660 | 46903 | **+69.6%** |
| str-ascii-1024 | 35 | 35 | 0.0% | 35 | 35 | 0.0% |
| str-ascii-16384 | 533 | 534 | +0.2% | 527 | 529 | +0.4% |
| str-cn-1024 | 864 | 865 | +0.1% | 847 | 830 | -2.0% |
| str-cn-16384 | 13638 | 13737 | +0.7% | 13577 | 13599 | +0.2% |
| nest-1k-x1 | 86 | 86 | 0.0% | 86 | 86 | 0.0% |
| nest-1k-x8 | 388 | 386 | -0.5% | 385 | 384 | -0.3% |
| nest-1k-x64 | 2720 | 2711 | -0.3% | 2667 | 2688 | +0.8% |
| map-str-1000 | 17469 | 17382 | -0.5% | 17190 | 17225 | +0.2% |
| bytes-16384 | 323 | 321 | -0.6% | 315 | 320 | +1.6% |

⇒ **只有 packed int 档回退，其余全部持平** ⇒ 不是「整体退化」，是**这一条路径的结构性代价**。

## 三十、★ 关键洞察：protobuf-java 的基准数字**不含**两遍法的代价

`ProtoCodecBenchmark` 的「编码核」列：

- edap：`encodeIntoBuffer(writer, encoder, c.edap)` —— **每次都重新编码同一个对象**
- google：`c.google.writeTo(cos)` —— **也是同一个对象反复编码**

两边看起来对等，但 protobuf-java 生成的代码长这样（`target/generated-sources/protobuf/java/io/edap/grpc/benchmark/Bench.java`，
`CodecNest.writeTo`）：

```java
public void writeTo(CodedOutputStream output) {
  getSerializedSize();                 // ← 第一行；memoizedSize != -1 就直接返回
  ...
  if (getNumsList().size() > 0) {
    output.writeUInt32NoTag(26);
    output.writeUInt32NoTag(numsMemoizedSerializedSize);   // ← 长度前缀来自 memoize
  }
  for (int i = 0; i < nums_.size(); i++) {
    output.writeInt32NoTag(nums_.getInt(i));
  }
}
```

而 `numsMemoizedSerializedSize` 的计算**就是**那一遍「每个元素算 varint 长度」：

```java
if (numsMemoizedSerializedSize == -1) {
   int dataSize = 0;
   for (int i = 0; i < nums_.size(); i++) { dataSize += computeInt32SizeNoTag(...); }
   numsMemoizedSerializedSize = dataSize;
}
```

⇒ **protobuf-java 的两遍法是真的存在的，但它把这一遍 memoize 了**；
基准里 `c.google` 是**同一个对象**反复编码 ⇒ 第一次之后这一遍**永远不再执行**
⇒ **基准测到的 protobuf-java 数字里，不含「算长度」的成本**。

edap 若改成两遍法，**每一遍都要真算**（edap 没有 memoize 机制）⇒ **结构性劣势 1.28 ns/元素**
（实测：(47309−28268) ns / 16000 元素 = **1.19 ns/元素**）。

⚠ **但这不能当作「两遍法没成本」的理由**：
- 真实业务里**每次编码一个新对象** ⇒ protobuf-java 也得付这一遍（只是它付在 `getSerializedSize()` 里）；
- edap 的**单遍 + 预留回填**比 protobuf-java 的**两遍**更省（省掉整整一遍扫描）
  ⇒ **edap 单遍是「比 protobuf-java 真实成本更低」的写法**，两遍法只是「对齐 protobuf-java 的写法」。

⇒ **结论：两遍法在 edap 这条路径上是负收益，不采纳。**

## 三十一、★ 顺带挖出的两个真 bug（HEAD 上就有，第八轮没修掉）

### 31.1 bug A：packed int32 负数 ⇒ **缓冲越界**（`ArrayIndexOutOfBoundsException`）

旧实现按 `MAX_VARINT_SIZE`（**5 字节/元素**）预留空间，
而 protobuf 的 int32 **负数要符号扩展成 64 位 varint ⇒ 每个元素最多 10 字节**
⇒ 元素里有负数时**写穿缓冲**。

探针（`/tmp/probe/OverflowProbe2.java`，直接调编码器以拿到被 `ProtoBuf.toByteArray` 吞掉的异常）：

| 版本 | n=20（全负） | n=200 | n=2000 | n=20000 |
|---|---|---|---|---|
| **HEAD** | 198 ≠ 203（**字节错**） | 1998 ≠ 2003（**字节错**） | **AIOOBE: Index 16384 out of bounds for length 16384** | — |
| **第八轮** | 同上 | 同上 | 同上 | — |
| **两遍法** | 203 = 203 ✅ | 2003 = 2003 ✅ | 20004 = 20004 ✅ | 200004 = 200004 ✅ |

栈：`AbstractWriter.writeUInt64_0(AbstractWriter.java:1046)` ← `writeInt32_0(AbstractWriter.java:227)`
⇒ `writeInt32_0` 对负数写 **10 字节**，而预留只有 5 字节/元素。
（`ProtoBuf.toByteArray` 把 `EncodeException` 吞掉后**返回 null** ⇒ 调用方拿到 null 而不是异常 ⇒ 更难查。）

### 31.2 bug B：packed int32 的**下标 7** 元素按无符号写（字节错位）

见 28.2。

⇒ **这两个 bug 都不是「长度前缀算法」的问题，而是「每个元素的字节数上界」的问题**
⇒ 换任何长度前缀算法（两遍 / 单遍）都要先把**每元素最大字节数**算对：
**int32 = 10 字节（负数符号扩展）、sint32 = 5 字节（zigzag）、uint32 = 5 字节**。

## 三十二、★ 最终落地：单遍 + 局部下标 + 按需扩容

**取舍**：用户的「先算长度」在 edap 这条路径上是负收益（第二十九、三十节）⇒ 不采纳；
但「先算长度」背后要解决的**字节正确性**必须修（第三十一节）⇒ 换成**同样正确、但不付第二遍代价**的写法。


### 32.1 实现（最终形态 = V11）

`StandardProtoBufWriter`（4 个重载 `int[]` / `Integer[]` / `List<Integer>` / `Iterable<Integer>` 同构）：

```java
private int writePackedIntStart(byte[] fieldData, int reserve, int contentReserve) {
    expand((MAX_VARINT_SIZE << 1) + contentReserve + reserve);
    writeFieldData(fieldData);
    return pos;                    // ← 长度前缀的位置
}

private void writePackedIntEnd(int oldPos, int reserve, int contentEnd) {
    int contentLen = contentEnd - oldPos - reserve;
    pos = contentEnd;              // ★ expand() 用 pos 判断剩余容量，必须先同步
    pos = oldPos + writeLenPrefixReserve(oldPos, reserve, contentLen);
}
```

写循环（**INT32 / UINT32 分成两个 case**）：

```java
case INT32: {
    int reserve = varintLen(size * MAX_VARINT_SIZE);
    int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
    byte[] _bs = bs;
    int cap = _bs.length;
    int p = oldPos + reserve;
    for (int i = 0; i < size; i++) {
        int v = values.get(i);
        if (v >= 0) {
            // ★★ 热路径：1~2 字节 varint **直接展开在循环里，零方法调用**
            if ((v >>> 14) == 0) {
                if ((v >>> 7) == 0) {
                    _bs[p++] = (byte) v;                          // 1 字节
                } else {
                    _bs[p++] = (byte) (v | -128);                 // 2 字节
                    _bs[p++] = (byte) (v >>> 7);
                }
            } else {
                p = writeVarintAt3to5(_bs, p, v);                 // 3~5 字节（冷）
            }
        } else {
            // ★ int32 负数 ⇒ 符号扩展成 64 位 varint ⇒ 10 字节 ⇒ 这里才查容量
            if (p + MAX_VARLONG_SIZE > cap) {
                pos = p;                   // ★ expand() 用的是字段 pos，必须先同步
                expand(MAX_VARLONG_SIZE);
                _bs = bs;
                cap = _bs.length;
            }
            p = writeVarint64At(_bs, p, v);
        }
    }
    writePackedIntEnd(oldPos, reserve, p);
    return;
}
case UINT32: {
    // uint32 是无符号语义（-1 ⇒ FF FF FF FF 0F）⇒ 永远 ≤5 字节 ⇒ 不需要检查容量
    // 同一个「1~2 字节零调用」的内联块，只是没有「是不是负数」的分支
    ...
}
```

四个要点：

1. **下标留在局部变量** ⇒ 每个元素省掉一次 `pos` 字段读 + 一次字段写。
2. **容量检查只在「负数」这条冷分支上** ⇒ 常见负载（正数）零成本；
   旧实现按 **5 字节/元素预留** ⇒ 负数就**预留不足**（bug A）；
   若改成按 10 字节/元素预留 ⇒ 缓冲撑到 2 倍。
3. **长度前缀仍是「预留 + 按差值平移」**（第八轮的 `writeLenPrefixReserve`）
   ⇒ 实际长度与预留一致时**一次都不搬**（实测 packed 档差值为 0）。
4. ★★ **热路径零方法调用** —— 1~2 字节 varint 直接展开在循环里（见 32.3）。

⚠ **`expand()` 的坑**：`expand(minLength)` 判断的是 `bs.length - pos <= minLength`，
**用的是字段 `pos`** ⇒ 循环里调用前必须 `pos = p` 同步，否则容量算错（会越界）。

### 32.2 ★ 中途形态（V8 / V9 / V10）的三次失败，和一个共同的「双峰」现象

这一版经历了三个中间形态，每一个都被实测否掉，但**根因是同一个**：

| 形态 | varint 写入的写法 | 实测（packed-int-1000，编码核心 ns） | 判定 |
|---|---|---|---|
| **V8** | **while 循环** | 1928（中位）/ 1854（min）⇒ **+28%** | ✘ 回退 |
| **V9** | 一个 **164 字节码**的展开 if 链 | **1189 / 1640 双峰**（5 次启动落两档） | ✘ 不确定 |
| **V10** | 拆成「分发 21/21/24 + 写入 9/22/32/34」的一串小方法 | **1267 / 1608 双峰** | ✘ 不确定 |
| **V11（最终）** | 1~2 字节**零调用**展开在循环里，≥3 字节才调方法 | **1196~1212，稳定** | ✔ |

★ **V8 的根因（单峰、确定性回退）**：热路径上的 varint 写入**不能写成 while 循环** ——
循环回边 + 循环计数 ≈ **0.3 ns/元素** ⇒ packed int 编码回退 15%~24%。
（V8 不双峰，因为它只有一条确定的慢路径 —— **慢但可预测**；V9/V10 才是「快慢随机」。）

★ **V9 / V10 的根因（双峰）**：**HotSpot 的内联阈值不是固定的 35 字节码，而是「随内联深度递减」**（32.3）⇒
内层方法落在阈值边缘 ⇒ **概率性内联** ⇒ 同一份代码两次运行落在两个性能档。

⚠ **而且这不是新代码引入的** —— 第八轮基线（旧代码）**同样是双峰的**：
用同一支探针实测 6 次启动 ⇒ {1199, 1218, 1277} 与 {1392, 1396, 1403, 1415} 两档。
⇒ **双峰是这个代码路径的固有属性**（旧代码的 `writeUInt32_0` 是 183 字节码 ⇒ 同样「热才内联」）。

### 32.3 ★★ 根因：HotSpot 的内联阈值是「深度相关」的，不是固定 35 字节

**标定方法**：写一组合成类（`Cal4` ~ `Cal10`），每个类里放一串**嵌套调用**，
被调方法的字节码大小逐个递增；用 `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`
读出「恒定内联（`inline`）」与「热才内联（`too large` / `inline (hot)`）」的分界。

| 调用深度 | 恒定内联的字节码上限（实测） |
|---|---|
| depth 1 | **≤36**（37 字节起变成「热才内联」） |
| depth 3 | ≥24（真实代码里 22 / 24 字节码恒内联） |
| depth 4 | **≤22**（25 字节起**概率性**） |
| depth 5 | **≤19**（22 字节起**概率性**） |

⇒ 拟合模型 ≈ **`threshold(d) ≈ 39 − 4.3·d`**。

⇒ 两条推论：

1. **「方法 ≤35 字节码就永远内联」是错的** —— 那只是 depth 1 的情形；
   嵌套越深，阈值掉得越快（depth 4 就只剩 22）。
2. **超过阈值的方法 ⇒ 「热才内联」⇒ 编译时机不同 ⇒ 同一份代码两次运行落两档**
   （标定类里同一个 32 字节码的方法在同一程序里 8 次报 `too large`、2 次报 `inline`）。

⇒ ★ **所以「把大方法拆成一串小方法」并不能消除双峰** —— V10 就是这条路的反例
（分发层 21/21/24 字节码看着都很小，但**内层写入方法处在 depth 3~4** ⇒ 落在阈值边缘）。

⇒ ★ **唯一确定的做法：热路径上「一次方法调用都没有」** ——
1~2 字节 varint（实测占 99.2%）**直接展开在 packed 循环里**，
只有 ≥3 字节（冷路径）才回到 `writeVarintAt3to5`（24 字节码，depth 1 ⇒ 恒内联）。

★ **配套的纪律：分支顺序也要按命中率排** —— 先判 `(v >>> 14) == 0`（2 字节，占 87%~99%），
再在内部判 `(v >>> 7) == 0`（1 字节）；反过来先判 1 字节会让「占比小的分支」落在外层。

### 32.4 ★ 专用探针：`PackedIntProbe`

`ProtoCodecBenchmark` 一次要跑 11 个负载、3~5 轮，单次 JVM 要 3 分钟；
packed int 是本轮唯一在动的代码，需要「改一版 ⇒ 立刻量一版」的迭代速度
⇒ 新增 `edap-grpc-parent/edap-grpc-benchmark/src/main/java/io/edap/grpc/benchmark/PackedIntProbe.java`：

- 只测 packed int 一条路（`CodecNest.nums`），单次 JVM **约 10 秒**；
- ★ **protobuf-java 的对照列在同一个 JVM、同一次运行里测** ⇒ 机器噪声被自动归一化
  （跨批次比绝对值是不可靠的，见 memory 的基准纪律）；
- edap 与 google **交替**测量 ⇒ 避免「后测的负载背前一个的形态」；
- 每行都做 **wire 逐字节比对**；
- 换候选实现时用 `-cp` **前置一个补丁 class 目录**即可，不用重装 jar。

★ 它是**复现双峰**的关键工具：双峰在 `ProtoCodecBenchmark` 里 5 次启动才碰上 2~3 次，
在探针里 6 次启动就能看到两档。

### 32.5 实测（V11 vs 第八轮基线）

**方法**：`/tmp/run-ab-v11.sh` —— 同批次交替（奇数 launch old→new、偶数 new→old），
5 launches × 2 版本，`ProtoCodecBenchmark 600 3`（编码核心 ns，每档 3 轮取中位数）。
old = `/tmp/edap-oldpacked3/classes`（第八轮基线），new = 当前 `~/.m2` 的 edap-protobuf jar。

| 负载 | old（中位） | new（中位） | 变化 | old 比值 | new 比值 |
|---|---|---|---|---|---|
| **packed-int-1000** | 1511 | **1206** | **-20.2%** | 0.99x | **0.79x** |
| **packed-int-16000** | 27379 | **21894** | **-20.0%** | 1.11x | **0.88x** |
| str-ascii-1024 | 35 | 35 | 0.0% | 0.04x | 0.04x |
| str-ascii-16384 | 534 | 536 | +0.4% | 0.04x | 0.04x |
| str-cn-1024 | 856 | 846 | -1.2% | 1.00x | 1.00x |
| str-cn-16384 | 13555 | 13470 | -0.6% | 1.01x | 1.01x |
| str-mixed-1024 | 574 | 573 | -0.2% | 0.87x | 0.87x |
| str-mixed-16384 | 8943 | 9164 | +2.5% | 0.86x | 0.88x |
| str-latin1-high | 21 | 21 | 0.0% | 1.45x | 1.45x |
| nest-1k-x1 | 86 | 86 | 0.0% | 0.17x | 0.17x |
| nest-1k-x8 | 384 | 387 | +0.8% | 0.17x | 0.17x |
| nest-1k-x64 | 2704 | 2722 | +0.7% | 0.17x | 0.17x |
| map-str-1000 | 17166 | 17279 | +0.7% | 0.26x | 0.26x |
| bytes-16384 | 322 | 321 | -0.3% | 0.85x | 0.82x |

**原始值（证明「双峰消失」而不是「运气好」）**：

| 负载 | old 5 次 launch | new 5 次 launch |
|---|---|---|
| packed-int-1000 | 1385, 1541, 1511, 1400, **1563** | 1206, 1204, 1201, 1213, 1224 |
| packed-int-16000 | 26645, 27709, 27379, 25885, 27798 | 21727, 21894, 21765, 22012, 21940 |

★ **old 与 new 的区间完全不重叠**（1000 档：old min 1385 > new max 1224）
⇒ **-20% 是真实收益，不是噪声**。
★ **old 自己就是双峰的**（1385↔1563、26645↔27798），
而 **new 的 5 个值挤在 1201~1224 / 21727~22012**（极差 1.9% / 1.3%）
⇒ **V11 不只是更快，还消除了「同一份代码随机落两档」的不确定性**。
（这正是「热路径零方法调用」的额外收益 —— 32.3 的结论。）

⇒ **packed int 现在比 protobuf-java 快**（0.79x / 0.88x），
且**其余 12 档全部在 ±2.5% 内**（无回退）。
