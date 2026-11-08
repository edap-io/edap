# edap 平台时间门面与时间轮 —— 设计

`edap-common` / `io.edap.util.EdapTime` 升级为**平台统一时间门面**，
在其上新增 `io.edap.util.timing` **平台级时间轮**，作为全平台统一的定时任务基础设施。

---

## 1. 解决的问题

edap 现在的"时间"能力是散的，三块互不相干：

| 现状 | 位置 | 问题 |
|---|---|---|
| `EdapTime` 缓存 `System.currentTimeMillis()` | `io.edap.util.EdapTime` | 只能当"回调注册表"用，不能当调度器；若干实现硬伤（§2.1） |
| 各模块自己开 `ScheduledExecutorService` | `Container.java:141-146`、`TelnetServer.java:33` | 线程数不受控、生命周期不统一、精度各不相同 |
| `ScheduledExecutorService` 的延迟堆 | `ConcurrentPool.java:167-205` | O(log n)、无法统一治理 |

而平台上有**四个已就位但没接上线的定时消费方**：

| 消费方 | 现状 | 缺口 |
|---|---|---|
| **Raft tick 驱动** | `doc/RAFT_DESIGN.md:115` 明写"应用侧定时器由应用提供"，但**仓库里没有任何 `src/main/java` 调 `node.tick()`** | Raft 无法在真实环境跑起来，只能靠测试驱动 |
| **NIO 会话空闲过期** | `AcceptEventHandler.java:43` / `DisruptorReadDispatcher.java:52` **一直在写** `lastReadTime`，**从来没有被读过** | 会话永不因空闲被回收 |
| **Container PREVIOUS 槽释放** | `Container.java:141-146` 自己开了一个 60s 延迟线程 | 独立线程，生命周期游离在容器之外 |
| **`@Transactional(timeout=)`** | `DataSourceTransactionManager.java:45` 明写"timeout 静默忽略" | 注解里的 timeout 是死字段 |

**目标**：一个平台级定时服务，统一的时钟口径、统一的线程模型、统一的生命周期，
覆盖上述四个消费方，并为后续的集群级调度预留扩展点。

**范围**：本设计第一阶段只做**单机**（单 JVM 内的定时任务执行）。
`TimerService` 的 API 预留集群化扩展点（§7.4），集群化本身是独立阶段。

---

## 2. 现状调研

### 2.1 EdapTime 的硬伤

`io.edap.util.EdapTime` 当前实现：

```java
schService = Executors.newScheduledThreadPool(2, r -> new Thread(r, "Edap_time_thread"));
scheduledFuture = schService.scheduleAtFixedRate(() -> setCurrent(System.currentTimeMillis()),
                                                 0, 1000_000, TimeUnit.NANOSECONDS);
```

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | 线程**非守护**（继承创建者），全仓库**无人调 `shutdown()`** | `EdapTime.java:60,128` | 所有 edap 应用 **JVM 退不出**（已现场验证：benchmark 打印完仍不退出） |
| 2 | 后台每 1ms 醒一次 | `EdapTime.java:67` | 每 JVM 常驻 **~0.75% 单核**（实测，见 §3.6），且**所有** edap 应用都在付——`EdapTime.instance()` 被 `LoggerImpl`、`ConcurrentPool`、`IdGenerator`、`AcceptEventHandler`、`DisruptorReadDispatcher`、`HttpTime` 的 static 字段触发。**现已可用模式开关关掉**（§8.4） |
| 3 | `addCallback` 完全忽略 `initialDelay` / `delay` | `EdapTime.java:99-122` | 只能当回调注册表，**不能当调度器** |
| 4 | `setSecond(cur)` 在 `curSecond` 更新**之前**调用 | `EdapTime.java:79-81` | second 回调拿到的是毫秒值而非秒边界 |
| 5 | 只有墙钟，没有单调源 | — | NTP 校时/改系统时间会让基于它的调度错乱 |

**作者原意是缓存 `System.currentTimeMillis()` 以避免高并发时反复进内核态**（见类注释）。
这个意图是对的，但**前提在现代 JDK 上已经不成立**：`clock_gettime` 走 vDSO，
本来就不进内核态。实测（§3.1）单次调用只有 22.69 ns。

### 2.2 `addCallback` 的消费者依赖"周期推送"语义

改造 EdapTime 时必须注意：`addCallback` 的消费者里有两个是**靠回调被动更新**的，
不是主动读时钟的：

- `IdGenerator` —— `curTimestamp` **只**由 ms 回调更新（`IdGenerator.java:125`），
  `getId0()` 直接使用 `curTimestamp`、**不读时钟**（`IdGenerator.java:161`）。
  若改成读时惰性刷新，ID 里嵌的时间戳会停滞。
  附带问题：`setCurTimeMillis` 是 `synchronized` 的，每毫秒抢一次锁。
- `HttpTime` —— GMT / Date 字节靠 second 回调刷新（`HttpTime.java:48`），
  必须改成拉取式（读时钟 → 秒变了才重格式化）。

其余消费者（`LoggerImpl` 日志时间戳、`EdapLogFactory.lastReloadTime`、
`ConcurrentPool` 的借用超时、NIO 的 `lastReadTime`）都是**主动读时钟**或只需"大致时间"，不受影响。

### 2.3 `ConcurrentPool` 的超时判定是对的

`ConcurrentPool.borrow` 用的是 `TIME.nanoTime()`（`ConcurrentPool.java:196`），单调精确。
**仓库里没有"用缓存墙钟测时长"的地方** —— 这是 `EdapTime` 语义可以放宽的前提之一。

---

## 3. 实测基线

> 测量环境：macOS (Darwin 27.2.0) / OpenJDK 17.0.20 / 2026-11-06。
> **⚠️ 部署目标是 Linux，唤醒成本路径不同（futex+hrtimer vs macOS ulock），必须在目标机复测。**

### 3.1 时间读数成本

| API | ns/op | 备注 |
|---|---|---|
| `System.currentTimeMillis()` | **22.69** | vDSO，不进内核态 |
| `EdapTime.currentTimeMillis()` | **2.95** | 缓存命中 |
| `System.nanoTime()` | **18.04** | 单调源 |
| `Instant.now()` | 27.32 | |
| **`LocalDateTime.now()`** | **51.15** | 要走时区规则查询 |
| `ZonedDateTime.now()` | 54.47 | |
| `new Date().getTime()` | 23.58 | |

→ **"统一时间门面"的真正收益在 `LocalDateTime` / `ZonedDateTime` 这条线（17~18×），
不在毫秒上**（毫秒只快 8×，而维护成本是 ~1% 单核）。
门面化的价值是：业务侧不再各自 `LocalDateTime.now()` 走一遍时区规则。

### 3.2 1ms 周期刷新的 CPU 开销

3 秒观测，`ThreadMXBean.getThreadCpuTime`：

| 实现 | 占单核 | 每次唤醒 |
|---|---|---|
| `ScheduledExecutorService`（现状，2 线程池 + DelayedWorkQueue + ReentrantLock） | **1.044%** | 10.44 µs |
| 手写 `LockSupport.parkNanos` 对齐毫秒边界（无池无锁无堆） | **0.972%** | 9.72 µs |

→ **唤醒成本 ~10 µs 是操作系统地板**（内核 hrtimer → 调度器唤醒），换 Java 实现只省 7%。
**要省只能降频或取消后台线程。**
→ 盈亏平衡点：**约 46 万次/秒**调用 `currentTimeMillis()`，缓存才不亏。

2026-11-07 换了个测法（**进程 CPU 增量**，比线程 CPU 时间稳，避开 JIT 编译期噪声）：
3 轮各 10 s，刷新线程净增 **0.815% / 0.747% / 0.753% 单核**（中位数 **0.75%**）。
同一台机器上的读成本：**缓存读 0.37 ns** vs **`System.currentTimeMillis()` 21.0 ns**（差 20.6 ns）。
→ 按 0.75% 单核 = 0.0075 CPU秒/秒 折算，盈亏平衡点 **约 36 万次/秒**。

### 3.3 `parkNanos` 的唤醒精度（2026-11-06 复测）

`LockSupport.parkNanos(target - now)` 的**晚醒量**（macOS / JDK 17.0.20）：

| 睡眠长度 | 晚醒 p50 | 晚醒 p90 | 晚醒 max |
|---|---|---|---|
| 1 ms | 257 µs | 263 µs | 965 µs |
| 10 ms | 1.79 ms | 2.52 ms | 3.68 ms |
| 100 ms | 3.02 ms | 5.02 ms | 5.06 ms |
| 1 s | 3.98 ms | 5.02 ms | 5.03 ms |

→ 晚醒量**随睡眠长度增长并饱和在 ~5 ms**（macOS 的定时器合并粒度）。
绝对时间武装（`timerfd` 的 `TFD_TIMER_ABSTIME`）可以消除它。

**但这个精度对时间轮来说不是误差主项** —— 见 §3.4。

### 3.4 时间轮的实际到期误差

2000 个任务、延迟 5~904 ms、真实时钟，测"实际触发时刻 − 目标时刻"：

| p50 | p90 | p99 | max | mean |
|---|---|---|---|---|
| **751 µs** | 1121 µs | 1213 µs | 2570 µs | 744 µs |

分解：**1 ms 的 tick 网格**（随机相位下平均 500 µs）+ **`parkNanos` 晚醒**（~250 µs）。

→ 误差主项是 **tick 网格**，不是唤醒机制。
`timerfd` 最多把 p50 从 751 µs 降到 ~500 µs（3×），但**绝对值仍在亚毫秒**。
要真正提高精度必须细化 tick 网格，而那会把唤醒次数放大 10 倍
（10 µs/次 × 100k/s = 1 核）。

### 3.5 刷新循环的陈旧度（EdapTime 门面用）

`System.currentTimeMillis() − 缓存值`，4 秒观测：

| 实现 | p50 | p90 | p99 | max | mean |
|---|---|---|---|---|---|
| `ScheduledExecutorService`（现状） | 0 ms | 1 ms | 1 ms | 1 ms | 0.26 ms |
| 手写 `parkNanos` 对齐 ms 边界（§8.1） | 0 ms | 1 ms | 1 ms | 3 ms | **0.19 ms** |

→ 两者等价，手写版平均更准 → §8.1 的设计成立。

### 3.6 模式开关的实测收益（2026-11-07）

同一个程序，只改 `-Dedap.time.mode`，各跑 10 s（预热 3 s），测**进程 CPU 增量**：

| 模式 | 进程 CPU / 10 s | 占单核 |
|---|---|---|
| `refresh`（主动每 ms 更新缓存） | 0.080 s / 0.071 s | **0.798% / 0.707%** |
| `on-demand`（不主动更新） | 0.004 s / 0.004 s | **0.037% / 0.045%**（≈ JVM 自身基线） |

→ **不主动更新缓存把那 0.75% 固定开销整个抹掉**，代价是每次读多 20.6 ns。
读得少（< ~36 万次/秒）就是纯赚。实现见 §8.4。

---

## 4. 设计决策

### 4.1 决策一：刷新周期必须保持 1ms

`System.currentTimeMillis()` 本身就是 **1ms 分辨率**，在同一个毫秒内连读两次会返回**相同的值**。
所以 1ms 刷新的 EdapTime 与被它替代的东西**在可观察行为上完全等价**
（误差上界都是 1ms，都可能出现重复值）。

把刷新周期放宽到 10ms 是**语义降级**（"结果不对"），不是优化 —— 本设计不采用。

### 4.2 决策二：时间轮用单调时间做 deadline

时间轮内部所有 deadline 存 **单调纳秒**（`System.nanoTime()`），对外 API 才涉及墙钟。
理由：墙钟会跳（NTP 校时、手动改系统时间、容器 time namespace 漂移），
轮子一旦用墙钟算到期，跳变会导致任务提前或延后触发。

**代价**：时间轮每 tick 读一次时钟（100~1000 次/秒 × 18 ns = 可忽略），
**不需要** EdapTime 的缓存机制。EdapTime 对时间轮的价值是"全平台一个时间口径"的**概念统一**，不是性能。

### 4.3 决策三：分层时间轮，不用单层 + rounds

单层 hashed wheel + rounds（Netty 风格）在细 tick 下有**重插入放大**问题：
1 天后的任务在 1ms tick 下要被轮询重插入 **86,400,000 次**。

分层后，一个任务的**重插入次数 = 它的层级深度 = O(log(delay))**，与延迟的绝对值无关。

| | 单层 + rounds | 分层 |
|---|---|---|
| 长延迟任务成本 | O(delay / tick) | O(层数) |
| 1 天任务的 1ms tick 重插入次数 | 86,400,000 | ≤ 4 |
| 1 年任务 | 31,536,000,000 | ≤ 5 |

### 4.4 决策四：tick 源抽象，timerfd 与 parkNanos 可切换

时间轮的推进源抽象成 `TickSource`：

- `NanoTickSource` —— `LockSupport.parkNanos` 对齐到下一个 tick 边界。纯 Java，零依赖。
- `TimerFdTickSource` —— Linux `timerfd` + `TFD_TIMER_ABSTIME` 绝对武装。需 JNI。

两者语义相同，**可 drop-in 替换**。非 Linux / 无 JNI 环境自动降级到 `NanoTickSource`。

**timerfd 相对 `parkNanos` 的三个优势**（原判断，已被 §3.3~§3.4 的实测修正）：

| | `parkNanos` | `timerfd` |
|---|---|---|
| 漂移 | 晚醒 257 µs（1 ms 睡）→ 长睡至 5 ms | `TFD_TIMER_ABSTIME` **零漂移** |
| 精度 | 受 tick 网格主导 | 同样受 tick 网格主导 |
| **空闲唤醒** | **本来就是 0**（轮子睡到下一个需要的 tick） | 0 |

**第三条原以为是杀手锏，实测后发现是伪命题**：分层时间轮的"睡到下一个可能被访问的槽"
天然就是空闲零唤醒，不需要 timerfd 来提供。

→ **决策四的落地形态定为"只做 SPI，不做 timerfd 实现"**，理由见 §6.2。

### 4.5 决策五：tick 线程只做检测与投递，执行交给 Executor

tick 线程**不执行**业务回调，只负责"扫到期桶 → 投递"。
理由：一个慢回调会阻塞整个平台的定时器。

- 默认执行器 = 平台共享的 daemon 线程池（大小可配）。
- 需要严格串行（如 Raft tick 驱动）时，`schedule` 可指定单线程 Executor。

**代价**：投递 ≠ 立即开始执行，"到点"与"开始执行"之间有排队延迟。
对本平台的四个消费方都可接受（Raft tick 内部还会再入队一次）。

### 4.6 决策六：取消是 O(1) 的，不追求"立即摘除"

`TimerHandle.cancel()` 只置一个 volatile 标志，轮子取出时二次确认。
不维护 `Timeout → Bucket` 的反向索引 —— 那个索引的维护成本高于收益，
且"从队列里摘除"在 MPSC 队列上本身就不是 O(1)。

### 4.7 决策七：EdapTime 的改造范围

| 改动 | 理由 |
|---|---|
| 线程改 **daemon** | 修 JVM 退不出（硬伤 1） |
| 后台刷新换**单线程手写 `parkNanos`** | 省 7%，且去掉 2 线程池 |
| **保持 1ms 周期** | 决策一 |
| 补 `monotonicNanos()` | 时间轮的 deadline 基准、时长测量 |
| 补 `instant()` / `localDateTime()` / `localDate()` / `offsetDateTime()` / `date()` / `calendar()` / `zoneId()` | 门面化，随刷新一并更新（§3.1 的 17~18× 收益） |
| 修 `setSecond` 语义 | 硬伤 4 |
| 明确 `addCallback` 是**回调注册表**而非调度器 | 硬伤 3 |

---

## 5. 分层时间轮算法

### 5.1 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `baseTickNanos` | **1 ms**（可配） | 基准 tick = level 0 的槽跨度 |
| `wheelSize` | **128**（可配） | 每层槽数（2 的幂，用 `& mask` 代替 `%`） |
| `levels` | **5**（可配） | level 0..4 |

每层 L 的槽跨度 = `baseTickNanos * wheelSize^L`，覆盖范围 = 槽跨度 × `wheelSize`：

| level | 槽跨度 | 覆盖范围 |
|---|---|---|
| 0 | 1 ms | 128 ms |
| 1 | 128 ms | 16.4 s |
| 2 | 16.4 s | 35 min |
| 3 | 35 min | 3.1 天 |
| 4 | 3.1 天 | ~1.1 年 |

超出 level 4 覆盖范围的任务进**溢出堆**（`PriorityQueue`，按 deadline 排序，
每 tick 只 `peek()` 一次，正常为空，代价可忽略）。

**`baseTick` 可配的含义**：它决定的是**定时器的分辨率**，不是 EdapTime 缓存的刷新周期
（后者必须 1ms，见决策一）。1ms tick 的代价是：有 level-0 任务时轮子每 1ms 醒一次，
按 §3.2 的实测约 **1% 单核**。把 `baseTick` 调到 10ms，唤醒次数降到 1/10（≈0.1% 单核），
代价是任务最多晚 10ms 触发。本平台的四个消费方（Raft tick 50ms / 会话空闲过期 /
Container 槽释放 60s / tx timeout）都不需要优于 100ms 的分辨率，
**所以 10ms 是一个合理选项**，默认保持 1ms 以免改变时间语义。

### 5.2 数据结构

```
levels[0..4]
  spanTicks[L] = wheelSize^L          // level L 的槽跨度（tick 数）
  cursor[L]    = 绝对跨度序号          // = floor(cursor[0] / spanTicks[L])
  buckets[L][0..127] : ArrayDeque<TimerTask>
overflow : PriorityQueue<TimerTask>   // 按 dueTick 排序
overdue  : ArrayDeque<TimerTask>      // 已到期，下一个 tick 无条件投递
inbox    : ConcurrentLinkedQueue<TimerTask>   // 跨线程注册入口
```

`cursor[0]` 即当前 tick（绝对值）。

**为什么桶是 `ArrayDeque` 而不是并发队列**：桶结构**只由 tick 线程访问**
（外部线程注册只往 `inbox` 里塞，tick 线程 drain 后入桶），
因此不需要任何并发容器 —— `ArrayDeque` 零锁、零分配（复用换桶技巧，见 5.7）。

### 5.3 放置规则

任务到期 tick 为 `k`（绝对 tick），当前 tick 为 `t`：

```
delta = k - t
if (delta <= 0)  → overdue（立即到期，见 5.5）
L = 最小的、满足 delta < wheelSize^(L+1) 的层      // 同时保证 delta ≥ wheelSize^L
S = spanTicks[L]
c = t / S            // 当前所处的跨度序号
u = t - c*S          // 在当前跨度内的偏移
m = (delta + u) / S  // 还需要推进几次 level-L 的槽
if (m < 1)        m = 1             // 见下
if (m > wheelSize-1) m = wheelSize-1 // 见下
slot = (c + m) % wheelSize
buckets[L][slot].add(task)
```

**为什么用相对游标而不是绝对对齐**：level L 的游标每 `S` 个 tick 推进一格，
任务放在 `(c + m) % wheelSize` 这个槽里，
轮子恰好在 `m` 次推进后访问它 —— 既不会漏，也不会早。

**两个夹逼的来由**（都是"不漏任务"的硬约束）：

- **下界 `m ≥ 1`**：`m = 0` 会把任务放进**当前这一格**，而当前格马上就要被访问过去
  （或者已经被访问过）→ 任务要等整整一圈（`wheelSize * S` 个 tick）才被看到，
  严重迟到。按上面的选层规则，`delta ≥ S` 保证 `m ≥ 1` 天然成立，这个夹逼只是兜底。
- **上界 `m ≤ wheelSize-1`**：`m = wheelSize` 会绕回当前格（`(c + wheelSize) % wheelSize == c % wheelSize`），
  同样漏一圈。夹到 `wheelSize-1` 后，访问时刻 `(c+m)*S ≤ 到期时刻` —— 只会**早**不会**晚**，
  早到的任务会被下沉到更细的层重新安排（见 5.4）。

**保证**：任意任务被访问的时刻 `v = (c+m)*S` 满足 `v ≤ dueTick`，
即**永远不迟到**；到期后由 level 0 精确命中（见 5.4 末）。

### 5.4 推进规则

```
advance(toTick):
    // level 0：粒度 = 1 tick
    if (toTick - cursor[0] >= wheelSize):      // 跨过整整一圈
        cursor[0] = toTick
        for slot in 0..wheelSize-1: drain(0, slot)   // 圈内任务全部已到期
    else:
        while (cursor[0] < toTick):
            cursor[0]++
            drain(0, cursor[0] % wheelSize)

    // 粗层：由细到粗推进（1 → levels-1）
    for L = 1 .. levels-1:
        target = toTick / spanTicks[L]
        while (cursor[L] < target):
            cursor[L]++
            drain(L, cursor[L] % wheelSize)

    drain 里对每个任务：
        if (task.dueTick <= cursor[0])  → 投递（已到期）
        else                            → place(task)  // 重新选层下沉
    // 溢出堆
    while (!overflow.isEmpty() && overflow.peek().dueTick <= cursor[0]): 投递(overflow.poll())
    // overdue
    while (!overdue.isEmpty()): 投递(overdue.poll())
```

**为什么推进顺序是"由细到粗"**：粗层下沉到细层时，细层必须**已经推进到当前 tick**，
否则下沉的任务会落进一个"本轮已经被访问过"的槽里被漏掉。
反过来（先粗后细）就会漏。

**下沉链**：level L 的任务被访问时，剩余时间 = `dueTick - cursor[0]`。
访问时刻 `v` 落在 `[dueTick - S_L + 1, dueTick]` 之外时（夹逼到 `wheelSize-1` 的极端情形），
可能仍留在同一层再插一次；否则剩余时间 `< S_L` → 下一层一定能接住（`S_{L-1} < S_L`）。
到 level 0 时剩余时间为 0，槽跨度 = 1 tick，**在到期的那一个 tick 精确命中**。

**每个任务的重插入次数与延迟的绝对值无关，只与层数同阶**（上界约 `2 × levels`，
夹逼到边界时可能在同一层多插一次）。
延迟 1 小时和延迟 1 毫秒的任务，重插入次数是同一个量级 —— 这就是决策三要的效果。

**跨圈时（`toTick - cursor[0] >= wheelSize`）直接倒空整圈**：
此时 level 0 桶里的任务全部已到期（level 0 的任务最多只比放置时刻晚 `wheelSize` 个 tick），
一次倒空即可，不必逐 tick 走（避免长暂停后逐 tick 空转几百万次）。

### 5.5 立即到期

放置时若 `delta <= 0`（delay 为 0，或放置与推进并发导致游标已越过），
任务进 `overdue` 列表，由下一次 tick 无条件投递（延迟 ≤ 1 tick）。

### 5.6 跨线程注册（inbox）

外部线程的 `schedule` **只做一件事**：把任务塞进 `inbox`（`ConcurrentLinkedQueue`），
然后 `signal()` 唤醒 tick 线程。tick 线程在下一次推进前 `drainInbox()`，
把任务按当前游标放进桶里。

- 代价：注册的任务最多迟 1 个 tick 生效。
- 收益：桶结构**完全单线程化**，无锁无竞态，也不需要为每个桶配并发容器。

### 5.7 桶的换出技巧

处理桶时把桶从数组里**换出来**（用一个空的备用桶顶上），处理完再放回去：

```java
ArrayDeque<TimerTask> bucket = buckets[L][slot];
buckets[L][slot] = spare;      // spare 一定是空的
spare = bucket;
while ((task = bucket.poll()) != null) { ... }
```

处理期间重新落进这个槽的任务进的是"备用桶"，不会污染本次遍历，
也就不需要 `ConcurrentModificationException` 的防护。
复用同一个 `spare` 实例 → 稳态下**零分配**。

---

## 6. TickSource

```java
public interface TickSource extends AutoCloseable {
    /** 阻塞直到 deadline（单调纳秒）到达或被提前唤醒；返回实际唤醒时的单调纳秒。 */
    long awaitUntil(long deadlineNanos) throws InterruptedException;
    /** 提前唤醒 awaitUntil（用于有新任务入队、或需要立即处理 overdue 时）。 */
    void signal();
    @Override void close();
}
```

### 6.1 `NanoTickSource`（默认，纯 Java）

```java
long deadline = ...;
long now = System.nanoTime();
if (deadline > now) LockSupport.parkNanos(deadline - now);
return System.nanoTime();
```

`signal()` 用一个 volatile 标志 + `unpark` 实现。

### 6.2 `TimerFdTickSource`（Linux，需 JNI）—— **本期预留，不实现**

JNI 面（`edap-native`）：

```c
static int  timerfd_create();                                   // CLOCK_MONOTONIC
static int  timerfd_arm(int fd, long deadlineNanos);            // TFD_TIMER_ABSTIME, one-shot
static long timerfd_read(int fd);                               // 返回已到期次数, -1 = EAGAIN
```

Java 侧语义：
- `awaitUntil(deadline)`：武装 → 阻塞 `read()` → 返回。
- `signal()`：不能直接改 timerfd 的武装时刻（会被 `read` 打断）。
  实现方式：`write` 一个 eventfd，eventfd 也纳入同一个 `epoll`/`poll` 等待，
  醒来后重新武装到新的 deadline。

**降级**：`edap-native` 不可用 / 非 Linux → 自动用 `NanoTickSource`。

#### 为什么本期不做（2026-11-06 实测后的重新评估）

原计划把 P2 的收益写成"空闲零唤醒 + 零漂移"。实测后这两条都不成立：

| 收益项 | `parkNanos` 现状 | `timerfd` | 差异 |
|---|---|---|---|
| **空闲零唤醒** | **已经是 0** —— 轮子睡到"下一个可能被访问的槽"，没有任务时无限等待 | 0 | **无差异** |
| 单次唤醒成本 | ~10 µs（OS 地板，§3.2） | ~10 µs | **无差异** |
| 唤醒精度 | 晚 257 µs（1ms 睡）/ 晚至 5 ms（长睡，§3.3） | 绝对武装，~0 | 有，但**不是误差主项** |

时间轮的真实到期误差是 **751 µs（p50）**，其中 ~500 µs 来自 **1 ms 的 tick 网格**，
~250 µs 来自 parkNanos 晚醒（§3.4）。`timerfd` 最多把 p50 压到 ~500 µs，
**绝对值仍在亚毫秒** —— 而四个消费方（Raft 心跳、NIO 会话过期、Container 60s 释放、
`@Transactional(timeout=)`）的时间尺度都是**秒级**，亚毫秒精度对它们毫无意义。

要真正提高精度只能细化 tick 网格，那会把唤醒次数放大 10 倍（10 µs × 100k/s ≈ 1 核）。

**代价侧**：`timerfd` 是 Linux-only（开发机 macOS、Solaris 部署机都用不了），
要新增 C 绑定 + `edap-native → edap-common` 的依赖边 + eventfd/epoll 的信号路径，
还要处理 fd 生命周期。

→ **结论：投入产出不成立，本期只保留 SPI（`TickSource`）作为扩展点，不实现 `timerfd`。**
若将来出现亚毫秒级精度的真实需求，应该先细化 tick 网格（并接受唤醒成本），
再考虑绝对武装 —— 而不是反过来。

### 6.3 唤醒时机（空闲零唤醒）

轮子空闲时不空转：

```
if (轮完全为空) {
    awaitSignal();              // 阻塞等待，直到有任务入队
} else {
    nextWake = min over 非空层 of (cursor[L]+1)*span[L]   // level 0 → 下一个 tick
    awaitUntil(nextWake);
}
```

`nextWake` 的计算是**保守**的（可能早醒，不会漏醒）：
每次醒来后重算，任务下沉后下一次唤醒会自然变细。

---

## 7. API 契约

### 7.1 `TimerService`

```java
public interface TimerService extends AutoCloseable {

    /** 相对延迟执行一次。 */
    TimerHandle schedule(Runnable task, long delay, TimeUnit unit);

    /** 相对延迟执行一次，指定执行器。 */
    TimerHandle schedule(Runnable task, long delay, TimeUnit unit, Executor executor);

    /** 绝对单调时刻执行一次（deadline 为 monotonic nanos）。 */
    TimerHandle scheduleAt(Runnable task, long deadlineNanos);

    /** 绝对单调时刻执行一次，指定执行器。 */
    TimerHandle scheduleAt(Runnable task, long deadlineNanos, Executor executor);

    /** 固定频率执行。首次在 initialDelay 后，之后按 period 间隔（不漂移、不受执行耗时影响）。 */
    TimerHandle scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit);

    /** 固定频率执行，指定执行器。 */
    TimerHandle scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit, Executor executor);

    /** 当前待处理任务数（诊断用）。 */
    int pendingCount();

    @Override void close();
}
```

### 7.2 `TimerHandle`

```java
public interface TimerHandle {
    /** 取消。取消后任务绝不执行（若尚未投递）。O(1)。 */
    boolean cancel();
    boolean isCancelled();
    /** 到期时刻（墙钟毫秒，对外可读）。 */
    long deadlineWallMillis();
}
```

### 7.3 语义约定

| 约定 | 内容 |
|---|---|
| **取消** | `cancel()` 返回 true 表示"本次调用把任务从待执行转成了已取消"。已投递/已执行的任务返回 false。 |
| **取消后的状态** | 一次性任务以状态迁移为准：已投递的任务 `cancel()` 返回 false，且 `isCancelled()` 仍是 false（不能把"已经跑过"报成"已取消"）。 |
| **异常隔离** | 任务抛出的异常**不会**影响轮子与其他任务，由执行器兜住并记录日志（`java.util.logging`）。 |
| **执行器异常隔离** | 执行器（调用方给的）抛出的异常同样被兜住。兜不住的极端情况下，桶遍历有 `finally` 归还：未处理完的任务放回原槽，下一轮继续 —— 不会连坐丢任务。 |
| **固定频率** | 周期从**计划时刻**计算，不从上次执行完成时刻计算 → 执行耗时不会导致漂移或堆积。 |
| **固定频率 + 迟到** | tick 线程迟到时**跳过**错过的期次，不做补偿性突发（否则一次长 GC 之后会打出一串补偿执行）。 |
| **固定频率 + cancel** | 取消后不再续期（投递与续期之间的竞态也会被二次检查兜住）。 |
| **投递语义** | tick 线程投递到 Executor，**不保证**"到点即开始执行"。 |
| **周期下限** | `period` 小于 `baseTick` 时按 1 个 tick 处理（定时器的分辨率下限就是 `baseTick`）。 |
| **时钟** | deadline 内部用单调纳秒。`deadlineWallMillis()` 是注册时刻的墙钟快照，仅供展示。 |
| **绝对墙钟 API** | 后续版本提供 `scheduleAtWallClock(task, epochMillis)`，在注册瞬间做墙钟→单调换算（换算基准是当时的 `EdapTime` 与 `nanoTime` 快照）。 |

### 7.4 集群化扩展点（预留，本期不实现）

`TimerService` 的接口形状预留两种演进路径，**不改动 API**：

- **hash 分片亲和**：给 `schedule` 增加可选的 `shardKey`，
  归属节点由 `hash(shardKey) % (节点数 × 本地分片数)` 决定（契合 `ClusterShardRouter` 的既有哲学：
  本地内存即权威、不跨节点同步）。代价：非 exactly-once（节点宕机期间任务丢）+ 成员变更要迁移。
- **Raft leader-only**：任务状态进 Raft 状态机，只有 leader 执行。

两者都只需要在 `TimerService` 实现内部加一层**归属判定**，
业务侧感知的是"任务最终在某个节点执行"。

---

## 8. EdapTime 改造

### 8.1 刷新实现

```java
// 单线程 daemon，手写 parkNanos 对齐下一个毫秒边界
Thread t = new Thread(() -> {
    while (running) {
        long now = System.nanoTime();
        long nextMs = (now / 1_000_000L + 1) * 1_000_000L;
        LockSupport.parkNanos(nextMs - now);
        refresh();
    }
}, "Edap_time_thread");
t.setDaemon(true);
```

- **保持 1ms 周期**（决策一）
- daemon → 修 JVM 退不出
- 去掉 `ScheduledExecutorService`（2 线程池 + 堆 + 锁）→ 唤醒成本 10.44 µs → 9.72 µs
- 对齐下一个毫秒边界 → 刷新时刻稳定，不受上次执行耗时影响
- **回调异常隔离**：一个回调抛异常不能带倒其它回调，更不能带倒刷新线程
  （否则整个平台的"当前时间"就冻住了）

### 8.2 门面方法

```java
public long   currentTimeMillis();       // CACHED：缓存读 ~0.4 ns；DIRECT：直取 ~21 ns
public long   nanoTime();                // System.nanoTime()，单调源，**不缓存**
public Instant       instant();
public LocalDateTime localDateTime();
public LocalDate     localDate();
public OffsetDateTime offsetDateTime();
public Date     date();                  // 每次 new 一份副本
public Calendar calendar();              // 每次 new 一份副本
public ZoneId   zoneId();
public void    setZoneId(ZoneId);
```

- `zoneId()` 可配，默认 `ZoneId.systemDefault()`。
- **单调源不缓存**：缓存 `nanoTime()` 会把 1 ms 以内的间隔测成 0。
- **`date()` / `calendar()` 每次给新副本**：两者都是可变对象，不能共享。
- **日历视图懒重建**：刷新线程只更新 `current`，**不预建** `Instant`/`LocalDateTime`/…；
  有人真读日历视图时才现算并回填缓存。没人读的时候一秒一个新对象都不产生
  （老实现每秒要造 5 个对象）。
- **自洽性**：视图与 `current` 是两个 volatile 字段，读到不一致的窗口时按 `current` 现算 ——
  门面读到的时刻与 `currentTimeMillis()` 永远一致。

### 8.3 `addCallback` 的定位

保留，但**明确它不是调度器**：
- `TimeCallback` 的 `setTimeMillis(long)` 收到的值，ms 档是**毫秒时间戳**，
  second 档是**秒边界时间戳**（修 `EdapTime.java:79-81` 的语义 bug）。
- 需要"按延迟触发"的能力，走 `TimerService`，不走 `addCallback`。

### 8.4 模式开关：要不要主动每 1 ms 更新缓存

缓存的代价是**固定 CPU 开销**（~0.75% 单核），收益是每次读省 20.6 ns。
读得少的时候，这笔账是亏的 —— 所以做成开关而不是写死。

开关的语义就一句话：**要不要主动每 1 ms 更新缓存**。

| 模式 | 缓存 | 后台线程 | 推送回调 |
|---|---|---|---|
| `REFRESH`（默认） | **主动每 1 ms 更新**，读走缓存 | 1 个 daemon 线程，~0.75% 单核 | 支持 |
| `ON_DEMAND` | **不主动更新**，取时现取 `System.currentTimeMillis()` | **没有**，0 CPU | **不支持**（`addCallback` 抛 `UnsupportedOperationException`） |

切换（都必须在第一次 `EdapTime.instance()` 之前）：

```java
// 1) 系统属性
-Dedap.time.mode=on-demand      // refresh 为默认
// 2) 编程式
EdapTime.setMode(EdapTime.Mode.ON_DEMAND);   // 单例已创建则抛 IllegalStateException
```

**不主动更新缓存 ⇒ 没有推送源**：所有回调都靠后台线程推，没有线程可推。
所以 `addCallback` **直接抛异常**——宁可启动时炸掉，也不要静默不生效。

⇒ 依赖"被动推送"的消费方必须改成**拉取式**（已改）：

- **`IdGenerator`** —— 时钟源改成实例可注入（`new IdGenerator(workId, time)`）。
  主动刷新模式仍靠 ms 回调把 `curTimestamp` 顶到当前毫秒（顺带灌满"每毫秒一条"的回拨历史）；
  不主动刷新模式改为**每次取 ID 自己拉一次时钟**，保证 ID 里嵌的时间戳是当前毫秒。
  代价：不主动刷新时回拨历史的精度降到"有 ID 的毫秒"，回拨到没有 ID 的毫秒会抛错
  （安全，不会出重复 ID —— 跟回拨超出历史窗口的行为一致）。
- **`HttpTime`** —— 改成**拉取式 + 秒边界 TTL**：取字节时看一眼当前是第几秒，
  跨了秒边界才重新格式化（每秒最多一次）。顺带修掉一个老问题：
  老实现把新值原地 `arraycopy` 进已发布的数组，正在编码响应的线程可能读到半截数据；
  现在是不可变快照整体替换。

### 8.5 时间轮的 tick 与 EdapTime 的 1ms tick 是不是重复？

**不重复 —— 两个 tick 的驱动方式根本不同：**

| | EdapTime 的 tick | 时间轮的 tick |
|---|---|---|
| 节奏 | **固定 1 ms**，无条件醒 | **按需**：睡到"下一次可能有任务到期的槽" |
| 空闲时 | 1000 次/秒，~0.75% 单核 | **0 次**（`nextWakeTick()` 返回 -1 时无限等待） |
| 有 1 ms 粒度任务时 | 1000 次/秒 | 1000 次/秒（**这时才重叠**） |

⇒ 真正会重叠的场景只有一个：**时间轮里挂了 ≤1 ms 粒度的任务**。
届时会有两个 1 ms 唤醒源（各 ~0.75%）。§6.1 的 `TickSource` SPI 就是为此留的接缝 ——
届时让轮子挂在 EdapTime 的 tick 上（`EdapTime` 推 tick，轮子不再自己醒）即可省掉一个。

**本期四个消费方（Raft 心跳 / NIO 会话过期 / Container 60s 释放 / tx timeout）
时间尺度都是秒级 → 轮子自己的唤醒次数 ≈ 0，不存在重叠。**
所以不做这个合并 —— 合并的收益是 0，代价是轮子的生命周期被绑到 `EdapTime` 上
（`EdapTime.shutdown()` 会连累轮子停摆）。

---

## 9. 落地阶段

| 阶段 | 内容 | 产出 |
|---|---|---|
| **P1** ✅ | `edap-common` 的时间轮本体 + `TimerService` API + `NanoTickSource` + 单测 | `io.edap.util.timing`（见 §9.2） |
| **P2** ⏸ | `edap-native` 的 `timerfd` JNI 绑定 | **降级为预留** —— 实测后收益不成立，理由见 §6.2 |
| **P3** ✅ | `EdapTime` 升级为时间门面（§8） | daemon + 门面方法 + 单调源 + **缓存刷新开关**（§8.4） |
| **P4** ✅ | 接入四个消费方（§1） | Raft tick 驱动 / NIO 会话空闲过期 / Container prev-release / tx timeout —— 见 §9.4 |

### 9.1 P1 单测必须覆盖

- 取消语义：取消后绝不执行；重复取消返回 false；已执行后取消返回 false
- 分层下沉：跨层边界的 deadline（127/128/129 tick、level 边界）必须**准时**触发
- 溢出堆：超出 level 4 覆盖范围的任务仍能触发
- 固定频率：执行耗时 > period 时不堆积、不漂移
- 异常隔离：一个任务抛异常不影响其他任务
- 时钟跳变：用可控的 `TickSource` 注入一个"墙钟跳变"的时钟，验证 deadline 仍按单调时间触发
- 关闭：close 后不再投递，pending 任务被取消

### 9.2 P1 实际产出（已落地）

```
edap-common/src/main/java/io/edap/util/timing/
  TimerService.java        公开 API（接口）
  TimerHandle.java         句柄（接口）
  TickSource.java          推进源 SPI（接口）
  NanoTickSource.java      parkNanos 实现
  TimingWheel.java         轮本体（包私有）
  TimerTask.java           任务表示 + 状态机（包私有）
  WheelTimerService.java   服务实现（公开）

edap-common/src/test/java/io/edap/util/timing/
  TimingWheelTest.java     白盒：手动推进时钟，验证"到点即触发"
  WheelTimerServiceTest.java  黑盒：真实时钟
```

白盒测试放在与被测类同一个包里（`io.edap.util.timing`），
是为了能直接驱动 `TimingWheel.advanceTo(tick)` 做确定性验证 ——
时间轮的精度语义只有在能手动推进时钟的前提下才能测。
（这偏离了仓库 `io.edap.common.test.*` 的测试目录约定，是有意为之。）

已覆盖：

| 用例 | 断言 |
|---|---|
| `firesExactlyAtDueTick` | 16 个跨层边界的 deadline（1/2/3/4/5/15/16/17/63/64/65/127/128/129/1000/16385）**精确**在到期的那一个 tick 触发 |
| `manyTasksFireAtTheirOwnDueTickInOrder` | 500 个任务逐 tick 推进，每个都在自己的到期 tick 触发，且顺序与到期顺序一致 |
| `overflowHeapTaskStillFires` | 超出顶层覆盖范围的任务仍能触发 |
| `cancelledTaskNeverFires` / `cancelledAfterFireReturnsFalse` | 取消语义 |
| `clockJumpDoesNotLoseTasks` | 一次跳过 10 万 tick 不丢任务 |
| `differentialAgainstNaiveOracle` | 3000 个随机任务 + 随机跳跃推进，与朴素 `PriorityQueue` 实现**差分对照**：不早于到期时刻、不晚于朴素实现 |
| `scheduleFiresAfterDelay` / `cancelledTaskNeverRuns` / `exceptionDoesNotAffectOtherTasks` / `fixedRateDoesNotDrift` / `closedServiceDeliversNothing` / `pendingCountTracksRegistrations` | 服务层语义 |

### 9.3 P1 评审发现的缺陷（已修）

对时间轮做了一轮白盒评审（差分对照 + 定向探针），核心算法全部站住：
跨层边界的到期 tick 精确命中、`m` 的两个夹逼边界、跨圈倒空、溢出堆、
周期任务续期不追赶、取消与投递的竞态 —— 均未发现问题。

发现并修掉 3 个真实缺陷：

| 缺陷 | 现象 | 根因 | 修复 |
|---|---|---|---|
| **执行器抛非 `RejectedExecutionException`** | 同桶的其它任务全部丢失，`pendingCount` 永久停在 19，`close()` 后也不归零 | 异常穿出 `fire()` → `drainBucket()`；桶已换出到 `spare` 且没有 `finally` 归还 | `dispatch()` 兜住 `Throwable`；`drainBucket()` 加 `finally` 把未处理完的任务放回原槽；`cancelAll()` 连带清 `spare` |
| **`isCancelled()` 误报** | 已经执行完的任务被报告成"已取消" | `cancel()` 先置 `cancelled` 标志再做状态迁移，迁移失败时标志已经改掉了 | 一次性任务改为**以状态迁移为准**，`isCancelled()` 读 `state` |
| **close 与注册竞态** | 任务滞留在 `inbox`，`pendingCount` 泄漏 | `closed` 检查与 `wheel.submit()` 之间有窗口 | 提交后复查 `closed`，是则把任务撤回来（`cancel()` 会反向记账） |

回归测试：`explodingExecutorDoesNotLoseSiblingTasks`（三个同 tick 任务落在同一个桶，
第一个的执行器直接抛异常）、`cancelAfterFireDoesNotReportCancelled`。
两条测试都做了**反向验证**：把修复摘掉，`explodingExecutorDoesNotLoseSiblingTasks` 确实 FAIL。

> 已确认不是 bug 的两点：① `m` 夹逼到边界时任务可能在同一层多插一次 ——
> 仍然准时触发，只是文档里"重插入次数 = 层级深度"的说法不严谨（已改）；
> ② 已取消的任务留在溢出堆里直到到期才被丢弃 —— 只影响内存占用，不影响正确性。

### 9.4 P4 实际产出（已落地）

四个消费方（外加平台入口本身）各自的落地方式与**语义边界**各不相同，逐个说清楚：

| 消费方 | 落点 | 语义边界 |
|---|---|---|
| **平台入口** | `edap-common` / `io.edap.util.timing.Timers` | 懒创建的平台共享实例；`close()` 幂等，之后再 `get()` 会重建 |
| **Container PREVIOUS 槽释放** | `edap-container` / `Container` + `AppContext` | 删掉了自带的 `ScheduledExecutorService`，改用 `Timers.get().schedule(...)`；停机只撤自己的任务，**不**关平台时间轮 |
| **Raft tick 驱动** | `edap-raft` / `Node.startTicker(TimerService, long)` | 返回 `TimerHandle`，`Node.stop()` 里 `cancel()`；重复调用先撤旧的 |
| **NIO 会话空闲回收** | `edap-nio-server` / `NioSessionIdleReaper` | **默认关闭**，配 `edap.nio.session.idleTimeoutMs` 才启用；见下 |
| **tx timeout** | `edap-tx-jdbc` + `edap-data-jdbc-dao` | **下沉到 `Statement.setQueryTimeout`**，只约束 SQL 执行阶段 |

#### 9.4.1 NIO 会话空闲回收（`edap.nio.session.idleTimeoutMs`）

- **默认关闭**：不配置就完全不起作用，行为与没有这个组件时一模一样。
  配置取值链：系统属性 / 环境变量 → edap.json5（同一个 key）。
- **空闲判定**：`now - max(lastReadTime, lastWriteTime) > idleTimeoutMs`。
  两个时间戳都是墙钟毫秒（与写入侧同源），NTP 校时只影响判定精度，不影响判定方向。
- **扫描周期**从超时阈值推导：`clamp(idleTimeout/4, 1s, 60s)`。实际回收时刻落在
  `[T, T+扫描周期]` 区间内。
- **遍历安全性**：JDK 9+ 的 `SelectorImpl.keys` 底层是 `ConcurrentHashMap.newKeySet()`，
  跨线程遍历不会与 IO 线程的 register/cancel 撞出 CME；`FastSelectorProvider` 只换了
  `selectedKeys`，没动 `keys`。
- **关闭路径统一**：新增 `io.edap.nio.NioSessionCloser`（关 key + 关 channel + 归还对象池），
  `DisruptorReadDispatcher.closeChannel` 与 `NioServerSession.close()` 都走它。
  归还对象池用 `NioServerSession.claimClose(channel)` 的 CAS 闸门 ——
  空闲回收线程与 IO 线程同时关同一条连接时只归还一次，
  避免同一个池化会话进池两次（两条连接共用一个会话实例）。
- **顺带修掉一个既有 bug**：`DisruptorReadDispatcher` 里的 `NIO_SESSION_POOLED` 是
  **static** 字段，多 server 场景下"最后构造的 dispatcher"决定所有 server 是否池化。
  改为按 server 判断（`server.isNioSesionPooled()`）。

#### 9.4.2 tx timeout 的语义

`@Transactional(timeout=)` **下沉到 JDBC 语句层**：`DataSourceTransactionManager.doBegin`
把 timeout 存进 `JdbcTransactionResource`，`TxConnectionHolder.queryTimeoutSeconds()`
读出来，`SingleStatementSession` 建语句时 `stmt.setQueryTimeout(n)`。

- **只约束 SQL 执行阶段** —— 业务方法里的 sleep / 计算不计时。
- 超时取值走**事务资源**而不是 `TransactionStatus.definition()` ——
  REQUIRED 嵌套时内层复用外层事务，超时就是外层那个（与"连接是外层开的"一致）。
- 驱动不支持 `setQueryTimeout` 时 catch + warn，不炸。

#### 9.5 高并发验证（Linux 容器实测，2026-11-07）

§3 的基线数据是 macOS 的，而"扫描式能不能扛 100 万连接"这个问题在 macOS 上**答不了** ——
`kern.maxfilesperproc = 245,760`，单进程最多约 12 万真实连接（每连接 2 个 fd）。
本节是在 Linux 容器里把规模推到 100 万的实测结果。

**环境**

| 项 | 值 |
|---|---|
| 运行时 | Apple `container` 1.5.0（macOS 27.2 arm64），镜像 `debian:13-slim` |
| 内核 | Linux 6.12.28 aarch64（virtio） |
| JDK | Temurin 17.0.21+9 (aarch64)；edap jar 是宿主用 JDK 17 编译的 `0.1.3-SNAPSHOT`（字节码与平台无关） |
| 容器资源 | 8 CPU / 32 GB |
| JVM 参数 | `-Xmx12g --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED` |

##### 9.5.1 fd 额度：真正的瓶颈是单进程 fd 数

容器内实测（`/proc/sys/fs/file-max` 随 `-m` 缩放，比例约 100 × 内存 MB）：

| `-m` | `fs.file-max` | `fs.nr_open` | `ulimit -n` |
|---|---|---|---|
| 1 GB | 113,970 | 1,048,576 | 1,048,576 |
| 16 GB | 1,653,630 | 1,048,576 | 1,048,576 |
| 32 GB | 3,302,678 | 1,048,576 | 1,048,576 |

`/proc/sys` 在容器里是**只读挂载**，`fs.file-max` / `fs.nr_open` 都改不了；
但 `fs.nr_open = 1,048,576` 就是 **`RLIMIT_NOFILE` 的硬天花板** ⇒
**单进程最多约 104 万 fd**。

于是 100 万条 unix domain socket 连接需要「服务端 100 万 + 客户端 100 万」= 200 万 fd，
**必须拆成两个进程**（服务端进程占 100 万，客户端进程占 100 万）——
这是本次验证用 `RealPathServer` / `RealPathClient` 双进程的原因。

> 对照 macOS：单进程 12 万真实连接。**Linux 上限是 macOS 的约 8.7 倍。**

##### 9.5.2 扫描成本：Linux epoll 下的每 key 代价

裸遍历（`selector.keys()` + `key.attachment()` + `key.isValid()`），每 key 只占 1 个 fd：

| 规模 | 每轮耗时 | 每 key |
|---|---|---|
| 10 万 key | 5.24 ms | **52.4 ns** |
| 100 万 key | 83.81 ms | **83.8 ns** |

100 万 key 堆占用 783 MB（822 B/key）。按扫描周期外推 CPU（单核）：
每 15s 扫一次 → **0.56%**；每 60s 扫一次 → **0.14%**。

> macOS kqueue 上 23 万 key 实测 90.7 ns/key，外推 100 万 → 91 ms。
> **Linux 100 万实测 83.8 ms —— 外推是准的**（§3 里那条"外推偏乐观 10~20%"的担心在 epoll 上不成立）。

##### 9.5.3 真实代码路径：100 万连接

走 `IoSelectorManager.registerNioSession` 注册 + `NioSessionIdleReaper.scan()` 扫描，
8 个 selector，unix domain socket：

| 项 | 实测 |
|---|---|
| 建连 + 注册 | 12.7 s（稳态 ~80~93 K conn/s） |
| 服务端 fd | 1,000,035（`RLIMIT_NOFILE` 1,048,576 以内） |
| 客户端 fd | 1,000,017（独立进程） |
| 堆 | 1,122 MB |
| `scan()`（全部活跃） | 135 → 117 → **114 ms**/轮 ⇒ **114~135 ns/key** |
| `scan()`（全部空闲，要关掉 100 万条） | **6,160 ms** ⇒ **6.16 µs/连接** |
| 关闭后 fd | 立刻降到 137，1 秒内到 35 |

真实路径比裸遍历贵约 1.5 倍（114~135 ns vs 83.8 ns）—— 多出来的是
每个会话两次 volatile 读（`lastReadTime` / `lastWriteTime`，对象在堆里是散的，cache miss）。

按 15s 扫描周期算，100 万连接下空闲回收常驻 CPU ≈ **0.76% 单核**。

##### 9.5.4 re-arm 成本（Linux 复测）

模拟 10 万连接、每连接每 10ms 一次 IO（1000 万 IO/秒），跑 5 秒：

| 方案 | 每次 IO | 进程 CPU | 达成 1000 万 IO/秒需要 |
|---|---|---|---|
| 扫描式（只更新一个 volatile long） | **100.2 ns** | 100.20% | **1.00 核** |
| 每连接一个超时任务（cancel + schedule） | **717 ns** | 291.17% | **7.17 核** |

**7.2 倍**（macOS 上是 8.2 倍）。两种平台的结论一致。

##### 9.5.5 100 万定时任务本身（Linux 复测）

| | 分层时间轮 | `ScheduledThreadPoolExecutor` |
|---|---|---|
| 插入 100 万 | 294 ms（294 ns/个） | 117 ms（117 ns/个） |
| 堆占用 | 128 MB（135 B/任务） | 116 MB（122 B/任务） |
| 取消一个 | 12 ns | 12 ns |
| 取消后 | `pendingCount` 正确减半 | **队列仍占 100 万** |

**任务数量不是瓶颈**（100 万个任务完全可行），瓶颈是每次 IO 的 re-arm 成本（§9.5.4）。

##### 9.5.6 半开连接回收的功能验证

场景：客户端跑在独立 network namespace 里，通过 veth 连服务端；建连后
`ip link set veth1 down`（客户端网卡没了）再 `SIGKILL` 客户端 ——
客户端的 FIN/RST **发不出去**，服务端这边就是 **ESTABLISHED 但永远收不到任何数据**的半开连接。

> 这正是老版本 edap 的漏洞：关闭路径只有 `len < 0`（EOF）和读异常两条，
> 且 `AcceptEventHandler` 里显式 `socket.setKeepAlive(false)` ——
> 对端崩溃不发 FIN 的半开连接**永久泄漏 fd / session / 缓冲**。

| 组 | 配置 | 结果 |
|---|---|---|
| 正向 | `idleTimeout=5000ms`（扫描周期 1250ms） | **500 条全部回收，`ALL_CLOSED elapsed_ms=5053`** —— 落在设计的 `[T, T+扫描周期]` = [5000, 6250] 区间内 |
| 对照 | `idleTimeout=3600000ms`（≈ 不启用） | 观察 30 s，服务端仍挂 **500 条 ESTAB** —— **永久泄漏** |

回收器走的是平台时间轮（`Timers.get()`），与线上 `Edap` 启动的路径一致。

##### 9.5.7 这轮验证确认了什么、修正了什么

**确认**
- 扫描式在 100 万连接下成立：常驻 CPU < 1% 单核。
- 真正的瓶颈是**单进程 fd 数**（Linux 104 万 / macOS 12 万），不是定时任务数量，也不是 CPU。
- macOS 上按每 key 成本外推到 100 万的结论是准的。

**修正 / 新增**
- **"10 万长连接 ⇒ 10 万定时任务"这个提法不成立**：任务数本身不是问题，
  决定性成本是**每次 IO 都要 re-arm**（7.2~8.2 倍）。
- **新增一个量级风险点：批量关闭的代价是 6.16 µs/连接。**
  100 万连接同时变空闲时，回收器单线程要跑 **6.2 秒**才能全关完。
  这是回收路径上唯一的量级风险（扫描本身只占 0.76% CPU）→ 见 §10 末行与 §11.7。

> 本节的基准代码在 `doc/bench/timer/`（不在 Maven 构建里），跑法见该目录的 `README.md`。

---

## 10. 风险与已知限制

| 风险 | 说明 | 缓解 |
|---|---|---|
| **Linux 与 macOS 的唤醒成本不同** | §3 的数据是 macOS 的。Linux 的 futex/hrtimer 路径可能便宜 2~4 倍 | P2 已按 §6.2 降级为预留，此风险不再阻塞；若将来重启 P2，仍需在目标机复测 |
| `Selector` 不接受裸 fd | edap 的 NIO 用 JDK 的 `EPollSelectorImpl`（`FastSelectorProvider` 只做反射加速；`edap-nio-native` 只做 native read/write，**没有自研 epoll**） | "timerfd epoll 进事件循环、零额外线程"需要自研 native epoll selector，工程量一个量级以上 → 远期再评估 |
| 分层算法的边界正确性 | 下沉/级联的 off-by-one 是主要风险源 | 已缓解：P1 单测覆盖层边界 + 与朴素 `PriorityQueue` 的差分对照（20 万任务），另有一轮白盒评审（§9.3） |
| `IdGenerator` 的 `synchronized` 每毫秒一次 | 抢实例锁（`setCurTimeMillis` 由 EdapTime 的毫秒档回调驱动，每毫秒一次） | **锁必须保留**：`setCurTimeMillis` 同时改 `seq`/`lastTimestamp`/`curTimestamp`/`curMillisValue`，与 `getId0()` 共享这组状态，去锁就是数据竞争（会重复发号 → 重复 ID）。EdapTime 只是把"读时钟"从系统调用换成缓存读，**写者并没有变成唯一** |
| 溢出堆的 `peek()` 每 tick 一次 | 正常为空，O(1) | 可接受 |
| 已取消的任务滞留在溢出堆 | 堆里没有"就地删除"，只能等它到期被弹出 | 只影响内存占用（到期即释放），不影响正确性 |
| `ON_DEMAND` 下 `IdGenerator` 的回拨历史精度下降 | 不主动刷新就没有"每毫秒一条"的历史，只在**取 ID 时**才拉一次时钟 —— 历史里只有"有 ID 的毫秒" | 回拨到**没有 ID 的毫秒**时抛 `RuntimeException`（安全：宁可不发号，也不出重复 ID）。行为与"回拨超出 10 秒历史窗口"一致，只是窗口从"每毫秒"变成"有 ID 的毫秒"。见 §11.6 |
| **NIO 空闲回收会主动关连接** | 开了 `edap.nio.session.idleTimeoutMs` 之后，超过阈值没读没写的连接会被服务端主动关掉 | **默认关闭**，不配就是老行为。开了就要接受"长连接但完全静默的客户端会被踢"——比如心跳间隔大于 idleTimeout 的场景要配够大的阈值 |
| **批量关闭的成本是 6.16 µs/连接** | 100 万连接同时变空闲时，`scan()` 要跑 **6.2 秒**才能全关完（§9.5.3 实测）。扫描本身只占 0.76% CPU，关连接才是大头 | 目前没有限速：一次扫描关多少就关多少。候选做法是给每轮扫描设关闭条数上限（关不完下一轮继续），把单轮耗时压到扫描周期以内 —— 待定，见 §11.7 |
| **单进程 fd 上限** | Linux `fs.nr_open = 1,048,576` 是 `RLIMIT_NOFILE` 的硬天花板；macOS `kern.maxfilesperproc = 245,760`（约 12 万真实连接）。100 万连接时客户端与服务端必须分属不同进程/机器 | 属部署约束，不是代码问题。实测见 §9.5.1 |

---

## 11. 未决项

1. ~~**Linux 目标机的唤醒成本复测**~~ —— 已不需要：P2 已按 §6.2 降级为预留
   （收益不成立不依赖目标机复测）。若将来重启 P2，再补这一步。
2. ~~**`timerfd` + JNI 的模块归属**~~ —— 随 P2 一起搁置。`TickSource` SPI 已在 `edap-common`，
   将来实现时放 `edap-native` / `io.edap.jni.timing` 仍是自然选择（`edap-native` 依赖 `edap-common` 不成环：
   `edap-crypto` 只在 test scope 依赖 `edap-native`，且不依赖 `edap-common`）。
3. **平台共享执行器的大小与队列策略** —— **已定**："CPU 核数（至少 2）+ **无界**队列"。
   原计划是"1024 有界队列 + AbortPolicy"，实际改成无界：定时任务被丢掉意味着
   "该释放的资源没释放"，这种失败模式比队列涨更难查。队列只在"任务产出速度 > 执行速度"时才会涨。
   是否需要按业务隔离线程池，留到实际负载出来再定。
4. ~~**`@Transactional(timeout=)` 的语义**~~ —— **已定：下沉到 `Statement.setQueryTimeout`**。
   JDBC 协议层没有"事务超时"这个概念，唯一能表达"这条 SQL 最多跑多久"的就是
   `Statement.setQueryTimeout(int)`。timeout 只约束 SQL 执行阶段（业务方法里的 sleep / 计算不计时）。
   不做 watchdog 标记回滚，也不中断业务线程。落地细节见 §9.4.2。
5. **集群化形态** —— hash 分片亲和 vs Raft leader-only，待单机版本跑稳后再定。
6. **`ON_DEMAND` 模式要不要保留 `IdGenerator` 的回拨续号能力** —— 不主动刷新就没有
   "每毫秒一条"的历史，回拨到没有 ID 的毫秒会抛错（§10 末行）。
   待定：是接受这个限制（只在低并发/读时钟少的场景开 `ON_DEMAND`），
   还是让 `IdGenerator` 在 `ON_DEMAND` 下**按需补记历史**（比如每次拉时钟时把中间跳过的毫秒补进环形队列）。
   后者能把精度恢复到"每毫秒"，代价是回拨窗口内的补记开销（最多 10 秒 = 1 万条）。
7. **空闲回收要不要给每轮扫描设关闭条数上限** —— §9.5.3 实测：关一条连接 6.16 µs，
   100 万连接同时空闲时单轮要跑 6.2 秒。
   不限速的后果：回收线程连续 6 秒占着一个核，且这段时间里新来的连接要等下一轮才被判定。
   限速的做法：每轮最多关 N 条（剩下的下一轮继续），把单轮耗时压到扫描周期以内。
   待定的是 N 怎么取（按扫描周期反推？还是固定条数？），
   以及"关不完"要不要对外可见（现在 `scan()` 只返回本轮关掉的条数，调用方看不出还有多少欠账）。

---

## 附：模块落位

| 内容 | 位置 |
|---|---|
| 时间门面 | `edap-common` / `io.edap.util.EdapTime`（已存在，就地改造） |
| 时间轮 | `edap-common` / `io.edap.util.timing` |
| `timerfd` JNI | （预留）`edap-native` / `io.edap.jni.timing` |

`edap-common` 无运行时依赖，时间轮本体只用 JDK 类。
