# edap-native JNI 加速库设计

> 跨模块 JNI 加速库，为 edap 内部热路径（JWT HS256 等）提供 OpenSSL 后端的 native 实现。
> 详见 `edap-auth-jwt/doc/JWT_DESIGN.md §5.6` 应用层集成与收益数据。

---

## 一、定位与设计目标

### 1.1 解决什么问题

edap 框架内部涉及大量 JWT verify（WS 握手 + HTTP 鉴权 + OAuth2 token 验证），单节点 QPS 峰值 50w+。JCE `Mac` 在 1-4 线程典型业务场景下仍占 1.5-10 μs/verify。`edap-native` 通过 JNI 直调 OpenSSL libcrypto 把延迟压到 1.4-2.2 μs（1-4 线程），并以"edap-native 在 classpath + 平台 .o 就绪 → 默认启用"的零配置方式集成。

### 1.2 设计原则

| 原则 | 体现 |
|---|---|
| **零硬依赖** | edap-auth-jwt 通过反射 / MethodHandle 加载 edap-native；不引入 edap-native 也能工作（自动 fallback 到 JCE） |
| **静默 fallback** | `Native.loadLibrary()` 加载失败 → `ENABLE_NATIVE=false` → 应用层 `isAvailable()` 走 Java |
| **平台白名单** | 仅 macOS aarch64（当前）；其他平台不引入 .o 资源，加载即失败 |
| **不改 API** | 对上层暴露等价 `Algorithm` 接口（Java / native 二选一，调用方无感） |

### 1.3 模块依赖图

```
edap-native                       (跨模块 JNI 加速库)
└─ io.edap.jni
   ├─ Native.java                 (加载入口：os.arch 解析 → System.load .o)
   └─ crypto
      └─ NativeHmacSha256.java    (Tier 1.5: HMAC_CTX 每线程缓存 + EVP_sha256() 进程级缓存)

edap-auth-jwt                     (应用层，零硬依赖)
└─ io.edap.auth.jwt.algorithm
   └─ HmacSha256.java              (统一入口 — 构造期自动委托 nested HmacSha256Native / HmacSha256Java)
      ├─ HmacSha256Native          (nested public static class — MethodHandle 加载 NativeHmacSha256)
      └─ HmacSha256Java             (nested private static class — 纯 JDK ThreadLocal<Mac> fallback)
```

---

## 二、平台支持

| os | arch | .o 文件 | 状态 |
|---|---|---|---|
| macOS | aarch64 | `edap-native-macos_aarch64.o` | ✅ 已落地 |
| macOS | x86_64 | `edap-native-macos_x86_64.o` | 待编译 |
| Linux | x86_64 | `edap-native-linux_x86_64.o` | 待编译 |
| Linux | aarch64 | `edap-native-linux_aarch64.o` | 待编译 |

不在以上平台 → `Native.ENABLE_NATIVE=false` → 自动 fallback JCE。加载逻辑见 `Native.java:60-93`。

---

## 三、加载机制

`Native.loadLibrary()` 幂等（双检锁 + `initialized` 标志）：

1. 读 `os.name` / `os.arch` → 映射到 `edap-native-{os}_{arch}.o` 资源名
2. `getResourceAsStream` 拿 .o bytes → 写到 temp 文件 → `System.load(absPath)` → `tmp.delete()`
3. 成功 → `ENABLE_NATIVE=true`；失败 → `ENABLE_NATIVE=false`（不影响启动）

**为什么不直接 `System.loadLibrary` 用 java.library.path**：

- 预编译 .o 而非 .so/.dylib，省去 link libcrypto 步骤（依赖运行环境）
- 资源打进 jar，部署友好（单 jar 即可，运行时不需要 -Djava.library.path）
- 平台隔离：一个 jar 同时含多个平台 .o，运行时按 os.arch 选

---

## 四、当前 API

### 4.1 `NativeHmacSha256`（JNI 入口）

```java
public class NativeHmacSha256 {
    public NativeHmacSha256(byte[] key);          // 拷贝 key；throw if !ENABLE_NATIVE
    public byte[] sign(byte[] data, int offset, int len);
    private static native byte[] sign0(byte[] key, byte[] data, int offset, int len);
}
```

设计要点：

- `key` 不可变（构造期拷贝），instance 持有自己的 `byte[] key`
- `sign()` 单次调用把 key + data 一起交给 native → stateless，多线程并发安全
- 异常路径：构造期 `ENABLE_NATIVE=false` 抛 `UnsupportedOperationException`；sign 失败抛 `RuntimeException`

### 4.2 `HmacSha256.HmacSha256Native`（Java 侧 MethodHandle 桥 — nested class）

```java
// 在 HmacSha256 类内作为 nested public static class：
public static class HmacSha256Native implements Algorithm {
    public static boolean isAvailable();          // 见 §3 + isAvailable 检测顺序
    public HmacSha256Native(String key);          // findConstructor + findVirtual + bindTo
    public byte[] sign(byte[] data, int offset, int len);  // signHandle.invokeExact
}
```

**完整调用链**（`HmacSha256` 构造期自动委托）：

```java
public class HmacSha256 implements Algorithm {
    private static final boolean NATIVE_AVAILABLE = HmacSha256Native.isAvailable();

    public HmacSha256(String key) {
        this.delegate = NATIVE_AVAILABLE
                ? new HmacSha256Native(key)   // nested class，自动获 native 加速
                : new HmacSha256Java(key);    // private fallback
    }
}
```

外部走法（按需选择）：

| 场景 | 用法 |
|---|---|
| 默认（自动委托） | `new HmacSha256(key)` |
| 强制走 native | `new HmacSha256.HmacSha256Native(key)` |
| 强制走 JDK | `-Dedap.jwt.hmac.native=false` |
| 替换默认工厂 | `AlgorithmRegistry.register("HS256", HmacSha256.HmacSha256Native::new)` |

`isAvailable()` 检测顺序（关键！）：

```java
// 1. -Dedap.jwt.hmac.native=false|disable|off  → 显式禁用
// 2. Class.forName(NATIVE_CLASS) 触发其 static init
//    → 内部 Native.loadLibrary() → 加载成功后 ENABLE_NATIVE=true
// 3. Native.ENABLE_NATIVE == true ?
//    → 1/2 通过 + 3 true 才走 native；任一失败静默 fallback 到 HmacSha256Java

// ⚠️ 必须先 Class.forName(NATIVE_CLASS) 触发 static init，
// 否则直接读 ENABLE_NATIVE 会拿到初始 false，永远走 Java。
```

---

## 五、性能基线

### 5.1 测得数据（macOS aarch64 + JDK 17 + JMH 1.37，`HmacSha256NativeBenchmark`，**Tier 2 落地后**）

| 场景 | payload | 线程 | javaMac（Mac+ThreadLocal） | nativeHmac（手动 HMAC-SHA256，无 provider dispatch） | 倍数 |
|---|---|---|---|---|---|
| 单线程 | 100B | 1 | 1.503 μs | 0.493 μs | **3.05x** |
| 单线程 | 500B | 1 | 3.216 μs | 0.653 μs | **4.92x** |
| 单线程 | 2000B | 1 | 10.074 μs | 1.289 μs | **7.81x** |
| 4 线程 | 100B | 4 | 1.678 μs | 0.511 μs | **3.28x** |
| 4 线程 | 500B | 4 | 3.451 μs | 0.669 μs | **5.16x** |
| 4 线程 | 2000B | 4 | 10.482 μs | 1.325 μs | **7.91x** |
| 16 线程 | 100B | 16 | 3.266 μs | 0.980 μs | **3.33x** |
| 16 线程 | 500B | 16 | 6.758 μs | 1.283 μs | **5.27x** |
| 16 线程 | 2000B | 16 | 20.681 μs | 2.511 μs | **8.24x** |

**vs Tier 1.5 基线**（Tier 2 前 16t 仍 ~12.4 μs/op 反退 Java）：

| | Tier 1.5 | Tier 2（手工 HMAC） | 倍数 |
|---|---|---|---|
| 1t 2000B | 2.191 μs | 1.289 μs | **1.70x** |
| 4t 2000B | 3.564 μs | 1.325 μs | **2.69x** |
| 16t 2000B | 12.738 μs | 2.511 μs | **5.07x** |

16 线程从"反退 Java 0.6x"变成"8.24x 超越 Java"。Native 现在随线程扩展接近线性（1t→4t 仅 1.03x 退化、4t→16t 仅 1.89x 退化），不再有反退曲线。

完整 18 行 throughput + 18 行 latency 见 `edap-auth-jwt/doc/JWT_DESIGN.md §5.6` 收益估算表。

### 5.2 MethodHandle vs 反射（实测）

两者在 ±3% 误差棒内打平。JDK 17 JIT 已把 `Method.invoke` 内联到接近 `invokeExact` 性能。保留 MethodHandle 不是为了 perf，是为了代码质量（无 checked exception 拆包、签名直接、未来可挂 `asType`/`filterArguments`）。

### 5.3 16t 反退（设计 bug）

**反直觉**：1-4 线程 native 显著胜（2-4.5x），16 线程 native 反而比 Java 慢 2-4x。

| 16t 100B vs 2000B | native | java |
|---|---|---|
| 差值 | 362ns | 17.3 μs |

native 16t 几乎不随 payload 增长 → 强证据瓶颈是**固定开销**，不是 HMAC 计算。有两个独立瓶颈源：

1. **JVM 侧 per-call STW/GC 压力**（详见 §6.1）—— 优先修
2. **OpenSSL 3.x `HMAC()` 内部锁**（`EVP_sha256()` fetch + `EVP_MD_CTX` alloc/free）—— 已记 backlog，详见 `edap-auth-jwt/doc/JWT_DESIGN.md §5.6 优化路径`

---

## 六、JNI 主路径优化（§15.3 主战场）

> 这部分是当前 `io_edap_jni_crypto_NativeHmacSha256.c` 的修订版本。第一版落地后就更新到这里。

### 6.1 当前实现的 per-call 开销

`io_edap_jni_crypto_NativeHmacSha256.c:16-52` 每次调用触发的 JVM 动作：

| 操作 | JVM 内部动作 | 16 线程影响 |
|---|---|---|
| `GetByteArrayElements(key, NULL)` | safepoint + GC write barrier（dirty card） | ⚠️ STW |
| `GetArrayLength(key)` | 又一个 JNI 调用，safepoint | ⚠️ STW |
| `GetByteArrayElements(data, NULL)` | safepoint + dirty card | ⚠️ STW |
| `HMAC(...)` | 纯 native | ✅ |
| `ReleaseByteArrayElements(key, JNI_ABORT)` | dirty card + unpin | ⚠️ STW |
| `ReleaseByteArrayElements(data, JNI_ABORT)` | dirty card + unpin | ⚠️ STW |
| `NewByteArray(32)` | TLAB 分配 + GC write barrier | ⚠️ GC 压力 |
| `SetByteArrayRegion(...)` | 拷贝 32B 到新数组 + dirty card | ⚠️ STW |

**6-7 次 STW + 1 次堆分配 / 调用**。16 线程并发时，所有线程同时进 safepoint，互相串行化，JVM 频繁触发 young GC，反过来又触发更多 safepoint。**经典正反馈死循环**。

**为什么 `EVP_MD_CTX` 复用不是第一步**：per-call STW / GC 压力是固定开销，跟 key 是否复用无关。即使 key 完全不变，16 线程也会卡在 STW 上。`GetPrimitiveArrayCritical` + 减分配 才是第一步，`HMAC_CTX` 复用（OpenSSL 侧锁优化）是第二步。

### 6.2 Tier 1（immediate）：`GetPrimitiveArrayCritical` + 传 `keyLen`

**目标**：6-7 次 STW → 3 次 STW + 1 次 alloc；省 dirty card write + 省 GetArrayLength safepoint。

**修改**（`io_edap_jni_crypto_NativeHmacSha256.c`）：

```c
JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeHmacSha256_sign0
  (JNIEnv *env, jclass cls,
   jbyteArray key, jint keyLen,        // 改成传 keyLen，省掉 GetArrayLength
   jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL) return NULL;

    /* Critical section 一次性 pin 两个数组，期间禁用 GC。
       关键：critical 区间不能调其他 JNI 函数，也不能抛异常。
       我们的 HMAC 是纯 native，正好满足。 */
    jbyte *keyPtr  = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, key,  NULL);
    if (keyPtr == NULL) return NULL;

    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);
        return NULL;
    }

    unsigned char result[32];
    unsigned int resultLen = 0;

    /* OpenSSL HMAC() one-shot 仍在 critical section 内。
       32B 栈分配没问题，没有 native heap allocation。 */
    int rc = HMAC(EVP_sha256(),
                  (const unsigned char *)keyPtr,             (size_t)keyLen,
                  (const unsigned char *)(dataPtr + offset), (size_t)len,
                  result, &resultLen);

    /* 立即 release，反向顺序 */
    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, key,  keyPtr,  JNI_ABORT);

    if (rc != 1) return NULL;

    /* 分配在 critical section 之外，不影响 GC */
    jbyteArray ret = (*env)->NewByteArray(env, (jsize)resultLen);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)resultLen, (const jbyte *)result);
    }
    return ret;
}
```

**关键变化对照**：

| 改动 | 收益 |
|---|---|
| `GetByteArrayElements` → `GetPrimitiveArrayCritical` | 省 dirty card write（2 次→0）+ 省可能 copy |
| 传 `keyLen` 代替 `GetArrayLength` | 省 1 次 safepoint |
| 不在 critical section 里调 JNI | 符合规范，JIT 不会展开 |
| 反向 `Release` | 配对安全，避免错乱 |
| `NewByteArray` 在 critical 外 | 不影响其他线程 GC 行为 |

**预期**：16 线程从 "1 个调用 6-7 个 STW" 降到 "1 个调用 3 个 STW + 1 个 alloc"。

**实际效果**（2026-08，macOS aarch64 + JDK 17 + JMH 1.37，avgt us/op）：

| 场景 | Tier 0（旧） | Tier 1（新） | Δ |
|---|---|---|---|
| 1t 100B | 1.378 | 1.274 | **-7.5%** |
| 1t 500B | 1.565 | 1.411 | **-9.8%** |
| 1t 2000B | 2.191 | 2.041 | **-6.8%** |
| 4t 100B | 2.904 | 2.645 | -8.9% |
| 4t 500B | 3.074 | 2.734 | -11.1% |
| 4t 2000B | 3.564 | 3.238 | -9.1% |

1-4 线程 7-11% 提升，绝对值降 100-330 ns/调用。

**16t 仍未解**（avgt us/op）：

| 场景 | Tier 0 | Tier 1 | Δ |
|---|---|---|---|
| 16t 100B | 12.376 | 12.510 | +1.1% |
| 16t 500B | 12.709 | 12.413 | -2.3% |
| 16t 2000B | 12.738 | 12.746 | +0.1% |

Tier 1 仅解 JVM 侧 per-call 开销，**16t 真瓶颈在 OpenSSL `HMAC()` 内部锁**（`EVP_sha256()` fetch + `EVP_MD_CTX` new/free），见 §6.5。

**Java 侧同步**：`Algorithm` 接口（`sign(byte[] data, int offset, int len)`）和 `NativeHmacSha256.sign(...)` 签名不变；但 JNI 内部 `sign0` 从 `(key, data, offset, len)` 改为 `(key, keyLen, data, offset, len)`，C 头文件同步。

### 6.3 Tier 2：消除分配（`NewByteArray` + `SetByteArrayRegion` → 0）

`NewByteArray` + `SetByteArrayRegion` 每次 32B 分配。16 线程高 QPS 下是 GC 压力的最大来源。

**路线 A：改 API，caller 提供 out buffer**（推荐）

```java
// Java
public static native int sign(byte[] key, byte[] data, int offset, int len, byte[] out);
// 返回写入字节数（32），out 由 caller 复用
```

```c
JNIEXPORT jint JNICALL Java_..._sign1(
    JNIEnv *env, jclass cls,
    jbyteArray key, jint keyLen,
    jbyteArray data, jint offset, jint len,
    jbyteArray out)
{
    jbyte *keyPtr  = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, key,  NULL);
    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    jbyte *outPtr  = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, out,  NULL);
    if (!keyPtr || !dataPtr || !outPtr) { /* cleanup */ return -1; }

    unsigned int outLen = 0;
    int rc = HMAC(EVP_sha256(),
                  (const unsigned char*)keyPtr, (size_t)keyLen,
                  (const unsigned char*)(dataPtr + offset), (size_t)len,
                  (unsigned char*)outPtr, &outLen);

    (*env)->ReleasePrimitiveArrayCritical(env, out,  outPtr,  0);     // 写回
    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, key,  keyPtr,  JNI_ABORT);

    return rc == 1 ? (jint)outLen : -1;
}
```

**0 分配**，签名结果写到 caller 提供的 32B buffer。

**路线 B：Java 侧 ThreadLocal 复用**（有线程安全风险，不推荐）

```java
private static final ThreadLocal<byte[]> MAC_BUF = ThreadLocal.withInitial(() -> new byte[32]);

public static byte[] sign(byte[] key, byte[] data) {
    byte[] out = MAC_BUF.get();
    int n = sign0(key, data, 0, data.length, out);
    return out;  // ⚠️ 这块 buffer 是共享的，caller 不能缓存，也不能跨线程传
}
```

caller 意识不到返回数组是线程共享的，极易误用。**除非 caller 全栈内部用**，否则不推荐。

### 6.4 Tier 3：批量签名（合并 JNI 调用）

如果 16 线程都在做 "一次签 N 条 entry" 的场景（典型：Raft AE、心跳批量），把 N 条合成一次 JNI 调用：

```c
JNIEXPORT jint JNICALL Java_..._signBatch(
    JNIEnv *env, jclass cls,
    jbyteArray key, jint keyLen,
    jbyteArray data,        // 连续 N 条 entry 拼起来的大 buffer
    jintArray offsets, jintArray lens,    // N 个 [offset, len] 对
    jbyteArray out)                       // N * 32 bytes 输出
{
    // 一次性 pin，一次性 HMAC_init，N 次 update + 一次 final
    // ...
}
```

N 次 JNI 调用合并成 1 次，per-call 固定开销摊到 N 条，Raft AE 场景 10-50x 提速。

**前置依赖**：Tier 1 + Tier 2 落地后，per-call 成本已经够低再考虑。否则批量 API 收益有限（瓶颈不在 per-call）。

### 6.5 Tier 1.5（立即做）：OpenSSL 内部锁优化

> 解决 16t 反退。Tier 1 落地后实测 16t 仍 ~12.4 μs/op 不随 payload 增长 —— 强证据瓶颈在 OpenSSL 内部。这步是必须做的"心理惦记"。

**瓶颈再确认**（OpenSSL 3.x `HMAC()` one-shot 内部三处锁）：

1. **`EVP_sha256()` 全局 fetch**：`#define EVP_sha256() EVP_MD_fetch(NULL, "SHA256", NULL)`，每次调用都过 `CRYPTO_THREAD_read_lock` 全局算法注册表
2. **`EVP_MD_CTX_new()` / `EVP_MD_CTX_free()`** 每次调用 alloc + init + free，堆分配器在 16t 有锁争抢
3. **`EVP_MD_CTX_new()` 内部还需访问 provider**（context 持有 libctx 引用），与 (1) 锁路径叠加

**修改方案**（`io_edap_jni_crypto_NativeHmacSha256.c`）：

```c
#include <pthread.h>

/* 进程级 cache EVP_sha256() */
static const EVP_MD *g_sha256_md = NULL;
static pthread_once_t g_sha256_once = PTHREAD_ONCE_INIT;
static void init_sha256_md(void) { g_sha256_md = EVP_sha256(); }

/* 每线程 cache HMAC_CTX */
static pthread_key_t g_hmac_ctx_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
static void hmac_ctx_destructor(void *arg) {
    HMAC_CTX *ctx = (HMAC_CTX *)arg;
    if (ctx) HMAC_CTX_free(ctx);
}
static void init_hmac_ctx_key(void) {
    pthread_key_create(&g_hmac_ctx_key, hmac_ctx_destructor);
}
static HMAC_CTX *get_hmac_ctx(void) {
    pthread_once(&g_key_once, init_hmac_ctx_key);
    HMAC_CTX *ctx = (HMAC_CTX *)pthread_getspecific(g_hmac_ctx_key);
    if (ctx == NULL) {
        ctx = HMAC_CTX_new();
        if (ctx != NULL) pthread_setspecific(g_hmac_ctx_key, ctx);
    }
    return ctx;
}

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeHmacSha256_sign0
  (JNIEnv *env, jclass cls,
   jbyteArray key, jint keyLen,
   jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL) return NULL;

    pthread_once(&g_sha256_once, init_sha256_md);
    if (g_sha256_md == NULL) return NULL;

    HMAC_CTX *ctx = get_hmac_ctx();
    if (ctx == NULL) return NULL;

    /* Tier 1：critical section */
    jbyte *keyPtr  = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, key,  NULL);
    if (keyPtr == NULL) return NULL;
    jbyte *dataPtr = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);
        return NULL;
    }

    unsigned char result[EVP_MAX_MD_SIZE];
    unsigned int resultLen = 0;

    /* Tier 1.5：HMAC_CTX 复用 */
    int rc = HMAC_CTX_reset(ctx);
    if (rc == 1) rc = HMAC_Init_ex(ctx, keyPtr, keyLen, g_sha256_md, NULL);
    if (rc == 1) rc = HMAC_Update(ctx, (const unsigned char *)(dataPtr + offset), (size_t)len);
    if (rc == 1) rc = HMAC_Final(ctx, result, &resultLen);

    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, key,  keyPtr,  JNI_ABORT);

    if (rc != 1) return NULL;

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)resultLen);
    if (ret != NULL) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)resultLen, (const jbyte *)result);
    }
    return ret;
}
```

**关键变化对照**：

| 改动 | 收益 |
|---|---|
| `static const EVP_MD *g_sha256_md` + `pthread_once` 进程级 cache | 避免每次 `EVP_sha256()` 走 provider dispatch + 全局锁 |
| `pthread_key_t` 每线程 cache `HMAC_CTX` | 避免每次 `EVP_MD_CTX_new/free` + 内部 provider 访问 |
| `HMAC_CTX_reset` + `HMAC_Init_ex/Update/Final` 替换 `HMAC()` one-shot | 复用 ctx 内部状态，省 SHA-256 子状态初始化 |
| `pthread_key` destructor 释放 ctx | 线程退出时自动清理，无泄漏 |

**预期效果**：16t ~12.4 μs/op → 期望 ~2-3 μs/op（接近 java 线程本地 `Mac` 水平），1t 也受益（process-level cache 命中，避免 fetch）。

**OpenSSL 3.x deprecation warning**：`HMAC_*` 系列在 OpenSSL 3.0 标记 deprecated（推荐 `EVP_MAC_*`），但仍然可用且短期不会移除。短期接受 warning，长期可重写到 `EVP_MAC_CTX` API —— 收益未必更大（同样要走 provider dispatch），优先级低。

**pthread 回收问题**：JVM thread pool 复用线程，`pthread_key` destructor 只在 pthread 真正退出时调用，对线程池无影响。Loom 虚拟线程场景下 destructor 时机取决于 carrier thread 生命周期，目前不专门处理。

### 6.6 Tier 2（实际落地）：绕开 OpenSSL 3.x provider dispatch

> Tier 1.5 落地后 16t 仍 ~12.4 μs/op 反退 Java。JMH 数据已确认 Tier 1.5 进程级 `EVP_sha256()` cache 没生效。本节是 16t 真正的修复路径。

#### 6.6.1 profile 实证（macOS `sample` 抓 16t 测量阶段 30s）

| 帧 | 占比/线程 | 含义 |
|---|---|---|
| `Java_..._sign0` | 39.5% | JNI 入口（含所有调用） |
| `HMAC_Init_ex` | **17.5%** | **关键瓶颈** |
| ↳ `evp_md_init_internal` | 16.0% | OpenSSL 3.x provider dispatch |
| ↳ ↳ `inner_evp_generic_fetch` | 15.1% | 算法注册表 fetch |
| ↳ ↳ ↳ `ossl_method_store_cache_get` | 12.1% | method store 缓存查找 |
| ↳ ↳ ↳ ↳ `pthread_rwlock_rdlock` | 4.1% | provider 注册表读锁 |
| ↳ ↳ ↳ ↳ `pthread_rwlock_unlock` | 7.9% | provider 注册表解锁 |
| `HMAC_Final` | 2.6% | 收尾（含 final 调用 dispatch） |
| `HMAC_Update` | <0.1% | 数据 update（2000B 不大） |
| GC 线程 | 0.01% | **不是 GC 问题** |

**关键发现**：`EVP_sha256()` cache 完全没用 —— dispatch 发生在 `HMAC_Init_ex` 内部调用的 `evp_md_init_internal(md)`，跟传入的 md 是否 cached 无关：

```c
// OpenSSL 3.x HMAC_Init_ex 内部（简化）
int HMAC_Init_ex(HMAC_CTX *ctx, const void *key, int key_len,
                 const EVP_MD *md, ENGINE *impl) {
    ...
    ctx->md = md;                          // 直接赋值（不 dispatch）
    ...
    if (!evp_md_init_internal(ctx->md))    // ← 每次都触发 dispatch
        return 0;
    ...
}

// evp_md_init_internal 内部
static int evp_md_init_internal(EVP_MD *md) {
    if (md->prov != NULL && md->prov->dbs != NULL)
        return evp_generic_fetch(md->prov, ..., md);  // ← 16t 瓶颈
    return 1;
}
```

OpenSSL 3.x 默认 provider 的 SHA-256 实现 `md->prov != NULL && md->prov->dbs != NULL`（dispatch table 存在），所以即便传 cached md 也会走 provider dispatch。

**多线程放大**：16 个线程并发 `pthread_rwlock_rdlock` + atomic ref-count（`evp_md_up_ref`）共享同一 cache line，单线程 16% 的开销在 16t 通过锁竞争 + atomic bouncing 进一步放大。

#### 6.6.2 修复策略：绕开 OpenSSL HMAC，改用 legacy SHA-256

OpenSSL 1.1 风格的 legacy `SHA256_Init/Update/Final`（deprecated in 3.0 但仍可用）是**直接 C 函数**，不走 provider dispatch，无锁、无 atomic、无 hash table lookup。OpenSSL 3.x 把它们标记 deprecated 是因为推荐 `EVP_MAC_*` 新 API（同样走 provider dispatch，无收益）。

手工展开 HMAC：
```
HMAC(K, m) = H((K xor opad) || H((K xor ipad) || m))
ipad = 0x36 * 64, opad = 0x5c * 64
K' = K (len ≤ 64) | SHA256(K) (len > 64) + zero-pad to 64
```

每调用成本：~35 个 SHA-256 block compression（2000B payload），约 ~1750 cycles / 3GHz = ~580ns pure compute + ~1μs JNI overhead ≈ 2-3μs/thread。

#### 6.6.3 落地代码（`io_edap_jni_crypto_NativeHmacSha256.c`，已替换）

```c
#include <jni.h>
#include <openssl/sha.h>   /* legacy SHA256_{Init,Update,Final} — direct C, no provider dispatch */
#include <string.h>

#define SHA256_BLOCK_SIZE 64
#define SHA256_DIGEST_SIZE 32

JNIEXPORT jbyteArray JNICALL Java_io_edap_jni_crypto_NativeHmacSha256_sign0
  (JNIEnv *env, jclass cls,
   jbyteArray key, jint keyLen,
   jbyteArray data, jint offset, jint len)
{
    if (key == NULL || data == NULL || keyLen < 0 || offset < 0 || len < 0)
        return NULL;

    /* key critical section：复制到栈上 k_pad[64] 后立即 release（key > 64 时需在 critical 内 hash） */
    jbyte *keyPtr = (*env)->GetPrimitiveArrayCritical(env, key, NULL);
    if (keyPtr == NULL) return NULL;

    unsigned char k_pad[SHA256_BLOCK_SIZE];
    if (keyLen > SHA256_BLOCK_SIZE) {
        SHA256((const unsigned char *)keyPtr, (size_t)keyLen, k_pad);
        memset(k_pad + SHA256_DIGEST_SIZE, 0, SHA256_BLOCK_SIZE - SHA256_DIGEST_SIZE);
    } else {
        memcpy(k_pad, keyPtr, (size_t)keyLen);
        memset(k_pad + keyLen, 0, SHA256_BLOCK_SIZE - keyLen);
    }
    (*env)->ReleasePrimitiveArrayCritical(env, key, keyPtr, JNI_ABORT);

    /* data critical section：HMAC 计算 */
    jbyte *dataPtr = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (dataPtr == NULL) return NULL;

    unsigned char inner_pad[SHA256_BLOCK_SIZE], outer_pad[SHA256_BLOCK_SIZE];
    for (int i = 0; i < SHA256_BLOCK_SIZE; i++) {
        inner_pad[i] = k_pad[i] ^ 0x36;
        outer_pad[i] = k_pad[i] ^ 0x5c;
    }

    SHA256_CTX inner;
    SHA256_Init(&inner);
    SHA256_Update(&inner, inner_pad, SHA256_BLOCK_SIZE);
    SHA256_Update(&inner, (const unsigned char *)(dataPtr + offset), (size_t)len);
    unsigned char inner_hash[SHA256_DIGEST_SIZE];
    SHA256_Final(inner_hash, &inner);

    SHA256_CTX outer;
    SHA256_Init(&outer);
    SHA256_Update(&outer, outer_pad, SHA256_BLOCK_SIZE);
    SHA256_Update(&outer, inner_hash, SHA256_DIGEST_SIZE);
    unsigned char result[SHA256_DIGEST_SIZE];
    SHA256_Final(result, &outer);

    (*env)->ReleasePrimitiveArrayCritical(env, data, dataPtr, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)SHA256_DIGEST_SIZE);
    if (ret != NULL)
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)SHA256_DIGEST_SIZE, (const jbyte *)result);
    return ret;
}
```

**关键变化**：
- 删除 `EVP_sha256()` 进程级 cache + `pthread_key_t` HMAC_CTX（Tier 1.5 那套不再需要）
- 删除所有 OpenSSL HMAC 调用 → 零 provider dispatch
- key 早 release（已 memcpy 到栈上），data critical 区间只覆盖 HMAC compute

**未优化的可选项**（按需）：
- per-thread cache `k_pad + ipad + opad` 省每次 64 字节 XOR（~128 cycles），16 线程独立不竞争，省 ~50ns/call，性价比低
- per-thread cache 预处理的 `SHA256_CTX`（key 已 update）省 `Init + Update(ipad) + Update(opad)`（~120 cycles），需 key validation 逻辑（Java 端 `HmacSha256.HmacSha256Native` 当前是构造期固定 key，但 sign0 是 static 不强制保证，先保持简单）

#### 6.6.4 Tier 2 落地后 profile 验证

`Java_..._sign0 → SHA256_Update → sha256_block_armv8`（ARM64 SHA-256 硬件加速指令）成主栈帧。**`HMAC_Init_ex` / `evp_md_init_internal` / `pthread_rwlock_*` / `ossl_method_store_cache_get` 全部消失**。剩余开销：sha256_block_armv8（pure compute）+ JNI Release/NewByteArray。

**JMH 实测**（macOS aarch64 + JDK 17 + 2000B payload）：

| | 1t | 4t | 16t |
|---|---|---|---|
| javaMac | 10.07 μs | 10.48 μs | 20.68 μs |
| **nativeHmac（Tier 2）** | **1.29 μs** | **1.33 μs** | **2.51 μs** |
| 相对 Tier 1.5 | 1.70x | 2.69x | **5.07x** |
| 相对 javaMac | 7.81x | 7.91x | **8.24x** |

16 线程从"反退 Java 0.6x"变成"8.24x 超越 Java"，扩展性恢复近线性。

**正确性回归**：与 `javax.crypto.Mac` 字节级一致（含 key>64 / 空 key 边界 / partial offset/len）。JUnit 测试见 `edap-auth-jwt/src/test/java/io/edap/auth/jwt/test/HmacSha256NativeTest.java`。

**deprecation warning**：`SHA256_*` 在 OpenSSL 3.0 标记 deprecated，但函数本身不删除且对接到 ARM64 `sha256_block_armv8` 硬件指令，性能等价。可在 build script 加 `-Wno-deprecated-declarations` 屏蔽编译警告。

---

## 七、实施指引

### 7.1 Tier 1 落地步骤

1. **C 头文件**：`io_edap_jni_crypto_NativeHmacSha256.h` 改 `sign0` 签名（`key` 后加 `keyLen`）
2. **C 实现**：`io_edap_jni_crypto_NativeHmacSha256.c` 替换为 §6.2 + §6.5 组合代码（Tier 1 + Tier 1.5 同时落地）
3. **重新编译 .o**：用 `scripts/build-native.sh` 自动探测 JDK + OpenSSL + pthread，输出到 `src/main/resources/` + `target/classes/`
   ```bash
   # 在 macOS aarch64 上
   cd edap-native
   JAVA_HOME=/path/to/jdk ./scripts/build-native.sh
   ```
4. **重跑 `HmacSha256NativeBenchmark`**：对比 §5.1 Tier 0 基线 9 行 vs 现在 9 行，1-4t 期望 7-11% 提升，16t 期望从 12.4 μs/op 降到 2-3 μs/op
5. **回归 `HmacSha256NativeTest`**：16 线程 × 5000 次并发 + 字节级一致性（HMAC_CTX 复用后不能破坏多线程隔离）
6. **更新 `HmacSha256Native.sign` 签名**：保持对外 `(byte[] data, int offset, int len)` 不变，内部把 `key.length` 算好后调 `sign0(key, keyLen, data, offset, len)`

### 7.2 Tier 2 / Tier 3 不在第一版做

- **Tier 2 路线 A**：改 `Algorithm` 接口（破坏 `edap-auth-jwt` 调用方），需要更大范围的兼容性论证
- **Tier 2 路线 B**：线程安全风险，性价比低
- **Tier 3**：适用场景窄（Raft AE），ROI 取决于业务是否真的批量签

建议 Tier 1 落地后，根据实际 16t 数据决定是否推进 Tier 2/3。

### 7.3 与 OpenSSL 优化的关系

Tier 1 + Tier 1.5 在同一份 C 改动里同时落地（v1 C 源码就是两者合体版本）。Tier 1 仅解决 JVM 侧 per-call STW，1-4 线程 7-11% 提升但 16t 无效；Tier 1.5 解决 OpenSSL 内部锁，预期 16t 从 12.4 μs/op 降到 2-3 μs/op。

落地后，`edap-auth-jwt/doc/JWT_DESIGN.md §5.6 优化路径` 中 OpenSSL backlog 那条可以删除（已 nested 在 Tier 1.5 落到 `io_edap_jni_crypto_NativeHmacSha256.c`）。

### 7.4 Tier 2 落地说明

Tier 2（§6.6）已在 v2 C 源码中实际落地（替换 Tier 1+1.5 组合）：
- 删除 OpenSSL HMAC API，改用 legacy `SHA256_*`（直接 C，无 provider dispatch）
- 删除 Tier 1.5 的 `pthread_key_t` HMAC_CTX 缓存（不再需要）
- 删除 Tier 1.5 的进程级 `EVP_sha256()` 缓存（不再需要）

落地效果：16t 从 12.7 μs/op 反退 Java → 2.51 μs/op 8.24x 超越 Java（详见 §5.1）。

`edap-auth-jwt/doc/JWT_DESIGN.md §5.6` 的 16t 数据需要从 Tier 1.5 改写到 Tier 2（后者才是当前 C 源码实际跑的数）。

### 7.5 统一入口重构（删 `HmacSha256Native.java`）

把 `HmacSha256Native` 从独立文件收纳进 `HmacSha256` 内部作 `public static class` 嵌套类，把 JCE fallback 收纳作 `private static class HmacSha256Java`，同时把 `isAvailable()` 调用从 `AlgorithmRegistry` 移到 `HmacSha256` 构造期。这是 7.4 系列改造的逻辑延续：

- 之前：`AlgorithmRegistry` 静态块做 `isAvailable() ? native : jce` 三元判断
- 现在：`HmacSha256` 构造期一次性决定 delegate，registry 只 `register("HS256", HmacSha256::new)`

外部 API 不变（`new HmacSha256(key)` 仍是最简入口），额外保留两条强制路径供高级用户：
- `new HmacSha256.HmacSha256Native(key)` —— 跳过 fallback，强制 native
- `AlgorithmRegistry.register("HS256", HmacSha256.HmacSha256Native::new)` —— 替换默认工厂

回归验证：8/8 JUnit 测试过（native 路径 + JDK 路径各一遍），自动委托方向正确，输出与 JCE `Mac.getInstance("HmacSHA256")` 字节级一致。

---

## 八、关联文档

- `edap-auth-jwt/doc/JWT_DESIGN.md §5.6` —— 应用层集成 + 收益估算 + OpenSSL 优化 backlog
- `edap-auth-jwt/src/test/java/io/edap/auth/jwt/benchmark/HmacSha256NativeBenchmark.java` —— JMH benchmark
- `edap-auth-jwt/src/test/java/io/edap/auth/jwt/test/HmacSha256NativeTest.java` —— 正确性 + 并发回归
- `edap-native/src/main/c/io_edap_jni_crypto_NativeHmacSha256.c` —— JNI 主路径（当前 + 拟修版本）
- `edap-native/src/main/java/io/edap/jni/crypto/NativeHmacSha256.java` —— Java 入口
- `edap-native/src/main/java/io/edap/jni/Native.java` —— 加载入口
