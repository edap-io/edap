# edap-crypto 设计

> 把 digest / MAC / cipher / random 四类密码学原语统一到 `CryptoProvider` 抽象之下，
> 让 **JCE（JDK 内置）** 与 **native（edap-native JNI）** 两套实现可按开关切换。
>
> 与 HTTP/2 那条线**完全解耦**：edap-crypto 不依赖 edap-http-parent，反之亦然。

---

## 一、定位

### 1.1 解决什么问题

edap 里到处是「每次调用都 `getInstance` 新建实例」的写法：

| 位置 | 现状 |
|---|---|
| `CryptUtil.md5/sha1/sha256` | 每次 `MessageDigest.getInstance(...)` |
| `SigV4Verifier` | 每次 `Mac.getInstance(...)` + `init` |
| `HmacSha256`（JWT） | 已有 native/JCE 双实现，但只有 HMAC-SHA256 一处 |

问题有三个：

1. **setup 开销**：JCE `getInstance` 要查 provider、创建 SPI，每次 1~3μs。
   小 payload 下这比摘要计算本身还贵。
2. **实现分散**：每个模块各写一套「native 优先 / 失败降级」的探测逻辑，
   探测顺序还各不相同（JWT 的 `HmacSha256Native.isAvailable()` 是完全静默的，
   native 不可用时没有任何日志）。
3. **无法统一治理**：想全局关掉 native、想看哪些地方走了 native，都做不到。

### 1.2 设计原则

| 原则 | 体现 |
|---|---|
| **零硬依赖** | edap-crypto **不依赖** edap-native（test scope），通过 `NativeBridge` 反射绑定 |
| **可观测的降级** | native 不可用 ⇒ 打**一次** WARN 后降级 JCE（不是静默）；严格模式可改成抛异常 |
| **统一治理** | 三段式开关（总闸 / 分项闸 / 强制实现）+ 严格模式 |
| **不接管 JDK TLS** | edap-crypto 不碰 `SSLEngine` / `javax.net.ssl` SPI |
| **不接管 edap-common** | edap-common → edap-crypto →（test）→ edap-native → edap-common 会形成 reactor 环 |
| **不依赖 edap-log-api** | edap-crypto 位于**日志 bootstrap 路径**上，反向依赖 edap-log-api 会形成**类初始化环** ⇒ 全应用退化成 NopLogger（见 §1.4） |

### 1.3 依赖图

```
edap-common ──(compile)──▶ edap-crypto ──(test)──▶ edap-native ──(compile)──▶ edap-log-api
```

**无环**。关键在于 `edap-native` 已经解除了对 `edap-common` 的依赖
（`Native.java` 里只用了 `FileUtil.inputStreamToFile`，已内联）。

**edap-crypto 的编译期依赖只有 JDK** —— 刻意不依赖 edap-log-api（见 §1.4）。

### 1.4 ★ 类初始化环：edap-crypto 为什么不能依赖 edap-log-api

**这是本模块踩过的最严重的坑**（C-8 迁移 `CryptUtil` 时引入）。

edap-crypto 位于**日志系统的启动路径**上：

```
LoggerManager.init() ──SPI──▶ EdapLogServiceProvider
   ──AppenderManager──▶ EncoderGenerator ──CryptUtil.md5──▶ edap-crypto.CryptoRegistry
```

如果 edap-crypto 反过来依赖 edap-log-api，并在**类初始化**时取 logger
（`private static final Logger LOG = LoggerManager.getLogger(...)`），
就会形成一条跨 4 个模块的**类初始化环**：

```
CryptoRegistry.<clinit> ──getLogger──▶ LoggerManager.init() ──SPI──▶
  EdapLogServiceProvider ──EncoderGenerator──▶ CryptUtil.md5 ──▶ CryptoRegistry.digest() ⟲
```

同线程重入 `CryptoRegistry.<clinit>` 时，JVM 直接返回 ⇒ 它的静态 map 还是 `null`
⇒ `NullPointerException` ⇒ SPI provider 实例化失败 ⇒
**整个应用退化成 NopLogger（一行日志都没有）**。

> 实测复现（2026-11-05）：把 `Logger LOG = LoggerManager.getLogger(...)` 放回
> `CryptoRegistry`，回归测试 `LoggerBootstrapIsolationTest` 立刻红：
> `expected: <null> but was: <true>`（`LoggerManager.INITED` 被提前置位）。

**结论**：edap-crypto **不依赖 edap-log-api**，降级告警改走
`io.edap.crypto.internal.CryptoDiagnostics`（默认 stderr，宿主可换成自己的 logger，
见 §4.2）。

**双保险**：`edap-common` 的 `CryptUtil` 对 crypto 层的 `RuntimeException`
有**裸 JCE 兜底**（`MessageDigest.getInstance`，完全绕开 edap-crypto），
并把问题打到 stderr —— 结果逐字节相同，不掩盖错误。
即便类初始化环还在，NPE 也会被兜底吃掉，不会再把整个日志系统打死。

**机器可验证的守卫**：

| 守卫 | 位置 | 作用 |
|---|---|---|
| 常量池扫描「不得出现 `io/edap/log`」 | `edap-crypto/src/test/.../NoLogApiDependencyTest` | 根因级：edap-crypto 任何类都不得引用 edap-log |
| 独立 ClassLoader 里的 bootstrap | `edap-log/src/test/.../LoggerBootstrapIsolationTest` | 行为级：`CryptoRegistry` 初始化后 `LoggerManager.INITED` 必须仍是 `null`；且 logger 不能是 NopLogger |

> 为什么必须用独立 ClassLoader：`LoggerManager` 是全局单例，surefire 复用同一个 JVM，
> 第二次调用时它早就初始化完了 —— 环根本不会重现。回归测试用
> `parent = platform loader` 的 `URLClassLoader` 把 edap-* 全部重新加载一遍。

---

## 二、SPI

四类原语的**生命周期模型完全不同**（digest 流式可重置 / mac 流式有 key /
cipher 一次性有 IV 和 tag / random 无状态），合并成一个大接口会产生一堆空实现。
所以是四个独立接口，注册表层收敛。

```java
public interface CryptoProvider {
    CryptoKind kind();
    String algorithm();
}

public enum CryptoKind { DIGEST, MAC, CIPHER, RANDOM }
```

### 2.1 digest

```java
public interface DigestProvider extends CryptoProvider {
    @Override default CryptoKind kind() { return CryptoKind.DIGEST; }
    DigestSession newSession();
    default int getDigestLength() { return -1; }
}

public interface DigestSession extends AutoCloseable {
    void update(byte[] data, int offset, int len);
    byte[] digest();
    void reset();
    @Override default void close() { }
}
```

**必须流式**：`CryptUtil.sha1(input, salt, iterations)` 的迭代语义一次性接口表达不了。

### 2.2 mac

```java
public interface MacProvider extends CryptoProvider {
    @Override default CryptoKind kind() { return CryptoKind.MAC; }
    MacSession newSession(byte[] key);
}

public interface MacSession extends AutoCloseable {
    void update(byte[] data, int offset, int len);
    byte[] doFinal();
    @Override default void close() { }
}
```

### 2.3 cipher

```java
public interface CipherProvider extends CryptoProvider {
    @Override default CryptoKind kind() { return CryptoKind.CIPHER; }
    String getTransformation();      // "AES/GCM/NoPadding"
    int getKeyLength();              // bits: 128 / 256
    int getIvLength();               // bytes: 12
    int getTagLengthBits();          // 128
    boolean isAead();
    CipherSession newSession(byte[] key, byte[] iv, int opmode) throws GeneralSecurityException;
}

public interface CipherSession extends AutoCloseable {
    byte[] doFinal(byte[] input);
    byte[] doFinal(byte[] input, int offset, int len);
    @Override default void close() { }
}
```

- **v1 只支持一次性 `doFinal`**（AEAD 流式需额外 tag 管理，成本高收益低）；
- **认证失败必须原样透出 `javax.crypto.AEADBadTagException`**（调用方要能区分
  「密文被篡改」和「IO 错误」）；
- `newSession` 必须校验 key 长度（128/256 bit）⇒ `InvalidKeyException`，
  iv 长度 ⇒ `InvalidAlgorithmParameterException`。

### 2.4 random

```java
public interface RandomProvider extends CryptoProvider {
    @Override default CryptoKind kind() { return CryptoKind.RANDOM; }
    void nextBytes(byte[] buf);
    void nextBytes(byte[] buf, int offset, int len);
}
```

### 2.5 ★ session 契约（重要）

`digest()` / `doFinal()` 之后 session 回到**初始状态**（已喂的数据丢弃、key 保留）——
与 JCE `MessageDigest.digest()` / `Mac.doFinal()` 一致。

**不喂数据连续调两次，第二次是「空输入」的结果，不是上次的结果。**

---

## 三、注册表

```java
public final class CryptoRegistry {
    public static void registerDigest(String algorithm, DigestProvider provider);
    public static void registerMac(String algorithm, MacProvider provider);
    public static void registerCipher(String name, CipherProvider provider);
    public static void registerRandom(String name, RandomProvider provider);

    public static DigestProvider digest(String algorithm);
    public static MacProvider mac(String algorithm);
    public static CipherProvider cipher(String name);
    public static RandomProvider random(String name);

    public static Set<String> digestNames();   // macNames / cipherNames / randomNames 同理
}
```

内部 4 个 `ConcurrentHashMap<String, XxxProvider>`；同名注册**覆盖**
（与 `AlgorithmRegistry` 同一形状）；`null`/空 key ⇒ `IllegalArgumentException`。

### 3.1 双通道注册

1. **主通道** —— `CryptoRegistry.registerXxx(...)` 显式注册；
2. **辅助通道** —— `ServiceLoader<CryptoProviderFactory>` 自动发现
   （`Edap.init()` 已有 ServiceLoader 先例）：

```java
public interface CryptoProviderFactory {
    CryptoKind kind();
    String algorithm();
    CryptoProvider create();
}
```

### 3.2 默认 provider 的解析

每个算法**只解析一次并缓存**（`DEFAULT_*` 四个 map）：

1. `edap.crypto.<kind>.provider=jce` ⇒ 强制 JCE（不告警）；
2. 开关要求 native 且 native 可用 ⇒ native provider；
3. 开关要求 native 但不可用 ⇒ 严格模式抛 `CryptoUnavailableException`，
   否则打**一次** WARN（去重）后降级 JCE；
4. 其它 ⇒ JCE。

---

## 四、开关（三段式）

| 属性 | 默认 | 语义 |
|---|---|---|
| `edap.crypto.native` | `true` | **总闸**。false ⇒ 全部走 JCE |
| `edap.crypto.digest.native` | `true` | digest 分项闸 |
| `edap.crypto.mac.native` | `true` | mac 分项闸 |
| `edap.crypto.cipher.native` | `true` | cipher 分项闸 |
| `edap.crypto.random.native` | `true` | random 分项闸 |
| `edap.crypto.strict` | `false` | 严格模式：native 不可用 ⇒ **抛异常**而非静默降级 |
| `edap.crypto.<kind>.provider` | 未设 | 强制 `native` / `jce` |

命名依据：`edap.jwt.hmac.native` 的既有先例。

布尔解析接受 `true/false`（大小写不敏感）、`on/off`、`yes/no`、`enable/disable`。

> **★ 什么时候该关掉 native（实测结论）**
> native **不是**无条件更快：
>
> | 场景 | native vs JCE | 建议 |
> |---|---|---|
> | 大 payload（≥8KB） | **2.7x ~ 9.6x** | 保持 native |
> | 小 payload（64B）单线程 | 0.24x ~ 2.9x（digest 亏、mac 赚） | 看算法 |
> | 小 payload + 高并发（16 线程） | **最差 0.24x**（比 JCE 慢 4 倍） | **设 `edap.crypto.native=false`** |
>
> 根因是 native 侧的 **JNI 调用 + 字节拷贝**是固定开销，
> 与 payload 无关（详见 §10.3）。业务形态是「大量小包 + 高并发」时必须关。
>
> 另：native 产物当前**不可移植**（§6.5），换机器可能直接降级到 JCE。

### 4.1 ★ 探测顺序

**必须先 `Class.forName(name)`（initialize=true）再读 `ENABLE_NATIVE`。**

- `Class.forName(name)` 会触发目标类的 `static{}` ⇒ 触发 `Native.loadLibrary()`
  ⇒ 这时 `Native.ENABLE_NATIVE` 才是真值；
- **不能**用 `Class.forName(name, false, loader)` —— `initialize=false` **不跑静态块**，
  读到的 `ENABLE_NATIVE` 恒为 false。这是个非常容易踩的坑。

`NativeBridge` 就是这么做的（`io.edap.crypto.internal.NativeBridge`）。

### 4.2 降级告警的出口（CryptoDiagnostics）

edap-crypto **不依赖 edap-log-api**（§1.4），所以降级告警不走 edap 的 logger，
而是走 `io.edap.crypto.internal.CryptoDiagnostics`：

| API | 说明 |
|---|---|
| `CryptoDiagnostics.warn(msg)` | 输出一条告警（**不去重** —— 去重由 `CryptoSwitches.warnOnce` 按 key 做） |
| `CryptoDiagnostics.setSink(sink)` | 宿主应用接管告警出口；传 `null` 恢复默认 |
| `CryptoDiagnostics.getSink()` | 取当前出口 |

默认出口是 **stderr**，格式 `[edap-crypto] WARN <message>` ——
与 `LoggerManager` 自己找不到 provider 时用 `Util.printError` 的做法一致。
宿主可以用 `setSink` 把它接到自己的 logger 上：

```java
CryptoDiagnostics.setSink(msg -> myLogger.warn("[edap-crypto] {}", msg));
```

去重语义不变：**同一个降级原因只告警一次**（按 key），key 由
`CryptoSwitches.warnOnce(key, message)` 的调用方给出
（如 `digest:MD5` / `mac:HmacSHA256`）。

---

## 五、JCE 实现

| Provider | 实现要点 |
|---|---|
| `JceDigestProvider` | `InstancePool<MessageDigest>`，每线程复用一个实例 |
| `JceMacProvider` | `InstancePool<Mac>` + `SecretKeySpec`；session 存 `key.clone()` |
| `JceCipherProvider` | `InstancePool<Cipher>`；`newSession` 校验 key/iv 长度 |
| `JceRandomProvider` | `ThreadLocal<SecureRandom>` 绕开 `NativePRNG` 引擎级 `synchronized` |

### 5.1 ★★ 实例必须被 session 独占（踩过的坑）

**原先的实现是「每个 provider 一个 `ThreadLocal<MessageDigest>`，session 直接取用」——
这是错的。**

`MessageDigest.reset()` / `Mac.init(key)` 会清掉内部状态。
同一线程里两个**活** session 共用一个实例时，后一个的 `reset()` / `init()`
会把前一个已喂的数据清掉 ⇒ **前一个算出来的摘要 / MAC / 密文是错的，而且不报错**。
cipher 更严重：会用**别人的 key** 加密。

**修法**：`io.edap.crypto.internal.InstancePool<T>` —— 每线程槽位 + **独占**语义：

- `acquire()`：取走该线程的槽位实例（独占），没有就新建；
- `release()`：归还。

三个 JCE provider 全部改为：
session 独占实例；`digest()` / `doFinal()` 算完即归还池；`reset()` / `close()` 也归还；
再次使用时重新 `acquire` + `init`。

**这是本次最重要的正确性修复**，有专门的回归测试。

---

## 六、native 实现

### 6.1 算法划分

| 原语 | native 路线 | 理由 |
|---|---|---|
| Digest (SHA-256 / SHA-1 / MD5) | **legacy `SHA256()` / `SHA1()` / `MD5()`** | 有实测数据（`edap-native/doc/NATIVE_DESIGN.md` §6.6） |
| HMAC-SHA256 / HMAC-SHA1 | **legacy + 手工展开 ipad/opad** | 同上 |
| AES-GCM | **必须 EVP** | legacy 没有 GCM API |
| Random | **不做 native** | JDK `SecureRandom` 默认就是 OS 原生熵源（`NativePRNG`），JNI `RAND_bytes` 收益 ≈ 0、风险大 |

### 6.2 沿用 legacy 还是重新验证（**已实测，结论：不迁移**）

原计划：v1 digest / HMAC **继续用 legacy**（有实测数据支撑）；
同时新增一条 JMH arm 测 `EVP_MAC_fetch` **缓存版** HMAC-SHA256，与 legacy 并排比。

OpenSSL 3.x 的正确姿势（如果哪天要迁）：

```c
EVP_MAC *mac = EVP_MAC_fetch(NULL, "HMAC", NULL);   /* 必须缓存！ */
OSSL_PARAM params[] = {
    OSSL_PARAM_construct_utf8_string("digest", "SHA256", 0),
    OSSL_PARAM_construct_end()
};
```

**必须缓存 fetch 结果** —— 每次 `EVP_MAC_fetch()` 都触发 provider dispatch，
正是 `NATIVE_DESIGN.md` §6.6 里 `inner_evp_generic_fetch` 吃 15.1% CPU 的元凶。

**★ 实测结论（纯 C 探针，不经 JNI、不改已提交的 `.o`）**

探针源码：`edap-native/scripts/evp-vs-legacy-probe.c`（自包含，可直接编译运行）。
三个 arm：

| arm | 实现 |
|---|---|
| `legacy` | `SHA256_Init/Update/Final` + 手工展开 ipad/opad，与 `edap_jni_common.h` 的 `edap_hmac_sha256` **逐行等价** |
| `evp_cached` | `EVP_MAC_fetch(NULL,"HMAC",NULL)` 只做一次，之后复用 |
| `evp_uncached` | 每次都 `EVP_MAC_fetch`（= 计划书里点名的 dispatch 元凶） |

```bash
cc -O2 -o evp_probe edap-native/scripts/evp-vs-legacy-probe.c -lcrypto
./evp_probe <payload> <iters>
```

实测（每 payload 20 万次迭代 × 5 轮取最优；三个 arm 输出**逐字节相同**）：

| payload | legacy (ns/op) | evp_cached | legacy/evp_cached | evp_uncached | legacy/evp_uncached |
|---|---|---|---|---|---|
| 64 | 152.2 | 724.5 | **0.21x** | 868.4 | **0.18x** |
| 1024 | 544.3 | 1114.2 | **0.49x** | 1269.7 | **0.43x** |
| 8192 | 3512.3 | 4068.2 | 0.86x | 4220.7 | 0.83x |
| 65536 | 27306.3 | 27738.4 | 0.98x | 27943.8 | 0.98x |

**结论**：**EVP 缓存版追不上 legacy** —— 中小 payload 慢 **2~5 倍**，
只有到 64KB 才勉强追平（0.98x）。EVP 的 provider dispatch + 参数解析 +
`EVP_MAC_CTX` 分配的**固定开销**在 payload 越大时越被摊薄，所以曲线是
「小 payload 巨亏、大 payload 打平」，**不存在「追平后反超」的区间**。

⇒ **v1 维持 legacy，不迁移 EVP。**

推论：**AES-GCM 也必须走 EVP**（legacy 没有 GCM API）⇒ native cipher 的固定开销
**必然**比 legacy digest/MAC 更高。在没有使用方之前不值得引入 EVP 复杂度 ⇒
**v1 cipher 保持 JCE-only**（详见 §10.5、§10.6、§11 已知限制）。

> 这条实测把原计划里的「C-10（EVP 缓存版 JMH arm）」从「必须做」降级为
> 「**已用更干净的纯 C 探针回答**」—— 探针剥离了 JNI 与 JMH 的噪声，
> 且能直接看到 `EVP_MAC_fetch` 的 dispatch 成本。
> 探针保留在仓库里，随时可复现。

### 6.3 edap-native 的 JNI 扩展

新增：

```
edap-native/src/main/c/
├── edap_jni_common.h                 公共常量 + key 预处理 + HMAC 展开
├── io_edap_jni_crypto_NativeDigest.c digest0(String alg, byte[] data, int off, int len)
├── io_edap_jni_crypto_NativeMac.c    hmacSha2560 / hmacSha10
└── io_edap_jni_crypto_NativeHmacSha256.c  （重构为复用 edap_jni_common.h）

edap-native/src/main/java/io/edap/jni/crypto/
├── NativeDigest.java     digest0(...)
└── NativeMac.java        hmacSha2560 / hmacSha10
```

**踩坑记录**：
- OpenSSL 3.x 里 SHA-1 的 ctx 类型叫 `SHA_CTX`（**没有** `SHA1_CTX`）；`SHA256_CTX` 存在。
- JNI 绑定方法**必须 public** —— `NativeBridge` 用 `MethodHandles.lookup()` 跨包访问，
  只能访问 public 成员。
- `Native.java` 的资源名 `"/edap-native-" + os + "_" + arch + ".o"` **前导斜杠不是 bug**
  （`Class.getResourceAsStream` 的绝对路径语义），已加注释防误改。

### 6.4 `build-native.sh`

```bash
./scripts/build-native.sh              # 输出到 target/native-build（不碰已提交产物）
./scripts/build-native.sh --commit      # 更新 src/main/resources/ 下的已提交产物
```

- 编译 `src/main/c/*.c` **全部**成一个共享库（`System.load` 只能加载一个文件）；
- 输出 `target/openssl_version.h`，版本取**编译时头文件**的版本（不是 PATH 上的 CLI ——
  本机 CLI 是 OpenSSL **4.1.0**，而头文件是 **3.6.4**，两者可能不是同一个安装）；
- 自检：`nm` 校验 4 个 JNI 符号齐全（缺则中止）、`file`、`shasum -a 256`。

**默认路径不变**：`.o` 继续预编译提交到 `src/main/resources/`，当前只有
`edap-native-macos_aarch64.o`。

### 6.5 ★ native 产物的可移植性（实测发现的重要限制）

已提交的 `edap-native-macos_aarch64.o` 用 `otool -L` 看，动态链接到的是**绝对路径**：

```
/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib
```

这意味着：

- **只有**装了 Homebrew `openssl@3`、且路径**完全一致**的机器能加载它；
- 其它机器（Linux / Windows / 别的 macOS 路径 / 没装 Homebrew）⇒ `System.load` 失败
  ⇒ `Native.ENABLE_NATIVE = false` ⇒ **静默降级到 JCE**（功能正常，只是没有 native 加速）；
- macOS 上 `.dylib` 路径写死在 Mach-O 里，**不能**靠 `DYLD_LIBRARY_PATH` 修正
  （SIP 下对系统保护路径无效；且 `DYLD_*` 变量对 `System.load` 的行为也不可靠）。

**要真正可移植，只有两条路**：

| 方案 | 说明 | 代价 |
|---|---|---|
| 每个平台各自预编译提交 `.o` | 与现状一致，按 os.arch 各提交一份 | 需要 CI/构建机覆盖 linux/macos/windows |
| 静态链接 libcrypto | `.o` 不再依赖外部 dylib | 体积变大；许可证（OpenSSL 3.x Apache-2.0）需核对 |

本轮**未做**这两件事 —— 当前定位是「开发机加速」，不是「生产环境必须 native」。
生产部署若需要 native，应按上表逐平台构建。

### 6.6 ★ OpenSSL 4.x 是否删了 legacy API（实测）

计划 §R-17 担心「OpenSSL 4.0 会删 `SHA256_*` / `SHA1_*` / `MD5_*` legacy API」。
本机 PATH 上的 OpenSSL CLI 是 **4.1.0**，实测其 `libcrypto.4.dylib` 的导出符号：

```bash
nm -gU /opt/homebrew/opt/openssl@4/lib/libcrypto.4.dylib | grep -E 'SHA256_Init|SHA1_Init|MD5_Init'
```

**结论：legacy 符号仍然存在**（`SHA256_Init/Update/Final`、`SHA1_*`、`MD5_*` 都命中）。
⇒ R-17 的紧急度下降，v1 继续用 legacy 是安全的；但这条结论**只对 4.1.0 成立**，
后续版本仍需复核（脚本 `build-native.sh` 已把 OpenSSL 版本写进
`target/openssl_version.h`，便于跨版本对照）。

---

## 七、消费方迁移

### 7.1 迁移顺序

| 阶段 | 内容 | 理由 | 状态 |
|---|---|---|---|
| C-1 | provider + SPI + 注册表 + 开关 | 建立抽象 | ✅ |
| C-2 | JCE 实现（实例池） | — | ✅ |
| C-3 | `NativeBridge` + `NativeDigest` / `NativeMac` + C 代码 | 有实测依据 | ✅ |
| C-4 | **基线基准（修前）跑 5 轮，落 `before-*.json`** | **必须在迁移前有基线** | ✅ 批次 `20261104-214643` |
| C-5 | 修后基准跑 5 轮，落 `after-*.json` | 中位数 + 变异系数 | ✅ 同批次 |
| C-6 | 迁移 JWT | 热路径明确，收益最大 | ✅ |
| C-7 | 迁移 `SigV4Verifier` | 收益是**纯 JCE**（省 setup） | ✅ |
| C-8 | 迁移 `CryptUtil` | 影响面最大，放最后 | ✅ |
| C-9 | 可选 `NativeCipher`（EVP + fetch 缓存） | **实测判定为不做**：EVP 固定开销高于 legacy（§6.2），仓库零 Cipher 用量 | ❌ **不做**（JCE-only） |
| C-10 | 可选 `EVP_MAC_fetch` 缓存版 HMAC 的 JMH arm | **已用纯 C 探针回答**（§6.2）：EVP 慢 2~5 倍 ⇒ 不迁移 | ❌ **不做**（维持 legacy） |

### 7.2 JWT 迁移细则

- 保持 `io.edap.auth.jwt.Algorithm` 接口不变；
- 保持 `AlgorithmRegistry.register("HS256", HmacSha256::new)` 不变；
- 保留 `HmacSha256.HmacSha256Native` 这个 **public nested class**（外部可能引用）；
- **实现换成 provider 抽象**：`HmacSha256` 只持有 key + 每线程一个 `MacSession`
  （`ThreadLocal`）；具体 native / JCE 由 `CryptoRegistry` 解析
  （显式注册的 provider 优先，否则按 `edap.crypto.mac.*` 开关）；
- 旧开关 `edap.jwt.hmac.native=false|disable|off` **仍生效**，语义明确：
  **JWT 作用域内**强制 JCE，且**不走注册表**。

**踩坑（已修正）**：第一版把旧开关「映射到 `edap.crypto.mac.native`」。
那样一个 **JWT 专用**的开关会变成**进程级**开关 —— SigV4 等其它模块也被迫走 JCE。
改为「JWT 作用域内强制 JCE」，不动全局系统属性。

**验证**：`HmacSha256ProviderTest`（5 例）覆盖
①签名与裸 JCE 逐字节一致 ②默认走注册表（注册一个计数 provider，断言它被用上）
③旧开关生效且绕过注册表 ④新开关 `edap.crypto.mac.provider=jce` 生效
⑤连续 sign 可复现（session 复用不串数据）。

### 7.3 SigV4 迁移细则

现状：`Mac.getInstance` + `init` **每次调用新建** ⇒ **开销在 setup 不在计算**。
迁移后 `hmac()` / `sha256Hex()` 走 `CryptoRegistry` ⇒ provider 内部按线程复用实例。

一次 SigV4 验签要算 **5 次 HMAC**（date → region → service → aws4_request → signature）
+ 1 次 SHA-256 ⇒ 迁移前是 6 次 `getInstance`；迁移后同线程连续调用复用同一个
`Mac` 实例（`InstancePool` 的「取用即独占、用完归还」语义）⇒ 这 6 次 setup 全省掉。

**刻意没做**：signing key 按 `(date, region, service)` 缓存 —— 那是业务层缓存，
不在 provider 层职责内，且要引入缓存失效策略，本次不引入。

**验证**：`SigV4VerifierTest`(22) + `SigV4PresignerTest`(27) 全绿（AWS 官方向量）。
另有 `SigV4HmacBenchmark`（`edap-s3-core`，before/after 两个 arm）单独量化这条链的收益，
见 §八 与 `edap-s3-parent/edap-s3-core/doc/bench/README.md`。

### 7.4 CryptUtil 迁移细则

优先级最低（edap-common 最底层，影响面最大）。
`sha1(input, salt, iterations)` 的迭代语义 ⇒ provider 必须支持流式 `DigestSession`：
迁移后 `digest()` 之后 session 回到初始状态 ⇒ 迭代直接接着喂上一轮结果。

**行为保持不变**（逐字节验证）：
- `md5(String)` 仍用**平台默认字符集**（历史行为）。改成 UTF-8 会让非 ASCII 输入的
  md5 值变化 ⇒ 已生成的 handler 名 / 转换器 key 全部变化；
- 异常类型仍是 `RuntimeException` 子类；
- 算法名统一成 JCE 规范名（`MD5` / `SHA-1` / `SHA-256`）—— JCE 名大小写不敏感，
  摘要值不变；但 native 侧按规范名匹配（`NativeDigestProvider` 的映射表是
  `MD5` / `SHA-1` / `SHA-256`），写 `md5` 会**静默拿不到 native**。

**依赖方向**：`edap-common → edap-crypto`（无环；
`edap-native` 已解除对 `edap-common` 的依赖；edap-crypto 不依赖 edap-log-api，见 §1.4）。

**★ 裸 JCE 兜底**：`CryptUtil` 位于**日志 bootstrap 路径**上
（`LoggerManager.init()` → SPI provider → appender 初始化 → `EncoderGenerator`
→ `CryptUtil.md5`）。crypto 层一旦抛 `RuntimeException`，会连带打死整个日志系统
（SPI provider 实例化失败 ⇒ 全应用退化成 NopLogger）。
所以 `digest(...)` 捕获 `RuntimeException` 后退回**裸 JCE**
（`MessageDigest.getInstance`，完全绕开 edap-crypto），并把问题通过
`CryptoDiagnostics.warn` 打到 stderr —— **结果逐字节相同，不掩盖错误**。
兜底也失败（算法根本不存在）⇒ 抛原始异常，保持既有语义。

**验证**：`CryptUtilTest`（9 例）—— golden 值全部用**迁移前的原始实现**算出，
用来证明「只换实现、不换结果」。

---

## 八、基准

### 8.1 基准类

| 类 | 位置 | 用途 |
|---|---|---|
| `CryptoBaselineBenchmark` | `edap-crypto` | **修前裸 JCE**（每次 `getInstance`）。**永久保留**以便复现对比 |
| `CryptoProviderBenchmark` | `edap-crypto` | 修后走 provider（JCE 实现，实例复用） |
| `NativeVsJceBenchmark` | `edap-crypto` | native vs jce 直接对比 |
| `SigV4HmacBenchmark` | `edap-s3-core` | **SigV4 整条签名链**（1 次 SHA-256 + 5 次 HMAC）的修前 / 修后对比 |

**参数**：payload 64 / 1024 / 8192 / 65536 字节；线程 1 / 4 / 16；
arm = md5 / sha1 / hmac-sha256（native 对比类是 native-jce 两组；
SigV4 基准的 payload 是 canonical request 长度 128 / 1024 / 8192）。

**实验设计**：`CryptoBaselineBenchmark` 与 `CryptoProviderBenchmark` **两边都用 JCE 实现**，
唯一变量是「每次 `getInstance`」vs「provider 复用实例」；
native 相对 JCE 的收益在 `NativeVsJceBenchmark` 里**单独测**。

### 8.2 纪律

- 修前/修后两批独立 JSON **都提交**（`doc/bench/before-*.json` / `after-*.json`），
  基线基准类**永久保留**可复现；
- 推演 vs 实测**分章**（本文 §九 / §十）；
- 每轮记录 loadavg（`sysctl -n vm.loadavg`）；
- ≥5 轮，报**中位数 + 最小/最大 + 变异系数（CV）**，不用单轮数字下结论；
- 记录元数据：date、uname、java -version、openssl version、git HEAD、loadavg。

### 8.3 怎么跑

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./edap-crypto/scripts/run-bench.sh 5          # crypto 三件套
./edap-s3-parent/edap-s3-core/scripts/run-sigv4-bench.sh 5   # SigV4 签名链
```

配对比较用 `edap-crypto/scripts/compare-bench.py`（before vs after 加速比）——
`run-bench.sh` 自己只出「按 benchmark 列表」的汇总。

产出（`edap-crypto/doc/bench/`）：

```
before-CryptoBaseline-<ts>-r<N>.json
after-CryptoProvider-<ts>-r<N>.json
after-NativeVsJce-<ts>-r<N>.json
summary-<ts>.md          中位数 + 最小/最大 + CV
bench-<ts>.log           原始日志（含每轮前后 loadavg）
```

### 8.4 EVP vs legacy 探针（不走 JMH）

「legacy OpenSSL API vs OpenSSL 3.x 的 `EVP_MAC`」这个对比**刻意没用 JMH**：
它要比较的是 OpenSSL 内部的固定开销，JMH + JNI + provider 三层噪声会盖住结论。
改用一个自包含的纯 C 程序：

```bash
cc -O2 -I/opt/homebrew/opt/openssl@3/include -L/opt/homebrew/opt/openssl@3/lib \
   -o /tmp/evp_probe edap-native/scripts/evp-vs-legacy-probe.c -lcrypto
/tmp/evp_probe 64 200000      # <payload> <iters>
```

编译期会有一堆 `SHA256_Init/Update/Final` 的 deprecation 警告 —— **预期的**，
本探针比的就是「legacy（3.0 已标 deprecated）vs EVP」。

三个 arm：`legacy`（与 `edap_jni_common.h` 逐行等价）/ `evp_cached` / `evp_uncached`。
结论见 §10.5 —— **EVP 缓存版追不上 legacy ⇒ 不迁移**。

---

## 九、推演（**未验证**，写在这里是为了标明「这是推理不是数据」）

> 以下全部是**基于机制与已有文献的推理**，不是本仓库的实测数据。
> 实测见 §十（**状态列已按 §十 的结果逐条更新**）。

| # | 推演 | 依据 | 状态 |
|---|---|---|---|
| D-1 | provider 复用实例能省掉 `getInstance` 的 1~3μs | JCE `getInstance` 要查 provider、创建 SPI | ✅ **已验证**（§10.2） |
| D-2 | 小 payload（64B）收益占比最大 | setup 固定开销不变，计算量小 | ✅ **已验证**（§10.2） |
| D-3 | 大 payload（64KB）收益被计算量摊薄 | 计算量 ∝ payload，setup 固定 | ✅ **已验证**（§10.2） |
| D-4 | native 在大 payload 上优势明显（>2x） | `NATIVE_DESIGN.md` §6.6 实测 native HMAC 相对 Java 7.81x~8.24x | ✅ **已验证**（§10.3，最大 **9.61x**） |
| D-5 | native 在小 payload + 高并发下可能**不如** JCE | JNI 调用 + `ByteAccumulator` 拷贝 + 临界区开销固定 | ✅ **已验证**（§10.3，Digest@64B/16t 只有 **0.24x**） |
| D-6 | 16 线程下 JCE 的 `ThreadLocal` 实例会成为竞争点 | JCE 实现内部有锁 | ❌ **已推翻**（§10.3）—— 竞争点在 **native 侧的 JNI 调用**，不在 JCE 的 `ThreadLocal`；JCE 侧 16t 相对 1t 只劣化 ~2.2x（接近线性），native 侧同条件劣化 **9.3x** |

---

## 十、实测

> 数据文件：`edap-crypto/doc/bench/`。
> 批次时间戳 **`20261104-214643`**，5 轮。
> 汇总见 `summary-20261104-214643.md`；配对比较见本节表格。

**环境**（见 `bench-20261104-214643.log` 头部）：

| 项 | 值 |
|---|---|
| 机器 | macOS Darwin 27.2.0, arm64, **10 核**（`louis-mbp-232`） |
| JDK | OpenJDK **17.0.20.1**（arm64） |
| OpenSSL CLI | **4.1.0**（编译用头文件是 `openssl@3` **3.6.4** —— 两者不是同一个安装） |
| git HEAD | `f362f8a2441140abc48988efea8e17795c4266ae` |
| native 产物 | `edap-native-macos_aarch64.o`（链接到 Homebrew `openssl@3` 绝对路径，本机可加载 ⇒ native arm **真实参与**了本批数据） |

**读数纪律**：本机是开发机（IDE、编辑器、其它后台进程都在跑），
跑批期间 **loadavg 19.77 ~ 77.99**（核数的 2~8 倍；脚本等 3×30s 后带 WARN 继续跑）。
所以：

- **绝对值不可与其它机器/其它批次混用**；
- 结论只看同一批次内的**相对关系**（before vs after、native vs jce）；
- 用 **CV** 判断可信度 —— 本批大部分 arm 的 CV 在 **0.1% ~ 5%**，少数 16 线程 arm 到 8%~21%（见 §10.3 备注）。

### 10.1 结论先行

| 结论 | 证据 |
|---|---|
| **provider 复用实例确有收益，但只在「小 payload + 高频调用」下明显** | §10.2：64B 时 1.12x~1.31x；64KB 时 1.00x~1.03x |
| **SigV4 整条签名链的收益 1.11x~1.37x，且几乎与 payload 无关** | §10.4：成本主体是 6 次 `getInstance` 的固定 setup |
| **native 在大 payload 上是数量级优势（~9x）** | §10.3：SHA-256@64KB 最高 **9.38x**，HMAC-SHA256@64KB 最高 **9.61x** |
| **native 在「小 payload + 高并发」下会输** | §10.3：SHA-256@64B/16t 只有 **0.24x**（比 JCE 慢 4 倍） |
| **JCE 的 `ThreadLocal` 不是竞争点** | §10.3：JCE 侧 16t/1t ≈ 2.2x；native 侧同条件 ≈ 9.3x |
| **HMAC-SHA256 是 native 最划算的场景** | §10.3：64B 就已经 **2.69x**，JCE 的 HMAC 实现本身就慢 |
| **OpenSSL 3.x 的 EVP 缓存版 HMAC 追不上 legacy ⇒ 不迁 EVP** | §10.5：中小 payload 慢 **2~5 倍**（0.21x / 0.49x），64KB 才打平（0.98x）；EVP 固定开销 ~570ns/次 |
| **顺带判定：native AES-GCM 也不做** | AES-GCM 在 OpenSSL 3.x 上只能走 EVP，固定开销只会更高；仓库零 Cipher 用量 |

### 10.2 修前（裸 JCE 每次 `getInstance`）vs 修后（provider 复用实例）

数据：`before-CryptoBaseline-20261104-214643-r{1..5}.json` vs
`after-CryptoProvider-20261104-214643-r{1..5}.json`，
中位数取 5 轮。加速比 = 修前 / 修后（>1 = 修后更快）。

| arm | payload | 修前中位数 (ns/op) | 修后中位数 (ns/op) | 加速比 | 修后 CV% |
|---|---|---|---|---|---|
| `mac_1t` | 64 | 1,769 | 1,580 | **1.120x** | 1.04 |
| `mac_4t` | 64 | 1,997 | 1,694 | **1.179x** | 2.92 |
| `mac_16t` | 64 | 4,417 | 3,381 | **1.307x** | 1.96 |
| `mac_1t` | 1024 | 6,122 | 5,830 | 1.050x | 0.57 |
| `mac_4t` | 1024 | 6,411 | 6,159 | 1.041x | 1.02 |
| `mac_16t` | 1024 | 13,609 | 11,772 | 1.156x | 2.76 |
| `mac_1t` | 8192 | 38,042 | 37,516 | 1.014x | 2.94 |
| `mac_4t` | 8192 | 39,491 | 39,013 | 1.012x | 0.42 |
| `mac_16t` | 8192 | 83,717 | 75,374 | 1.111x | 5.21 |
| `mac_1t` | 65536 | 294,434 | 290,819 | 1.012x | 0.44 |
| `mac_4t` | 65536 | 304,464 | 300,619 | 1.013x | 0.16 |
| `mac_16t` | 65536 | 599,085 | 586,162 | 1.022x | 2.77 |
| `md5_1t` | 64 | 234.09 | 204.30 | **1.146x** | 0.44 |
| `md5_4t` | 64 | 240.90 | 238.81 | 1.009x | 5.50 |
| `md5_16t` | 64 | 469.41 | 510.54 | **0.919x** | 7.24 |
| `md5_1t` | 1024 | 1,738 | 1,682 | 1.033x | 0.28 |
| `md5_1t` | 8192 | 12,833 | 12,752 | 1.006x | 0.33 |
| `md5_1t` | 65536 | 101,549 | 101,360 | 1.002x | 0.30 |
| `sha1_1t` | 64 | 442.16 | 392.59 | **1.126x** | 0.71 |
| `sha1_4t` | 64 | 452.08 | 446.57 | 1.012x | 2.91 |
| `sha1_16t` | 64 | 883.77 | 917.25 | **0.964x** | 3.68 |
| `sha1_1t` | 1024 | 3,266 | 3,142 | 1.040x | 0.53 |
| `sha1_1t` | 8192 | 24,028 | 23,779 | 1.010x | 0.36 |
| `sha1_1t` | 65536 | 190,210 | 188,541 | 1.009x | 0.27 |

**读法**：

- **收益随 payload 增大而稀释** —— 正是 D-2 / D-3。setup 固定开销（`getInstance`）
  被摊薄到计算量里。
- **最大收益 1.307x**（`mac_16t`@64B）；**64KB 时基本打平**（1.00x~1.03x）。
- **两个负收益点**（`md5_16t`@64B = 0.919x、`sha1_16t`@64B = 0.964x）都发生在
  **16 线程 + 最小 payload** —— 那里总耗时只有几百 ns，`getInstance` 的收益
  已被线程调度噪声淹没；对应 CV 也最高（7.24% / 3.68%）。
  **不要把这两个点当成「provider 变慢」的证据**，它们在噪声范围内。
- **绝对收益换算**：64B/1 线程时每次调用省 **约 190 ns**（1,769 → 1,580）——
  与进程内微探针测到的「`getInstance` 本身约 300ns、re-init 约 39ns」量级一致
  （微探针脚本 `/tmp/macprobe/MacProbe2.java`，每个变体独立 JVM、200k warmup + 5×200k 轮取最优；
  A=每次 `getInstance`+init 1506.9 ns/op、B=复用+reset 1206.1、C=复用+每次 init 1244.0、
  D=复用+不 init 1205.3、E=仅 init 48.7）。

### 10.3 native vs JCE

数据：`after-NativeVsJce-20261104-214643-r{1..5}.json`。
digest = SHA-256，mac = HmacSHA256，两边都走 provider（唯一变量是底层实现）。
「native 相对 jce」= jce 中位数 / native 中位数（>1 = native 更快）。

| 原语 | payload | 线程 | native 中位数 | jce 中位数 | native 相对 jce | native CV% | jce CV% |
|---|---|---|---|---|---|---|---|
| SHA-256 | 64 | 1 | 612 | 608 | 0.99x | 0.56 | 0.96 |
| SHA-256 | 64 | 4 | 842 | 697 | **0.83x** | 6.95 | 4.63 |
| SHA-256 | 64 | 16 | 5,674 | 1,359 | **0.24x** | 5.72 | 3.68 |
| SHA-256 | 1024 | 1 | 1,044 | 4,870 | 4.67x | 0.69 | 1.03 |
| SHA-256 | 1024 | 4 | 1,314 | 5,103 | 3.88x | 7.65 | 0.95 |
| SHA-256 | 1024 | 16 | 5,761 | 9,724 | 1.69x | 3.64 | 2.89 |
| SHA-256 | 8192 | 1 | 4,439 | 36,616 | 8.25x | 0.63 | 1.21 |
| SHA-256 | 8192 | 4 | 4,823 | 37,986 | 7.88x | 1.20 | 0.42 |
| SHA-256 | 8192 | 16 | 9,981 | 78,886 | 7.90x | 3.65 | 8.36 |
| SHA-256 | 65536 | 1 | 31,667 | 290,964 | 9.19x | 0.24 | 0.60 |
| SHA-256 | 65536 | 4 | 33,016 | 300,893 | 9.11x | 0.16 | 0.09 |
| SHA-256 | 65536 | 16 | 63,399 | 594,409 | 9.38x | 3.63 | 3.57 |
| HMAC-SHA256 | 64 | 1 | 579 | 1,561 | **2.69x** | 0.64 | 0.65 |
| HMAC-SHA256 | 64 | 4 | 601 | 1,708 | **2.84x** | 0.92 | 2.28 |
| HMAC-SHA256 | 64 | 16 | 1,148 | 3,348 | **2.92x** | 1.62 | 3.67 |
| HMAC-SHA256 | 1024 | 1 | 1,017 | 5,811 | 5.72x | 1.13 | 0.96 |
| HMAC-SHA256 | 1024 | 4 | 1,061 | 6,127 | 5.78x | 0.14 | 1.67 |
| HMAC-SHA256 | 1024 | 16 | 2,030 | 11,860 | 5.84x | 1.93 | 4.50 |
| HMAC-SHA256 | 8192 | 1 | 4,451 | 37,483 | 8.42x | 5.20 | 1.02 |
| HMAC-SHA256 | 8192 | 4 | 4,625 | 39,009 | 8.43x | 0.41 | 3.74 |
| HMAC-SHA256 | 8192 | 16 | 8,836 | 76,980 | 8.71x | 8.05 | 4.10 |
| HMAC-SHA256 | 65536 | 1 | 31,444 | 289,644 | 9.21x | 1.29 | 0.74 |
| HMAC-SHA256 | 65536 | 4 | 32,748 | 301,831 | 9.22x | 0.28 | 1.90 |
| HMAC-SHA256 | 65536 | 16 | 62,339 | 598,973 | **9.61x** | 5.78 | 21.20 |

**读法（三条）**：

1. **大 payload 上 native 是数量级优势**（8x~9.6x）——
   与 `edap-native/doc/NATIVE_DESIGN.md` §6.6 记的 7.81x~8.24x 同量级，
   本机（arm64 / M 系列）实测略高。**D-4 成立。**
2. **小 payload + 高并发时 native 会输** —— SHA-256@64B：
   1 线程 0.99x（打平）→ 4 线程 0.83x → 16 线程 **0.24x**（慢 4 倍）。
   固定成本（JNI 调用 + 每次 `newSession` 的对象分配 + 跨语言拷贝）压过了计算量。
   **D-5 成立，而且比推演更严重。**
3. **HMAC-SHA256 是 native 最划算的场景** —— 64B 就已经 **2.69x**，
   因为 JCE 的 HMAC 实现本身慢（每块要走两次 SHA-256 压缩 + 嵌套 key 派生），
   而 native 侧手工展开 ipad/opad 后快得多。
   **这正是 edap 的主要调用点（JWT HS256 / SigV4）** ⇒ native 的实际价值比
   「digest 平均领先多少」要高。

**D-6 被推翻**：JCE 侧 16 线程相对 1 线程只劣化 **~2.2x**（接近线性），
`ThreadLocal` + `InstancePool` **不是**竞争点；真正的竞争点是 **native 侧的 JNI 调用**
（SHA-256@64B：native 16t/1t = **9.3x**，jce 16t/1t = **2.2x**）。

**⚠️ 备注**：jce CV 21.20% 那一格（HMAC@65536/16t）明显偏高 —— 那一组
jce 中位数 598,973 ns 与 16 线程下其它组差距不大，怀疑是 loadavg 高峰期的调度抖动。
该格的**相对结论**（native 仍快 ~9.6x）不受影响，因为 native 侧同条件 CV 只有 5.78%。

### 10.4 SigV4 整条签名链（修前 vs 修后）

数据：`edap-s3-parent/edap-s3-core/doc/bench/sigv4-20261104-231103-r{1..5}.json`。
测的是**一次验签的密码学部分**（1 次 SHA-256 + 5 次 HMAC-SHA256），
`before` = 旧实现（每次 `getInstance`），`after` = 走 provider。

| 线程 | canonical 长度 | 修前中位数 (ns/op) | 修后中位数 (ns/op) | 加速比 | 修后 CV% |
|---|---|---|---|---|---|
| 1 | 128 | 8,051 | 7,163 | **1.124x** | 1.65 |
| 1 | 1024 | 8,027 | 7,160 | **1.121x** | 2.62 |
| 1 | 8192 | 8,019 | 7,249 | 1.106x | 1.15 |
| 4 | 128 | 9,405 | 7,606 | **1.236x** | 6.06 |
| 4 | 1024 | 9,496 | 7,719 | **1.230x** | 0.98 |
| 4 | 8192 | 9,505 | 7,654 | **1.242x** | 3.27 |
| 16 | 128 | 21,661 | 15,773 | **1.373x** | 2.12 |
| 16 | 1024 | 21,581 | 15,746 | **1.371x** | 4.03 |
| 16 | 8192 | 21,361 | 18,642 | 1.146x | 10.24 |

**读法**：

- **加速比 1.11x ~ 1.37x**，且**几乎与 canonical request 长度无关** ——
  因为这条链的成本主体是 **6 次 `getInstance` 的固定 setup**，不是摘要计算。
  这与 §10.2「收益随 payload 增大而稀释」的规律**不矛盾**：这里 payload 只影响
  其中 1 次 SHA-256 / HMAC，另外 5 次 HMAC 的输入是固定长度的短串。
- **线程越多收益越大**（1t 1.12x → 4t 1.24x → 16t 1.37x）——
  固定 setup 在高并发下摊到每个请求上的相对代价更高。
- 绝对值：单线程一次验签的密码学部分从 **~8.0μs 降到 ~7.2μs**，
  16 线程下从 **~21.6μs 降到 ~15.8μs**。

### 10.5 EVP 缓存版 vs legacy（纯 C 探针，不经 JNI）

数据来源：`edap-native/scripts/evp-vs-legacy-probe.c`（自包含 C 程序，
5 轮取最优，与 §10.2~§10.4 的 JMH 批次**不是**同一次运行，绝对值不可混用）。
方法说明见 §6.2。

| payload | legacy (ns/op) | evp_cached (ns/op) | legacy/evp_cached | evp_uncached (ns/op) | legacy/evp_uncached |
|---|---|---|---|---|---|
| 64 | 152.2 | 724.5 | **0.21x** | 868.4 | **0.18x** |
| 1024 | 544.3 | 1114.2 | **0.49x** | 1269.7 | **0.43x** |
| 8192 | 3512.3 | 4068.2 | 0.86x | 4220.7 | 0.83x |
| 65536 | 27306.3 | 27738.4 | 0.98x | 27943.8 | 0.98x |

三个 arm 的输出**逐字节相同**（正确性 OK）。

**读法**：

- **EVP 缓存版追不上 legacy**。中小 payload 慢 **2~5 倍**；
  payload 越大越被摊薄，到 64KB 才勉强打平（0.98x）。
- **缓存 fetch 确实有用，但杯水车薪**：`evp_cached` 比 `evp_uncached` 快
  16%~20%（64B: 724.5 vs 868.4）—— 证实 `NATIVE_DESIGN.md` §6.6 里
  `inner_evp_generic_fetch` 的 dispatch 开销真实存在，
  但**即便缓存掉也仍然远慢于 legacy**。
- 曲线的形状说明：EVP 的**固定开销**（provider dispatch + 参数解析 +
  `EVP_MAC_CTX` 分配）约 **570 ns**，与 payload 无关；
  legacy 几乎零固定开销。
- ⇒ **不存在「追平后反超」的区间** ⇒ 没有迁移动机。

### 10.6 对设计决策的影响

| 决策 | 实测后的结论 |
|---|---|
| v1 digest / MAC 用 **legacy** OpenSSL API | **保留**。legacy 在大 payload 上已经 ~9x；且实测 OpenSSL 4.1.0 仍导出 legacy 符号（§6.6），迁移 EVP 的紧迫度下降。**更关键：EVP 缓存版实测追不上 legacy**（§10.5，中小 payload 慢 2~5 倍）⇒ **不迁 EVP** |
| v1 **cipher 只走 JCE** | **保留**。仓库当前零 Cipher 用量；且 AES-GCM 在 OpenSSL 3.x 上**只能**走 EVP，而 EVP 的固定开销实测 ~570ns/次（§10.5）—— 收益无法证明必要（C-9 判定为**不做**） |
| v1 **random 只走 JCE** | **保留**（`SecureRandom` 本来就是 OS 原生熵源） |
| **provider 抽象本身** | **保留**。收益虽只有 1.0x~1.3x，但它是 native / JCE 可切换的**前提**，且零行为变更 |
| **native 默认开** | **保留但要在文档里说清代价**：小 payload + 高并发场景 native 会输给 JCE（0.24x）。若业务是「大量小包高并发」，应当 `edap.crypto.native=false` |
| **原计划的 C-10（EVP 缓存版 JMH arm）** | **降级为「已用纯 C 探针回答」**。探针剥离 JNI/JMH 噪声，且能直接看到 `EVP_MAC_fetch` 的 dispatch 成本；探针留在 `edap-native/scripts/evp-vs-legacy-probe.c` 可随时复现 |

### 10.7 全 reactor 回归的失败归因（**实测：全部预存在**）

Crypto 线改完跑全 reactor（`mvn -o install -fae -DskipTests=false
-Dtest='!io.edap.protobuf.wire.**'`）有 5 个模块报红。**「红了」不等于「我改坏的」** ——
所以用 `git worktree add /tmp/edap-head-clean HEAD` 建一棵**干净 HEAD 树**，用**同一条命令**跑基线，
逐模块对比失败数与失败用例名：

```bash
git worktree add /tmp/edap-head-clean HEAD
cd /tmp/edap-head-clean && mvn -o -pl <模块> -am install -DskipTests=false
```

| 模块 | 工作区 | HEAD 干净树 | 归因 |
|---|---|---|---|
| `edap-log` | 3 failures / 394 tests | **3 failures / 391 tests**（同名：`TestLogArgsImpl.testFormat`、`TestLogArgsImpl.testThrew`、`TestTimeBasedRollingPolicy.testRollover`） | **预存在**。工作区多的 3 例正是新增的 `LoggerBootstrapIsolationTest`（全绿） |
| `edap-tx` | 56 失败 | **56 失败**（同名：`PropagationDecisionTest` / `SuspendResumeStackTest` / `CommitRollbackTest` / `StaleStateTest`） | **预存在**。且 `edap-tx` 的依赖树里**只有 `edap-tx-api`**，**完全不经过 `edap-common`/`edap-crypto`** ⇒ 结构上不可能被本次改动影响 |
| `edap-data-jdbc-dao` | 84 失败 | **84 失败**（8 failures + 49 errors，全是 `Connection.getAutoCommit()` 因 `con` 为 null） | **预存在**。测试要 `jdbc:h2:~/test` 的 H2 实例，属环境问题 |
| `edap-bean-convert` | 1 失败（`TestConvertorRegister.testGenerateError`） | — | **预存在**。验证方式不同：把 `CryptUtil.java` 临时还原成 `git show HEAD:` 的迁移前版本重装 `edap-common`，失败依旧；随后已恢复迁移后版本并重装 |
| `edap-mqtt-wire` | 1 失败（`StringPropertyTest.testWriteTo`） | 175 tests 全绿 | **flaky**（见下） |

`edap-mqtt-wire` 的 flaky 机制（**实测定位，非推测**）：

- 测试用 `randomStr(new Random().nextInt(Byte.MAX_VALUE))` 生成随机串，
  `randomStr` 每次迭代产出 2 个随机字节按 UTF-8 解码 ⇒ **可能得到空串**；
- `StringProperty.writeTo` 对空值**直接 return 不写任何字节**（`StringProperty.java:34-36`），
  而测试仍按 `2 + len` 字节构造 expected ⇒ `len == 0` 时必然 `assertArrayEquals` 失败；
- `nextInt(127) == 0` 的概率 = **1/127**。独立探针跑 **200000** 次，
  实测触发 **1583** 次 = **0.7915%**，与理论值 `1/127 = 0.787%` 吻合；
- 该路径上**没有任何 crypto 调用**（`MqttWriter.writeString` → `StringUtil`/`UnsafeUtil`）；
- 在**工作区**重复跑该用例 **12 次全过** ⇒ 不是系统性回归，是随机命中。

⇒ **结论：本次 Crypto 线的改动没有引入任何新的测试失败。** 全 reactor 的红灯全部有独立成因。

---

## 十一、风险与已知限制

| # | 风险 | 缓解 |
|---|---|---|
| R-01 | **共享 JCE 实例导致静默算错** | `InstancePool` 独占语义 + 回归测试 |
| R-02 | **`Class.forName` 用 initialize=false** | `NativeBridge` 只用 initialize=true；代码注释标注 |
| R-03 | **OpenSSL 4.0 删 legacy API** | **实测缓解**：① OpenSSL 4.1.0 的 `libcrypto.4.dylib` **仍导出** `SHA256_*` / `SHA1_*` / `MD5_*`（§6.6）⇒ 紧迫度下降；② **EVP 缓存版实测追不上 legacy**（§10.5，中小 payload 慢 2~5 倍）⇒ 即使 legacy 被删，迁移 EVP 也是「性能倒退」，届时应另找替代方案（如自带实现）而不是直接换 EVP |
| R-04 | **native 不可用时的静默降级** | 降级打**一次** WARN；`edap.crypto.strict=true` 可改成抛异常 |
| R-05 | **edap-crypto → edap-native → edap-common → edap-crypto 的 reactor 环** | edap-native 已解除对 edap-common 的依赖 |
| R-06 | **provider 全局单例被误当线程安全对象使用** | provider 线程安全（内部池），session 独占实例 —— 已在 javadoc 写明 |
| R-07 | **cipher 的 AEAD 认证失败被吞掉** | `AEADBadTagException` 原样透出；`doFinal` 用 try/finally 保证实例归还 |
| R-08 | **edap-crypto → edap-log-api ⇒ 跨模块类初始化环 ⇒ 全应用退化成 NopLogger** | edap-crypto **不依赖 edap-log-api**（§1.4）；告警走 `CryptoDiagnostics`（§4.2）；`CryptUtil` 有裸 JCE 兜底；两道回归测试守着（常量池扫描 + 独立 ClassLoader bootstrap） |
| R-09 | **edap-crypto 位于日志 bootstrap 路径 ⇒ crypto 层故障会波及日志系统** | `CryptUtil` 捕获 crypto 层的 `RuntimeException` 后退回裸 JCE，并把问题打到 stderr；结果逐字节相同 |

### 已知限制

- **v1 cipher 只走 JCE**（C-9 判定为**不做**）。原计划是「由实测决定」——
  现在实测结论已经落地：① AES-GCM 在 OpenSSL 3.x 上**只能**走 EVP provider dispatch；
  ② EVP 的固定开销实测约 **570 ns/次**（§10.5），比 legacy 高一个数量级；
  ③ 仓库当前零 Cipher 用量。⇒ 保持 JCE-only，不引入 EVP 复杂度。
  若将来真有 Cipher 热点需求，应先重新测一轮 native AES-GCM，**不要**照搬 digest 的结论。
- **v1 random 只走 JCE**。
- **v1 只支持一次性 `doFinal`**（AEAD 流式未实现）。
- **`edap-crypto` 不接管 JDK TLS 栈** ⇒ `edap-http-parent` 不依赖 `edap-crypto`。
- **native 产物当前不可移植**：已提交的 `.o` 链接到 Homebrew `openssl@3` 的**绝对路径**
  ⇒ 只有路径完全一致的机器能加载，其它机器静默降级到 JCE。详见 §6.5。
- **native arm 的基准结果只在「native 真的加载成功」的机器上才有**。读 JSON 前先确认
  native arm 有产出，否则不能拿 native vs jce 下结论。
- **native 在「小 payload + 高并发」下会输给 JCE**（实测 SHA-256@64B/16t 只有 **0.24x**，
  §10.3）。若业务形态是「大量小包 + 高并发」，应当设 `edap.crypto.native=false`。
- **§10.2~§10.4 的 JMH 批次（`20261104-214643` / `20261104-231103`）是在高 loadavg
  （19.77~77.99，核数的 2~8 倍）下跑的** ⇒ 绝对值不可外推，只看同批次内的相对关系；
  §10.5 的探针是另一次独立运行，两者的绝对值不可混用。
