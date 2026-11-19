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

**这是本模块踩过的最严重的坑**（C-8 迁移 `Md5Util` 时引入）。

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

**双保险**：`edap-common` 的 `Md5Util` 对 crypto 层的 `RuntimeException`
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
| `JceMacProvider` | `InstancePool<KeyedMac>`（**实例记住自己是哪把 key init 的**）+ `SecretKeySpec`；session 存 `key.clone()` |
| `JceCipherProvider` | `InstancePool<KeyedCipher>`（**实例记住自己是哪把 key**）+ `SecretKeySpec`；`newSession` 校验 key/iv 长度 |
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

### 5.2 ★★ MAC 的 key 不重复 init（Solaris 上的 ~130μs/次）

`JceMacProvider` 早期版本每次操作都 `new SecretKeySpec(key, algorithm)`。
在 Solaris 上 `HmacSHA256` 落在 `SunPKCS11-Solaris`（PKCS#11 provider，key 要导进 token），
**JDK 的 token 导入缓存按 key *对象身份* 走、跟字节无关** ⇒ 每次 `new` 就等于每次重新导一次 key
⇒ 一次 HMAC 的固定成本从 ~12μs 涨到 ~136μs。

**修法**：池里的实例记住「自己是哪把 key init 的」（`KeyedMac`）——
key 字节相同就**跳过 `init`**（`Mac.doFinal()` 之后实例已经回到「init 完、无待算数据」的状态）；
换 key 才 `init` 并记录新 key；`init` 失败则把实例标记成「无 key」后归还，保证下次重新 init。

**回归测试用「数 init 次数」而不是测时间**（Solaris 与开发机的时间差是 3000 倍，
时间断言在任一台机器上都不可靠）⇒ 计数器直接放在 provider 内部的 **init 调用现场**
（`JceMacProvider#initCount()` / `#keySpecCount()`），用例放在 `io.edap.crypto.mac` 包内
（`JceMacProviderKeyReuseTest`）。实测数据与完整归因见 §10.8。

> ⚠️ **计数点为什么在 provider 内部，而不是插一个 JCE 替身 provider**：
> 早先的写法是往 JCE provider 列表头部插一个自己的 provider，靠 JCE 选中它来数 `init`。
> **那个办法在 Solaris 上不成立** —— 实测 `Mac.getInstance("HmacSHA256")` /
> `Cipher.getInstance("AES/GCM/NoPadding")` **没选中替身**（计数恒为 0）。
> 根因：JDK 的 `GetInstance` 在 **SPI 构造抛异常时会静默回退到下一个 provider**
> （已用本地探针复现：`Mac.getInstance` 返回 `SunJCE` 而不是替身，且**不报错**）。
> ⇒ 计数点改到**自己代码里的调用现场**：平台无关，而且被守的正是那一行。

### 5.3 ★★ cipher 的 key 对象复用（只复用 key 对象，不复用 init）

`JceCipherProvider` 早期版本同样每次 `new SecretKeySpec(key, algorithm)`。

> ⚠️ **但实测推翻了「cipher 也走 PKCS#11」的假设**：Solaris 上
> `Cipher(AES/GCM/NoPadding)` 落在 **`OracleUcrypto`（纯用户态）**，**不是** `SunPKCS11-Solaris`
> ⇒ **cipher 侧没有「key 导进 token」这笔钱** ⇒ 本节修法的收益只有 **534 ns/次（4.9%）**，
> 而不是 Mac 侧的 12.9 倍。详见 §10.10。
> 修法本身**语义上仍然正确**（key 对象身份是 JDK 的通用契约，不依赖平台），
> 只是**别把它当成 Mac 那样的大幅提升**。

**cipher 与 `Mac` 的关键差别**：

| | `Mac.init(key)` | `Cipher.init(opmode, key, iv)` |
|---|---|---|
| 是否带 IV | 否 | **是** |
| 能否整体跳过 init | **能**（key 字节相同就跳过） | **不能** —— AEAD 每条消息必须换 nonce |

⇒ cipher 侧**只复用 `SecretKeySpec` 对象**，**不复用整个 `init`**。
实现上池里的实例记住自己是哪把 key（`KeyedCipher`），
key 字节相同 ⇒ 返回**同一个** `SecretKeySpec` 对象；key 变了 ⇒ 新建并替换。

**回归测试用「数造了几个 key 对象」** ⇒ `JceCipherProvider#keySpecCount()`
（计数器在 `KeyedCipher.keySpec()` 的**构造现场**：同一把 key 返回缓存的那个对象 ⇒ 计数不涨）
+ `io.edap.crypto.cipher.JceCipherProviderKeyReuseTest`（5 个用例）。
「造了几个对象」与「传给 init 的是不是同一个对象」是等价的一条信息 ——
而计数点就在被守的那一行，**不需要任何 JDK 内部机制** ⇒ 平台无关。

> ⚠️ 早先的写法是往 JCE provider 列表头部插一个替身 provider 来数「init 见过几个 key 对象」
> （按 **key 对象身份**用 `IdentityHashMap` 统计，因为 `SecretKeySpec.equals()` 比的是**字节**，
> 按字节比就分不出「同一个对象」与「字节相同但对象不同」）。
> **那个办法在 Solaris 上不成立**（JCE 没选中替身 ⇒ 计数恒为 0，JDK 静默回退），
> 已改成 provider 内部的调用现场计数。详见 §5.2 的说明。

### 5.4 ★★ GCM 的 nonce 复用：JDK 的保护是「按实例」的（**已实测**）

本机 JDK 17.0.20.1 上用探针实测 `SunJCE` 的 GCM 语义，三条事实：

1. **同一个 `Cipher` 实例**上用**同一个 IV** 做第二次**加密**
   ⇒ 抛 `InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption`；
2. 换**不同** IV 再 `init` ⇒ **正常**；
3. `doFinal()` 后**不** re-init 直接再 `doFinal()`
   ⇒ 抛 `IllegalStateException: Must use either different key or iv for GCM encryption`。
   （`Cipher` **没有** `reset()` 方法，`Mac` / `MessageDigest` 才有。）

**推论（当前实现的已知行为）**：本实现按线程池化 `Cipher` 实例 ⇒
**同一线程**上「同 provider + 同 key + 同 IV」连续加密两次 ⇒ **抛异常**；
**换线程** ⇒ 拿到另一个池化实例 ⇒ **不抛**。
⇒ **同一个调用在两个线程上行为不同（不确定性）**。

**为什么不在本层兜住**：
「禁止 nonce 复用」需要**跨线程的共享状态**（例如 provider 级的一个「本 key 上次用的 nonce」表），
而 §5.1 明确要求**每方法最多一把锁、按最小粒度隔离** ⇒ 为一条本就不该发生的调用
（复用 nonce 是 GCM 的硬性要求，属于**调用方的错**）引入共享状态不划算。

**⇒ 结论：调用方绝不能复用 nonce。** 调用方自己保证 nonce 唯一；
本层**原样透出** JDK 的 `InvalidAlgorithmParameterException`，不静默吞掉。

> **★ 已定案（2026-11-16，Solaris 实测后）**：**保持池化，不为确定性放弃它。**
> 代价对比：`Cipher.getInstance` 在 Solaris 上是 **32302 ns**（32μs）
> ⇒ 每次用全新实例 ⇒ 一次完整加密 ≈ **42.6μs**，比现在（**10.3μs**）**慢 4.1 倍**。
> ⇒ 那个不确定性**维持现状**，由**调用方保证 nonce 唯一**（GCM 的硬性要求）。
> 完整数据见 §10.10。

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
├── io_edap_jni_crypto_NativeDigest.c digestSha2560 / digestSha10 / digestMd50
├── io_edap_jni_crypto_NativeMac.c    hmacSha2560 / hmacSha10
├── io_edap_jni_crypto_NativeHmacSha256.c  （重构为复用 edap_jni_common.h）
└── io_edap_jni_crypto_NativeStream.c ★ 流式摘要/MAC（§10.11）
    └── update / finish / free 三个入口

edap-native/src/main/java/io/edap/jni/crypto/
├── NativeDigest.java     digestSha2560 / digestSha10 / digestMd50
├── NativeMac.java        hmacSha2560 / hmacSha10
└── NativeStream.java     ★ update / finish / free（流式，摘要与 MAC 共用一套上下文）
```

**★ 流式入口的会话形状**：`update* → finish`，**没有独立的 `init`** ——
「建上下文」并进了「追加数据」（`update` 的句柄为 0 时顺便建）。
理由：JNI 往返在 Solaris/SPARC 上是**微秒级**量级，分成 `init` + `update` 两个入口
⇒ 每次操作多一次 native 调用；并进一次后「喂数据 + 收尾」只要 **2 次**
（老的一次性 API 是 1 次，流式化只多 1 次）。
`finish` 在 native 侧**释放上下文**（句柄随即失效，不能再 `free`）⇒ 不依赖调用方 close，
不会漏。

**踩坑记录**：
- OpenSSL 3.x 里 SHA-1 的 ctx 类型叫 `SHA_CTX`（**没有** `SHA1_CTX`）；`SHA256_CTX` 存在。
- JNI 绑定方法**必须 public** —— `NativeBridge` 用 `MethodHandles.lookup()` 跨包访问，
  只能访问 public 成员。
- `Native.java` 的资源名 `"/edap-native-" + os + "_" + arch + ".o"` **前导斜杠不是 bug**
  （`Class.getResourceAsStream` 的绝对路径语义），已加注释防误改。
- ★ **改 JNI 签名 ⇒ 目标机上的 `.o` 必须重建**：符号名不变但**参数表变了**时，
  JVM 会拿新参数表去调旧 C 函数（**参数错位 ⇒ 崩**，而不是 `UnsatisfiedLinkError`）。
  ⇒ 每次改签名都要在目标机重建 `.o` 并跑一遍符号自检。

### 6.4 `build-native.sh`

```bash
./scripts/build-native.sh              # 输出到 target/native-build（不碰已提交产物）
./scripts/build-native.sh --commit      # 更新 src/main/resources/ 下的已提交产物
```

- 编译 `src/main/c/*.c` **全部**成一个共享库（`System.load` 只能加载一个文件）；
- 输出 `target/openssl_version.h`，版本取**编译时头文件**的版本（不是 PATH 上的 CLI ——
  本机 CLI 是 OpenSSL **4.1.0**，而头文件是 **3.6.4**，两者可能不是同一个安装）；
- 自检：`nm` 校验 **10 个** JNI 符号齐全（缺则中止）、`file`、`shasum -a 256`：
  `NativeHmacSha256_sign0` / `NativeDigest_digestSha2560` / `_digestSha10` / `_digestMd50` /
  `NativeMac_hmacSha2560` / `_hmacSha10` / `NativeStream_update` / `_finish` / `_free`。
  ⚠️ 符号**改名或改参数表**都不会让旧 `.o` 缺符号 ⇒ 自检通过**不代表** `.o` 是新的 ⇒
  改了签名必须在目标机重建（§6.3 踩坑记录）。

**默认路径不变**：`.o` 继续预编译提交到 `src/main/resources/`，当前有两份：

| 产物 | 平台 | 链接的 libcrypto | 可移植性 |
|---|---|---|---|
| `edap-native-macos_aarch64.o` | macOS / arm64 | `/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib`（**绝对路径**） | ❌ 只有路径完全一致才能加载（§6.5） |
| `edap-native-sunos_sparcv9.o` | Solaris / SPARC V9 | `libcrypto.so.3`（**soname，不带路径**） | ✅ 只要目标机有 `/lib/64/libcrypto.so.3` 就能加载 |

★ **SPARC 那份是 2026-11-19 09:08 在 build-01 上用 gcc 重建的**
（21,320 B，sha256 `3aa39a5eff648f3b57a5f8326a6cb2da674c9410da5bb401bbcc4cf6c44c5d09`，
含 §10.14.8 的三项优化）。
⇒ ★★ **这份入库前，SPARC 上全新 clone 的仓库是没有任何 native 产物的**
⇒ `Native.doLoad()` 静默失败 ⇒ **所有加密悄悄退回 JVM 内置实现**
（功能全对、性能悄悄变差 —— 正是 `Platform.java` 注释里点名的最难发现的那类问题；
build-01 之前能跑，只是因为它的工作区**编过**）。
⇒ 对照：`edap-nio-native` 模块本来就提交了 4 个平台的 `.o`
（`macos_aarch64` / `linux_aarch64` / `macos_x86_64` / `SunOS_sparcv9`）
⇒ **「仓库自带预编译产物」是本仓库的既有惯例，edap-native 此前是漏了。**

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

★ **而 SPARC 那份没有这个问题**：`strings` 看它的依赖是
`libc.so.1` / **`libcrypto.so.3`** / `libnsl.so.1` / `libpthread.so.1` / `libsocket.so.1`
—— **全是 soname，没有绝对路径** ⇒ 由动态链接器按 `DT_NEEDED` + `ld.so` 搜索路径解析
⇒ 只要目标 SPARC 上有 `/lib/64/libcrypto.so.3`（Solaris 11 自带）就能加载
⇒ **比 macOS 那份可移植得多**。

**要真正可移植，只有两条路**：

| 方案 | 说明 | 代价 |
|---|---|---|
| 每个平台各自预编译提交 `.o` | 与现状一致，按 os.arch 各提交一份 | 需要 CI/构建机覆盖 linux/macos/windows |
| 静态链接 libcrypto | `.o` 不再依赖外部 dylib | 体积变大；许可证（OpenSSL 3.x Apache-2.0）需核对 |

本轮**未做**这两件事 —— 当前定位是「开发机加速」，不是「生产环境必须 native」。
⇒ 但 **SPARC 那份已经入库**（2026-11-19），所以**目标平台现在不需要现场编**。
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
| C-8 | 迁移 `Md5Util` | 影响面最大，放最后 | ✅ |
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

**★ 裸 JCE 兜底**：`Md5Util` 位于**日志 bootstrap 路径**上
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
| `JvmVsJniPerfTest` + `JvmVsJniProbe` | `edap-crypto` | **JCE vs JNI 的对比用例（面向 Solaris 生产机）**：一个候选一个 JVM、阶段分解、报告落盘（§10.8） |
| `CryptoCostProbe` | `edap-crypto` | **固定成本归因**：把「一次操作」拆到 JDK 的具体 API 调用上（`Mac.init` / `getInstance` / `update` / `doFinal`），并打出 provider 归属 |
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
| D-5 | native 在小 payload + 高并发下可能**不如** JCE | JNI 调用 + `ByteAccumulator` 拷贝 + 临界区开销固定 | ✅ **已验证**（§10.3，Digest@64B/16t 只有 **0.24x**）<br>★ 2026-11-17 流式化后**拷贝那一项已消失**（§10.11）⇒ 依据只剩「JNI 调用 + 临界区」<br>★★ **流式化后 16t 复测：结论翻案**（§10.12）—— Digest@64B/16t **0.24x → 1.04x**，native 不再输 ⇒ **D-5 对当前实现不成立** |
| D-6 | 16 线程下 JCE 的 `ThreadLocal` 实例会成为竞争点 | JCE 实现内部有锁 | ❌ **已推翻**（§10.3）—— 竞争点在 **native 侧**，不在 JCE 的 `ThreadLocal`；JCE 侧 16t 相对 1t 只劣化 ~2.2x（接近线性），native 侧同条件劣化 **9.3x**<br>★ 流式化后 native 侧劣化降到 **3.56x**（§10.12）⇒ 老的竞争点是 **`ByteAccumulator` 的分配**，不是 JNI 调用本身 |

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
| **native 在大 payload 上是数量级优势（~9x）** | §10.3：SHA-256@64KB 最高 **9.38x**，HMAC-SHA256@64KB 最高 **9.61x**<br>★ 流式化后复测升到 **9.3x~11.1x**（§10.12）<br>★ SPARC 上是 **1.2x~1.6x**（§10.13）—— 基数不同（Solaris 的 JCE 侧本来就快） |
| ~~**native 在「小 payload + 高并发」下会输**~~ | ❌ **已作废**（流式化前 §10.3：SHA-256@64B/16t 只有 **0.24x**）<br>★ 流式化后复测 **1.04x**，native 不再输（§10.12）<br>★ **SPARC 上从未成立**：24 格全部 native 赢，最低 **1.22x**（§10.13） |
| **JCE 的 `ThreadLocal` 不是竞争点** | §10.3：JCE 侧 16t/1t ≈ 2.2x；native 侧同条件 ≈ 9.3x<br>★ 流式化后 native 侧降到 **3.56x**（§10.12）⇒ 老的竞争点是 `ByteAccumulator` 的分配 |
| **HMAC-SHA256 是 native 最划算的场景** | §10.3：64B 就已经 **2.69x**，JCE 的 HMAC 实现本身就慢<br>★ 流式化后仍是最划算的（64B/16t **2.02x**，§10.12） |
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

> ⚠️ **本节测的是「流式化之前」的 native 实现**（一次性 API + Java 侧 `ByteAccumulator` 累积）。
> 该实现已被 §10.11 的流式化整体替换 ⇒ **本节的两条结论已被 §10.12 的复测取代**
> （「小 payload + 高并发会输给 JCE」作废；「native 侧 16t 劣化 9.3x」降到 3.56x）。
> 本节保留是为了对照「改之前是什么样」。

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
| **native 摘要改流式 API**（`SHA256_Init/Update/Final` 句柄式会话、Java 侧零累积） | **不做**（2026-11-16 实测推翻）。流式能省掉的只是累加器那次拷贝（native `hash − setup` = 17027 ns/64KB），**OpenSSL 算 SHA-256 的 126295 ns/64KB 一点都省不掉** ⇒ 最好情况仍比 JCE/ucrypto（95100 ns/64KB）**慢 1.33 倍**（§10.8 结论 3）⇒ JNI ABI 变更 + Solaris 重建 `.o` 换不来反超 |
| **Solaris 上的 provider 选型** | **分项拆开选**：摘要走 JCE、MAC 走 native —— `edap.crypto.digest.provider=jce` + `edap.crypto.mac.provider=native`（**现有开关就够，不用改代码**）。依据：Solaris 上摘要 JCE 赢 0.64~0.82×（ucrypto 比 OpenSSL 快 1.34 倍）；MAC 则相反 —— 修掉 key 重复导入后 JCE 约 13μs、native 约 3μs（**待 build-01 复测确认**）。macOS 上反过来（native 两项全赢 1.19×~9.15×），故**选型必须分平台** |

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

### 10.8 ★ Solaris/SPARC 上的 JCE vs JNI（2026-11-16，build-01 实测）

**为什么单独一节**：§10.1~§10.7 的数据全是 **macOS/arm64 开发机** 上的，
而生产机是 **Solaris 11.4 / SPARC**。跨平台的绝对值不能混用（只能各机各表），
但**方法**（一个候选一个 JVM、阶段分解、CV）可以复用。

**环境**：

| 项 | 值 |
|---|---|
| 机器 | build-01，Solaris 11.4（kernel-zone），SPARC（`uname -m`=sun4v，JDK `os.arch`=sparcv9） |
| JDK | Oracle **17.0.19** |
| native | `edap-native-sunos_sparcv9.o`（目标机现场 `build-native.sh --commit` 构建） |
| 用例 | `io.edap.crypto.test.JvmVsJniPerfTest`（`@Tag("perf")`） |
| 报告 | `edap-crypto/target/crypto-perf/jvm-vs-jni-sunos-sparcv9-20261116-225731.json` |

> **⚠ 旧表（`…-210157.json`）已作废**：那轮的 JCE 侧预热不足（见下「口径缺陷」两行），
> JCE 的读数系统性偏高 18%~43%，连「sha256@8192 JNI 快 1.32x」这种**方向性相反**的结论都是
> 口径造成的假象。下面这张表是**口径修好后**（commit `655111c7`）重跑的结果。

**头条数字**（ns/op，跨 3 轮取中位数；JNI/JCE > 1 = JNI 更快）：

| alg | size | JCE ns/op | JNI ns/op | JNI/JCE | JCE MB/s | JNI MB/s |
|---|---|---|---|---|---|---|
| sha256 | 64 | 6563.8 | 10248.4 | 0.64x | 10.0 | 6.4 |
| sha256 | 1024 | 8807.3 | 12656.4 | 0.70x | 116.3 | 80.9 |
| sha256 | 8192 | 18236.6 | 22354.9 | 0.82x | 449.2 | 366.4 |
| sha256 | 65536 | 96985.4 | 143731.5 | 0.67x | 675.8 | 456.0 |
| hmacsha256 | 64 | 134352.4 | 3067.0 | **43.81x** | 0.5 | 21.4 |
| hmacsha256 | 1024 | 132562.8 | 4971.0 | **26.67x** | 7.7 | 206.0 |
| hmacsha256 | 8192 | 141693.2 | 14641.2 | 9.68x | 57.8 | 559.6 |
| hmacsha256 | 65536 | 220627.3 | 95882.5 | 2.30x | 297.1 | 683.7 |

**四条结论**：

1. **「JNI 快 43 倍」是固定成本差，不是「哈希快 43 倍」。**
   阶段分解显示：JCE 的 HMAC 把 ~130μs 固定成本付在 **setup 段**
   （与载荷大小无关 —— 4 个载荷的 `setup − session` 分别是
   **132128 / 129358 / 126769 / 125540 ns**，跨度只有 5%），而 native 同一段 ≈ 0。
   ⇒ 差的是「JCE 每次操作都要重新准备一次 HMAC 实例」，**不是** OpenSSL 的 HMAC 更快。
   **根因已定位并修复**（见下「★★ 根因与修法」）：`Mac.init` 每次都要把 key 导进 PKCS#11 token。
2. **纯吞吐上 JCE 并不慢**：SHA-256@64KB，JCE **675.8 MB/s** vs JNI **456.0 MB/s** ——
   JNI 在这一格输掉，**主因不是 OpenSSL 慢，而是 edap-crypto 的 native 路径多搬了一次载荷**：
   `ByteAccumulator` 从 256 字节起按 1.5 倍几何增长 ⇒ 追加 64KB 要扩容 13 次、
   把已攒下的数据反复搬一遍（总搬运量约 **3 倍**载荷）；
   而 JCE 的 `MessageDigest.update` 直接从调用方的数组取数据，**零拷贝**。
   ⇒ 已把首次分配改成「按需分配」、后续按 2 倍增长（搬运量 3 倍 → 1 倍）。
   **这处拷贝本身是语义要求**：JCE 是在 `update()` 那一刻取数据的，
   native 侧要等到 `digest()` 才有完整数据 ⇒ 不拷贝就会算到「被改写后的内容」。
3. **★ native 的摘要路径输在「算法本身」，不是「拷贝」** ——
   这一条把「要不要给 native 做流式 API」这个决策点的答案**直接否掉**：

   | 侧 | 测点 | ns/64KB | 折算 | 这一段到底在干什么 |
   |---|---|---|---|---|
   | JCE | `hash − setup` | **94018.8** | **697 MB/s** | `MessageDigest.update(64KB)` ⇒ **ucrypto 的纯压缩** |
   | native | `full − hash` | **126295.1** | 519 MB/s | `digest()`：JNI `GetPrimitiveArrayCritical` 取数组（**不拷贝**）+ OpenSSL `SHA256()` + `NewByteArray(32)` ⇒ **≈ OpenSSL 算 SHA-256 的纯时间** |

   ⇒ **OpenSSL 在 SPARC 上算 SHA-256 比 ucrypto 慢 1.34 倍**（126295 vs 94019 ns/64KB）。
   ⇒ ★★ **改成流式 API（`SHA256_Init/Update/Final`）也追不上**：
   流式能省掉的只是累加器那次拷贝（native `hash − setup` = 17026.9 ns），
   **算摘要的那 126295 ns 一点都省不掉** ⇒ native 摘要的最好情况 ≈ 126295 vs JCE ≈ 95100
   ⇒ **仍是 1.33 倍落后**。
   ⇒ ⇒ **Solaris 上摘要走 JCE（ucrypto），不要为了 native 去改 JNI ABI。**
   （这条结论**推翻**了早先「native 输的是拷贝 ⇒ 做流式 API 就能反超」的推演 ——
   那个推演错在把 `full − hash` 里的 OpenSSL 计算时间当成了拷贝时间。）
4. **JNI 的收益集中在 HMAC 与中大载荷**；SHA-256 全部四档 JNI 都输（0.64x~0.82x）——
   固定开销（JNI 调用 + OpenSSL 一次性 API 的准备）吃掉了全部收益。

**★ 阶段分解本身也修了两个口径缺陷**（不修的话，上面的差值全是噪声）：

| 缺陷 | 现象 | 修法 |
|---|---|---|
| **预热次数随载荷大小变化** | `warmup = max(iters/4, 1000)`，而 `iters = 4MB/size` ⇒ 64B 预热 16384 次、其余只有 1000 次。HotSpot 调用阈值是 Tier3=200 / **Tier4=5000** ⇒ 大载荷的「session」段（**根本不碰载荷**）只跑到 C1：读出来 64B 是 36.9ns、64KB 是 1447.2ns —— 差 40 倍，差的全在 JIT 状态 | 预热次数改成**固定 20000 次**（`--warmup`），与载荷大小、阶段无关 |
| **辅助阶段用更少的批次** | 辅助阶段 `--batches=2`、头条阶段 5 批 ⇒ 两个阶段的「批内 min」不在同一口径，`full − hash` 被系统性偏置（实测 JCE 的 hash 段一度读出**比 full 还慢** —— 荒谬，但完全由口径造成） | 所有阶段批次数一律相同（不再单独传 `--batches`） |

**★ 归因探针在 build-01 上的结果**（`CryptoCostProbe`，一个测点一个 JVM）：

| provider 归属 | 值 |
|---|---|
| `MessageDigest(SHA-256)` | **OracleUcrypto (UcryptoProvider)** —— 无 token，纯用户态 |
| `Mac(HmacSHA256)` | **SunPKCS11-Solaris (SunPKCS11)** —— PKCS#11 provider，key 要进 token |

| op | ns/op | 备注 |
|---|---|---|
| md.getInstance | 13376.5 | ★ 极贵，但被 `InstancePool` 躲开（不每次付） |
| md.update64 | 1284.5 | |
| md.digestEmpty | 5129.6 | 收尾固定成本（SPARC 上比 macOS 的 301ns 慢 17 倍） |
| md.digest64 | 6496.1 | = update64 + digestEmpty，自洽 |
| md.digest64k | 108633.6 | 纯吞吐 ≈ 642 MB/s |
| mac.getInstance | 4136.9 | |
| mac.keySpec | 307.0 | |
| **mac.init** | **10384.9** | ★ 同一个 key 对象 ⇒ 命中 JDK 的 token 导入缓存 |
| mac.reset | 38.9 | |
| mac.doFinal64 | 11796.5 | |
| mac.doFinal64k | 105762.8 | |
| native.digest64 | 10150.0 | |
| native.mac64 | 2908.2 | |

**探针读数与用例逐项对齐**（互相印证口径已修好）：
native 摘要 10150.0 vs 用例 JNI 10248.4；JCE 摘要 6576.4 vs 用例 6563.8；
native HMAC 2908.2 vs 用例 3067.0 —— 三项都在 1% 以内。

**★ 摘要侧已经算平**：JCE 没有 key 概念，`md.digest64`(6496) = `update64`(1284) + `digestEmpty`(5130)。
`MessageDigest.getInstance` 要 13.4μs，但 `InstancePool` 让它不每次付 —— **池化在 Solaris 上比在 macOS 上重要 26 倍**。
⇒ **Solaris 上 JCE 的 64B 摘要（6496ns）比 native（10143ns）还快**（OracleUcrypto 无 token 开销），
而 64KB 上 JCE 也更快（117736 vs 153550）—— **native 的摘要路径在 Solaris 上没有优势**。

**★★ HMAC 侧的账：根因已锁定在 `Mac.init` 里的「key 重新导入 token」**：
把 134352ns 拆开 —— `getInstance` 被池化躲开（0）、`keySpec` 307、
`update(64B)` ≈1284、`doFinal` ≈10512、`init` 探针实测 **10385** ⇒ 合计 **≈23.5μs**，
但用例测到 **134.4μs** ⇒ **~111μs 藏在 `Mac.init` 里面**，而探针量到的 init 只要 10.4μs。
**两者唯一的差别是传进去的 `SecretKeySpec` 对象**：
探针用**同一个静态对象**（`KEY_SPEC`），而 edap-crypto 每次操作都 `new SecretKeySpec(...)`
⇒ **SunPKCS11 每次 init 都要把 key 重新导进 token**（Solaris 的 PKCS#11 软 token 落盘
⇒ 一次 import 就是一次文件操作）。

**★ 交叉定位结果**（`CryptoCostProbe` 的 6 个新测点，build-01 实测，ns/op）：

| 测点 | Mac 实例 | key 对象 | ns/op | 读出来的含义 |
|---|---|---|---|---|
| `mac.init` | 池化复用 | **同一个** | **10384.9** | 同 key 对象 ⇒ JDK 缓存了 token 导入 |
| `mac.initFreshKey` | 池化复用 | **每次 new**（字节同） | **133667.5** | ★ **差 12.9 倍** ⇒ 缓存按**对象身份**走，跟字节无关 |
| `mac.initNewInstanceSameKey` | 全新 | 同一个 | 18351.1 | 全新实例也要导一次（但 key 对象命中 ⇒ 比 133667 便宜） |
| `mac.initNewInstanceFreshKey` | 全新 | 每次 new | 151212.3 | 两者都变 |
| `mac.realPathPooled` | 池化复用 | 每次 new | **136788.4** | ★ **≈ 用例里的 134352 ⇒ 模型闭合** |
| `mac.realPathCachedKeySpec` | 池化复用 | 同一个 | **16296.5** | ★ **复用 key 对象 ⇒ 快 8.4 倍** |
| `mac.realPathNewInstance` | 全新 | 每次 new | 145875.8 | 不池化的代价 |

（macOS 上的同一组读数：`initFreshKey` 79.7 / `realPathPooled` 1766.5 /
`realPathCachedKeySpec` 1717.6 / `realPathNewInstance` 3064.7 ns ——
**SunJCE 没有 key 导入成本**，所以这组数在 macOS 上分不出叉，只有 Solaris 上才有判别力。
⇒ **这条性能缺陷在开发机上完全看不出来**，必须靠「init 次数」这个结构性事实来守，见下。）

**★★ 根因与修法**：

| | 内容 |
|---|---|
| **根因** | `JceMacProvider` 每次操作都 `new SecretKeySpec(key, algorithm)` ⇒ **`Mac.init` 每次都把 key 重新导进 PKCS#11 token** ⇒ 一次 HMAC 的固定成本从 ~12μs 涨到 ~136μs（**比一次 OpenSSL HMAC 还贵 40 倍**） |
| **修法** | 池里的实例**记住自己是哪把 key init 的**（`KeyedMac`）：<br>① key 字节相同 ⇒ **跳过 `init`**（`Mac.doFinal()` 之后实例已经回到「init 完、无待算数据」的状态 —— 这正是 `MacSession#doFinal()` 契约里「key 保留」的含义）；<br>② key 不同 ⇒ `init(new SecretKeySpec(...))` 并记录新 key；<br>③ `init` 抛 `InvalidKeyException` ⇒ 把实例的 key 置 `null` 后归还池子（保证下次一定重新 init，不复用状态不可知的实例） |
| **预期收益** | Solaris 上 `mac.realPathPooled` 从 **136788 → ~1.2 万 ns**（≈8.4 倍）；用例里 hmacsha256 的 JCE 侧从 134352 → ~1.2~1.9 万 ns ⇒ **JNI/JCE 从 43.81x 降到 ~2~4x** |
| **影响面** | 三条热路径全部汇到 `JceMacProvider` 一个点 ⇒ 改一处三个路径一起修好：<br>① `edap-s3-core` 的 `SigV4Verifier`（**一次请求算 5 次 HMAC ⇒ ≈667μs/请求**）<br>② `edap-auth-jwt` 的 `HmacSha256`（**≈133μs/签一个 token**）<br>③ `edap-crypto` 自身的 `MacProvider` 调用方 |
| **同类问题（已单独处理）** | `JceCipherProvider` 每次也 `new SecretKeySpec` ⇒ 同一条机制。**已于 2026-11-16 一并修复**（见 §5.3）：cipher 侧**只复用 key 对象、不复用 init**（cipher 的 init 带 IV，AEAD 每条消息必须换 nonce ⇒ 躲不掉） |

**★ 为什么用「数 init 次数」守这条不变量，而不是测时间**：
Solaris 上一次 `Mac.init(new SecretKeySpec(...))` ≈ 130μs，开发机（SunJCE 软件路径）只要 ~40ns ——
**同一个缺陷在两台机器上的时间差是 3000 倍** ⇒ 时间断言要么在开发机上永远绿、要么在 Solaris 上永远 flaky。
能跨平台守住它的只有「`init` 被调了几次」这个**结构性事实**。
⇒ **计数器就放在 provider 内部的 `init` 调用现场**：`JceMacProvider#initCount()`（init 次数）
与 `#keySpecCount()`（造过几个 `SecretKeySpec`）。
⇒ 用例 `io.edap.crypto.mac.JceMacProviderKeyReuseTest`（5 个用例）：同一 key 连续 5 次 ⇒ 只 init 1 次；
换 key ⇒ 重新 init 且结果跟着变；A→B→A ⇒ 每次切换都 init；
同一 session 连续 doFinal ⇒ 第二个是空数据的 MAC；同一 key 两个 session 交替用 ⇒ 结果一致。
**反向验证**：把 `JceMacProvider` 换回修复前 ⇒ `testSameKeyDoesNotReInit` 如期失败（`expected: <1> but was: <5>`）。

> ★ **踩过的坑：别用「插一个 JCE 替身 provider」来数 init**。
> 那个办法在 macOS 上能跑通、在 **Solaris 上数到的永远是 0** ——
> 根因是 JDK 的 `GetInstance` 在 **SPI 构造抛异常时会静默回退到下一个 provider**
> （已用本地探针复现：替身的 SPI 构造里抛异常 ⇒ `Mac.getInstance("HmacSHA256")`
> 返回 `SunJCE` 而不是替身，**且不报错**）。
> ⇒ 计数点放在**自己代码的调用现场**：平台无关（Solaris / macOS 行为一致），
> 而且被守的正是「init 有没有被多调」这一行本身。
> （附带结论：JDK 17 的 `Provider.Service` 只认**无参构造**（`setConstructorParameter` 不存在），
> 这也是当时写替身 provider 时的第一个坑。）

### 10.9 ★ cipher 侧：复用 key 对象（GCM 的特殊之处）

**背景**：`JceCipherProvider` 与 §10.8 的 `JceMacProvider` 是**同一个缺陷** ——
每次操作都 `new SecretKeySpec(...)` ⇒ 每次 `Cipher.init` 都要把 key 导进 PKCS#11 token。
**但 cipher 侧修法不一样**：

| | `Mac` | `Cipher`（AEAD） |
|---|---|---|
| `init` 是否带 IV | 否 | **是** |
| 能否整体跳过 `init` | **能**（key 相同就跳过） | **不能** —— 每条消息必须换 nonce |
| 本次修法 | 复用 key 对象 **+ 跳过 init** | **只复用 key 对象** |

⇒ 池里的实例（`KeyedCipher`）记住「自己是哪把 key」；key 字节相同 ⇒ 返回**同一个**
`SecretKeySpec` 对象；key 变了 ⇒ 新建并替换。**`init` 照旧每次都调**。

**★ 回归测试用「数造了几个 key 对象」** ⇒ `JceCipherProvider#keySpecCount()`
（计数点在 `KeyedCipher.keySpec()` 的构造现场）+ `io.edap.crypto.cipher.JceCipherProviderKeyReuseTest`
（5 个用例）。「造了几个对象」与「传给 init 的是不是同一个对象」等价。

> ⚠️ 早先用「JCE 替身 provider + `IdentityHashMap` 按 key 对象身份统计」，
> 在 Solaris 上数到的永远是 0（JCE 静默回退到 `OracleUcrypto` / `SunPKCS11-Solaris`），
> 已改成 provider 内部的调用现场计数 —— 详见 §5.2 / §10.8 的说明。

**★★ 顺带实测到的三条 JDK GCM 语义**（本机 JDK 17.0.20.1 / `SunJCE`，探针实测）：

1. **同一个 `Cipher` 实例**上用**同一个 IV** 做第二次**加密**
   ⇒ `InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption`；
2. 换**不同** IV 再 `init` ⇒ **正常**；
3. `doFinal()` 后**不** re-init 直接再 `doFinal()`
   ⇒ `IllegalStateException: Must use either different key or iv for GCM encryption`
   （`Cipher` **没有** `reset()`，`Mac` / `MessageDigest` 才有）。

**⇒ 推论（当前实现的已知行为）**：本实现按线程池化 `Cipher` 实例 ⇒
**同线程**「同 provider + 同 key + 同 IV」连续加密两次 ⇒ **抛异常**；
**换线程** ⇒ 拿到另一个池化实例 ⇒ **不抛** ⇒ **同一调用在两个线程上行为不同**。

**为什么不在本层兜住**：「禁止 nonce 复用」需要**跨线程共享状态**，
与 §5.1「每方法最多一把锁、按最小粒度隔离」的取向冲突；
而且**复用 nonce 本来就是调用方的错**（GCM 的硬性要求）⇒ 本层**原样透出** JDK 的异常，不静默吞掉。

> **★ 已定案（Solaris 实测后，见 §10.10）**：**保持池化**。
> Solaris 上 `cipher.getInstance` = **32302 ns（32μs）** ⇒ 每次全新实例 ⇒ 一次完整加密
> ≈ **42.6μs**，比现在（10.3μs）**慢 4.1 倍** ⇒ **代价太大，不换**。
> ⇒ 那个不确定性**维持现状**，由**调用方保证 nonce 唯一**。

**★ macOS 上的读数**（`CryptoCostProbe`，一个测点一个 JVM，ns/op）：

| 测点 | ns/op | 读出来的含义 |
|---|---|---|
| `cipher.getInstance` | 3848.3 | `Cipher.getInstance` 本身就不便宜（被池化躲开） |
| `cipher.keySpec` | 45.1 | `new SecretKeySpec` 的构造成本 |
| `cipher.init` | **93.9** | 池化实例 + **同一个** key 对象 + 新 IV |
| `cipher.initFreshKey` | **95.4** | 池化实例 + **每次 new** key 对象（字节相同） |
| `cipher.realPathPooled` | 2443.3 | 修前真实路径：池化 + 每次 new key + init + doFinal |
| `cipher.realPathCachedKeySpec` | 2201.2 | 修后真实路径：池化 + 同一 key 对象 + init + doFinal |

⇒ **`init` 与 `initFreshKey` 只差 1.5 ns** —— **macOS 上这条缺陷完全看不出来**
（`SunJCE` 是软件路径，没有 token 导入）⇒ 与 §10.8 的 `mac` 侧完全一样，
**只能靠「key 对象身份」这个结构性事实来守**。

### 10.10 ★★ Solaris 上的 cipher 归因（2026-11-16，build-01 实测）—— **推翻了「机制同源」的假设**

**先看 provider 归属**（这一行决定了后面所有结论）：

| 原语 | provider | 有 token 导入吗 |
|---|---|---|
| `MessageDigest(SHA-256)` | **OracleUcrypto** | 否 |
| `Mac(HmacSHA256)` | **SunPKCS11-Solaris** | **是** ← §10.8 那笔 ~130μs 的来源 |
| `Cipher(AES/GCM/NoPadding)` | **OracleUcrypto** | **否** ← ★ 与 Mac **不同** |

⇒ ★★ **cipher 走的是 `OracleUcrypto`（纯用户态），不是 PKCS#11**
⇒ **cipher 侧根本没有「key 导进 token」这笔钱** ⇒
**§5.3 的修法在 Solaris 上没有 Mac 侧那种 12.9 倍的收益**。
**先测再改的规矩救了这个结论** —— 如果照着「机制同源」直接下结论，
就会把「修了一个不存在的性能缺陷」当成成果汇报。

**Solaris 上的 cipher 读数**（`CryptoCostProbe`，一个测点一个 JVM，ns/op）：

| 测点 | Solaris | macOS/arm64 | 读出来的含义 |
|---|---|---|---|
| `cipher.getInstance` | **32302.4** | 3848.3 | ★ **32μs** —— 是 init 的 **45 倍**！ |
| `cipher.keySpec` | 210.5 | 45.1 | `new SecretKeySpec` 的构造成本 |
| `cipher.init`（同一 key 对象） | **718.2** | 93.9 | |
| `cipher.initFreshKey`（每次 new） | **969.0** | 95.4 | ★ **只差 250.8 ns** |
| `cipher.realPathPooled`（修前） | **10796.8** | 2443.3 | |
| `cipher.realPathCachedKeySpec`（修后） | **10262.8** | 2201.2 | ★ 修后**快 534 ns（4.9%）** |

**归因**：
- `initFreshKey − init` = **250.8 ns** ≈ `cipher.keySpec` 的 **210.5 ns**
  ⇒ **这 250ns 就是 `new SecretKeySpec` 的构造成本**（外加一点 init 内部的重复准备）
  ⇒ **不是** token 导入（`OracleUcrypto` 没有 token）。
  ⇒ 对照 Mac 侧的 `initFreshKey − init` = **123258 ns**（130μs，token 导入）
  ⇒ **同一台机器上，Mac 差 250ns，cipher 也差 250ns，但 Mac 的绝对值是 cipher 的 500 倍。**
- ⇒ **修法的收益 = 534 ns/次（4.9%）** —— 真实但很小。
  修法**语义上仍然正确**（不依赖平台：key 对象身份是 JDK 的通用契约），
  但**别把它说成「性能大幅提升」**。

**★★ 但真正的 cipher 大头是 `cipher.getInstance` = 32.3μs**（是 init 的 45 倍、是完整
加密路径 10.3μs 的 **3 倍**）⇒ **池化才是 cipher 侧的性能命脉**，而池化恰恰就是
「同 IV 复用会抛」的那个设计（§5.4）。

**⇒ §5.4 的待决策就此定案：保持池化，不为「同 IV 必抛」的确定性放弃它。**
代价对比（Solaris 实测）：

| 方案 | 一次完整加密 | 相对现在 |
|---|---|---|
| **现在**：池化 + 复用 key 对象 | **10262.8 ns** | 1.00× |
| 每次全新 `Cipher` 实例（放弃池化） | ≈ 32302 + 10263 ≈ **42565 ns** | **≈ 4.1× 慢** |

⇒ **32μs 的代价太大** ⇒ 「同 IV 复用 ⇒ 同线程抛 / 换线程不抛」这个不确定性
**维持现状**，由**调用方保证 nonce 唯一**（GCM 的硬性要求，本就该由调用方负责）。

**★ 顺带印证了 mac 侧的修复（同一份探针输出）**：

| 测点 | Solaris | 读出来的含义 |
|---|---|---|
| `mac.init`（同一 key 对象） | **10325.9** | 命中 token 导入缓存 |
| `mac.initFreshKey`（每次 new） | **133643.5** | ★ **差 12.9 倍** |
| `mac.realPathPooled`（修前写法） | **145403.3** | |
| `mac.realPathCachedKeySpec`（修后写法） | **15120.9** | ★ **快 9.6 倍** |

⇒ **mac 侧修复在 Solaris 上确认有效**（145403 → 15121 ns）。
⚠️ `mac.realPathPooled` 两次运行读出 136788.4 / 145403.3（**差 6%**）
⇒ **探针读数跨运行有几个百分点的波动**（build-01 上有其它负载）⇒ 引用时带上这一点。

---

### 10.11 ★★ native 摘要/MAC 流式化（2026-11-17，build-01 实测）—— **选型结论翻转**

#### 10.11.1 改了什么

早先的 native 摘要只有**一次性**入口（`digestSha2560` 等），流式语义只能在 Java 侧用
`ByteAccumulator` 累积 —— 整份数据先拷一遍进 Java 堆，等 `digest()` 才交给 native。
实测（Solaris/SPARC）：64KB 载荷光这次累积就要 **~17.7µs**（一次 64KB 分配 +
一次 64KB arraycopy），**与一次 SHA-256 本体同量级**。

⇒ 改成 **native 侧持有摘要上下文**，数据喂一次算一次（`NativeStream`：
`update` / `finish` / `free` 三个入口，摘要与 MAC 共用一套上下文）。
`ByteAccumulator` 随之删除（无使用者）。

★ 上下文本身的固定成本又做了三项优化（小载荷分流 / 上下文按线程复用 /
`SHA256_CTX` 8 字节对齐）⇒ 见 **§10.14.7**。

#### 10.11.2 ★ 根因：Solaris 上 OpenSSL 的**一次性 API** 有 ~7μs 固定开销

纯 C 探针（`edap-native/scripts/oneshot-vs-stream-probe.c`，不经 JNI，四臂：
oneshot / legacy 流式 / 分块流式 / EVP），build-01 实测，ns/op（7 轮中位数）：

| payload | oneshot(`SHA256()`) | stream1(Init+Update+Final) | streamN | EVP | **stream1/oneshot** |
|---|---|---|---|---|---|
| 64 | 7463.1 | **411.4** | 414.7 | 7137.2 | **0.055** |
| 1024 | 8588.6 | **1472.7** | 1486.9 | 8222.0 | **0.171** |
| 8192 | 16878.5 | **9380.8** | 9433.7 | 16433.5 | **0.556** |
| 65536 | 80905.2 | **72579.1** | 73676.9 | 81497.1 | **0.897** |

（CV 0.3%~1.1%；四个 arm 的输出逐字节相同。链接的是 `/lib/64/libcrypto.so.3`。）

⇒ ★★ **一次性 `SHA256()` 与 EVP 几乎同价**（7137 vs 7463 ns）⇒ **一次性 API 也走
provider dispatch 那条路**；legacy 的 `Init/Update/Final` 绕开了它 ⇒ **64B 时便宜 18 倍**。
⇒ 这解释了 §10.8 里那个未解之谜：「native sha256@64B（10196 ns）反而比 native
hmacsha256@64B（3085 ns）慢 7.1μs」—— 那 7.1μs 就是一次性 API 的固定开销
（老 HMAC 路径不走一次性 SHA-256）。

> ⚠️ **2026-11-19 更正：本节的 ~7μs 结论只对 SHA-256 成立，对 MD5 不成立。**
> 另一个纯 C 探针（`edap-native/scripts/md5-vs-sha256-probe.c`，同机 build-01，
> 同一条 `/lib/64/libcrypto.so.3`）实测：
>
> | 64B | 一次性 | legacy 流式 | **流式/一次性** |
> |---|---|---|---|
> | SHA-256 | 7525.2 ns | 411.0 ns | **0.055（18.3x 差）** |
> | MD5 | 413.0 ns | 371.9 ns | **0.90（1.11x 差）** |
>
> ⇒ **MD5 的 `MD5()` 是直接调用，没有 provider dispatch 税**，只比流式贵 11%
> （多一次 provider fetch）⇒ ① 上面的「一次性 API 也走 provider dispatch」机制
> **只对 SHA-256 成立**；② **native 摘要走 legacy 流式对 SHA-256 是必须的**，
> **对 MD5 几乎无差**；③ **MD5 若改回一次性 API 省不到什么**。
> ⇒ ★ 这也把「老 native sha256@64B 反而比 native hmacsha256@64B 慢 7.1μs」彻底解释掉：
> **那 7.1μs 就是 SHA-256 一次性 API 的 provider dispatch 税**（HMAC 路径不走它）。
> ⇒ 完整推导见 **§10.15.7**。

#### 10.11.3 ★ 流式化后的 JCE vs JNI（两轮独立跑，数字几乎重合）

`JvmVsJniPerfTest`，build-01，JDK 17.0.19，Oracle，SPARC。
报告 `jvm-vs-jni-sunos-sparcv9-20261117-124221.json` / `…-125005.json`。

| alg | size | JCE ns/op | JNI ns/op | **JNI/JCE** | 流式化前 JNI/JCE | JNI 读数变化 |
|---|---|---|---|---|---|---|
| sha256 | 64 | 6680.4 | **2408.3** | **2.77x** | 0.65x | 10196 → 2408（**快 4.2x**） |
| sha256 | 1024 | 8747.6 | **4108.2** | **2.13x** | 0.72x | 12365 → 4108（快 3.0x） |
| sha256 | 8192 | 18420.2 | **12120.9** | **1.52x** | 0.83x | 22372 → 12121（快 1.8x） |
| sha256 | 65536 | 96682.7 | **75351.7** | **1.28x** | 0.67x | 145999 → 75352（快 1.9x） |
| hmacsha256 | 64 | 11497.6 | 3974.1 | 2.89x | 3.72x | 3085 → 3974（**慢 0.9μs**） |
| hmacsha256 | 1024 | 13460.8 | 5570.3 | 2.42x | 2.69x | 5010 → 5570（慢 0.6μs） |
| hmacsha256 | 8192 | 23660.1 | 13726.6 | 1.72x | 1.60x | 14909 → 13727（快 1.1μs） |
| hmacsha256 | 65536 | 100502.1 | **76592.8** | **1.31x** | 1.05x | 95566 → 76593（快 1.25x） |

⇒ ★★ **native 摘要全档反超 JCE**（0.65x~0.83x → 1.28x~2.77x）⇒
**§10.8 的「Solaris 摘要走 JCE」结论作废** ⇒ **摘要可以走 native 了**。

⇒ ★ **老 native 的 64KB 双峰消失**：sha256@64KB 的 CV 从 **15.2%** 降到 **0.7%**
（145999.8 → 75351.7）。⇒ 那个双峰是**老路径的产物**（一次性 API + Java 侧累加器
扩容），**不是噪声**。

⇒ ★ **Java 侧那次拷贝确实没了**：native 的 `hash − setup` @64KB = **74064 ns**，
而纯 C 的 OpenSSL 流式 = **72579 ns** ⇒ Java 侧开销 ≈ **0**（老路径这一段是 17027 ns）。

⇒ ⚠️ **HMAC 的小载荷回退了 ~0.6~0.9μs**：老路径 `hmacSha2560` 是**一次** JNI 调用，
新流式是**两次**（update + finish）⇒ 每次操作多付一次 JNI 往返
（**实测 ≈ 863 ns**）。大载荷省掉拷贝，净赚。**相对 JCE 仍然全档赢。**

#### 10.11.4 ★ 选型结论（Solaris）

| 项 | 旧结论（§10.8） | 新结论 |
|---|---|---|
| 摘要 | 走 JCE（native 全档输） | ★ **走 native**（1.28x~2.77x 全档赢） |
| MAC | 走 native（分档） | **走 native**（1.31x~2.89x 全档赢） |

⇒ 框架的**默认解析逻辑本来就是「native 可用就用 native」**（`CryptoRegistry.resolveDigest`）
⇒ **Solaris 部署侧那条 `edap.crypto.digest.provider=jce` 的压制可以撤掉**。

#### 10.11.5 遗留 / 待决

- **小载荷的 JNI 往返**：流式形态天生比一次性多一次 native 调用（≈863 ns）。
  若要再压，得加「单次喂入走 one-shot」快路径 —— 但 one-shot 要么多一次拷贝、
  要么推迟读取（**改变「update 立即消费数据」的语义**，与 JCE 不一致）⇒
  **本轮不做**，记在这里备查。
- ~~**macOS 的 16t 并发基准未复测**~~ ⇒ **已在 §10.12 复测**（结论：流式化后 D-5 翻案）。

---

### 10.12 ★★ native vs JCE 复测（流式化后，macOS）—— **D-5 翻案**

#### 10.12.1 为什么要复测

§10.3 的表测的是**流式化之前**的 native 实现（一次性 API + Java 侧 `ByteAccumulator` 累积）。
流式化把这个实现整个换掉了（§10.11），于是 §10.3 的两个结论被**作废**：

- 「**native 在小 payload + 高并发下会输给 JCE**」（SHA-256@64B/16t 只有 **0.24x**）
- 「native 侧 16t 相对 1t 劣化 **9.3x**」，而 JCE 侧只劣化 2.2x ⇒ 竞争点在 native 侧

这两条正是 D-5 / D-6 的依据 ⇒ 必须用新实现重测，否则文档里留着一个**已经不成立的告警**。

#### 10.12.2 数据

批次 `20261117-134252`，5 轮，`NativeVsJceBenchmark`（JMH 1.37），
与 §10.3 **同一个基准类、同一台机器、同一套口径**（一个 arm 一个 fork，5 轮取中位数 + CV）。
数据文件：`after-NativeVsJce-stream-20261117-134252-r{1..5}.json`。

| 项 | 值 |
|---|---|
| 机器 | macOS Darwin 27.2.0, arm64, **10 核**（`louis-mbp-232`，与 §10.3 同机） |
| JDK | OpenJDK **17.0.20.1**（arm64） |
| git HEAD | `6e2de5ba`（native 摘要/MAC 流式化） |
| native 产物 | `edap-native-macos_aarch64.o`（**含 `NativeStream` 三个新符号**） |
| loadavg | 跑批期间 **21.6 ~ 34.8**（核数的 2~3.5 倍；含基准自身） |

> ⚠️ 与 §10.3 同样的读数纪律：绝对值不可外推，只看同批次内的**相对关系**。

#### 10.12.3 结果（native 相对 jce，>1 = native 更快）

| 原语 | payload | 线程 | native 中位数 | jce 中位数 | **native 相对 jce** | native CV% | jce CV% | 流式化前相对 jce |
|---|---|---|---|---|---|---|---|---|
| SHA-256 | 64 | 1 | 394 | 613 | **1.56x** | 1.16 | 0.23 | 0.99x |
| SHA-256 | 64 | 4 | 524 | 708 | **1.35x** | 2.43 | 1.54 | 0.83x |
| SHA-256 | 64 | 16 | 1,400 | 1,459 | **1.04x** | 13.99 | 2.05 | **0.24x** |
| SHA-256 | 1024 | 1 | 791 | 4,946 | **6.25x** | 0.67 | 0.36 | 4.66x |
| SHA-256 | 1024 | 4 | 949 | 5,166 | **5.44x** | 1.31 | 0.43 | 3.88x |
| SHA-256 | 1024 | 16 | 2,080 | 10,580 | **5.09x** | 4.83 | 0.97 | 1.69x |
| SHA-256 | 8192 | 1 | 3,801 | 37,234 | **9.80x** | 0.30 | 0.19 | 8.25x |
| SHA-256 | 8192 | 4 | 4,007 | 37,862 | **9.45x** | 0.35 | 0.36 | 7.88x |
| SHA-256 | 8192 | 16 | 8,199 | 79,597 | **9.71x** | 0.55 | 3.56 | 7.90x |
| SHA-256 | 65536 | 1 | 27,796 | 295,817 | **10.64x** | 0.17 | 0.22 | 9.19x |
| SHA-256 | 65536 | 4 | 28,362 | 300,886 | **10.61x** | 0.08 | 0.09 | 9.11x |
| SHA-256 | 65536 | 16 | 57,443 | 635,262 | **11.06x** | 1.03 | 5.70 | 9.38x |
| HMAC-SHA256 | 64 | 1 | 611 | 1,540 | **2.52x** | 0.51 | 0.21 | 2.70x |
| HMAC-SHA256 | 64 | 4 | 706 | 1,634 | **2.31x** | 4.00 | 2.65 | 2.84x |
| HMAC-SHA256 | 64 | 16 | 1,742 | 3,525 | **2.02x** | 6.61 | 2.53 | 2.92x |
| HMAC-SHA256 | 1024 | 1 | 1,017 | 5,857 | **5.76x** | 0.53 | 0.14 | 5.71x |
| HMAC-SHA256 | 1024 | 4 | 1,125 | 6,037 | **5.37x** | 1.20 | 0.46 | 5.77x |
| HMAC-SHA256 | 1024 | 16 | 2,446 | 12,619 | **5.16x** | 4.34 | 1.28 | 5.84x |
| HMAC-SHA256 | 8192 | 1 | 4,021 | 38,157 | **9.49x** | 0.23 | 0.11 | 8.42x |
| HMAC-SHA256 | 8192 | 4 | 4,175 | 38,853 | **9.31x** | 0.42 | 0.03 | 8.43x |
| HMAC-SHA256 | 8192 | 16 | 8,645 | 81,237 | **9.40x** | 1.66 | 5.71 | 8.71x |
| HMAC-SHA256 | 65536 | 1 | 27,971 | 296,500 | **10.60x** | 0.22 | 0.06 | 9.21x |
| HMAC-SHA256 | 65536 | 4 | 28,599 | 301,696 | **10.55x** | 0.21 | 0.14 | 9.22x |
| HMAC-SHA256 | 65536 | 16 | 57,656 | 628,016 | **10.89x** | 0.72 | 1.13 | 9.61x |

#### 10.12.4 ★ 读法（三条）

**① 摘要在每一个格子都变快了**（native 中位数，流式化前 → 后）：

| payload | 1t | 4t | 16t |
|---|---|---|---|
| 64 | 612 → 394（**0.64x**） | 842 → 524（0.62x） | 5,674 → 1,400（**0.25x**） |
| 1024 | 1,044 → 791（0.76x） | 1,314 → 949（0.72x） | 5,761 → 2,080（0.36x） |
| 8192 | 4,439 → 3,801（0.86x） | 4,823 → 4,007（0.83x） | 9,981 → 8,199（0.82x） |
| 65536 | 31,667 → 27,796（0.88x） | 33,016 → 28,362（0.86x） | 63,399 → 57,443（0.91x） |

⇒ 收益随并发度放大：**16t/64B 快 4 倍**（5,674 → 1,400 ns）。

⇒ 老路径每次操作要**分配两个 Java 数组**（`new ByteAccumulator()` ⇒ `new byte[256]`，
首次 `append` 再 `new byte[64]`；数据用 `buffer()` 零拷贝交给 native），
新路径每次操作**只分配一个会话对象**（数据直接进 native 上下文，一个字节都不进 Java 堆）。
★ 「16 线程下的分配竞争」是**最合理的解释，但本轮没有直接证据**（没做分配剖析）
⇒ 按纪律当作**推断**记下，不当成已验证的根因。**已验证的只是「老路径在 16t 下劣化得多得多」。**

**② 并发劣化倍数塌了**（16t 相对 1t）：

| | 64 | 1024 | 8192 | 65536 |
|---|---|---|---|---|
| native（流式化前） | **9.27x** | 5.52x | 2.25x | 2.00x |
| native（流式化后） | **3.56x** | 2.63x | 2.16x | 2.07x |
| jce | 2.38x | 2.14x | 2.14x | 2.15x |

⇒ **D-5 翻案**：native 在「小 payload + 高并发」下**不再输给 JCE**
（SHA-256@64B/16t：0.24x → **1.04x**）。
⇒ **D-6 的「竞争点在 native 侧的 JNI 调用」也要改**：native 侧的并发劣化
原本比 JCE 差 4 倍，现在只差 1.5 倍，且**在最小载荷那一格最明显** ——
而新路径每次操作**仍是 2 次 JNI 调用**（§10.11.3 测到 ≈863 ns/次的往返）
⇒ 老的竞争点**不是 JNI 调用本身**，更可能是 Java 侧的分配（见 ①，标为推断）。

**③ MAC 在小载荷上退化了（几十 ns ~ 0.6μs），但相对 JCE 仍然全档赢**：

| payload | 1t | 4t | 16t |
|---|---|---|---|
| 64 | 579 → 611（1.06x） | 601 → 706（1.17x） | 1,148 → 1,742（**1.52x**） |
| 1024 | 1,017 → 1,017（1.00x） | 1,061 → 1,125（1.06x） | 2,030 → 2,446（1.20x） |
| 8192 | 4,451 → 4,021（0.90x） | 4,625 → 4,175（0.90x） | 8,836 → 8,645（0.98x） |
| 65536 | 31,444 → 27,971（0.89x） | 32,748 → 28,599（0.87x） | 62,339 → 57,656（0.92x） |

⇒ 与 §10.11.3 在 Solaris 上观察到的是**同一件事**：流式形态比一次性多一次 JNI 往返
（macOS 上便宜一些，Solaris 上 ≈863 ns）。
⇒ **大载荷净赚，小载荷净亏，但两种情况下相对 JCE 都仍然赢**（最差 2.02x）。

#### 10.12.5 ⚠️ 一个诚实的读数备注（**根因已定位，见 §10.14**）

`nativeDigest_16t@64B` 的 CV 高达 **13.99%**，逐轮读数是
**1,400 / 1,059 / 1,411 / 1,107 / 1,414** ⇒ **双峰**（~1060 与 ~1410 两档）。
同批次的 `jceDigest_16t@64B` 很稳（1,415~1,488，CV 2.05%）⇒ 抖动在 native 侧。

两个模式**都 ≥ JCE 的中位数** ⇒ **D-5 的结论不受影响**（要么打平，要么赢）。

> ★ **2026-11-17 更新：本节原来写的「没有定位（候选：每 fork 的 JIT 编译决策 / GC 布局差异）」
> 两个候选都已被实测排除；真因是「这台机器上的突发型外部 CPU 干扰」。**
> 完整定位过程、排除清单与干预实验见 **§10.14**。
> 一句话版：**这批数据是在 loadavg 21~37 的 10 核机上跑的**（脚本自己把 loadavg 记在日志里，
> 当时另有 Spotlight 索引 / IDEA 在抢 CPU）⇒ **每轮落在哪一档由那 2.5 秒窗口里有没有突发干扰决定**。
> 干预实验能按需复现慢档（加 6 个 CPU burner ⇒ native 臂 +29%、**JCE 臂 +16%**）
> ⇒ **不是 native 路径的代码缺陷，是这台开发机不能当基准机**。
> ⚠️ 别把 loadavg 当「这轮慢」的判据 —— 实测**不同向**（§10.14.3）。

#### 10.12.6 结论

| 项 | 旧结论（§10.3，流式化前） | 新结论（§10.12） |
|---|---|---|
| 摘要 | 小 payload + 高并发会输给 JCE（0.24x） | ★ **不再输**（64B/16t **1.04x**），全档 ≥1.04x |
| MAC | 小 payload 高并发仍赢（2.92x） | 仍赢，但优势收窄（64B/16t **2.02x**） |
| 大 payload | 8x~9.6x | **9.3x~11.1x**（略好） |

⇒ **文档里那条「大量小包高并发 ⇒ 设 `edap.crypto.native=false`」的告警作废**。

---

### 10.13 ★★ native vs JCE 复测（流式化后，Solaris/SPARC，build-01）—— **目标平台的并发数补齐**

#### 10.13.1 补的是什么

§10.12 是 **macOS** 的并发读数（D-5 翻案）；§10.11.3 是 **build-01 的单线程**读数。
「目标平台的并发数」一直是 §11 已知限制里明写的遗留项 ⇒ 本节把它补上。

#### 10.13.2 跑法与机器

| 项 | 值 |
|---|---|
| 机器 | build-01，Solaris 11.4（kernel-zone），**SPARC，4 核**（`nproc`=4） |
| JDK | 17.0.19（Oracle，SPARC） |
| git HEAD | `6e2de5ba`（native 摘要/MAC 流式化） |
| native 产物 | `edap-native-sunos_sparcv9.o`（**本轮在 build-01 上用 gcc 重建**，含 `NativeStream` 三个新符号） |
| 基准 | `NativeVsJceBenchmark`（JMH 1.37），5 轮，一 arm 一 fork，`@Threads(1/4/16)` |
| 前置校验 | `ENABLE_NATIVE=true / nativeDigest=true / nativeMac=true`（**必须**，见 §10.13.6） |
| loadavg | 跑批期间 **≈0.04**（机器空闲；比 macOS 那批的 21~35 干净得多） |

> ⚠️ **4 核 ⇒ 16t 是 4 倍超载** ⇒ 这一档的绝对值**不可与 macOS（10 核、16t 超载 1.6 倍）比**。
> **build-01 上最有意义的是 4t（正好 = 核数，不超载）**。

#### 10.13.3 数据（5 轮中位数；native/jce = jce 中位数 ÷ native 中位数，>1 = native 更快）

| 原语 | payload | 线程 | native (ns/op) | jce (ns/op) | **native/jce** | native CV% | jce CV% |
|---|---|---|---|---|---|---|---|
| digest | 64 | 1t | 2,497.8 | 7,023.1 | **2.81x** | 0.91 | 1.06 |
| digest | 64 | 4t | 4,549.4 | 25,311.7 | **5.56x** | 1.12 | 0.67 |
| digest | 64 | 16t | 21,636.5 | 113,895.8 | **5.26x** | ⚠️ 160.80 | 3.95 |
| digest | 1024 | 1t | 3,585.7 | 8,394.3 | **2.34x** | 1.23 | 0.71 |
| digest | 1024 | 4t | 4,433.6 | 27,078.1 | **6.11x** | 0.87 | 1.76 |
| digest | 1024 | 16t | 23,085.4 | 124,672.4 | **5.40x** | 14.33 | 2.08 |
| digest | 8192 | 1t | 11,918.2 | 18,484.0 | **1.55x** | 0.78 | 0.97 |
| digest | 8192 | 4t | 12,388.4 | 28,572.7 | **2.31x** | 0.97 | 1.33 |
| digest | 8192 | 16t | 54,674.7 | 135,633.0 | **2.48x** | 4.43 | 6.20 |
| digest | 65536 | 1t | 77,964.2 | 98,663.4 | **1.27x** | 0.26 | 0.34 |
| digest | 65536 | 4t | 78,246.9 | 103,437.4 | **1.32x** | 0.57 | 0.84 |
| digest | 65536 | 16t | 376,321.5 | 459,387.7 | **1.22x** | ⚠️ 177.11 | 9.40 |
| mac | 64 | 1t | 4,045.3 | 11,747.5 | **2.90x** | 1.16 | 1.27 |
| mac | 64 | 4t | 4,951.6 | 18,795.6 | **3.80x** | 1.40 | 2.47 |
| mac | 64 | 16t | 23,921.1 | 77,343.7 | **3.23x** | 8.32 | 2.47 |
| mac | 1024 | 1t | 5,122.4 | 13,227.1 | **2.58x** | 0.77 | 0.95 |
| mac | 1024 | 4t | 6,324.6 | 19,282.3 | **3.05x** | 1.09 | 1.83 |
| mac | 1024 | 16t | 30,976.5 | 84,955.6 | **2.74x** | 18.51 | 4.37 |
| mac | 8192 | 1t | 13,405.3 | 23,701.8 | **1.77x** | 0.44 | 0.91 |
| mac | 8192 | 4t | 13,921.0 | 31,317.7 | **2.25x** | 1.39 | 1.29 |
| mac | 8192 | 16t | 67,675.4 | 148,513.5 | **2.19x** | 5.30 | 5.64 |
| mac | 65536 | 1t | 78,756.3 | 103,410.6 | **1.31x** | 0.21 | 0.75 |
| mac | 65536 | 4t | 81,401.5 | 128,359.2 | **1.58x** | 0.13 | 6.61 |
| mac | 65536 | 16t | 360,594.4 | 493,102.1 | **1.37x** | 4.09 | 4.39 |

并发劣化（同 arm，16t 中位数 ÷ 1t 中位数）：

| 原语 | payload | native 劣化 | jce 劣化 |
|---|---|---|---|
| digest | 64 | 8.66x | 16.22x |
| digest | 1024 | 6.44x | 14.85x |
| digest | 8192 | 4.59x | 7.34x |
| digest | 65536 | 4.83x | 4.66x |
| mac | 64 | 5.91x | 6.58x |
| mac | 1024 | 6.05x | 6.42x |
| mac | 8192 | 5.05x | 6.27x |
| mac | 65536 | 4.58x | 4.77x |

#### 10.13.4 ★ 三条读法

1. **24 格全部 native 赢**（最低 **1.22x**，最高 **6.11x**）⇒
   目标平台上「native 可用就用 native」**没有反例**。
2. ★★ **与 §10.11.3 交叉验证**：本轮 **1t** 档的 8 个比值与那套**完全不同的测试台**
   （`JvmVsJniPerfTest` 探针，一个测点一个 JVM）**逐格吻合**：

   | 原语 | §10.11.3（JNI/JCE） | 本轮 1t | |
   |---|---|---|---|
   | sha256@64 | 2.77x | **2.81x** | ✓ |
   | sha256@1024 | 2.13x | **2.34x** | ✓ |
   | sha256@8192 | 1.52x | **1.55x** | ✓ |
   | sha256@64KB | 1.28x | **1.27x** | ✓ |
   | hmac@64 | 2.89x | **2.90x** | ✓ |
   | hmac@1024 | 2.42x | **2.58x** | ✓ |
   | hmac@8192 | 1.72x | **1.77x** | ✓ |
   | hmac@64KB | 1.31x | **1.31x** | ✓ |

   ⇒ 两个独立测试台在同一台机器上给出同一组比值 ⇒ **读数可信**（这是本节最有价值的一条）。
3. **「小载荷 + 高并发在 SPARC 上会翻盘」这个担心没有发生**：
   mac@64B/4t **3.80x**、digest@64B/4t **5.56x** ⇒
   之前担心的「Solaris 上 JNI 往返 ≈863 ns ⇒ MAC 小载荷并发可能输给 JCE」**不成立**。
   （根因见 §10.11.3：Solaris 上 JCE 侧的固定成本本来就高 —— 64B 摘要 JCE 要 7.0μs，
   而 native 只要 2.5μs ⇒ 基数差得远，多一次 JNI 往返翻不动。）

#### 10.13.5 ★ 16t 档的高 CV：是「离群轮次」，不是双峰（逐轮读数定性）

§10.13.3 里 `digest@64B/16t` 与 `digest@64KB/16t` 的 native CV 高达 **160.80% / 177.11%**
（同批 jce 侧只有 3.95% / 9.40%）。逐轮读数（`/tmp/rounds.py` 的输出）说明原因：

| 格子 | 逐轮读数 (ns/op) | 判定 |
|---|---|---|
| native `digest@64B/16t` | 21,637 / **291,432** / 22,519 / 20,801 / 19,521 | 第 2 轮 = 中位数的 **13.5 倍**；其余 4 轮很紧（19,521~22,519） |
| native `digest@64KB/16t` | 333,500 / 418,105 / 376,321 / 351,369 / **7,411,782** | 第 5 轮 = 中位数的 **19.7 倍**；其余 4 轮 333k~418k |
| jce `digest@64B/16t` | 108,560 / 117,683 / 110,220 / 113,896 / 118,855 | 无离群（CV 3.95%） |
| jce `digest@64KB/16t` | 459,388 / 470,880 / 377,955 / 442,838 / 486,710 | 无离群（CV 9.40%） |

⇒ ★ **不是双峰分布，是各有一个孤立的离群轮次**（且分属**不同轮次、不同 payload**）。
剔除那两个离群轮后，native 的 CV 变成 **6.0% / 9.5%** ⇒ **与 jce 侧同格持平**
⇒ **native arm 本身并不抖**。

⇒ ★ **中位数不受影响**（离群值都落在中位数之上，且只占 1/5 样本）⇒ §10.13.3 的结论成立。

⇒ ⚠️ **成因**：**2026-11-17 已在 macOS 上定位到机制类（见 §10.14）—— CPU 超订 + 外部负载争用。**
  候选清单更新如下：
- ✅ **外部负载抢占 CPU / 超订调度**（**机制已确认**）：macOS 上做了**干预实验**
  （加 6 个 CPU burner ⇒ native 臂 +29%、JCE 臂 +16%，可按需复现慢档），
  且原始批次的日志里 loadavg 是 **21~37**（10 核机）⇒ **这就是根因类**。
  build-01 是 **4 核跑 16 线程 = 4 倍超订**，比 macOS 的 1.6 倍更极端 ⇒
  形态从「双峰」变成「孤立离群轮」是合理的（超订越深，塌陷越稀疏）。
- ❌ **JVM 停顿（STW GC）**：macOS 上用 `-XX:+UseEpsilonGC -Xms32g -Xmx32g`（**零 GC**）
  实测，双峰**依旧** ⇒ GC 不是成因（SPARC 侧未单独测，但机制上同源，且 macOS 已排除）。
- ❌ **每 fork 的 JIT 编译决策 / MethodHandle 派发**：macOS 上另建
  `NativeBindingVsDirectBenchmark`（`direct_*` 臂**直调** `NativeStream.update/finish`，
  绕开 `NativeStreamBinding` 的 `MethodHandle.invokeExact`）⇒ **两个臂双峰幅度相同**
  ⇒ MethodHandle 不是成因。

⇒ ★ **跨平台的同型现象**：macOS 那边 `nativeDigest_16t@64B` 也是 **native 侧双峰、jce 侧稳**
（1400/1059/1411/1107/1414，§10.12.5）⇒ 「**native arm 在高并发下偶发慢轮**」是
**跨平台现象，不是 Solaris 特有**。形态不同（macOS 持续双峰、Solaris 孤立离群轮），
但**机制类相同**（超订 CPU 上的外部争用）⇒ **合并成一条结论**。

⇒ ★★ **为什么「jce 侧同轮没抖」不能当成「native 路径有问题」的证据**：
JMH 每个 benchmark 方法各起一个 fork，native 与 jce arm 是**先后跑的**，
而外部负载是**突发型**的 ⇒ 慢档落在哪个臂上取决于那段时间外部负载在不在。
macOS 上把两臂放在同一批次复测（6 轮交错）：**JCE 臂同样出现 1445→2508 的 74% 摆动**
⇒ **两臂都会抖，谁碰上负载谁抖**。

⇒ 注：16t 在 4 核机上是 4 倍超载，这一档本就由 OS 调度主导；与 macOS 的 16t
—— 10 核、1.6 倍超载——**不可直接对比**。

#### 10.13.6 纪律：跑之前必须确认 native arm 真参与了

`NativeDigestProvider.tryCreate` 把 `Throwable` 吞掉返回 null ⇒ `.o` 过期/没重建时
**静默退回 JCE**，基准就变成「JCE vs JCE」而不报错。
⇒ 每次跑之前先跑 `CheckNative`（打印 `ENABLE_NATIVE` / `nativeDigest` / `nativeMac`），
三个都 true 才继续。
⇒ 另：**改 JNI 签名 ⇒ 目标机 `.o` 必须重建**（参数表变 ⇒ JVM 拿新参数表调旧 C 函数 ⇒ 崩，
不是 `UnsatisfiedLinkError`）⇒ build-01 的 `edap-native-sunos_sparcv9.o` 是**本轮重建**的。

#### 10.13.7 结论

| 项 | 结论 |
|---|---|
| **摘要** | 目标平台 **native 全档赢**（1.22x~6.11x）⇒ §10.11.4 的选型成立 |
| **MAC** | 目标平台 **native 全档赢**（1.31x~3.80x）⇒ 同上 |
| **并发** | **不再有「目标平台的并发数待补」** ⇒ §11 那条遗留项可以划掉 |
| **D-5** | macOS 上翻案、**SPARC 上从未成立**（这里 native 全赢）⇒ 文档里那条「大量小包高并发 ⇒ 设 `edap.crypto.native=false`」的告警**两台机器都作废** |

---

### 10.14 ★★★ 「native arm 高并发偶发慢轮」的根因定位（2026-11-17，macOS）

**一句话结论：不是 native 路径的代码缺陷，是这台开发机上的「突发型外部 CPU 干扰」
（Spotlight 索引 / IntelliJ / WindowServer）在 16 线程超订（10 核机）下把 per-op 延迟
整体平移了一档。两个臂都会抖，native 臂只是更容易被看见。**

**证据链（每条都有实测）**：
1. **干预实验可按需复现慢档**：加 6 个 CPU burner ⇒ native 臂 **+29%**、JCE 臂 **+16%**（§10.14.4）；
2. **恒定负载反而更稳**：8 burner（超订更狠）下两臂 CV 只有 **2%**（§10.14.4）
   ⇒ **「超订」不是成因，「变动的外部干扰」才是**；
3. **五个假说逐一排除**：MethodHandle / GC-GCLocker / critical-section 拷贝 /
   机器等比变慢 / 测量期 JIT 编译（§10.14.2）；
4. **方差几乎全在 fork 之间**：fork 内 5 次迭代 CV 恒为 4.6~5.0%（三臂一致），
   fork 间分数摆动 34%（§10.14.2）；
5. **延迟而非吞吐**：慢轮 user CPU 与快轮几乎相同（37.6~39.7s）⇒ **多等不多算**。

⚠️ **一条必须说清的边界**：**loadavg 只能说明「机器整体在争用」，预测不了「某个 fork 落在哪一档」**
（实测慢轮出现在 loadavg 12.4/14.2/59.5，快轮出现在 49.5/66.6/77.0 ⇒ 不同向；§10.14.3）
⇒ **别把「loadavg 高」当成「这轮一定慢」的判据**。

#### 10.14.1 现象的精确刻画

原始批次 `after-NativeVsJce-stream-20261117-134252-r{1..5}.json`（**36 个格 × 5 轮**）：

| 格子 | r1 | r2 | r3 | r4 | r5 | CV |
|---|---|---|---|---|---|---|
| `nativeDigest_16t@64` | 1400 | **1059** | 1411 | **1107** | 1414 | **13.99%** |
| `nativeDigest_16t@1024` | 1978 | 2081 | 2080 | 2231 | 1996 | 5.0% |
| `nativeDigest_16t@8192` | 8182 | 8273 | 8224 | 8153 | 8199 | 0.6% |
| `nativeDigest_16t@65536` | 57943 | 57368 | 58716 | 57443 | 57285 | 1.0% |
| `nativeDigest_1t@64` | 388 | 389 | 394 | 399 | 395 | 1.1% |
| `nativeDigest_4t@64` | 525 | 496 | 524 | 510 | 524 | 2.4% |
| `jceDigest_16t@64` | 1475 | 1459 | 1434 | 1488 | 1415 | 1.9% |

⇒ **只有「16 线程 + 64B」这一格双峰**；1t / 4t 全稳，大载荷全稳。
⇒ 同一 fork 的 5 次迭代紧密聚在一档（换 fork 才换档）⇒ **per-fork 的二元状态**。

#### 10.14.2 ★ 五个候选假说与实测排除

| # | 假说 | 怎么测的 | 结果 |
|---|---|---|---|
| 1 | **MethodHandle 派发** | 新建 `NativeBindingVsDirectBenchmark`，加 `direct_16t` 臂**直调** `NativeStream.update/finish`（绕开 `NativeStreamBinding` 的 `MethodHandle.invokeExact`） | ❌ 排除：`direct` 臂 12 轮摆动 **34%**、`binding` 臂 **33%**，**幅度相同** |
| 2 | **GC / GCLocker** | `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms32g -Xmx32g`（**零 GC**） | ❌ 排除：双峰依旧（1430~1071 / 1426~1104）；且 `-Xlog:gc` 显示每轮固定 3 次 `GCLocker Initiated GC`，**GC 次数与快慢不相关** |
| 3 | **`GetPrimitiveArrayCritical` 的拷贝路径** | 自写 JNI 探针（`/tmp/jniprobe/probe.c`）打印 `isCopy` | ❌ 排除：**JDK 17 上 `isCopy` 恒为 false**（年轻代/老年代都一样，永远直指针） |
| 4 | **机器整体等比变慢** | 并发跑固定工作量标定器（`/tmp/calib.py`），比对 calib ns/iter 与分数 | ❌ 排除：calib（113~121 ns/iter）与分数（1101~1300）**不相关** |
| 5 | **测量期内的 JIT 编译**（per-fork 的编译决策落在测量窗口里） | A/B 交错 8 轮，A=默认（有后台编译线程）、B=`-Xbatch`（无后台编译线程 ⇒ 编译只在触发线程同步做） | ❌ 排除：**B 臂双峰依旧**（1059→1275，摆幅 20%，比 A 臂 14% 还大）⇒ 编译活动不是成因 |

补充证据（**指向「延迟等待」而非「多干活」**）：
- 慢轮与快轮的 **user CPU 时间几乎相同**（37.6~39.7s，跨度 5.5%），但墙钟更长
  ⇒ **慢轮不是多算，是多等**。（JMH 跑固定时长 ⇒ 快的 fork 做更多 op ⇒ 反而更耗 CPU。）
- `sample` 剖面（macOS，8 轮）：线程绝大部分时间阻塞在 `__psynch_cvwait` 8722 /
  `semaphore_wait_trap` 6472 / `__psynch_mutexwait` 2822（**等 CPU**），
  真正干活的 `sha256_block_armv8` 只有 364 个样本。
- 单 fork 内的 per-op 直方图（`/tmp/probe/OpDist.java`）是**多峰**的（native 峰在
  500/750/1000/1250ns；时钟分辨率实测 41.67ns ⇒ 250ns 峰间距是真实的）。
  ⇒ **同一个 fork 内延迟分布的形状就在变**，不是「每 fork 固定偏移」。
- ★ **fork 内 vs fork 间的方差分配**（3 臂 × 12 轮，每轮记 5 次迭代值）：
  **fork 内 5 次迭代的 CV 恒为 4.6~5.0%（三个臂都一样）**，
  而 **fork 间分数摆动 34%** ⇒ **方差几乎全在「fork 之间」**，
  ⇒ 存在一个「fork 级别」的二元/准二元状态（与 §10.12.5 观察到的「同 fork 聚簇」一致）。

#### 10.14.3 ★ 原始批次是在重度争用的机器上跑的

`run-bench.sh`（及其变体）每轮都会把 loadavg 写进日志。原始批次：

```
=========== round 1/5 (loadavg=21.65) ===========
=========== round 2/5 (loadavg=29.94) ===========
=========== round 3/5 (loadavg=28.08) ===========
=========== round 4/5 (loadavg=24.42) ===========
=========== round 5/5 (loadavg=36.71) ===========
```

**10 核机上 loadavg 21~37** ⇒ 基准自己的 16 线程之外，**还有 5~21 个外部 runnable 任务**。
而这台机器**常态如此**（当时 `ps` 实测）：

```
53.3%  mds_stores      ← Spotlight 索引（突发型！）
50.0%  idea            ← IntelliJ
26.9%  WindowServer
 9.6%  bun / 7.2% bun
```

⇒ ★★ **这批数据的绝对值不可外推**（§10.12 的所有比值只能当同批次内的相对关系看）。

⚠️ ★ **但 loadavg 预测不了「某个 fork 落在哪一档」** —— 这点必须说清楚，否则会读成
「loadavg 高 = 慢轮」。3 臂 × 12 轮实测（loadavg 为该轮实测值）：

| loadavg | 12.4 | 14.2 | 32.2 | 49.5 | 59.5 | 66.6 | 72.0 | 69.8 | 61.0 | 63.8 | 64.7 | 76.9 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| native 分数 | **1346** | **1321** | 1171 | 1070 | **1331** | 1002 | 1050 | **1302** | **1314** | **1274** | 1023 | 1014 |

⇒ **慢轮出现在 loadavg 12.4/14.2/59.5，快轮出现在 49.5/66.6/77.0** ⇒ **不同向**。
⇒ 原因很直白：**loadavg 是 1 分钟均值，而一个 fork 只有 2.5 秒** ⇒ 颗粒度差两个数量级，
它只能说明「这台机器整体在争用」，**不能当某一档的判据**。
⇒ ★ **要判某一 fork 落在哪一档，只能靠 fork 内自己的读数（迭代间的聚簇形态）**。

#### 10.14.4 ★★ 干预实验（可按需复现慢档）

A/B 交错跑 `nativeDigest_16t@64`（6 轮 × 2 条件），唯一变量是**人为加 6 个 CPU burner**：

| 条件 | 逐轮读数 (ns/op) | 均值 | CV |
|---|---|---|---|
| **0 burner** | 1255 / 1345 / 1241 / 1285 / 1330 / 1332 | **1298** | 3.4% |
| **6 burner** | 1753 / 1663 / 1667 / 1637 / 1677 / 1656 | **1676** | 2.5% |

⇒ **+29%**，**可复现**。慢档（~1400）正好落在「无外部负载」(1298) 与「有外部负载」(1676) 之间。

**JCE 臂同样的 A/B**（证明不是 native 特有）：

| 条件 | 均值 | CV |
|---|---|---|
| 0 burner | **1732** | 4.1% |
| 6 burner | **2007** | 2.7% |

⇒ JCE 臂也位移 **+16%** ⇒ **两臂都受外部负载影响**。
⇒ 相对灵敏度 native(+29%) > jce(+16%)：因为 64B 下 **SHA-256 本体只有 70ns/op**
（自写 C 基准 `/tmp/sslprobe/osslprobe.c`，OpenSSL 3.4：`SHA256_Init+Update(64B)+Final`
= 70.4ns/op，其中 `SHA256_Init` 仅 ~10ns）⇒ **per-op 里 ~95% 是等待/开销** ⇒
**对调度延迟最敏感**。

★★ **最有说服力的一条：恒定负载 ⇒ 读数稳定**（8 burner，5 轮，两臂交替）：

| 臂 | 逐轮读数 (ns/op) | 均值 | CV |
|---|---|---|---|
| native | 1555 / 1571 / 1503 / 1516 / 1495 | 1528 | **2.0%** |
| jce | 1992 / 1930 / 1957 / 2024 / 1929 | 1966 | **2.1%** |

⇒ **超订更严重了（8 burner + 16 线程 = 24 个 runnable 抢 10 核，比裸跑超订 1.6 倍更狠），
但读数反而比无干预时更稳** ⇒ ★★ **「超订」本身不是成因；「变动的外部干扰」才是**：
- **恒定**的外部负载 ⇒ 每秒的推进速率恒定 ⇒ 读数稳；
- **突发型**的外部负载（本机的 Spotlight 索引 / IDEA）⇒ 推进速率忽快忽慢 ⇒ **双峰**。

⇒ ★ **这解释了为什么「per-fork 二元状态」成立**：突发干扰落在测量窗口里就整档偏慢，
不落就整档偏快；而一个 fork 的测量窗口只有 2.5 秒 ⇒ 窗口内基本恒定 ⇒ **同 fork 聚簇、换 fork 换档**。

⚠️ **注意：百分比不是剂量-响应曲线**（6 burner +29%、8 burner +18%）。
因为**机器自身的背景负载在两次实验之间也在漂**（Spotlight 时有时无）
⇒ 只能读作「**加负载 ⇒ 整体变慢**」这个方向性结论，**不能拿百分比当斜率用**。

#### 10.14.5 ★ 线程数扫描：没有「干净的阈值」，两臂都抖

`direct_8t / direct_12t / direct_16t / jce_8t / jce_16t` 各 6 轮（同一台机器、同样负载）：

| 臂 | 逐轮读数 | CV |
|---|---|---|
| `direct_8t` | 598 / 600 / **1058** / 673 / 565 / 637 | 22% |
| `direct_12t` | 844 / 1100 / 795 / 835 / 780 / 1116 | 15% |
| `direct_16t` | 1114 / 1153 / 1046 / 1348 / 1171 / 1469 | 12% |
| `jce_8t` | 982 / 1036 / 948 / 984 / 831 / 831 | 8.6% |
| `jce_16t` | 1445 / **2508** / 1616 / 1508 / 1503 / 1510 | 21% |

⇒ **8 线程（= P 核数）也抖**（`direct_8t` 有一轮 1058）；**`jce_16t` 的摆动（74%）比 native 还大**
⇒ **不存在「越过某个线程数才出问题」的阈值**，也**不存在「只有 native 抖」**。

#### 10.14.6 ★★ 由此得到的纪律

1. **这台 Mac 不能当基准机**：loadavg 常驻 16~37（10 核），且外部负载是**突发型**
   （Spotlight `mds_stores` 会突然占满一个核）。⇒ **任何 CV 高的读数先查 loadavg，
   再看是不是「同 fork 聚簇、换 fork 换档」**。
   ⚠️ **但反过来不成立**：loadavg 高**不等于**这轮一定慢（§10.14.3）⇒
   **loadavg 只用来判断「这台机器的数据能不能外推」，不用来判断「哪一轮慢」**。
2. **要判 native vs JCE，取两臂的相对比值（jce ÷ native），不要看单臂的绝对值**：
   实测「无外部负载」native/jce = 1.33×，「有外部负载」= 1.20× ⇒ **比值也只在 ±10% 内可信**。
3. **要判「是不是机器造成的」，用干预实验，不用相关性**：
   人为加 N 个 CPU burner，看读数能否**按需**平移（+6 burner ⇒ +29%/+16%）。
   ★ **反过来更有力**：**恒定** burner（8 个）下两臂 CV 只有 2% ⇒
   **「恒定干扰 ⇒ 稳、突发干扰 ⇒ 双峰」** ⇒ 只要能让干扰恒定，双峰就消失。
4. **真正的基准机**应该是：关掉 Spotlight 索引 + 退出 IDEA + 独占机器。
   本项目的 SPARC 机（build-01）也是**开发机**（4 核）⇒ 两边的并发档读数都要按本节打折看。
5. ⇒ **§10.13.5 里「native arm 在高并发下偶发慢轮」作为「native 路径可疑信号」的含义作废**；
   它只是「这台机器当时有突发外部干扰」的信号。

#### 10.14.7 顺带查出来的真实优化点（**已实施**，有数据）

> ★ **2026-11-17 落地**：下表 1/2/3 全部实施。
> ⚠️ 落地位置与本表原样不同 —— 1/2/3 落在 **`NativeStream`（流式入口）** 上，
> 而本节是在流式化**之前**的探针里查出来的（那时说的「每次 op malloc/free」
> 指的是流式化后的 `edap_stream_ctx`，已随流式化一起落地）。
> 一次性入口（`NativeDigest` / `NativeMac` / `NativeHmacSha256`）在本次一并补齐了第 1 项。

| # | 点 | 数据 | 落地情况 |
|---|---|---|---|
| 1 | 小载荷（≤1KB）把 `GetPrimitiveArrayCritical` 换成 `GetByteArrayRegion` 拷栈缓冲 | JNI 成本实测：critical+release = **102ns（与载荷无关）**；region = 55ns@64B / 67ns@1KB / 202ns@8KB ⇒ **交叉点约 1.5~2KB** | ✅ **已实施**：阈值 `EDAP_SMALL_PAYLOAD_MAX = 1024` 与分流 helper 收敛到 `edap-native/src/main/c/edap_jni_array.h`（`edap_jni_consume_bytes`），**流式入口 + 三个一次性入口共用同一份**；临界区封在 helper 内部（调用方拿不到半开的临界区）。小载荷 **-45ns/op**（★ **分项**探针数字 ⇒ **不可相加**；**端到端合计实测见 §10.14.8**） |
| 2 | 每次 op `malloc(352)` + `memset(352)` + `free` ⇒ 上下文按线程复用 | `edap_stream_ctx` = 352 字节（macOS；Solaris 上 568） | ✅ **已实施**：空闲池（`pthread_key` + 线程私有，`EDAP_CTX_POOL_MAX = 8`）⇒ malloc/free 从 ~25.9ns 降到 ~3.5ns。★ **本次又补掉了残留的 `memset`**：OpenSSL 的 Init 会把上下文全量复位，取用时不需要 memset；改到**归还时** cleanse HMAC 的 ipad/opad（它们是 key 的简单 XOR，留在池里等于把 key 留在内存里）。★★ **与第 1 项的合计收益见 §10.14.8**（A/B 只能测**合计**，拆不出单项） |
| 3 | `edap_stream_ctx` 布局：`SHA256_CTX` 落在 offset 132（4 字节对齐）⇒ 把 union 放前面 | ⚠️ **原判断要更正**：本机实测（macOS，OpenSSL 3.6.4）`SHA_LONG` 是 `unsigned int`（4 字节）⇒ `sizeof(SHA256_CTX)`=112、**对齐要求只有 4 字节** ⇒ 旧布局（偏移 132）**本来就满足** ⇒ **这一项在当前 OpenSSL 3.x 上是 no-op**。只有当目标平台的 `sha.h` 把 `SHA_LONG` 定成 8 字节（部分 OpenSSL 1.x 按平台区分）时旧布局才违反要求 | ✅ **已实施**（防御性）：两个 union 挪到结构体最前面（基址由 malloc 给 ⇒ 16 字节对齐 ⇒ 天然满足任何 ≤16 字节的对齐要求）。★ **编译期断言用 `__alignof__(SHA256_CTX)` 按平台自动取值**（负长度数组，Solaris 上老 gcc 的默认方言未必是 C11 ⇒ 不用 `_Static_assert`）：谁往 union 前面加字段、或目标机换了 OpenSSL 版本导致对齐要求变化，都会当场编译失败 ⇒ **不硬编码任何对齐数字**。⚠️ 目标机（链接 `libcrypto.so.3` ⇒ 同为 3.x）是否真的要求 8 字节，**待在 build-01 上实测 `__alignof__(SHA256_CTX)` 才有定论** |
| 4 | `OPENSSL_cleanse` 出现在 profile 里（每次 finish 清零 32 字节结果缓冲） | `sample` 剖面 | ❌ **不做**：它来自 OpenSSL 自己的 `SHA256_Final`（内部 cleanse 上下文），不是我们的代码 ⇒ 收益极小且不可控 |

**★ 第 1 项为什么对一次性入口也值得做**：`NativeHmacSha256.sign0` 是
**edap-auth-jwt 的 HS256 生产路径**（`HmacSha256.HmacSha256Native` 默认委托它），
JWT 签名串通常几百字节 ⇒ 走小载荷路径省掉 critical section 的 ~102ns 固定成本。

**★ 一次性摘要入口（`NativeDigest`）另有一笔更大的账**：它用的是 OpenSSL 的
**一次性 API**（`SHA256()` / `SHA1()` / `MD5()`），而一次性 API 在 Solaris 上
**同样走 provider dispatch**（~7µs 固定开销，§10.11.2）⇒
在 Solaris 上它比流式入口慢 ~18 倍。
⇒ **流式语义不要接回 `NativeDigest`**，走 `NativeStream`（`update` / `finish` / `free`）。
⚠️ **2026-11-19 更正**：那个「~7µs / 18 倍」**只对 SHA-256 成立**——
同一台机器上 **MD5 的一次性 API 没有 provider dispatch 税**
（413.0 ns@64B vs legacy 流式 371.9 ns ⇒ 只差 1.11 倍）⇒
**MD5 若改走一次性入口省不到什么，也丢不掉什么**；
上面对 `NativeDigest` 的结论仍然成立，但**依据是 SHA-256 那条**。详见 §10.11.2 与 §10.15.7。

#### 10.14.8 ★★★ 三项优化的端到端 A/B（2026-11-18，macOS）

**为什么必须单独测一次**：§10.14.7 表里的数字来自**孤立的 JNI 探针**
（critical+release vs region 的差、malloc/free 的差）⇒ 那是**分项**成本，
**不能相加**（每项的测量条件都与真实路径不同）⇒ **合计收益必须用端到端基准测**。

**实验设计**（`edap-crypto/scripts/ab-native-opts.sh`，可复跑）：

| 项 | 取值 | 为什么这么定 |
|---|---|---|
| before 臂 | **HEAD 的 C 源码**编译出的 `.o`（流式化已在，三项都没有） | `git show HEAD:…` 把文件**取出来**编译，**不动工作树**（工作树有未提交改动；★ 不用 `git checkout` 做基线） |
| after 臂 | 工作树 C 源码编译出的 `.o` | 两臂的差别经 `git diff HEAD -- edap-native/src/main/c/` 逐行核对：**只有这三项** |
| 轮内交替 | 每轮先 before 再 after，共 5 轮 | ★ 本机是开发机（§10.14）⇒ 跨批次**绝对值**不可比 ⇒ 两臂必须共享同一段时间窗 |
| 对照臂 | **JCE arm**（完全不受换 `.o` 影响） | 它的 before/after 差 = 这段时间的**机器漂移** ⇒ native 臂的差要显著大于漂移才算真收益 |
| 换 `.o` 的办法 | 目标 `.o` 拷进 `edap-native/target/classes/`，并让该目录**排在 jar 之前** | native 从**类路径**读资源（`io.edap.jni.Native`），没有路径开关 ⇒ 靠类路径顺序 |

**数据**（macOS/aarch64，JDK 17.0.20.1，5 轮**中位数**，ns/op；★ JMH AverageTime ⇒ 越大越慢）：

| 原语 | payload | before | after | **变化** | before CV% | after CV% | JCE 对照漂移 |
|---|---|---|---|---|---|---|---|
| digest | 64 | 391.6 | 355.4 | **-36.2 ns（-9.24%）** | 0.81 | 0.97 | -0.00% |
| digest | 1024 | 777.0 | 753.3 | **-23.7 ns（-3.05%）** | 1.22 | 0.35 | -0.49% |
| digest | 8192 | 3723.7 | 3705.7 | +0.48%（噪声） | 0.50 | 0.60 | -0.42% |
| digest | 65536 | 27205.1 | 27215.9 | -0.04%（噪声） | 0.60 | 0.78 | -0.36% |
| mac | 64 | 600.3 | 581.3 | **-19.0 ns（-3.17%）** | 1.21 | 1.16 | -0.10% |
| mac | 1024 | 997.8 | 975.5 | **-22.3 ns（-2.23%）** | 0.77 | 0.36 | -0.52% |
| mac | 8192 | 3914.9 | 3918.4 | -0.09%（噪声） | 0.64 | 0.27 | -0.60% |
| mac | 65536 | 27386.9 | 27377.6 | +0.03%（噪声） | 0.42 | 0.17 | -0.46% |

（上表是 **1t 档**；**4t / 16t 档全部落在噪声里** —— before/after 的 CV 2~8%，
JCE 对照漂移最大 **-8.88%** ⇒ 本机 10 核跑 16 线程超载 + 常驻外部负载
⇒ **并发档不能下结论**，与 §10.14 同一结论。）

**读法（五条）**：

1. **收益集中在「单线程 + 小载荷」**：digest@64B **-36.2 ns/op（-9.2%）** 是最实的一格，
   digest@1KB -23.7 ns、mac@1KB -22.3 ns、mac@64B -19.0 ns。
   这四格的 CV 都 ≤1.3%、JCE 对照漂移 ≤0.5% ⇒ **不是噪声**。
2. **载荷 > 1KB 的格子没有变化**（≤0.5%，且在 CV 之内）—— **这是预期的**：
   大载荷本来就走 critical section（不受第 1 项影响），
   而第 2 项（池）是**固定成本**节省，在 8KB / 64KB 的计算量面前占比 <0.6%
   （8192B 档 CV 只有 0.5~0.6% ⇒ 量不出来）。
3. ★ **端到端合计（-36 ns）明显小于分项相加（-45 + -25.9 = -71 ns）**
   ⇒ **分项探针数字不可相加**（各自测量条件与真实路径不同；
   critical section 的固定成本在真实路径上被 JVM 的其它工作部分掩盖）。
   **这是本次 A/B 最有价值的一条结论** —— 也是为什么上表那两列不能当收益预期用。
4. **第 3 项（对齐）在本次 A/B 里没有贡献**：本机 OpenSSL 3.x 的 `SHA_LONG` 是 4 字节
   ⇒ 对齐要求 4 字节 ⇒ 旧布局本来就满足（§10.14.7 第 3 行已更正）
   ⇒ **它的价值是防御性的**（编译期断言按平台自动取值）；
   要证明它在目标机上的收益/必要性，得在 build-01 上核 `__alignof__(SHA256_CTX)`。
5. **本次 A/B 只覆盖流式入口**：provider 走 `NativeStream`；
   **一次性入口**（`NativeDigest` / `NativeMac` / `NativeHmacSha256`，
   其中 `NativeHmacSha256.sign0` 是 **edap-auth-jwt 的 HS256 生产路径**）
   也切到了小载荷分流，但 `NativeVsJceBenchmark` 不覆盖它 ⇒ **JWT 路径的收益未测**。

**★ 交叉校验（两批互证）**：before 臂（HEAD 源码编译）与 §10.12 那批**独立**测量的 native 臂：

| 格 | §10.12（20261117-134252 批） | 本次 before 臂 | 差 |
|---|---|---|---|
| digest@64B/1t | 393.6 | 391.6 | -0.5% |
| mac@64B/1t | 611.0 | 600.3 | -1.8% |
| digest@1KB/1t | 790.9 | 777.0 | -1.8% |
| digest@64KB/1t | 27796 | 27205 | -2.1% |

⇒ **整批偏低 1~2%**（两次跑批之间的机器状态差异）⇒ 两批互证「before 臂确实复现了优化前的状态」。

**★ 产物一致性核对（A/B 测的是不是最终发布的那份）**：

A/B 跑完后，仓库里已提交的 `.o`、A/B 的 after 臂、A/B 的 before 臂**三份整文件哈希互不相同**
⇒ 触发了「A/B 到底测的是哪份实现」的疑问。查清结论：

| 差异层 | 来源 | 什么时候变 |
|---|---|---|
| 链接期元数据 | `LC_UUID`（16B，ld64 随机）、代码签名里的 UUID + ECDSA 签名（32B） | **每次链接都变** |
| 链接期元数据 | `__TEXT` 里嵌入的输出路径（`LC_ID_DYLIB` 的 install name） | 输出路径一变就变 |
| **布局** | 输出路径**长度**变化 ⇒ 段偏移平移 ⇒ 代码里 PC 相对立即数（ADRP 等）随之变 | 输出路径长度一变就变 |

★ 实测：**同一份源码、同样的编译参数，只有输出路径不同**，`__text` 段哈希就出现 4 个不同值
⇒ **整文件哈希和 `__text` 哈希都不是稳定的身份标识**，不能用来判「是不是同一份代码」。
**正确口径 = 逐符号的归一化反汇编**（屏蔽地址与立即数，只比指令序列）：

| 比较 | 归一化后同码符号数 | 结论 |
|---|---|---|
| A/B after 臂 vs 仓库里已提交的 `.o` | **18 / 18 全同** | ⇒ **A/B 实测的就是最终发布的那份实现**，结论对已提交产物成立 |
| A/B before 臂 vs after 臂 | **0 / 10 全不同** | ⇒ A/B 确实比的是两份不同实现 |

（口径与命令见 `edap-native/doc/NATIVE_DESIGN.md §2.5`。）

★ 另核：`~/.m2` 里 `edap-native-0.1.3-SNAPSHOT.jar` 内的 `.o` 与
`src/main/resources/` 下已提交的 `.o` **逐字节一致** ⇒ `mvn -pl edap-crypto test`
跑的确实是当前产物（不是旧 jar 里的过期 `.o`）。

**复跑**：`cd edap-crypto && ./scripts/ab-native-opts.sh 5`（约 35 分钟，10 轮）。
**数据文件**：`doc/bench/ab-20261118-003241-{before,after}-r<N>.json`、
汇总 `doc/bench/summary-ab-20261118-003241.md`、原始日志 `doc/bench/bench-ab-20261118-003241.log`。

### 10.15 ★★★ MD5 的 native vs JCE（2026-11-18，**macOS + Solaris/SPARC**）—— **两个平台的结论相反**

#### 10.15.1 为什么要单独建一个基准类

`NativeVsJceBenchmark` 与两个汇总脚本（`ab-summary.py` / `native-vs-jce-summary.py`）
都按**方法名里有没有 `Digest` / `Mac`** 来分类原语 ⇒ MD5 的 arm 叫
`nativeMd5_*` / `jceMd5_*`（不含 `Digest`）⇒ 会被误判成 `mac`；
硬塞进同一个类还会让 MD5 与 SHA-256 在 `(kind, arm, 线程数, payload)` 这个 key 上
**撞车，两组数被静默合并**（这正是 `summarize-bench.py` 踩过的坑）。
⇒ 单独建 `Md5VsJceBenchmark` + 单独建 `scripts/md5-summary.py`（按 `Md5` 单独认 arm）。

**跑法**（`edap-crypto/scripts/md5-bench.sh 5`，约 8 分钟）：

| 项 | 取值 |
|---|---|
| 两臂 | `nativeMd5_*`（edap-native 流式摘要，算法编号 2）vs `jceMd5_*`（`MessageDigest.getInstance("MD5")`） |
| payload | 64 / 1024 / 8192 / 65536 B（`@Param`） |
| 线程 | 1 / 4 / 16 |
| **每轮的比对** | `@Setup` 里**逐字节比对两臂的 MD5**，不一致直接抛异常把这一轮作废（防「测的是 JCE vs JCE」） |
| **native arm 参与校验** | 脚本每轮 grep 一遍 JSON —— `NativeDigestProvider.tryCreate` 会吞掉 `Throwable`，`.o` 过期时静默退回 JCE，比值会恒为 1.00 却看不出来 |
| JCE provider 归属 | `@Setup` 打印 —— 本机是 **SUN (MD5)**（纯 Java 实现） |
| 跨 launch 取值 | **中位数**（不取 min —— 本机是开发机，min 等于挑运气） |

#### 10.15.2 数据（批次 B：机器较空的一批，loadavg ≈ 6，5 轮中位数，ns/op）

| payload | 线程 | native 中位数 | jce 中位数 | **native/jce 倍数** | native CV% | jce CV% |
|---|---|---|---|---|---|---|
| 64 | 1t | 471.0 | 206.6 | **0.44x** | 1.86 | 1.73 |
| 64 | 4t | 703.5 | 268.3 | **0.38x** | 8.48 | 5.08 |
| 64 | 16t | 1424.1 | 535.0 | **0.38x** | 9.91 | 9.43 |
| 1024 | 1t | 1849.6 | 1677.2 | **0.91x** | 2.22 | 2.88 |
| 1024 | 4t | 2115.7 | 1793.8 | **0.85x** | 2.04 | 1.66 |
| 1024 | 16t | 3975.0 | 3476.0 | **0.87x** | 14.42 | 17.84 |
| 8192 | 1t | 12019.9 | 12827.4 | **1.07x** | 0.92 | 1.77 |
| 8192 | 4t | 12715.0 | 13220.6 | **1.04x** | 2.81 | 1.32 |
| 8192 | 16t | 24565.8 | 24824.2 | **1.01x** | 10.13 | 11.48 |
| 65536 | 1t | 93660.0 | 103012.9 | **1.10x** | 1.24 | 2.34 |
| 65536 | 4t | 97654.4 | 105042.5 | **1.08x** | 0.62 | 3.65 |
| 65536 | 16t | 179354.3 | 196475.9 | **1.10x** | 6.82 | 14.17 |

**同批 SHA-256 对照**（同一套脚本、同一段机器状态窗口，
`BENCH_CLASS=NativeVsJceBenchmark BENCH_TAG=sha256ctl SUMMARY_PY=native-vs-jce-summary.py`，
只跑摘要臂）：

| payload | 线程 | native 中位数 | jce 中位数 | **native/jce 倍数** |
|---|---|---|---|---|
| 64 | 1t | 355.0 | 597.4 | **1.68x** |
| 1024 | 1t | 746.1 | 4798.6 | **6.43x** |
| 8192 | 1t | 3669.3 | 36159.1 | **9.85x** |
| 65536 | 1t | 26941.6 | 287125.8 | **10.66x** |

⇒ **同一个 harness、同一个窗口：SHA-256 在 64B 就已经 1.68x 赢，
MD5 在 64B 却是 0.44x 输** ⇒ 差异来自算法本身，不是跑法。

★ 另有**批次 C**（2026-11-19，与 SPARC 那批**同一天**，loadavg 9.23，git HEAD `87ca34fb`）：
1t 档 64B **0.43x**、1KB **0.91x**、8KB **1.05x**、64KB **1.07x**
⇒ 与批次 B 互差 ≤3%（详见 §10.15.5 第 3 条）。

#### 10.15.3 ★★ 为什么（macOS 上）MD5 与 SHA-256 的结论完全相反

把 1t 的两端（64B 与 64KB）连成线，得到**每字节的边际成本**：

| 原语 | native 每字节 | native 吞吐 | jce 每字节 | jce 吞吐 | 硬件加速 |
|---|---|---|---|---|---|
| SHA-256 | **0.406 ns/B** | **2.43 GB/s** | 4.376 ns/B | 228 MB/s | native 侧吃到了 **SHA-NI**（Apple Silicon 的硬件指令）⇒ 数量级优势 |
| MD5 | **1.423 ns/B** | 700 MB/s | **1.570 ns/B** | 636 MB/s | **MD5 没有对应的硬件加速** ⇒ 两边每字节成本几乎一样 |

★ **这就是全部**：MD5 的 native 侧每字节只比 JCE 快 **10%**
（1.423 vs 1.570 ns/B），而 JNI + session 的**固定成本** native 要多付约 **270 ns**
（按两端线性外推：native ≈ 380 ns、JCE ≈ 106 ns）
⇒ **交叉点**：按两端线性外推约 **1.8 KB**，实测落在 **1KB 与 8KB 之间**
（1KB 时 native 仍输 9%，8KB 时 native 赢 7%，64KB 赢 10%）。

⇒ **MD5 的 native 路径最多只能赚 10%，却要付一笔固定成本** ⇒ 小载荷必输；
SHA-256 则相反：每字节便宜 **10.8 倍**，固定成本立刻被摊掉 ⇒ 64B 就已经 1.68x。

#### 10.15.4 ★ 影响面：edap 的 MD5 生产调用方**根本不走 native**

| 调用方 | 走的实现 | 输入规模 |
|---|---|---|
| `io.edap.util.Md5Util.md5`（**edap-common**）—— `edap-log` 的 `EncoderGenerator` / `TextEncoderGenerator`、`edap-json` 的 `JsonUtil`、`edap-bean-convert` 的 `ConvertorRegister`、`edap-data-jdbc-dao` 的 `DaoUtil` 都用它 | **裸 `MessageDigest.getInstance("md5")`**（绕开 `CryptoRegistry` ⇒ **不受 native 开关影响**） | 短串（类名 / 格式名） |
| `io.edap.util.CryptUtil.md5`（edap-crypto，走 `CryptoRegistry` ⇒ 默认走 native） | **无生产调用方**（只有 javadoc 提到） | — |
| `edap-s3-core` 的 `InMemoryObjectStore` | 直接 `MessageDigest.getInstance("MD5")` | 对象 body（大） |

⇒ **结论：MD5 的 native 路径在 edap 里目前是「有实现、无调用方」**
⇒ 这次对比的产出是**选型知识**（将来若有人把 MD5 摘要走 registry，
小载荷会踩坑），**不是待修的性能 bug**。

★ 顺带记一条文档不一致：`edap-common` 的 `Md5Util` 类注释写着
「实现走 edap-crypto 的 `DigestProvider` 抽象」，但代码里是裸
`MessageDigest.getInstance("md5")` ⇒ **注释与实现不符**（不影响行为）。

#### 10.15.5 ★ 三条读法

1. **★ 比值不可跨平台外推** —— 本机 JCE 侧 MD5 落在 **SUN（纯 Java）** provider。
   Solaris 上 MD5 可能落在 **OracleUcrypto（原生实现）** ⇒ 那一边的「JCE 成本」
   是另一回事 ⇒ **同一个比值在两台机器上可能完全相反**。
   与 §10.10「cipher 侧 provider 归属推翻了『机制同源』的假设」是同一条纪律。
   ★★ **这条预警已被实测兑现**：SPARC 上的 MD5 比值是 1.27x~3.09x（native 赢），
   与 macOS 的 0.43x~1.10x **方向相反** ⇒ 详见 **§10.15.7**。
2. **★ 并发档（4t/16t）不能下结论** —— CV 2~17%，本机 10 核跑 16 线程是超载
   + 常驻外部负载（§10.14）。不过两臂的**相对关系**与 1t 档一致
   （64B 都是 0.38x），方向可信、幅度不可外推。
3. **★ 数据可信度：三批独立复跑，中位数互差 ≤3%** ——
   批次 A（机器重负载，loadavg 47~71）1t 档：64B **0.43x**、1KB **0.89x**、
   8KB **1.04x**、64KB **1.08x**；批次 B（机器较空）：**0.44x / 0.91x / 1.07x / 1.10x**；
   批次 C（**2026-11-19，与 SPARC 那批同一天**，loadavg 9.23）：
   **0.43x / 0.91x / 1.05x / 1.07x**。
   ★ 批次 A 的**第 1 轮是离群轮**（native@64B 946.1 ns，其余 4 轮 476~500 ns；
   同窗口 SHA-256 对照批**没有**这个现象）⇒ 用**中位数**天然排除了它。
   ★ 批次 C 的意义：**macOS 与 SPARC 的对比是「同一天」做的**（批次 C 的 git HEAD
   `87ca34fb`）⇒ 「两个平台结论相反」不是「不同日期的机器状态差异」造成的。
   ★ 另注：**本环境里的 C 层探针不可信** —— 试过用 C 程序分解 JNI 固定开销，
   但进程被反复剥夺 CPU（实测 2 ms 的 CPU 工作花了 0.43 s 墙钟）⇒ 数字是调度噪声，
   **作废**；所以本节的固定成本只由 JVM 内的两批 JMH 数据外推，不引用任何 C 层数字。

#### 10.15.6 复跑

```bash
cd edap-crypto
./scripts/md5-bench.sh 5                     # MD5（native vs JCE），约 8 分钟
# 同批 SHA-256 对照：
BENCH_CLASS=NativeVsJceBenchmark BENCH_TAG=sha256ctl \
SUMMARY_PY=native-vs-jce-summary.py BENCH_INCLUDE='.*Digest.*' \
./scripts/md5-bench.sh 5
```

**数据文件**：`doc/bench/md5-20261118-110024-r<N>.json`（批次 A，重负载）、
`doc/bench/md5-20261118-154842-r<N>.json`（批次 B，机器较空）、
`doc/bench/md5-20261119-091540-r<N>.json`（**批次 C**，与 SPARC 同一天）、
`doc/bench/sha256ctl-20261118-111351-r<N>.json`（SHA-256 同批对照），
汇总 `doc/bench/summary-md5-*.md` / `doc/bench/summary-sha256ctl-*.md`。

#### 10.15.7 ★★★ 目标平台 Solaris/SPARC 实测（build-01）—— **结论翻转**

同一套代码、同一套 harness，在 **build-01（Solaris 11.4 / SPARC / 4 核 / JDK 17.0.19）**
上的结论与 macOS **完全相反**：MD5 在 SPARC 上 **native 全档赢**。

| 项 | 值 |
|---|---|
| 机器 | build-01，Solaris 11.4（kernel-zone），**SPARC sun4v，4 核** |
| JDK | 17.0.19（Oracle，SPARC） |
| git HEAD | `87ca34fb`（相对 `6c7b546f` **只新增了 `edap_jni_array.h`**，无 C 逻辑改动 ⇒ 重建的 `.o` 与 HEAD 等价） |
| 批次 | **定稿**：`md5-20261119-091606`（5 轮，一 arm 一 fork）+ 同批 SHA-256 对照 `sha256ctl2-20261119-093215` |
| loadavg | 跑批期间 **0.97 → 5.6**（比 macOS 那批干净得多） |
| native 产物 | `edap-native-sunos_sparcv9.o`（**2026-11-19 09:08 在 build-01 上用 gcc 重建** ⇒ 含 §10.14.8 的三项优化） |
| 对照批次 | `md5-20261118-223533`（**用过期 `.o`** ⇒ 只作「三项优化之前」的对照，见下「★ 三项优化在 SPARC 上的收益」） |

**1t 档（信号最干净）与 macOS 对照（ns/op；macOS 列取批次 B，批次 C 互差 ≤3%）**

| payload | native (SPARC) | jce (SPARC) | **native/jce** | 同格 macOS |
|---|---|---|---|---|
| 64 | 2,182.5 | 6,739.1 | **3.09x** | 0.44x（native 输） |
| 1024 | 3,396.4 | 8,042.2 | **2.37x** | 0.91x（native 输） |
| 8192 | 11,252.1 | 17,852.7 | **1.59x** | 1.07x |
| 65536 | 77,127.8 | 97,949.1 | **1.27x** | 1.10x |

**4t 档（= 核数，不超载 ⇒ build-01 上唯一有效的并发档）**

| payload | native | jce | **native/jce** |
|---|---|---|---|
| 64 | 2,191.8 | 24,383.6 | **11.13x** |
| 1024 | 3,449.1 | 26,359.2 | **7.64x** |
| 8192 | 11,299.2 | 28,538.6 | **2.53x** |
| 65536 | 93,695.6 | 102,216.0 | **1.09x**（⚠ 噪声，见下） |

**16t 档：作废** —— 4 核跑 16 线程 = **4 倍超载**（§10.13 同一条纪律）；
native CV 高达 **191.90% / 212.24%**（64B / 8KB 两档）、jce CV **167.77%**（1KB）
⇒ 16t 档**不可下任何结论**。

**同批 SHA-256 对照**（`sha256ctl2-20261119-093215`，同一段机器状态窗口）：

| 原语 | payload | 线程 | native | jce | **native/jce** |
|---|---|---|---|---|---|
| digest | 64 | 1t | 2,206.0 | 7,025.6 | **3.18x** |
| digest | 1024 | 1t | 3,478.2 | 8,448.6 | **2.43x** |
| digest | 8192 | 1t | 11,478.6 | 18,454.7 | **1.61x** |
| digest | 65536 | 1t | 77,678.5 | 98,589.6 | **1.27x** |
| digest | 64 | 4t | 2,270.0 | 25,156.3 | **11.08x** |
| digest | 1024 | 4t | 3,566.8 | 27,051.4 | **7.58x** |
| digest | 8192 | 4t | 11,677.9 | 28,651.4 | **2.45x** |
| digest | 65536 | 4t | 78,015.9 | 104,105.3 | **1.33x** |

⇒ ★ **JCE 侧与上一批逐格重合**（MD5：6,739.1 vs 6,760.4 / 8,042.2 vs 8,022.4 /
17,852.7 vs 17,887.1 / 97,949.1 vs 98,232.9，偏差 ≤0.3%；SHA-256：7,025.6 vs 7,023.1 /
8,448.6 vs 8,394.3 / 18,454.7 vs 18,484.0 / 98,589.6 vs 98,663.4，偏差 ≤0.7%）
⇒ **两批机器状态一致、可直接相减**（这是「JCE 臂作漂移对照」的标准用法）。

##### ★ 三项优化在 SPARC 上的收益（用过期 `.o` 那批作对照）

| 原语 | payload | 线程 | 旧 `.o` native | **新 `.o` native** | 变化 |
|---|---|---|---|---|---|
| MD5 | 64 | 1t | 2,436.6 | **2,182.5** | **-254.1 ns（-10.4%）** |
| MD5 | 1024 | 1t | 3,470.5 | 3,396.4 | -74.1 ns（-2.1%） |
| MD5 | 8192 | 1t | 11,613.2 | 11,252.1 | -361.1 ns（-3.1%） |
| MD5 | 65536 | 1t | 77,429.9 | 77,127.8 | -302.1 ns（-0.4%） |
| SHA-256 | 64 | 1t | 2,497.8 | **2,206.0** | **-291.8 ns（-11.7%）** |
| SHA-256 | 1024 | 1t | 3,585.7 | 3,478.2 | -107.5 ns（-3.0%） |
| SHA-256 | 8192 | 1t | 11,918.2 | 11,478.6 | -439.6 ns（-3.7%） |
| SHA-256 | 65536 | 1t | 77,964.2 | 77,678.5 | -285.7 ns（-0.4%） |

⇒ ★ **小载荷收益比 macOS 大**（macOS digest@64B 是 -9.2%，SPARC 是 **-10.4% ~ -11.7%**）
⇒ 符合预期：**SPARC 的 JNI 越贵，避开 critical section + 省掉 malloc/free 就越值钱**。

⇒ ★★ **但真正的收获在并发档**（这一条 macOS 上没测出来，因为 macOS 的并发档本身不可信）：

| 原语 | payload | 线程 | 旧 `.o` | **新 `.o`** | 变化 |
|---|---|---|---|---|---|
| MD5 | 64 | 4t | 4,575.4 | **2,191.8** | **-2,383.6 ns（-52.1%）** |
| SHA-256 | 64 | 4t | 4,549.4 | **2,270.0** | **-2,279.4 ns（-50.1%）** |
| MD5 | 1024 | 4t | 4,336.1 | 3,449.1 | -887.0 ns（-20.5%） |
| SHA-256 | 1024 | 4t | 4,433.6 | 3,566.8 | -866.8 ns（-19.5%） |
| MD5 | 8192 | 4t | 12,187.3 | 11,299.2 | -888.1 ns（-7.3%） |

★ **旧 `.o` 在 4t 档有一个「悬崖」**：64B 时 native@4t 比 native@1t **慢 1.88 倍**
（4,575.4 vs 2,436.6）⇒ 4 线程时每个操作多付约 **2.1 μs**。
**新 `.o` 把这个悬崖整个抹平了**：64B 时 4t ≈ 1t（2,191.8 vs 2,182.5，**持平**）。

⇒ ★★ **根因 = 上下文池（`pthread_key` 线程私有空闲池）**：
旧实现每次操作 `malloc(360) + free(360)`（§10.14.8 记的 macOS 数字是 ~25.9 ns/op），
**4 线程同时跑 ⇒ 分配器成为竞争点** ⇒ 每操作多付 ~2.1 μs；
新实现改成「按线程复用一个空闲上下文」⇒ **分配竞争整个消失**。
⇒ 这是**本轮唯一一处「macOS 测不出来、只有目标平台能证明」的收益**：
macOS 的并发档 CV 2~8%（§10.10.8.8），本来就不可信。

★ 顺带一个副作用：MD5@64KB/4t 出现 +20.4%（93,695.6 vs 77,841.2），
但**同批 SHA-256 对照的 64KB/4t 是 -0.3%**（78,015.9 vs 78,246.9）
⇒ 两臂方向相反 ⇒ **那一格是噪声**（该格 native CV 9.02%、同批 SHA-256 同格 CV 11.06%），
**不下结论**。★ 这正是「同批放一个对照臂」的价值：没有它就会把噪声当成回归。

##### ★ 为什么翻转：是**固定成本**，不是每字节成本

把 1t 的两端（64B 与 64KB）连成线，做线性分解：

| 平台 | 原语 | 固定成本（外推） | 每字节边际成本 | 吞吐 |
|---|---|---|---|---|
| SPARC | native | **2,109 ns** | 1.1447 ns/B | 873 MB/s |
| SPARC | jce | **6,650 ns** | 1.3931 ns/B | 718 MB/s |
| macOS | native | 380 ns | 1.4233 ns/B | 700 MB/s |
| macOS | jce | 106 ns | 1.5702 ns/B | 636 MB/s |

★ **每字节成本在两个平台上几乎一样**（native 1.145 vs 1.423，jce 1.393 vs 1.570）
⇒ **翻转不来自算法快慢，而来自「每次操作的固定成本」**：

* SPARC 的 JCE 侧每次操作要付 **~6.65 μs**（macOS 只有 ~0.11 μs，**差 60 倍**）
  ⇒ 64B 时这笔固定成本占 JCE 总耗时的 **98.7%** ⇒ native 赢 **3.09x**。
* macOS 的 JCE 侧几乎零固定成本 ⇒ 每字节成本说了算
  ⇒ MD5 没有硬件加速（Apple Silicon 的 SHA-NI 只惠及 SHA-256，§10.15.3）
  ⇒ native 每字节只快 10%，抵不过 native 自己那 ~274 ns 的固定成本
  ⇒ 小载荷 native 输 **0.44x**。

⇒ ★ **MD5 的选型结论是「按平台给」的**：
**SPARC（目标平台）上 MD5 应该走 native（1t 档 1.27x~3.09x，4t 档最高 11.13x）**；
**macOS 上小载荷 MD5 应该走 JCE（native 输 56%）**。
★ 交叉点也随平台反转：macOS 的 MD5 交叉点约 **1.8 KB**（§10.15.3），
SPARC 上 64B 就已经赢 3.09x ⇒ **SPARC 不存在「native 反超」的那个点**。

##### ★★ 为什么 SPARC 的 JCE 侧会有 ~6.65 μs 固定成本（**已实测定位**）

**已核实（build-01，2026-11-18）**：`grep "JCE provider" doc/bench/bench-md5-20261118-223533.log`
⇒ **`JCE provider = OracleUcrypto (MD5)`**（每轮都打印一次）。

⇒ **SPARC 的 JCE 侧根本不是纯 Java** ⇒ 那 6.65 μs 是
**ucrypto 这一层每次操作的一次额外往返**（不是算法本身慢）。
★ **已实测到的是「provider 归属 + 固定成本形状」；ucrypto 内部到底走了
PKCS#11 token 还是 Solaris 内核 crypto 框架，本轮未查**
（但形状与 §10.11 已实测的「Solaris 上 JCE-HMAC 每次 new SecretKeySpec
⇒ 每次导 key 进 PKCS#11 token ⇒ 固定 ~130μs」是同一类现象）。
**推演被实测兑现**（原推演的两条旁证也全部对上）：

1. **同一算法在两台机器上的每字节成本差 3.1 倍，且方向反直觉**：
   JCE SHA-256 在 SPARC 上是 **1.3985 ns/B**（定稿批次线性分解），
   在 macOS 上是 **4.377 ns/B**（§10.15.2 同批对照；§10.12.3 的另一批也是 4.5 ns/B 量级）
   ⇒ 若是同一个纯 Java 实现，**不该差 3 倍**；何况 **SPARC 的每字节成本还更快**
   —— 一台老 CPU 在纯 Java 上比 M1 快 3 倍，不可能
   ⇒ **SPARC 的 JCE 不是纯 Java 实现**。★ **现已由 provider 归属直接证实**。
2. **SPARC 上 JCE 的 MD5 与 SHA-256 每字节成本几乎相同**（1.3931 vs 1.3985，差 0.4%）
   ⇒ 两个复杂度差 ~2.8 倍的算法成本相同 ⇒ 底层是**同一个通用实现**
   ⇒ 与「ucrypto → OpenSSL / Solaris 内核 crypto 框架」的特征吻合
   （也吻合 §10.14 已记录的「Solaris 上 OpenSSL 一次性 `SHA256()` 有 ~7μs 固定开销」
   —— 本轮 JCE 的 6.65~6.94 μs 与它是同一量级）。

⇒ ★ **这条与 §10.10 / §10.11 是同一条纪律的第三次兑现**：
「provider 归属不查 ⇒ 机制结论可能整个反过来」
（§10.10 cipher 侧、§10.11 HMAC 侧的 key 导入、本节 digest 侧）。
**OracleUcrypto 与 macOS 的 SUN provider 不是一回事** ⇒
**同一个比值在两台机器上可以完全相反**（本节 0.43x ↔ 3.09x 就是实例）。

##### ✅ 已闭环：`.o` 过期问题（重建 + 重跑 + 同批对照都已完成）

**发现**：build-01 上 `.o`（11-17 14:56，16,472 B）比 C 源码（11-18 21:45）旧 ——
`6f17b84d`（2026-11-18 21:03:56「优化小体积包的摘要信息jni逻辑」）
改了 **4 个 C 文件**（+410/-80 行，含新增 `edap_jni_array.h`）
⇒ 早先那批 native 臂跑的是「三项优化之前」的实现
（小载荷没有 `GetByteArrayRegion` 路径、上下文没有按线程复用、union 还是旧布局）。

**处置**（2026-11-19 09:08，build-01）：

```bash
cd /build/workspace/edap/edap
export JAVA_HOME=/build/jdk-17.0.19
cd edap-native
OPENSSL_INCLUDE=-I/opt/csw/include OPENSSL_LIB="-L/opt/csw/lib" ./scripts/build-native.sh --commit
ls -l src/main/resources/edap-native-sunos_sparcv9.o     # 时间戳变成 11-19 09:08，21,320 B
# ★ 确认「classpath 里加载的就是刚编出来的那份」
cmp target/classes/edap-native-sunos_sparcv9.o src/main/resources/edap-native-sunos_sparcv9.o   # ⇒ SAME
cd ../edap-crypto && ./scripts/md5-bench.sh 5
```

★ **判「新 `.o` 生效」的读数特征已兑现**：native@64B/1t =
**2,182.5 ns** < §10.13.3 记录的 2,497.8 ns（那一批用的也是当期 `.o`，
⇒ 差异只可能来自这三项优化）⇒ **-12.6%**。
★ 重跑后**顺带重跑了同批 SHA-256 对照**（`sha256ctl2-20261119-093215`），
两臂的方向、幅度都吻合 ⇒ 交叉验证成立。

⇒ ★ **本节的所有结论现在都建立在「当期 `.o`」上**，不再是「偏悲观的旧 `.o`」。

★ **顺带查出一个仓库级 bug（已修）**：
`edap-native/src/main/c/edap_jni_array.h`（§10.14.8 第 1 项的分流 helper）
**此前从未被提交进 git**（本地是 untracked）⇒ 但 `6f17b84d` 的 C 源码 `#include` 了它
⇒ **任何新克隆 / 新机器拉下来的 native 模块都编译不过**
（build-01 是靠已同步的工作区才编得出来的）。
⇒ **已修**：先用 base64 分块方式把文件直送 build-01
（sha256 = `88a0b1250c100066283605131839d9eafee0c97b22bf4f71321e206b60d08f3b`，3,704 B，
与仓库里那份逐字节一致），再由 commit **`87ca34fb`「添加edap_jni_array.h」**
（2026-11-19 09:07，+86 行）正式入库 ⇒ **新克隆现在能编得过 native 模块了**。

##### ✅ 两个待核实项都已闭环（2026-11-19，build-01 raw OpenSSL 探针）

探针：`edap-native/scripts/md5-vs-sha256-probe.c`（**不经 JNI、不经 JVM**），
每个算法测 `oneshot` / `stream` / `evp` 三臂，每臂 7 轮取**中位数**并报 CV。
链接的是 `/lib/64/libcrypto.so.3`（**OpenSSL 3.0.16**）。

| payload | MD5 oneshot | MD5 **stream** | MD5 evp | SHA-256 oneshot | SHA-256 **stream** | SHA-256 evp |
|---|---|---|---|---|---|---|
| 64 | 413.0 | **371.9** | 6949.8 | 7525.2 | **411.0** | 7209.8 |
| 1024 | 1470.9 | **1430.1** | 8014.6 | 8657.8 | **1469.4** | 8304.9 |
| 8192 | 9290.0 | **9274.4** | 16268.3 | 16859.4 | **9428.7** | 16402.4 |
| 65536 | 73004.4 | **71950.3** | 80943.5 | 81597.0 | **73037.5** | 80754.7 |

（ns/op；CV **0.2%~3.7%**，唯一例外是 SHA-256 stream@8KB 的 6.4%；三个 arm 的输出逐字节相同。）

**线性分解（`stream` 臂 = legacy `Init/Update/Final`，也就是 edap-native 走的那条路）**：

| 原语 | 每字节边际成本 | 吞吐 | 固定成本 |
|---|---|---|---|
| MD5 | **1.0933 ns/B** | 914.7 MB/s | **302.0 ns** |
| SHA-256 | **1.1093 ns/B** | 901.5 MB/s | **340.0 ns** |
| ⇒ **SHA-256/MD5** | **1.015** | | |

**★ 第二批独立复跑（同一台机、另一段机器状态）—— 结论完全复现**

探针原样重跑一次，原始 stdout 落成 `doc/bench/probe-md5-vs-sha256-build01.txt`：

| payload | MD5 oneshot | MD5 **stream** | MD5 evp | SHA-256 oneshot | SHA-256 **stream** | SHA-256 evp |
|---|---|---|---|---|---|---|
| 64 | 410.7 | **383.1** | 6974.9 | 7509.8 | **411.7** | 7215.0 |
| 1024 | 1472.7 | **1433.2** | 8034.6 | 8645.7 | **1472.0** | 8331.1 |
| 8192 | 9373.5 | **9234.8** | 16127.0 | 16647.7 | **9416.1** | 16262.4 |
| 65536 | 72501.8 | **71857.9** | 80591.7 | 80664.4 | **73111.4** | 81846.9 |

（ns/op；CV **0.2%~4.3%**；两批的 `oneshot==stream==evp` 全部 OK。）

**逐格对照（第二批 / 第一批 − 1）**：

| 臂 | 最大偏差 | 备注 |
|---|---|---|
| MD5 stream@64B | **+3.0%** | 唯一超 1% 的格子，也是 CV 最大的一格（4.0%） |
| 其余 23 格 | **≤1.4%** | |
| SHA-256 stream@64B | **+0.2%** | 与 §10.11.2 那个独立探针的 411.4 ns 构成三方互证 |

**线性分解两批对照**：

| | 第一批 | 第二批 | 偏差 |
|---|---|---|---|
| MD5 marginal | 1.0933 ns/B | 1.0917 ns/B | **-0.15%** |
| SHA-256 marginal | 1.1093 ns/B | 1.1104 ns/B | **+0.10%** |
| ⇒ **SHA-256/MD5** | **1.015** | **1.017** | **+0.2%** |
| MD5 fixed | 302.0 ns | 313.2 ns | +3.7% |
| SHA-256 fixed | 340.0 ns | 340.6 ns | +0.2% |

⇒ ★ **两批独立复跑把「每字节边际成本」钉死了**（偏差 ≤0.15%）
⇒ 结论 1 的「SHA-256/MD5 = 1.015~1.017」**不是某一轮的巧合**。
⇒ ★ **「一次性 vs 流式」的比值同样钉死**：MD5 **1.07~1.11x**（两批）、
SHA-256 **18.2~18.3x**（两批）⇒ **MD5 没有 provider dispatch 税**这条修正
在两批上同样成立（下面「附带一条必须修正的旧结论」那节）。
⇒ ★ **固定成本的偏差（MD5 +3.7%）明显大于边际成本** ⇒ 因为固定成本是「两个大数相减」
⇒ **外推的放大效应** ⇒ 引用固定成本时按 ±4% 看待（结论 2 的 JNI/OpenSSL 拆分不受影响，
因为那用的是**第一批**且有 §10.11 的独立交叉验证）。

★★ **跨探针互证（三方）**：本探针两批的 SHA-256 stream@64B = **411.0 / 411.7 ns**，
而 §10.11.2 那个**完全独立的** C 探针（`oneshot-vs-stream-probe.c`）当年测到 **411.4 ns**
⇒ **三个探针两两差 ≤0.17%** ⇒ 这批 C 层读数在 build-01 上是可信的
（对照：§10.15.5 记的「本机 C 层探针是调度噪声」只对 Mac 成立，**build-01 上 C 探针可用**）。

**★ 结论 1：「MD5 ≈ SHA-256」的原因找到了 —— 而且原假说被推翻**

原假说：「OpenSSL 在 SPARC 上**有 `sha256-sparc` 汇编、没有 MD5 汇编** ⇒ SHA-256 靠汇编追平」。
⇒ **实测方向相反**：raw OpenSSL 里 **SHA-256 比 MD5 还略慢**（1.1093 vs 1.0933，比值 **1.015**）
⇒ **若 SHA-256 真有汇编，它应该更快而不是持平** ⇒ **假说作废**。
★ 真实解释：**这个 OpenSSL 在 SPARC 上 MD5 与 SHA-256 都没有汇编加速**
（两者都是通用 C 实现 ⇒ 成本被同一层因素拉平到几乎相等）。
★★ **而且两个完全不同的 provider 给出同一个形状**：
ucrypto（JCE 臂）MD5 1.3931 / SHA-256 1.3985；OpenSSL（native 臂）MD5 1.0933 / SHA-256 1.1093
⇒ 两个独立实现都是「两算法同价」⇒ **不是某一家库的实现选择，
而是 SPARC 上「通用 C 摘要」的成本下限**。
★ **附带一条跨 provider 的定量对照**：ucrypto 的每字节成本是 OpenSSL 的
**1.27x（MD5）/ 1.26x（SHA-256）** —— **两个算法的比值几乎恒定**
⇒ 再次印证「底层是同一类通用实现」（§10.11.4 同源纪律的又一次兑现）。

**★★ 结论 2：native 那 2,109 ns 固定成本的拆分 —— JNI 占了绝大部分**

| 分量 | MD5 | SHA-256 |
|---|---|---|
| native 臂固定成本（JMH，见上面的线性分解） | **2,109 ns** | **2,132 ns** |
| raw OpenSSL legacy stream 固定成本（探针） | 302 ns | 340 ns |
| ⇒ **JNI + Java 侧** | **1,807 ns（占 86%）** | **1,792 ns（占 84%）** |

★ **独立交叉验证（不是同源数字）**：§10.11 在 build-01 上实测**一次 JNI 往返 ≈ 863 ns**
（老的一次性 HMAC 调用 vs 新的两次 `update+finish` ⇒ 差值 863 ns）。
native 流式摘要是 **2 次 JNI 调用** ⇒ 2 × 863 = **1,726 ns**
⇒ 与上面相减得到的 1,807 ns **差 4.5%** ⇒ **模型闭合**。

⇒ ★★★ **SPARC 上 native 臂的固定成本里，OpenSSL 只占 14%（302 / 2,109），86% 是 JNI + Java 侧；
而 JNI 侧基本就是「2 次往返 × 863 ns」**。
⇒ ★★ **SPARC 一次 JNI 往返 ≈ 863 ns，而 macOS 上一次 JNI 数组访问只要 55~102 ns**（§10.14.7）
⇒ **SPARC 的 JNI 往返贵 ~8~16 倍**。
⇒ ★★★ **这是本轮最有价值的一条（对所有 JNI 加速项都成立）**：
**在 SPARC 上「少调一次 JNI」（~863 ns）比「把算法本体优化 20%」值钱得多**
⇒ 这正是流式化在 SPARC 上大赢、而每字节优化（-0.05 ns/B）无关紧要的原因。

**★ 结论 3：每字节成本不是问题**

| | native（JMH） | raw OpenSSL stream | 差 |
|---|---|---|---|
| MD5 | 1.1447 ns/B | 1.0933 ns/B | **+0.0514 ns/B（+4.7%）** |
| SHA-256 | 1.1527 ns/B | 1.1093 ns/B | **+0.0434 ns/B（+3.9%）** |

★ 源码侧可确认**这不是拷贝**：Java 侧 `NativeDigestProvider` 直接把数组交给
`binding.update(...)`（**无 `arraycopy`**），大载荷走 `GetPrimitiveArrayCritical`（**不拷贝**）
⇒ **+0.05 ns/B 的成因未定位**（≤4.7%，等效 ~19.5 GB/s，不影响任何结论）。

**★★★ 附带一条必须修正的旧结论（§10.11.2）**

★ **「OpenSSL 一次性 API 有 ~7μs 固定开销」只对 SHA-256 成立，对 MD5 不成立**：

| @64B | MD5 | SHA-256 |
|---|---|---|
| oneshot（`MD5()` / `SHA256()`） | **413.0 / 410.7** | **7525.2 / 7509.8** |
| stream（`Init/Update/Final`） | **371.9 / 383.1** | **411.0 / 411.7** |
| EVP | 6949.8 / 6974.9 | 7209.8 / 7215.0 |
| ⇒ **oneshot / stream** | **1.11x / 1.07x** | **18.3x / 18.2x** |

（每格「第一批 / 第二批」⇒ 两批一致 ⇒ 这条修正不是单轮现象。）

⇒ **§10.11.2 的根因结论（一次性 API 也走 provider dispatch）只对 SHA-256 成立**；
MD5 的 `MD5()` 是**直接调用**，不但没有 7μs 税，反而比流式还贵一点（多一次 provider fetch）。
⇒ ★ **对 edap 的直接含义**：
① native 摘要走 legacy 流式 ⇒ 对 **SHA-256 是必须的**（否则白付 ~7μs），
对 **MD5 几乎无差**（413 vs 372，差 11%）⇒ **流式化的收益只属于 SHA-256 / HMAC**；
② **MD5 若改回一次性 API 省不到什么**。
⇒ ★ 这也把 §10.11.2 里「老 native sha256@64B 反而比 native hmacsha256@64B 慢 7.1μs」彻底解释掉：
**那 7.1μs 就是 SHA-256 一次性 API 的 provider dispatch 税**（HMAC 路径不走它）。

✅ **该探针文件已提交进 git**：commit **`6e0d9e3a`「添加md5和sha256对比的c程序」**
（2026-11-19 11:04，+312 行）⇒ **新克隆/新机器上都能跑这个探针了**。

##### ★ 复跑

```bash
# build-01 上（Solaris）
cd edap-crypto && ./scripts/md5-bench.sh 5
# 同批 SHA-256 对照（做「MD5 vs SHA-256」的同窗口横向比）
BENCH_CLASS=NativeVsJceBenchmark BENCH_TAG=sha256ctl2 \
SUMMARY_PY=native-vs-jce-summary.py BENCH_INCLUDE='.*Digest.*' \
./scripts/md5-bench.sh 5
```

**数据文件**（SPARC 那批已于 2026-11-19 从 build-01 拷回本地仓库）：

| 批次 | 文件 | 说明 |
|---|---|---|
| **定稿** | `doc/bench/md5-20261119-091606-r<N>.json`、`summary-md5-20261119-091606.md`、`bench-md5-20261119-091606.log` | 新 `.o`，MD5 |
| **定稿对照** | `doc/bench/sha256ctl2-20261119-093215-r<N>.json`、`summary-sha256ctl2-20261119-093215.md`、`bench-sha256ctl2-20261119-093215.log` | 同窗口 SHA-256 |
| **C 探针（第二批）** | `doc/bench/probe-md5-vs-sha256-build01.txt` | raw OpenSSL 探针原始 stdout（**不经 JNI/JVM**） |
| 对照（旧 `.o`） | `doc/bench/md5-20261118-223533-r<N>.json`、`summary-md5-20261118-223533.md`、`bench-md5-20261118-223533.log` | 三项优化**之前**的 native 臂 —— ★ **仍在 build-01 上，未拷回** |
| macOS 批次 C | `doc/bench/md5-20261119-091540-r<N>.json`、`summary-md5-20261119-091540.md`、`bench-md5-20261119-091540.log` | **本机**，与 SPARC 同一天 |

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
| R-08 | **edap-crypto → edap-log-api ⇒ 跨模块类初始化环 ⇒ 全应用退化成 NopLogger** | edap-crypto **不依赖 edap-log-api**（§1.4）；告警走 `CryptoDiagnostics`（§4.2）；`Md5Util` 有裸 JCE 兜底；两道回归测试守着（常量池扫描 + 独立 ClassLoader bootstrap） |
| R-09 | **edap-crypto 位于日志 bootstrap 路径 ⇒ crypto 层故障会波及日志系统** | `Md5Util` 捕获 crypto 层的 `RuntimeException` 后退回裸 JCE，并把问题打到 stderr；结果逐字节相同 |

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
- ~~**native 在「小 payload + 高并发」下会输给 JCE**~~ ⇒ **已作废**（流式化前实测
  SHA-256@64B/16t 只有 **0.24x**，§10.3）。★ 流式化后复测为 **1.04x**（§10.12），
  ★ **SPARC 上从未成立**（native 全档赢 1.22x~6.11x，§10.13）
  ⇒ **不再需要**为「大量小包 + 高并发」而设 `edap.crypto.native=false`。
- ~~**Solaris 上的并发数尚未复测**~~ ⇒ **已补齐**（§10.13，build-01 实测：
  24 格全部 native 赢；1t 档与 §10.11.3 的独立测试台逐格互证）。
- ★ **MD5 的选型结论按平台给，且依赖 JCE provider 归属**：
  macOS（JCE = SUN 纯 Java）上 MD5 小载荷 **native 输 0.43x**（三批一致）；
  build-01/SPARC（1t 档 **native 赢 1.27x~3.09x**、4t 档最高 **11.13x**）上则全赢（§10.15.7）。
  ⇒ **MD5 走 native 只在目标平台成立**；
  ★ SPARC 那一侧的机制解释**已全部闭环**：① provider 归属（= OracleUcrypto，
  不是纯 Java）② `.o` 新旧（已用 gcc 重建并重跑，定稿批次）
  ③ **MD5/SHA-256 每字节成本异常**（= 这个 OpenSSL 在 SPARC 上两算法都没有汇编）
  ④ **native 固定成本里 JNI 与 OpenSSL 的占比**（= OpenSSL 只占 14%，JNI+Java 侧占 86%）
  ⇒ 四项均已由 build-01 raw OpenSSL 探针**两批独立复跑**闭合（§10.15.7）
  ⇒ ⇒★ **由此得到一条通用结论**：SPARC 一次 JNI 往返 ≈ 863 ns（macOS 只 55~102 ns）
  ⇒ **在 SPARC 上少调一次 JNI，比把算法本体优化 20% 值钱得多**。
- **§10.2~§10.4 的 JMH 批次（`20261104-214643` / `20261104-231103`）是在高 loadavg
  （19.77~77.99，核数的 2~8 倍）下跑的** ⇒ 绝对值不可外推，只看同批次内的相对关系；
  §10.5 的探针是另一次独立运行，两者的绝对值不可混用。
- ★★ **本机（Mac，10 核）不能当基准机**：常驻外部负载 **loadavg 16~37**
  （Spotlight 索引 `mds_stores` 53% CPU、IntelliJ 50%、WindowServer 27%），
  且负载是**突发型**的 ⇒ 在 16 线程超订档会把 per-op 延迟**整体平移一档**。
  ⇒ **「native arm 高并发偶发慢轮」的真因已定位（§10.14）**：外部 CPU 争用，
  **不是 native 路径的代码缺陷**（干预实验：加 6 个 CPU burner ⇒ native 臂 +29%、
  JCE 臂 +16%，两臂都会抖）。⇒ 读本文件里的并发档数字前，**先看 loadavg**。
