# Edap / Container / 事务（Transaction）详细设计

> 本文档定义 edap 容器层事务管理的完整抽象、传播模型、资源管理与扩展点。
>
> **目标读者**：在 `edap-container-parent` / `edap-tx-parent`（未来拆分）模块下做事务相关改动的开发者。
>
> **前置文档**：
>
> - [`README.md`](../README.md) §13「容器核心类设计」——给出顶层架构全景。
> - [`CONTAINER_APPCONTEXT_DESIGN.md`](./CONTAINER_APPCONTEXT_DESIGN.md)——定义 Container / AppContext / RouterHub 三层架构，本文档是该架构下的事务子系统。
> - [`WS_HANDLER_DESIGN.md`](./WS_HANDLER_DESIGN.md)——展示"协议适配层 / 横切关注点层"分离的范式（WS handler 也是协议适配层、不烧业务逻辑进字节码），本文档的事务拦截器同样遵循此分层。

---

## 一、目标与范围

### 1.1 设计目标

1. **业务侧声明式事务**。开发者用 `@Transactional` 标注方法或类，edap 容器自动织入拦截器，不写 try / catch / commit / rollback 模板代码。
2. **完整传播模型**。实现 Spring `Propagation` 全部 7 种语义（`REQUIRED` / `REQUIRES_NEW` / `NESTED` / `SUPPORTS` / `NOT_SUPPORTED` / `MANDATORY` / `NEVER`），覆盖分布式/嵌套/只读/强制存在等全部典型场景。
3. **资源句柄抽象**。事务管理器接口不绑定 JDBC `Connection`——未来扩展 Seata TCC / Saga / XA 时，下游实现只需替换资源类型，拦截器层零改动。
4. **线程模型清晰**。事务状态与资源通过 ThreadLocal 绑定，支持挂起/恢复（`SuspendedResourcesHolder`），与 NIO event loop 复用兼容。
5. **edap 自有而非 Spring 兼容层**。不引入 `spring-tx` 依赖，注解/异常/接口都是 edap 命名空间内的独立实现——后续要集成分布式事务时不被 Spring 生态绑死。

### 1.2 不在本文档范围内

- **XA / 二阶段提交协议实现**——本文档只预留扩展点，具体协议实现交给后续子模块
- **响应式事务**（`Mono` / `Flux` 上的事务编排）——edap 当前模型是同步阻塞
- **多数据源事务管理器**的路由策略——单 manager 单数据源是 1.0 目标，多数据源是 2.0 议题
- **业务侧 `@Transactional` 注解的 AOP 代理字节码生成**——属于 edap 拦截链（`AroundInterceptor`）的能力，本文只定义事务拦截器接口
- **持久化层的具体集成**（MyBatis / Hibernate / JPA）——只规定 `DataSource.getConnection()` 由业务侧显式获取

### 1.3 三层组件边界

| 层级 | 接口 / 类 | 角色 | 数量 |
|------|-----------|------|------|
| 注解层 | `io.edap.tx.annotation.Transactional` / `io.edap.tx.annotation.ManualTransaction` | 业务侧声明切点 + 事务定义 | 每方法/类 1 个 |
| 拦截层 | `io.edap.container.transactional.TransactionalClassGenerator` (ASM wrapper) | 直接生成 wrapper 字节码;调用 `EdapTransactionManager.getTransaction(def)` + `commit/rollback` + `TransactionContext.bind/unbind` | 每个 bean 1 个 wrapper class |
| 管理层 | `io.edap.tx.EdapTransactionManager` + `DefaultEdapTransactionManager`(基类)+ 多个 impl（`DataSourceTransactionManager` / 未来的 `SeataGlobalTransactionManager`） | 真正的资源获取/提交/回滚逻辑 | 每种资源类型 1 个 |
| 资源抽象 | `io.edap.tx.TransactionResource` | 持有资源的统一接口（commit / rollback / 同步点注册） | 每个事务 1 个 |
| 线程绑定 | `io.edap.tx.TxScope` (单 ThreadLocal 持有 `TxSnapshot`) + `TxSnapshot`(不可变快照) | 单 ThreadLocal 替代原 6 个 ThreadLocal;suspend/resume 通过 `TxScope.swap()` 原子交换 | 单例工具类 |
| 业务侧 ctx | `io.edap.tx.TransactionContext` | `@ManualTransaction` 路径下业务方拿到的 ctx(commit/rollback/setRollbackOnly) | 静态工厂 `bind` + `current()` |
| 状态对象 | `TransactionDefinition` / `TransactionStatus` | 不可变定义 + 运行时状态 | 每个事务 1 对 |

```
业务方法 @Transactional(REQUIRES_NEW)
   │
   ▼
[edap bean 拦截链 AroundInterceptor.invoke()]
   │
   ▼
TransactionInterceptor.invoke(bean, method, args)
   │ 1. 解析 @Transactional → TransactionDefinition
   │ 2. 调 EdapTransactionManager.getTransaction(def)
   │    ├─ 当前 ThreadLocal 有 tx？→ 传播决策矩阵
   │    └─ 当前无 tx？→ 直接走"开新"路径
   │ 3. try { 调用原方法 } catch { rollback } finally { 清理 }
   ▼
DataSourceTransactionManager（impl）
   │ ├─ 取 Connection（绑到当前 ThreadLocal）
   │ ├─ 切换 autoCommit
   │ └─ 执行业务方法
   ▼
业务方法体（拿 DataSource.getConnection() 自己用）
```

### 1.4 与现有架构的关系

- **拦截链**：复用 edap 已有的 `AroundInterceptor` 机制（bean 拦截链），不重做 AOP 框架。事务拦截器作为普通 bean 注册即可。
- **异常体系**：事务异常（`TransactionException`）放 `io.edap.container.tx.exception` 包，**继承 `RuntimeException`**——遵循 edap 容器异常一律 runtime 的约定（详见 `feedback_edap_exc_runtime.md`）。
- **ClassLoader 隔离**：事务管理器实现类由 `containerCL` 加载，业务侧只依赖接口——和现有 `JwtService` 跨 CL 注入模式同构，避免 `appCL` 看不到 `DefaultJwtService` 那类历史问题。

---

## 二、核心抽象

### 2.1 `Propagation` 枚举

7 种传播模型。命名与 Spring 完全一致——便于开发者迁移，但**实现独立**。

| 值 | 语义 | 当前无 tx | 当前有 tx |
|---|------|----------|----------|
| `REQUIRED`（默认） | 有则复用、无则新建 | 开新 | 复用（计数 +1） |
| `REQUIRES_NEW` | 永远开新，挂起当前 | 开新 | 挂起当前、开新 |
| `NESTED` | 有则嵌套（savepoint），无则新建 | 开新 | 复用 + savepoint |
| `SUPPORTS` | 有则用、无则不用 | 跑非事务 | 复用 |
| `NOT_SUPPORTED` | 永远非事务，挂起当前 | 跑非事务 | 挂起当前、跑非事务 |
| `MANDATORY` | 必须有，否则抛异常 | 抛 `IllegalTransactionStateException` | 复用 |
| `NEVER` | 必须无，否则抛异常 | 跑非事务 | 抛 `IllegalTransactionStateException` |

### 2.2 `Isolation` 枚举

4 种隔离级别。**第一期只声明枚举、不实现 JDBC `setTransactionIsolation()`**——后续按需启用，默认走底层默认级别。

```java
public enum Isolation {
    DEFAULT(-1),       // 用底层数据源/驱动默认
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);
}
```

### 2.3 `TransactionDefinition`（不可变）

把传播模型、隔离级别、超时、只读、异常回滚规则打包：

```java
public final class TransactionDefinition {
    private final Propagation propagation;
    private final Isolation isolation;
    private final int timeout;              // 秒，-1 表示无超时
    private final boolean readOnly;
    private final Class<? extends Throwable>[] rollbackFor;
    private final Class<? extends Throwable>[] noRollbackFor;
    private final String name;              // 可选事务名（用于日志/监控）
    // 构造器、getter、equals/hashCode（不可变必加）
}
```

**默认回滚规则**（与 Spring 对齐）：
- `RuntimeException` / `Error` → 默认回滚
- `checked Exception` → 默认不回滚（业务侧可显式声明 `rollbackFor = Exception.class`）

### 2.4 `TransactionStatus`（可变运行时态）

```java
public final class TransactionStatus {
    private final TransactionDefinition definition;
    private final TransactionResource resource;       // null 表示非事务
    private final boolean newTransaction;              // 是否本次新建（区别于复用）
    private final boolean newSynchronization;          // 是否本次新建同步点
    private final boolean readOnly;

    // 嵌套 REQUIRED 计数 + 挂起快照
    private int nestingCount;                           // REQUIRED 嵌套层数

    private boolean rollbackOnly;
    private boolean completed;                         // commit/rollback 后置 true

    // REQUIRES_NEW / NOT_SUPPORTED 场景下挂起的外层事务完整快照
    // 替换原 List<Object> suspendedResources —— 由 TxSnapshot 持有 status +
    // synchronizations + resources + xid + context 的整体快照
    private TxSnapshot suspendedSnapshot;

    // NESTED 场景下挂的 savepoint 引用,rollback 时回滚到该 savepoint
    private Object savepoint;
}
```

**关键设计**：
- `completed` 标志——`commit()` / `rollback()` 后置 true,manager 的 stale 检测据此判断 ThreadLocal 上的 status 是否为残留;wrapper finally 块据此跳过已 commit 的 status
- `nestingCount`——`REQUIRED` 嵌套调用时计数 +1,最外层 commit 时才真正提交,内层只 decrement
- `suspendedSnapshot`——`REQUIRES_NEW` / `NOT_SUPPORTED` 时把当前线程的完整 `TxSnapshot` 快照存到这里,本事务结束时通过 `txScope.swap(suspendedSnapshot)` 原子恢复

### 2.5 `TransactionResource` 接口

**最关键的扩展点**——这是分布式事务能插进来的地方。

```java
public interface TransactionResource {
    /** 提交当前资源 */
    void commit() throws TransactionException;

    /** 回滚当前资源 */
    void rollback() throws TransactionException;

    /** 注册事务完成后的同步回调（afterCommit / afterCompletion） */
    void registerSynchronization(Synchronization sync) throws TransactionException;

    /** 资源是否已标记为 setRollbackOnly */
    boolean isRollbackOnly();
}
```

**实现层**：
- `JdbcConnectionResource`——持有 `Connection` + 当前隔离级别/autoCommit 状态
- 未来的 `SeataTccResource`——持有 TCC 三阶段句柄
- 未来的 `XaResource`——持有 `XAResource`

**为什么不让 `EdapTransactionManager` 直接持有 `Connection`**：
- 分布式事务没有"一个 Connection"概念，资源可能是 TCC 三段句柄集合
- 抽象出 `TransactionResource` 后，manager 接口永远不变，新增资源类型只需新增一个 `*Resource` 实现 + 一个 `*TransactionManager` 实现

### 2.6 `EdapTransactionManager` 接口

```java
public interface EdapTransactionManager {
    /**
     * 根据传播决策：决定开新 / 复用 / 挂起 / 抛异常。
     * 返回 TransactionStatus，status.resource 可能为 null（非事务场景）。
     */
    TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException;

    /** 提交。嵌套场景下只在最外层真正提交。 */
    void commit(TransactionStatus status) throws TransactionException;

    /** 回滚。嵌套场景下回滚到 savepoint 或全部。 */
    void rollback(TransactionStatus status) throws TransactionException;

    /** 当前线程是否存在活跃事务 */
    boolean hasResource();
}
```

**实现层**：
- `DataSourceTransactionManager`——Phase 2 实现，处理 JDBC
- 未来的 `SeataGlobalTransactionManager`——全局事务协调
- 未来的 `JtaTransactionManager`——多数据源 XA 协调

### 2.7 `TxScope` + `TxSnapshot`（单 ThreadLocal 工具 + 不可变快照）

**TxSnapshot** —— 不可变快照,持有事务线程的全部状态:

```java
public final class TxSnapshot {
    private final TransactionStatus status;                  // 当前活跃事务 status(非事务时 null)
    private final List<Synchronization> synchronizations;    // 同步点列表(未 init 时 null)
    private final Map<Object, Object> resources;             // 多资源映射(XA 场景)
    private final String xid;                                // 全局事务 ID(RPC 透传用)
    private final TransactionContext context;                // 业务侧 ctx(由 wrapper bind 写入)

    public static TxSnapshot empty();                        // 空快照 factory
    public TxSnapshot withStatus(TransactionStatus);
    public TxSnapshot withSynchronizations(List<Synchronization>);
    public TxSnapshot withResources(Map<Object, Object>);
    public TxSnapshot withContext(TransactionContext);
}
```

**TxScope** —— 单 ThreadLocal 持有 `TxSnapshot`,替代原 6 个 ThreadLocal 散装工具:

```java
public final class TxScope {
    private static final ThreadLocal<TxSnapshot> CURRENT = new ThreadLocal<>();

    // 直接读写
    public static TxSnapshot current();
    public static void setCurrent(TxSnapshot snapshot);
    public static void clear();

    // 原子交换 —— 挂起 / 恢复的核心原语
    public static TxSnapshot swap(TxSnapshot next);

    // 便捷读取(空时返回 null / 空集合,不抛)
    public static TransactionStatus currentStatus();
    public static List<Synchronization> currentSynchronizations();
    public static Map<Object, Object> currentResources();
    public static String currentXid();
    public static boolean isTransactionActive();

    // synchronizations 列表管理
    public static boolean isSynchronizationActive();
    public static void initSynchronization();
    public static void addSynchronization(Synchronization sync);
    public static List<Synchronization> clearSynchronization();
}
```

**核心原语 `swap(next)`** —— 原子交换,返回旧 snapshot(suspend/resume 的基础):

```java
// 挂起:把当前 snapshot 保存,清空 ThreadLocal
TxSnapshot suspended = TxScope.swap(TxSnapshot.empty());
newStatus.setSuspendedSnapshot(suspended);

// 恢复:从 status 取回保存的 snapshot,swap 回去
TxScope.swap(status.getSuspendedSnapshot());
status.setSuspendedSnapshot(null);
```

**为什么是单 ThreadLocal 而非 6 个散装**:
- **原子性** —— suspend 时一次性原子快照 status + resources + synchronizations,避免多 ThreadLocal 间状态不一致
- **跨 CL 注入稳定性** —— `TxConnectionHolder` 等外部模块通过 SPI 单例加载,只读 TxScope 即可拿到当前 status,不再依赖多个 ThreadLocal 实例的可见性
- **简化 wrapper 字节码** —— wrapper 入口只需 `tm.getTransaction(def)` + `TransactionContext.bind(...)`,不直接读写 ThreadLocal,不再需要 wrapperDepth 防御层
- **stale state 检测** —— manager 在 `getTransaction` 入口检测 `status.isCompleted()`(wrapper bug 路径漏 unbind 导致 ThreadLocal 残留),自动重置,避免 REQUIRED 嵌套计数错误膨胀

**关键变化 vs 旧设计**:
- 移除 `suspend()` / `resumeSuspended()` / `SuspendedResources` 抽象 —— 由 manager 直接 `TxScope.swap()` 实现
- 移除 `wrapperDepth` / `callerDepth` ThreadLocal —— proper wrapper discipline 不需要 stale 防御
- 移除 `bindResource` / `unbindResource` —— 通过 `snapshot.withResources()` 派生新 snapshot 实现

### 2.8 `Synchronization` 接口（同步点）

```java
public interface Synchronization {
    /** 提交前 */
    default void beforeCommit() {}

    /** 提交后（已成功提交，可能仍抛异常——需自行处理） */
    default void afterCommit() {}

    /** 完成时（无论 commit / rollback 都调，status 参数区分结果） */
    default void afterCompletion(int status) {
        // STATUS_COMMITTED / STATUS_ROLLED_BACK / STATUS_UNKNOWN
    }
}
```

**为什么 Day 1 就有这个接口**：
- 后续分布式事务的事件通知（消息发件箱 / 缓存清理 / 二阶段补偿）依赖 `afterCommit` / `afterCompletion` 回调
- 现在不定义，等到分布式阶段再补，所有 manager 实现都要改一遍

---

## 三、传播决策矩阵（核心状态机）

`TransactionInterceptor` 进入时第一步就是这张表。把"当前线程状态 × 注解传播"映射到具体动作：

```
              │ 当前无 tx       │ 当前有 tx           │ 当前有嵌套 savepoint
──────────────┼─────────────────┼─────────────────────┼─────────────────────────
REQUIRED      │ 开新            │ 复用,nestingCount+1 │ 复用,savepoint 计数+1
REQUIRES_NEW  │ 开新            │ 挂起当前,开新       │ 挂起当前,开新
NESTED        │ 开新            │ 复用,打 savepoint   │ 复用,子 savepoint
SUPPORTS      │ 非事务          │ 复用                │ 复用
NOT_SUPPORTED │ 非事务          │ 挂起当前,非事务     │ 挂起当前,非事务
MANDATORY     │ 抛异常          │ 复用                │ 复用
NEVER         │ 非事务          │ 抛异常              │ 抛异常
```

### 3.1 挂起 / 恢复的栈帧模型

```
ThreadLocal:  TxScope.CURRENT → TxSnapshot[depth=1]
              (持有 status + synchronizations + resources + xid + context)

状态           snapshot 示意
─────────      ──────────────────────────────────────────────
外层 REQUIRED  { status=s1, sync=[..], resources={..} }
  内层 REQ     { status=s1 (复用,count=2), sync=[..], resources={..} }
  内层 REQNEW  { status=s3, sync=[..], resources={..} }   ← outer snapshot 存到 s3.suspendedSnapshot
  内层结束     { status=s1, sync=[outer-syncs], resources={..} }   ← swap(s1.suspendedSnapshot) 恢复
外层结束       (空 snapshot)
```

**实现细节**:
- **TxScope.CURRENT 是单 ThreadLocal,持有 TxSnapshot**——不像旧设计是 status + synchronizations + resources + xid + suspendedXids 等 6 个 ThreadLocal 各自散装
- **挂起(suspend)**:manager 在 REQUIRES_NEW / NOT_SUPPORTED 路径上 `TxScope.swap(TxSnapshot.empty())`,把旧 snapshot 一次性原子保存到新 status 的 `suspendedSnapshot` 字段
- **恢复(resume)**:manager 在 cleanup 阶段 `TxScope.swap(status.getSuspendedSnapshot())`,把 snapshot 整体写回 ThreadLocal
- **多层嵌套**:旧 snapshot 里嵌套引用上一次挂起的 status + synchronizations + resources,逐层 resume 时一并恢复 —— 不会出现"半恢复"中间态
- **stale state 检测**:manager 入口检测 ThreadLocal 上 `status.isCompleted()`(wrapper bug 路径漏 unbind 的残留),视为无事务,避免 REQUIRED 嵌套计数错误膨胀

**为什么是单 snapshot 而非栈**:
- 旧设计有 "suspend 时把 status + resources 各自压栈" 的双栈一致性维护负担
- 新设计把 status + synchronizations + resources + xid + context 一起塞进 TxSnapshot 不可变对象,挂起时一次性 swap,suspendedSnapshot 引用由 status 持有 —— 单一所有权,无双栈对齐问题

### 3.2 NESTED 与 REQUIRED 的本质区别

| 维度 | REQUIRED | NESTED |
|------|---------|--------|
| 失败回滚 | 全部回滚到事务起点 | 回滚到 savepoint |
| 外层能看到内层修改 | 能 | 不能（savepoint 后） |
| savepoint 支持 | 不需要 | 需要底层（JDBC `setSavepoint()`） |
| 嵌套计数 | nestingCount | savepoint 链 |

**关键**：NESTED 需要底层支持 savepoint（JDBC 标准能力，但不是所有驱动都实现）——`DataSourceTransactionManager` 检测到驱动不支持时，应**降级为 REQUIRED 行为并打 WARN 日志**，避免悄无声息地回滚语义错误。

### 3.3 异常路径

| 场景 | 处置 |
|------|------|
| `MANDATORY` 无当前 tx | 抛 `IllegalTransactionStateException("No existing transaction found for transaction marked with propagation 'mandatory'")` |
| `NEVER` 有当前 tx | 抛 `IllegalTransactionStateException("Existing transaction found for transaction marked with propagation 'never'")` |
| 驱动不支持 savepoint 但用了 NESTED | 打 WARN，降级 REQUIRED；后续若真用到 savepoint 抛 `NestedTransactionNotSupportedException` |
| 业务方法抛 `rollbackFor` 之外的异常 | 仍然 commit（不污染事务） |
| 业务方法抛 `noRollbackFor` 中的异常 | 仍然 commit（显式忽略） |
| commit 阶段异常 | 标记 `completed`，抛 `TransactionSystemException`，不再次 rollback（避免双重回滚） |
| commit/rollback 后调用 `commit/rollback` | 抛 `IllegalTransactionStateException("Transaction is already completed")` |

---

## 四、生命周期

### 4.1 完整事务生命周期

```
TransactionInterceptor.invoke(bean, method, args, def)
│
├─ [阶段 1: 决策]
│   ├─ if (def == null) → 非事务调用,直接 proceed()
│   └─ else → getTransaction(def)
│
├─ [阶段 2: 资源获取] getTransaction(def)
│   ├─ 查 currentStatus（ThreadLocal）
│   ├─ 根据 def.propagation 走决策矩阵:
│   │   ├─ REQUIRED/REQUIRES_NEW/NESTED/SUPPORTS/MANDATORY/NEVER/NOT_SUPPORTED
│   ├─ 如果需要挂起 → suspend(currentStatus)
│   ├─ 如果需要开新 → manager.doBegin() → 创建 TransactionResource → bind 到 ThreadLocal
│   └─ 返回 TransactionStatus
│
├─ [阶段 3: 同步点激活]
│   └─ if (status.isNewSynchronization()) → 初始化 synchronizations 列表
│
├─ [阶段 4: 业务调用] try
│   ├─ proceed() → 执行业务方法体
│   └─ 业务侧抛异常 → catch 块
│
├─ [阶段 5: 异常分类] catch (Throwable ex)
│   ├─ rollbackFor 命中 或 默认回滚规则命中 → rollback(status)
│   ├─ noRollbackFor 命中 → 不回滚
│   └─ 其他 → 不回滚
│
├─ [阶段 6: 提交/回滚]
│   ├─ 正常完成 + 非 rollbackOnly → commit(status)
│   │   ├─ 调用所有 Synchronization.beforeCommit()
│   │   ├─ resource.commit()
│   │   ├─ 调用所有 Synchronization.afterCommit()
│   │   └─ 调用所有 Synchronization.afterCompletion(STATUS_COMMITTED)
│   └─ 异常/rollbackOnly → rollback(status)
│       ├─ resource.rollback()
│       └─ 调用所有 Synchronization.afterCompletion(STATUS_ROLLED_BACK)
│
└─ [阶段 7: 清理] finally
    ├─ markCompleted()                       ← status 标 completed(防双重提交)
    ├─ clearSynchronization()                ← TxScope 取出并清空 sync 列表
    ├─ setCurrent(snapshot.withStatus(null)) ← 从 snapshot 移除 status
    └─ if (suspendedSnapshot != null) → TxScope.swap(suspendedSnapshot)   ← 恢复挂起的外层事务
```

### 4.2 嵌套调用示例

业务方法 `outer` 标 `REQUIRED`，`inner` 标 `REQUIRES_NEW`：

```java
@Transactional
public void outer() {
    jdbcTemplate.update("INSERT INTO a ...");
    try {
        inner();  // 独立事务提交,a 的写入如果 inner 失败仍然能保留
    } catch (Exception e) {
        log.warn("inner failed", e);
    }
    jdbcTemplate.update("INSERT INTO b ...");
}

@Transactional(REQUIRES_NEW)
public void inner() {
    jdbcTemplate.update("INSERT INTO c ...");
    throw new RuntimeException("oops");
}
```

**执行轨迹**：
1. `outer()` 进入 → 决策矩阵：当前无 tx，`REQUIRED` 开新，tx1 (REQUIRED, count=1)
2. `inner()` 进入 → 决策矩阵：当前有 tx1，`REQUIRES_NEW` 挂起 tx1，开新 tx2
3. `inner()` 抛异常 → tx2 rollback → 恢复 tx1 → tx1.status 仍存活
4. `outer()` catch 住 → 继续执行 INSERT b → 正常 commit tx1 → a + b 写入，c 没写入

### 4.3 与 edap handler 字节码的关系

`HttpHandlerGenerator` 生成的 handler **不**烧事务字节码：

```java
// edap 生成的 OrderServiceHandler.handle (概念示意)
public void handle(HttpRequest req, HttpResponse resp) {
    DemoRequest body = Eson.parseObject(req.getBody().getBytes(), DemoRequest.class);
    DemoResponse result = bean.createOrder(body);   // ← 拦截链在此织入
    Eson.toJsonString(result, resp);
}
```

**事务逻辑在拦截链上**——`bean.createOrder` 是经过 edap 拦截链包装的代理对象（具体形态取决于 edap AOP 实现），拦截链中包含 `TransactionInterceptor`，自动处理传播/提交/回滚。

**为什么不在字节码层烧**：handler 是协议适配层（见 WS_HANDLER_DESIGN.md 同构设计），事务是横切关注点——分层隔离，避免字节码体积爆炸和回归测试噩梦。

---

## 五、分布式事务：edap-pg-proxy 用户名路由 + 智能事务

> **核心设计**：所有 SQL **统一走 edap-pg-proxy**——数据库代理根据 **JDBC 用户名**路由到具体 PG 实例，并根据 **xid 是否跨实例**自动决定走**单连接本地事务**还是 **XA 分布式事务**。
>
> **业务代码完全无感**：继续写 `@Transactional`，不解析 SQL、不感知数据库拓扑、不感知事务策略——edap 容器只发 XA 命令，数据库代理观察命令自动协调。

### 5.1 整体架构：业务实例 → 数据库代理 → PG 集群

```
┌─────────────────────────────────────────────────────────────────────┐
│ edap 业务实例集群（N 个,N 为分片亲和 shard 数）                      │
│                                                                      │
│  HTTP/RPC 入口                                                       │
│       │                                                              │
│       ▼                                                              │
│  ClusterShardRouter:按 userId 路由到本 shard 实例                     │
│       │                                                              │
│       ▼                                                              │
│  @Transactional 业务方法                                              │
│       │                                                              │
│       ▼                                                              │
│  TransactionInterceptor(极简)                                        │
│       │ 1. 分配/复用 global xid                                       │
│       │ 2. 发 XA START 'xid' on 当前 JDBC 连接                       │
│       │ 3. 执行业务方法(业务代码正常写 DAO)                           │
│       │ 4. 发 XA COMMIT 'xid'(数据库代理智能协调)                    │
│       ▼                                                              │
│  业务 DAO 调用 → JDBC(用户名带分片标识)                              │
│       ├─ ordersDAO  →  user=orders_app@pg-x                          │
│       ├─ couponsDAO  → user=coupons_app@pg-x                         │
│       └─ paymentsDAO → user=payments_app@pg-y                        │
│                                                                      │
│  RPC 调下游:RPC 帧带 xid                                             │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ 所有 JDBC 连接都指向 edap-pg-proxy:5432
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│ edap-pg-proxy(统一入口 + 用户名路由 + 智能事务)                      │
│                                                                      │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ PG 前端协议 Server(监听 5432)                       │            │
│  │   - 解析 PG 协议                                     │            │
│  │   - 提取 JDBC 用户名(如 orders_app@pg-x)             │            │
│  │   - 转发 SQL 到后端 PG                               │            │
│  └─────────────────────┬───────────────────────────────┘            │
│                        │                                            │
│                        ▼                                            │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ 用户名路由表(配置项)                                 │            │
│  │   orders_app    → pg-x                              │            │
│  │   coupons_app   → pg-x                              │            │
│  │   inventory_app → pg-x                              │            │
│  │   payments_app  → pg-y                              │            │
│  │   users_app     → pg-x                              │            │
│  └─────────────────────┬───────────────────────────────┘            │
│                        │                                            │
│                        ▼                                            │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ xid 路由表(运行时维护)                               │            │
│  │   xid-100 → {pg-x-conn-1}              ← 同库       │            │
│  │   xid-200 → {pg-x-conn-2, pg-y-conn-1} ← 跨库(XA)  │            │
│  └─────────────────────┬───────────────────────────────┘            │
│                        │                                            │
│                        ▼                                            │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ 智能事务协调                                         │            │
│  │   - 同库(xid 单 PG 实例)→ 合并到单连接本地事务      │            │
│  │   - 跨库(xid 多 PG 实例)→ XA 二阶段协调            │            │
│  └─────────────────────┬───────────────────────────────┘            │
│                        │                                            │
│                        ▼                                            │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ 后端连接池(按 PG 实例分池)                          │            │
│  │   pg-x-pool  ──→ PG-X                               │            │
│  │   pg-y-pool  ──→ PG-Y                               │            │
│  └─────────────────────┬───────────────────────────────┘            │
│                        │                                            │
│                        ▼                                            │
│  ┌─────────────────────────────────────────────────────┐            │
│  │ 启动恢复:扫描所有 PG 的 pg_prepared_xacts           │            │
│  │          强制 ROLLBACK PREPARED 所有残留 xid         │            │
│  └─────────────────────────────────────────────────────┘            │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ JDBC(按用户名路由分发)
                            ▼
┌──────────────────────────────┐    ┌──────────────────────────────┐
│ PG-X                         │    │ PG-Y                         │
│  orders / coupons / users    │    │  payments / refunds          │
│  inventory                   │    │                              │
│                              │    │                              │
│  XA 状态权威:                │    │                              │
│   pg_prepared_xacts(WAL)     │    │                              │
└──────────────────────────────┘    └──────────────────────────────┘
```

**核心特征**：
- **业务实例零 PG 权限**——所有 JDBC 连 edap-pg-proxy,用 edap 账号
- **edap-pg-proxy 统一权限**——后端 PG 用统一的 proxy_service_account
- **edap-pg-proxy 零 SQL 解析**——只看用户名就知道目标 PG
- **edap 不参与事务协调**——只发 XA 命令,数据库代理自动协调
- **业务侧零感知**——继续写 `@Transactional`

### 5.2 核心洞察:用户名路由 + 智能事务

#### 5.2.1 用户名路由取代 SQL 解析

**传统方案**:
- edap-pg-proxy 解析 SQL,识别目标表 → 映射到 PG 实例
- 需要写 SQL parser,性能开销,配置复杂

**edap 方案**:
- JDBC 用户名自带分片标识:`{service}_app@{pg-instance}`
- edap-pg-proxy 解析用户名,直接知道目标 PG
- **零 SQL 解析,零 SQL parser,零配置复杂度**

**用户名格式约定**:

| 格式 | 含义 | 示例 |
|------|------|------|
| `{service}_app@{pg}` | 读写用户,连指定 PG | `orders_app@pg-x` |
| `{service}_ro@{pg}` | 只读用户,连 PG 只读副本 | `orders_ro@pg-x-ro` |
| `{service}_app@{pg-shard}` | 多 shard 场景,按 shard 路由 | `orders_app@pg-x-1` |

**配置示例(edap-pg-proxy)**:

```toml
[user-routing]
orders_app@pg-x      = "pg-x"
orders_ro@pg-x-ro    = "pg-x-ro"
coupons_app@pg-x     = "pg-x"
inventory_app@pg-x   = "pg-x"
users_app@pg-x       = "pg-x"
payments_app@pg-y    = "pg-y"
payments_ro@pg-y-ro  = "pg-y-ro"
```

#### 5.2.2 自适应事务:同库本地 / 跨库 XA

**edap-pg-proxy 维护 xid 路由表**(运行时):

```
xid-100: [{ pg: pg-x,  conn: pg-x-pool-1 }]                    ← 单 PG,本地事务
xid-200: [{ pg: pg-x,  conn: pg-x-pool-2 },                     ← 多 PG,XA 协调
          { pg: pg-y,  conn: pg-y-pool-1 }]
```

**事务路由决策**(每次新连接 + XA START 时):

```
onReceive(conn, xid, sql):
    currentPg = userRouting[conn.user]      // 当前连接的目标 PG
    xidEntries = xidTable[xid]              // 当前 xid 已绑定的 PG 集合
    
    if xidEntries is empty:
        // 第一个连接,直接绑定
        xidEntries.add({ pg: currentPg, conn: backendConn })
        forwardToBackend(conn, sql)
    
    else if any entry in xidEntries has same pg == currentPg:
        // 同库,合并到已有后端连接
        existingConn = find entry where entry.pg == currentPg
        forwardToBackend(existingConn, sql)
    
    else:
        // 跨库,开新连接 + 启用 XA
        xidEntries.add({ pg: currentPg, conn: backendConn, xaMode: true })
        forwardToBackend(conn, sql)
```

**事务提交决策**(XA COMMIT 时):

```
onReceiveXaCommit(xid):
    entries = xidTable[xid]
    pgSet = unique(entries[].pg)
    
    if len(pgSet) == 1:
        // 单 PG 实例 → 单连接本地事务
        entries[0].conn.execute("COMMIT")          // 普通 COMMIT
    
    else:
        // 多 PG 实例 → XA 二阶段协调
        for entry in entries:
            entry.conn.execute("XA PREPARE '" + xid + "'")
        
        // 全部 PREPARE 成功 → 全部 COMMIT
        for entry in entries:
            entry.conn.execute("XA COMMIT '" + xid + "'")
```

**关键**:
- **同库** → 普通 `COMMIT`(单连接本地事务,最强一致性,零 XA 开销)
- **跨库** → `XA PREPARE` + `XA COMMIT`(分布式事务,PG 协议层协调)
- **edap-pg-proxy 自然判断**,零 RPC 协调,零 SQL 解析

### 5.3 edap 容器职责(极简)

edap 容器在分布式事务场景下**只做四件事**:

#### 5.3.1 `@Transactional` 拦截器(发 XA 命令)

```java
public class TransactionInterceptor implements AroundInterceptor {
    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        // 1. 拿 xid(从上游 RPC 透传 or 新分配)
        String xid = TransactionContext.getCurrentXid();
        if (xid == null) {
            xid = UUID.randomUUID().toString();
            TransactionContext.setCurrentXid(xid);
        }
        
        // 2. 发 XA START:绑定 xid 到当前 JDBC 连接
        //    数据库代理收到后,自动登记 xid 到 xid 路由表
        jdbcConn.execute("XA START '" + xid + "'");
        
        try {
            // 3. 执行业务方法(业务代码正常写 DAO,SQL 通过 JDBC 到数据库代理)
            Object result = inv.proceed();
            
            // 4. 发 XA COMMIT:数据库代理智能判断同库/跨库,自动决定事务策略
            jdbcConn.execute("XA COMMIT '" + xid + "'");
            return result;
        } catch (Exception e) {
            // 5. 发 XA ROLLBACK:数据库代理自动清理 xid 路由表
            jdbcConn.execute("XA ROLLBACK '" + xid + "'");
            throw e;
        }
    }
}
```

**关键设计取舍**:
- edap 拦截器**只发 XA 命令**,不判断同库/跨库
- 不解析 SQL、不维护全局事务表、不实现协调逻辑
- **所有智能路由由 edap-pg-proxy 在协议层完成**

#### 5.3.2 RPC context 透传 xid

```java
// RPC Client 拦截器(edap 已有 RpcClientInterceptor 框架)
public class TxContextPropagator implements RpcClientInterceptor {
    @Override
    public void beforeInvoke(RpcRequest req) {
        String xid = TransactionContext.getCurrentXid();
        if (xid != null) {
            // xid 写入 RPC 帧 metadata,跟业务字段完全无关
            req.getContext().put("edap.tx.xid", xid);
        }
    }
}

// RPC Server 拦截器(edap 已有 RpcServerInterceptor 框架)
public class TxContextReceiver implements RpcServerInterceptor {
    @Override
    public void beforeHandle(RpcRequest req) {
        String xid = (String) req.getContext().get("edap.tx.xid");
        if (xid != null) {
            // 上游有事务,绑 xid 到当前线程
            // 但不开启新事务 — 等业务代码的 @Transactional 拦截器处理
            TransactionContext.setCurrentXid(xid);
        }
    }
    
    @Override
    public void afterHandle(RpcRequest req, Object result) {
        // 清理 ThreadLocal,避免线程复用泄漏
        TransactionContext.clearCurrentXid();
    }
}
```

**关键**:xid 是 RPC 帧 metadata,业务代码完全无感知。

#### 5.3.3 全局 xid 与业务完全解耦

**业务方法签名举例**(完全无感):

```java
@Transactional
public OrderResult createOrder(CreateOrderRequest req) {
    // 业务代码不需要关心事务
    userDAO.insert(req.getUser());                       // pg-x(orders_app@pg-x)
    couponServiceClient.grantWelcomeCoupon(req.getUserId()); // RPC,框架带 xid
    paymentServiceClient.deductPayment(req.getOrderId());   // RPC,框架带 xid
    return result;
}
```

**edap 不解析 SQL、不感知数据库拓扑、不判断事务策略**——所有这些由 edap-pg-proxy 完成。

#### 5.3.4 edap 容器不做的事

- ❌ ~~维护全局事务表~~
- ❌ ~~实现 XA 二阶段协调~~
- ❌ ~~解析 SQL 路由~~
- ❌ ~~判断同库/跨库~~
- ❌ ~~持久化事务状态~~
- ❌ ~~实现分布式锁~~

edap 容器只发 XA 命令,**所有复杂度下沉到 edap-pg-proxy**。

### 5.4 edap-pg-proxy 子模块设计契约(Phase 4b)

#### 5.4.1 子模块定位

- **模块名**:`edap-pg-proxy-parent` / `edap-pg-proxy`
- **包名**:`io.edap.pgproxy`
- **进程形态**:独立 Java 进程
- **通信端口**:
  - **5432**(默认 PG 前端协议端口)—— 业务实例 JDBC 直连此端口
  - **后端连 PG 集群**:按 PG 实例分连接池

#### 5.4.2 三重职责

| 职责 | 说明 |
|------|------|
| **PG 协议代理** | 解析 PG 前端协议,转发到后端 PG |
| **用户名路由** | 根据 JDBC 用户名路由到具体 PG 实例,**零 SQL 解析** |
| **智能事务路由** | 同库 → 单连接本地事务 / 跨库 → XA 二阶段协调 |

#### 5.4.3 智能事务路由的实现

**xid 路由表的数据结构**:

```java
public class XidRoutingTable {
    private final Map<String, XidEntry> xidMap = new ConcurrentHashMap<>();
    
    public static class XidEntry {
        private final String xid;
        private final List<BranchEntry> branches = new ArrayList<>();
        private boolean needsXa = false;   // 是否需要 XA 协调(任一分支跨库 → true)
        
        public boolean isCrossDb() {
            return branches.stream().map(b -> b.pgInstance).distinct().count() > 1;
        }
    }
    
    public static class BranchEntry {
        private final String pgInstance;    // pg-x / pg-y
        private final Connection backendConn; // 后端连接
        private boolean xaPrepared = false;  // XA PREPARE 是否成功
    }
}
```

**XA COMMIT 的协调逻辑**(伪代码):

```java
public void onXaCommit(String xid) {
    XidEntry entry = xidTable.get(xid);
    if (entry == null) {
        // xid 不存在 — 可能已经恢复,跳过
        return;
    }
    
    if (!entry.isCrossDb()) {
        // 同库场景(单 PG 实例)→ 单连接本地事务
        BranchEntry branch = entry.branches.get(0);
        try {
            branch.backendConn.execute("COMMIT");  // 普通 COMMIT
        } catch (SQLException e) {
            throw new TransactionException("local commit failed", e);
        }
    } else {
        // 跨库场景(多 PG 实例)→ XA 二阶段协调
        // 阶段一:所有分支 PREPARE
        for (BranchEntry branch : entry.branches) {
            try {
                branch.backendConn.execute("XA PREPARE '" + xid + "'");
                branch.xaPrepared = true;
            } catch (SQLException e) {
                // 任一 PREPARE 失败 → 全部 ROLLBACK
                rollbackAll(entry);
                throw new TransactionException("XA prepare failed", e);
            }
        }
        
        // 阶段二:所有分支 COMMIT
        for (BranchEntry branch : entry.branches) {
            try {
                branch.backendConn.execute("XA COMMIT '" + xid + "'");
            } catch (SQLException e) {
                // 已 PREPARE 但 COMMIT 失败 → 需要人工干预
                // 重启时会通过 pg_prepared_xacts 扫描强制 ROLLBACK
                throw new TransactionSystemException("XA commit failed", e);
            }
        }
    }
    
    // 清理路由表 + 归还后端连接
    releaseXid(xid);
}

private void rollbackAll(XidEntry entry) {
    for (BranchEntry branch : entry.branches) {
        try {
            if (branch.xaPrepared) {
                branch.backendConn.execute("XA ROLLBACK '" + xid + "'");
            } else {
                branch.backendConn.execute("ROLLBACK");
            }
        } catch (SQLException e) {
            // log + 继续(节点重启会通过 pg_prepared_xacts 强制清理)
        }
    }
    releaseXid(xid);
}
```

#### 5.4.4 启动恢复流程

```
edap-pg-proxy 启动:
   │
   ├─ 1. 加载用户路由配置(从配置文件 / 配置中心)
   │
   ├─ 2. 初始化后端连接池(按 PG 实例分池)
   │
   ├─ 3. 对每个后端 PG 实例:
   │     └─ SELECT gid FROM pg_prepared_xacts
   │        └─ 对每个 PREPARED 的 gid:
   │           └─ ROLLBACK PREPARED 'gid'
   │
   ├─ 4. 初始化 xid 路由表(空)
   │
   └─ 5. 启动 PG 前端协议端口监听(5432)
```

**关键**:`pg_prepared_xacts` 是 PG 的系统表,WAL 重放后仍然存在——这是"edap-pg-proxy 重启,但 PG 不失忆"的根本。

#### 5.4.5 edap 端对 edap-pg-proxy 的依赖

- **业务实例不感知 edap-pg-proxy 存在** —— JDBC URL `jdbc:postgresql://edap-pg-proxy:5432/db`,直连即可
- **业务实例零 PG 权限** —— 用 edap 账号连 edap-pg-proxy,后端 PG 由 edap-pg-proxy 统一管理
- **edap-pg-proxy 统一后端权限** —— 用 `proxy_service_account` 连所有 PG 实例

### 5.5 全局 xid 分片的天然支持

**edap-pg-proxy 因为是所有 SQL 的统一入口**,天然具备"按 global xid 分片"的特性:

#### 5.5.1 同一个 xid 自然绑到同一个事务边界

- 业务实例 A 用 `orders_app@pg-x` 连过来 → edap-pg-proxy 看到 xid-100
- 业务实例 B 用 `coupons_app@pg-x` 连过来 → edap-pg-proxy 看到 xid-100
- **同一个 xid,edap-pg-proxy 自动合并到同一个后端连接**(同库复用)

#### 5.5.2 跨库场景天然识别

- 业务实例 C 用 `payments_app@pg-y` 连过来 → edap-pg-proxy 看到 xid-200
- xid-200 已涉及 pg-x,新增 pg-y → **自动标记跨库,启用 XA**

#### 5.5.3 edap 业务实例的分片亲和 + edap-pg-proxy 的事务分片

| 维度 | edap 业务实例 | edap-pg-proxy |
|------|------------|--------------|
| **分片 key** | userId(微服务分片亲和) | global xid(事务分片) |
| **分片路由** | ClusterShardRouter 按 userId 路由业务请求 | 用户名路由 + xid 路由表自动合并事务 |
| **复用机制** | 同一 userId 请求路由到同一 edap 实例 | 同一 xid 的同库连接合并到同一 PG 后端连接 |
| **价值** | 业务逻辑集中,少 RPC 跳转 | 事务连接集中,少 XA 协调 |

**两个分片亲和是不同维度,互补不冲突**。

### 5.6 XA 的隔离级别降级(明确边界)

**XA 协议限制**(必须在文档里说清楚):

| 维度 | 本地事务 | XA 分布式事务 |
|------|---------|--------------|
| 隔离级别 | 业务可自由指定(DEFAULT / RC / RR / SR) | **强制 READ_COMMITTED** |
| 原因 | 单连接,DB 自己保证 | 多分支协调,REPEATABLE_READ 在 PG 上与 PREPARE TRANSACTION 冲突(PG 不支持 RR 下的 XA PREPARE) |

**edap 处理策略**:

1. **配置项**:`edap.tx.xa.forcedIsolation = READ_COMMITTED`(默认值,强制)
2. **业务侧声明 RR 时的行为**:
   - XA 路径下:edap-pg-proxy 自动降级到 RC,打 WARN 日志
   - 本地事务路径下:尊重业务声明
3. **文档标注**:跨库场景必须意识到,强读一致性的幻读保护不再有

**应用层的应对**:
- 依赖强隔离的业务(如对账、出入库)**必须在同库内完成**,不走跨库事务
- 跨库调用场景通常只关心"最终一致"——XA + RC 满足
- 真正的强一致需求用 `SELECT FOR UPDATE` + 单连接本地事务

**edap-pg-proxy 的策略**:
- 检测到跨库场景时,自动在 PG 连接上 `SET transaction_isolation = 'read committed'`
- 同库场景:保留业务声明的隔离级别

### 5.7 性能优化的方向

PG 的进程模型下,每连接一进程,1 万并发就是 1 万进程——PG 的核心痛点由 edap-pg-proxy 承担:

| 优化 | 说明 | 实现位置 |
|------|------|----------|
| **连接复用** | 前端 N 个连接共享后端 M 个连接(M << N) | edap-pg-proxy 后端池 |
| **同库连接合并** | 同一 xid 的同库连接合并到单个后端 PG 连接 | edap-pg-proxy xid 路由表 |
| **协议层多路复用** | 单后端连接同时服务多前端连接(按 query id 路由响应) | edap-pg-proxy 协议层(Phase 4b 远期) |
| **prepared statement 缓存** | 复用 PG 服务端 prepared statement | edap-pg-proxy |
| **结果集流式** | 大结果集不分块缓冲 | edap-pg-proxy |

**edap 端的配合**:JDBC URL 改指 edap-pg-proxy,业务侧无感知;连接池大小可调小(HikariCP `maximumPoolSize` 不需要按并发峰值配,因为 edap-pg-proxy 会复用)。

### 5.8 分阶段交付路径

| 阶段 | 内容 | edap 端 | edap-pg-proxy 端 |
|------|------|---------|------------------|
| **Phase 1** | 接口 + 决策矩阵 + ThreadLocal 工具 | ✅ 完整 | ❌ 不涉及 |
| **Phase 2** | JDBC 单连接事务实现 | ✅ `DataSourceTransactionManager` + 单测 + 集成测试 | ❌ |
| **Phase 3** | edap handler 集成 + 业务侧端到端 | ✅ 拦截链挂上 | ❌ |
| **Phase 4a** | edap 端 XA 适配(发 XA 命令) | ✅ `TransactionInterceptor` 发 `XA START/COMMIT/ROLLBACK` | ❌ |
| **Phase 4b-1** | PG 协议代理 + 用户名路由 | ❌ | ✅ `edap-pg-proxy` 子模块独立迭代 |
| **Phase 4b-2** | 智能事务路由 + XA 协调 | ❌ | ✅ xid 路由表 + 同库合并 + 跨库 XA |
| **Phase 4b-3** | 启动恢复 + 集成测试 | ✅ edap 端到 XA 路径测试 | ✅ pg_prepared_xacts 扫描测试 |
| **Phase 4c** | 性能优化(多路复用 / prepared statement 缓存) | ❌ | ✅ 长期迭代 |

**关键决策**:
- **Phase 4a 在 edap 主线交付** —— `@Transactional` 拦截器改为发 XA 命令,业务代码无需改动
- **Phase 4b 是独立子项目** —— edap-pg-proxy 有自己的文档、自己的测试、自己的发布节奏,不阻塞 edap 主线
- **edap 端不实现协调逻辑** —— 事务协调全部下沉到 edap-pg-proxy

### 5.9 与 Seata / ShardingSphere 的边界

| 维度 | Seata | ShardingSphere | edap 自研 |
|------|-------|----------------|----------|
| 协议 | 自定义 AT/TCC/Saga/XA | MySQL 协议代理 + XA | **PG 协议代理 + 用户名路由 + 智能事务** |
| 协调器 | Java 进程(独立 Server 集群) | 嵌入应用进程(也可独立) | **edap-pg-proxy 协议层自动协调(零专用协调进程)** |
| 全局事务上下文传递 | 自定义协议(RootContext / ThreadLocal 序列化) | 自带 | **edap RPC 帧 metadata**(天然) |
| 同库/跨库区分 | 不区分,统一 XA 协调 | 不区分,统一 XA 协调 | **智能区分**:同库本地事务 / 跨库 XA |
| 业务侵入 | AT 模式无侵入;TCC 业务需 try/confirm/cancel | 无侵入 | **完全无侵入**(业务代码不感知事务) |
| 依赖 | seata-client(重) | shardingsphere-jdbc(重) | **零第三方依赖** |

**edap 自研 vs Seata / SS 的关键差异**:

| 维度 | Seata AT | ShardingSphere XA | edap 智能事务 |
|------|---------|------------------|--------------|
| 同库场景开销 | 全程 XA 协调(过度设计) | 全程 XA 协调(过度设计) | **单连接本地事务,零 XA 开销** |
| 跨库场景 | XA 协调 | XA 协调 | **XA 协调** |
| 业务代码 | 无侵入 | 无侵入 | **无侵入** |
| 数据库拓扑感知 | 不感知 | 不感知 | **edap-pg-proxy 自动识别** |
| 路由机制 | 无 | SQL 解析 | **JDBC 用户名(零 SQL 解析)** |
| 协调进程 | Seata Server 集群 | 无(嵌入应用) | **edap-pg-proxy 协议层自动协调** |
| 第三方依赖 | 重 | 重 | **零** |

**edap 自研的理由**:

1. **同库场景零开销** —— Seata / SS 都用 XA 协调同库场景,过度设计;edap 用本地事务处理同库,性能最优
2. **用户路由名单取代 SQL 解析** —— 不需要写 SQL parser,配置简单,性能开销几乎为零
3. **零专用协调进程** —— 协调逻辑在 edap-pg-proxy 协议层自动完成,不引入 Seata Server 集群
4. **edap 是 PG 路线** —— 不需要 MySQL 兼容性,专注 PG XA 协议优化
5. **零第三方依赖** —— edap 自有协议栈,回归可控
6. **天然与 edap 容器集成** —— RPC context 透传 xid + ClusterShardRouter 分片亲和

**不与 Seata / SS 竞争的边界**:

- edap 不做 AT(自动 UNDO_LOG)—— XA + 智能事务足够 PG 场景
- edap 不做 SQL 路由 / 分片—— edap-pg-proxy 是"协议代理 + 用户名路由",不是分库分表中间件
- edap 不做异构数据库——只服务 PostgreSQL

### 5.10 故障恢复场景矩阵

| 故障点 | 影响 | edap-pg-proxy 行为 | 业务侧观察 |
|--------|------|-------------------|-----------|
| **edap-pg-proxy 进程挂** | 内存 xid 路由表清空;前端连接断开 | 重启后扫所有后端 PG 的 pg_prepared_xacts,强制 ROLLBACK 残留分支 | 业务实例 JDBC 重连,继续工作 |
| **edap-pg-proxy 挂掉期间** | 业务实例 JDBC 不可达 | 业务实例重试 | 业务侧重试 |
| **业务实例挂** | 该实例的 xid 进入孤儿 | edap-pg-proxy 扫描到对应分支,等待超时后 ROLLBACK | 业务侧重试 |
| **某 PG 实例挂** | 该 PG 的 XA 分支不可用 | 其他 PG 分支继续;协调时该分支 PREPARE 失败 → 全局 ROLLBACK | 业务侧重试,等 PG 恢复 |
| **edap-pg-proxy 在 XA COMMIT 时挂** | 部分分支已 COMMIT,部分未 COMMIT | 重启后扫 pg_prepared_xacts → 强制 ROLLBACK 未 COMMIT 的分支 | 已收到 COMMIT 响应的请求最终一致;其他视为 ROLLBACK |
| **同库场景下后端连接挂** | 单连接本地事务自动回滚 | PG 单连接挂掉 → 事务 ROLLBACK | 业务侧重试 |

**关键风险(最后一行)**:edap-pg-proxy 已通知 PG COMMIT 但挂掉——重启后 PREPARED 状态被强制 ROLLBACK。

**业务侧应对**:
- 长事务(涉及外部资源如发短信/调第三方)不能用纯 XA——必须用 TCC 或本地消息表
- 短事务(纯 DB 写入)且**幂等**——接受这个代价,依赖对账兜底
- 关键业务(如支付)建议用本地事务 + 异步复制,不走 XA
- **同库场景几乎无此风险**——单连接事务,PG 自己保证,edap-pg-proxy 挂了直接重连即可

### 5.11 关键决策解释

1. **edap 不实现 XA 协调逻辑** —— 事务协调全部下沉到 edap-pg-proxy 协议层;edap 容器只发 XA 命令,极简实现
2. **JDBC 用户名路由取代 SQL 解析** —— 用户名自带分片标识,edap-pg-proxy 零 SQL 解析,配置简单
3. **同库本地事务 / 跨库 XA 智能区分** —— 大多数微服务场景是同库(80%+),edap 自动走本地事务,性能最优;只有跨库才启用 XA,代价明确
4. **统一走 edap-pg-proxy** —— 所有 JDBC 都过代理,业务侧零 PG 权限,edap-pg-proxy 统一后端权限管理
5. **零专用协调进程** —— 不引入 Seata Server 集群、不引入独立 edap 协调节点;协调逻辑在 edap-pg-proxy 协议层自动完成
6. **edap-pg-proxy 无状态化** —— XA 的关键状态由 PG 自己持久化(pg_prepared_xacts + WAL),edap-pg-proxy 重启时扫一遍强制 ROLLBACK 即可。代价是 edap-pg-proxy 挂掉期间已响应的全局事务可能被回滚,业务侧必须幂等
7. **global xid 与业务完全解耦** —— xid 是 RPC 帧 metadata,业务代码完全无感;事务上下文由 edap 容器自动管理
8. **XA 强制 RC 隔离级别** —— 跨库场景下 XA 协议限制,edap-pg-proxy 自动降级 + WARN 日志;同库场景不受影响
9. **edap 容器不做 SQL 路由 / 分片** —— edap-pg-proxy 是"协议代理 + 用户名路由",不是分库分表中间件;不与 ShardingSphere 竞争
10. **Phase 4 拆 4a / 4b** —— 4a 是 edap 端 XA 适配(发 XA 命令,必做,不阻塞主线),4b 是 edap-pg-proxy 子模块(独立迭代,按需排期)

### 5.12 术语表

| 术语 | 含义 |
|------|------|
| **global xid** | 全局事务 ID,在整个事务生命周期内由事务发起方生成,所有参与的连接共享 |
| **用户名路由** | edap-pg-proxy 根据 JDBC 用户名(如 `orders_app@pg-x`)路由到具体 PG 实例 |
| **同库** | 多个连接共享同一个 PG 实例(xid 涉及 1 个 PG 实例) |
| **跨库** | 多个连接涉及多个 PG 实例(xid 涉及 N 个 PG 实例) |
| **XA 协调** | 跨库场景下的二阶段提交(EDAP-pg-proxy 协议层自动完成) |
| **pg_prepared_xacts** | PG 的系统表,记录所有 PREPARED 但未 COMMIT/ROLLBACK 的事务 |
| **proxy_service_account** | edap-pg-proxy 连后端 PG 的统一服务账号 |
---

## 六、模块划分与依赖

### 6.1 包结构

```
edap-tx-parent/
├── edap-tx-api/                              (接口 + 工具类,零 JDBC 依赖)
│   └── io/edap/tx/
│       ├── annotation/
│       │   ├── Transactional.java
│       │   └── ManualTransaction.java
│       ├── exception/
│       │   ├── TransactionException.java                (extends RuntimeException)
│       │   ├── IllegalTransactionStateException.java
│       │   ├── TransactionSystemException.java
│       │   └── NestedTransactionNotSupportedException.java
│       ├── propagation/
│       │   └── Propagation.java
│       ├── isolation/
│       │   └── Isolation.java
│       ├── TransactionDefinition.java
│       ├── TransactionStatus.java
│       ├── TransactionResource.java
│       ├── Synchronization.java
│       ├── EdapTransactionManager.java
│       ├── TransactionContext.java             (业务侧 ctx,合并旧 ctx 三层)
│       ├── TxSnapshot.java                    (单 ThreadLocal 持有的不可变快照)
│       └── TxScope.java                       (单 ThreadLocal 工具,suspend/resume 原子交换)
│
├── edap-tx/                                  (决策矩阵 + commit/rollback 路径)
│   └── io/edap/tx/
│       ├── DefaultEdapTransactionManager.java (Phase 1 mock 资源的默认实现)
│       └── ...
│
└── edap-tx-jdbc/                             (真实 JDBC 资源实现)
    └── io/edap/tx/jdbc/
        ├── DataSourceTransactionManager.java (Phase 2 默认生产实现)
        ├── JdbcTransactionResource.java        (Connection 持有 + commit/rollback)
        └── TxConnectionHolder.java            (SPI:dao 自动从当前 tx 拿共享连接)

edap-container-parent/
└── edap-container/
    └── io/edap/container/transactional/
        ├── TransactionalClassGenerator.java   (ASM 字节码生成 wrapper)
        └── TransactionalBeanPostProcessor.java (BPP:扫描 @Transactional + 生成 wrapper)
```

### 6.1.1 TM 静态注册表(`io.edap.tx.TransactionManagers`)

> edap-tx-api 模块的全局静态注册表,代替早期版本的 `TransactionManagerResolver`。
> 由 `AppContext.configureTransactionalInfrastructure()` 在 Phase 2.5 阶段按 DataSource bean 填充;
> wrapper 字节码 `<clinit>` 通过 `TransactionManagers.get(name)` 取 TM(替代动态 resolver 字段)。

```java
public final class TransactionManagers {
    public static void register(String name, EdapTransactionManager tm);
    public static EdapTransactionManager get(String name);
    // name == null → ""(同 Spring @Transactional 默认 transactionManager 语义)
    // 未注册 → throw IllegalStateException(避免 silent fallback 到错 ds)
}
```

### 6.2 模块依赖

| 模块 | 依赖方向 | 备注 |
|------|---------|------|
| `edap-tx-api` | （零依赖,纯 JDK） | 接口 + 不可变对象 + ThreadLocal 工具 |
| `edap-tx` | 依赖 `edap-tx-api` | 决策矩阵 + commit/rollback 路径,可独立用 mock 资源单测 |
| `edap-tx-jdbc` | 依赖 `edap-tx-api` + `edap-data-jdbc-dao` (ConnectionHolder) + JDBC API | Phase 2 真 JDBC 实现 |
| `edap-container` | 依赖 `edap-tx-api` (manager 接口 + ctx) + `edap-tx-jdbc`(可选) | 字节码生成 wrapper + BPP 扫描;不依赖 `edap-tx` 决策矩阵实现 |

**为什么拆 `edap-tx-parent` 子模块**:
- Phase 1 决策矩阵可用 `MockTransactionResource` 在 edap-tx 模块单测,不引入 JDBC,回归快速
- edap-tx-jdbc 仅依赖 edap-tx-api 接口,不依赖 edap-tx 实现 —— 决策矩阵代码修改不影响 JDBC 集成测试
- 业务侧(经由 edap-container)只依赖 edap-tx-api 接口,不感知实现细节

**ClassLoader 隔离约束**:
- 事务管理接口(`EdapTransactionManager` / `TransactionContext` / `TxScope` / `TxSnapshot`)由 **containerCL 加载**
- 业务侧(appCL)只 import 接口,**不**直接 import `DataSourceTransactionManager`——通过 bean 名注入
- `TxConnectionHolder` 跨 CL:edap-data-jdbc-dao 通过 ServiceLoader 加载 edap-tx-jdbc 的 impl,经 TxScope 读到当前 status;TxScope 在哪个 CL 加载决定可见性,本设计统一由 containerCL 加载

### 6.3 ClassLoader 隔离约束

- 事务管理接口（`EdapTransactionManager` 等）由 **`containerCL` 加载**
- 业务侧（`appCL`）只 import 接口，**不**直接 import `DataSourceTransactionManager`——通过 bean 名注入
- 实现类同样由 `containerCL` 加载，跨 CL 注入遵循现有 `JwtService` 模式（详见 task #24）

---

## 七、测试策略

### 7.1 单元测试（不依赖 DB）

| 测试 | 模块 | 覆盖点 |
|------|------|--------|
| `TransactionDefinitionTest` | edap-tx-api | 不可变性、equals/hashCode、Builder |
| `TxScopeTest` | edap-tx-api | 单 ThreadLocal 读写、swap 原子性、sync 列表生命周期、嵌套 swap 栈 |
| `PropagationDecisionTest` | edap-tx | 7×3 决策矩阵(mark mock resource);stale state 检测路径 |
| `CommitRollbackTest` | edap-tx | commit/rollback 顺序 + rollbackOnly 路径 + sync 回调触发 |
| `SuspendResumeStackTest` | edap-tx | 多层 REQUIRES_NEW 嵌套 + NOT_SUPPORTED 混合挂起栈 + swap 原子性 |
| `StaleStateTest` | edap-tx | wrapper bug 路径(status 已 completed 但 ThreadLocal 未清空);clear 后无 callerDepth 路径 |

### 7.2 集成测试（依赖 H2 内存库）

| 测试 | 覆盖点 |
|------|--------|
| `DataSourceTransactionManagerTest` | commit / rollback / autoCommit 切换 / 连接归还 |
| `NestedJdbcTest` | NESTED savepoint 回滚外层保留 |
| `RequiresNewJdbcTest` | REQUIRES_NEW 隔离失败 |
| `TimeoutTest` | 超时回滚 |
| `RollbackRulesTest` | `rollbackFor` / `noRollbackFor` |

### 7.3 端到端测试

- 起 edap 容器 + 业务 EAR（`UserService.createUser` 标 `@Transactional`）
- HTTP 调用 → 验证 commit / rollback 路径
- 多线程并发调用 → 验证 ThreadLocal 隔离（不复用连接池导致脏数据）

---

## 八、分阶段交付

| 阶段 | 内容 | 交付物 | 估时 |
|------|------|--------|------|
| **Phase 1** | edap 本地事务接口 + 决策矩阵 + ThreadLocal 工具 + 单元测试 | 7×3 决策矩阵全绿；无任何 JDBC 依赖 | 1 周 |
| **Phase 2** | edap 本地事务 `DataSourceTransactionManager` + JDBC 集成测试 | 嵌套/REQUIRED/REQUIRES_NEW/NESTED 全通 | 1 周 |
| **Phase 3** | edap handler 集成 + 业务侧端到端 | `@Transactional` 方法 HTTP 调用通 | 3 天 |
| **Phase 4a** | edap 端 XA 接口扩展（`TransactionResource.prepare()` + `XaTransactionManager` + `XaCoordinatorClient`）+ mock 协调器 | edap 端 XA 接口稳定可测 | 2 周 |
| **Phase 4b-1** | `edap-pg-proxy` 子模块：PG 前端协议代理 + 连接复用 | 性能压测报告（连接复用效果） | 4 周 |
| **Phase 4b-2** | `edap-pg-proxy`：XA 协调器主体（持久化 + 二阶段 + 故障恢复） | 二阶段提交流程跑通 | 4 周 |
| **Phase 4b-3** | 端到端集成测试 | edap XA 路径全链路跑通 | 2 周 |
| **Phase 4c** | 性能优化（协议多路复用 / prepared statement 缓存 / 批量 PREPARE） | 长期迭代 | — |

### 8.1 阶段依赖关系

```
Phase 1 ──► Phase 2 ──► Phase 3
   │           │           │
   │           └──────┐    │
   │                  ▼    ▼
   ├────────────► Phase 4a（edap 端 XA 接口）
   │                      │
   │                      ▼
   │           ┌──────────────────────────┐
   │           │  Phase 4b-1 (PG 代理)     │  ← 独立子项目
   │           │       │                  │
   │           │       ▼                  │
   │           │  Phase 4b-2 (XA 协调器)  │
   │           │       │                  │
   │           │       ▼                  │
   │           └──── Phase 4b-3 (端到端) ◄┘
   │                      │
   │                      ▼
   └────────────► Phase 4c (性能优化，长期)
```

**关键路径**：
- **Phase 1 → 2 → 3** 是 edap 主线交付（本地事务）
- **Phase 4a** 与 Phase 3 可并行（不阻塞本地事务发布）
- **Phase 4b-1/2/3** 是 `edap-pg-proxy` 独立子项目，不在 edap 主仓发布节奏内

### 8.2 阶段验收标准

- **Phase 1**：所有 7×3=21 个决策矩阵用例单测绿；无任何 `java.sql.*` import
- **Phase 2**：H2 集成测试覆盖 REQUIRED / REQUIRES_NEW / NESTED / SUPPORTS / MANDATORY / NEVER 六种传播；连接归还通过 `HikariCP.resetConnectionOnReturn` 校验
- **Phase 3**：业务 EAR 起容器，`@Transactional(REQUIRES_NEW)` 方法 HTTP 调用通；非 `@Transactional` 方法调用通（确保拦截链不影响普通方法）
- **Phase 4a**：edap 端 `XaTransactionManager` + mock `XaCoordinatorClient` 单测全绿；接口与 `edap-pg-proxy` 协调器协议对齐（双方握手跑通 mock 通信）
- **Phase 4b-1**：PG 前端协议 80% 覆盖（Simple Query / Extended Query / COPY 暂缓）；连接复用压测 1000 前端 → 100 后端，QPS 不下降
- **Phase 4b-2**：XA 协调器二阶段提交 / ROLLBACK / 故障恢复（`pg_prepared_xacts` 扫描）单测 + 集成测试全绿
- **Phase 4b-3**：edap 业务 `@Transactional` 启用 XA 后端到端跑通；XA RC 隔离级别生效（PG `SHOW transaction_isolation` 验证）

---

## 九、关键决策解释

1. **edap 自写而非包装 Spring**：edap 自身定位是"小而精的微服务框架"，引入 `spring-tx` jar 等于把 5MB 依赖拽进来换 100 行实现。更关键的是，Spring 的接口（`PlatformTransactionManager`）设计绑定其自身生态，未来要扩展分布式事务时反而碍事。
2. **`TransactionResource` 抽象而非直接持 `Connection`**：这是给分布式事务留的"预制件"——`SeataTccResource` / `XaResource` 实现这个接口就能直接套用所有传播模型和同步点逻辑。
3. **`Synchronization` 接口 Day 1 就有**：分布式事务的事件通知（消息发件箱、二阶段补偿）必须依赖 afterCommit / afterCompletion 钩子。现在不定义，Phase 4 要补时所有 manager 都要改一遍。
4. **单 ThreadLocal `TxScope` + 不可变 `TxSnapshot` 集中管理事务线程状态**：替代原 6 ThreadLocal 散装设计。优势:挂起时一次性原子快照 status + synchronizations + resources + xid + context,无双栈对齐维护负担;跨 CL 注入稳定性;wrapper 字节码不再需要 wrapperDepth/callerDepth 防御层;manager 入口用 `status.isCompleted()` 做 stale state 检测,避免 REQUIRED 嵌套计数错误膨胀。
5. **handler 字节码不烧事务**：handler 是协议适配层（参考 WS_HANDLER_DESIGN.md），事务是横切关注点——分层隔离，字节码体积可控、回归测试简单。事务 wrapper 由独立 `TransactionalClassGenerator` 生成,在 `BeanPostProcessor` 阶段织入到 bean。
6. **挂起/恢复用 `TxScope.swap()` 原子交换**:suspend 时 `TxScope.swap(TxSnapshot.empty())` 把旧 snapshot 整体 swap 出去存到新 status 的 `suspendedSnapshot` 字段;resume 时 `TxScope.swap(suspendedSnapshot)` 整体 swap 回去——单 ownership(由 status 持有 suspended snapshot),无双栈对齐维护负担。
7. **NESTED 驱动不支持时降级 REQUIRED + WARN**：避免悄无声息的回滚语义错误。日志里能看到降级，应用层能感知。
8. **`REQUIRED` 嵌套用计数而非栈**——commit 只在最外层（count==1）真正提交；内层只 decrement，rollback 则全部 rollback。语义清晰，避免"内层 commit 后外层 rollback"这种部分提交情况。
9. **异常一律 RuntimeException**——遵循 edap 容器异常约定（`feedback_edap_exc_runtime.md`），不污染 `EdapTransactionManager` 接口签名。
10. **分布式事务走 XA + edap 业务实例内置协调器,不走独立协调器集群**——edap 业务实例和协调器天然共用同一进程生命周期；协调器下沉到 edap 内是 edap 容器一体化的最大优势。
11. **跨实例协调复用 edap RPC + 分片亲和**——edap 已有 `ClusterShardRouter` 按 `userId` 路由业务请求;事务 xid 作为 RPC 帧 metadata 透传;同一 userId 的 XA 分支天然在同一 edap 实例,**分布式事务的分片亲和与微服务的分片亲和天然合一**。
12. **`edap-pg-proxy` 不再承担协调器职责**——只做 PG 前端协议代理 + 连接复用(性能优化);协调器永远内置于 edap 实例。子模块职责更纯粹,部署更简单。
13. **全局 xid 与业务完全解耦**——上游向下游传递的事务上下文就是 RPC 帧 metadata 里的 xid 字符串;业务代码完全无感,不需要手动管理事务状态;业务侧契约只有"幂等"一项。
14. **`TransactionResource.prepare()` 默认实现为 `UnsupportedOperationException`**——本地事务资源零改动即可兼容 XA 接口,**接口扩展不污染本地路径**。
15. **协调器无状态化**——XA 的关键状态由 PG 自己持久化(`pg_prepared_xacts` + WAL),协调器只是个调度路由器,没必要冗余持久化。代价是实例宕机期间已响应的全局事务可能被回滚,业务侧必须幂等。**这是为简化运维和工程复杂度付出的明确代价,不是 bug**。
16. **Phase 4 拆 4a / 4b**——4a 是 edap 端 XA 全栈(XA 接口 + 内置协调器 + RPC 透传 + 远程协调 RPC),必做,在 edap 主线交付;4b 是 `edap-pg-proxy` 子模块(仅 PG 代理 + 连接复用),独立子项目按需排期。这样本地事务和 XA 接口都不依赖 `edap-pg-proxy` 进度。
17. **wrapper 直接调 `tm.getTransaction(def)` + `tm.commit/rollback(status)` + `TransactionContext.bind/unbind()`** —— 无 callerDepth 参数(removed),无 wrapperDepth 防御层;proper wrapper 字节码只需 status.isCompleted() 守卫双重 commit / rollback,manager 通过该标志自动检测 stale state。这意味着 wrapper 字节码比旧版本少 2 个 slot + 8 行 INVOKESTATIC,体积更小、可读性更高。
18. **`TransactionContext` 合并原 ctx 三层间接** —— 旧设计有 `TransactionContext` interface + `DefaultTransactionContext` impl + `TransactionContexts` 静态工具共 3 个类型管同一件事;新设计是单一 final class(protected 构造器),静态方法 `current()` / `currentOrNull()` / `bind(tm, status)` / `unbind()`,实例方法 `commit()` / `rollback()` / `setRollbackOnly()` / `createSavepoint()` / `rollbackTo(sp)` 等。ctx 由 `bind()` 写入 TxSnapshot 的 `context` 字段,wrapper finally 通过 `unbind()` 清空。

---

## 十、未来扩展（不在本文档范围）

**edap 主线（与事务子系统相关）**：
- **响应式事务**（Reactor `Mono` / `Flux` 上的事务编排）
- **多数据源事务管理器**的路由（`RoutingTransactionManager`）
- **编程式事务**（`TransactionTemplate`）
- **声明式事务监听器**（`@TransactionalEventListener`，依赖 `Synchronization` 钩子）
- **事务传播的统计 / 监控**（`Micrometer` 埋点）
- **事务隔离级别的精细控制**（当前只声明枚举，未实现 JDBC `setTransactionIsolation()`）

**`edap-pg-proxy` 子模块（与分布式事务相关）**：
- **SQL 路由 / 读写分离**（PG 代理层的横切能力）
- **prepared statement 服务端缓存**（减少 PG 端 parse 压力）
- **协议层多路复用**（单后端连接同时服务多前端连接）
- **异构数据库适配**（MySQL XA 协议代理——目前只承诺 PG）

**edap 主线（与 RPC context 透传相关）**：
- **xid 与其他微服务上下文协同**（userId / traceId / authToken 一起走 RPC metadata）
- **xid 注入到 HTTP 请求头**（与 `@RequireAuth` 拦截器协同）
- **xid 注入到日志 MDC**（日志关联）
- **xid 注入到 Micrometer 埋点**（分布式追踪 + 性能监控）

---

## 附录 A：与 Spring 设计的差异

| 维度 | Spring | edap |
|------|--------|------|
| 注解名 | `org.springframework.transaction.annotation.Transactional` | `io.edap.tx.annotation.Transactional` / `io.edap.tx.annotation.ManualTransaction` |
| Manager 接口 | `PlatformTransactionManager` | `EdapTransactionManager` |
| 资源抽象 | `ConnectionHolder` / `XAResource` 各自一套 | `TransactionResource` 统一接口 |
| 同步点 | `TransactionSynchronization` + `TransactionSynchronizationManager` | `Synchronization` + 单 ThreadLocal `TxScope`(持有不可变 `TxSnapshot`) |
| 挂起模型 | `SuspendedResourcesHolder`(抽象类) | `TxScope.swap(TxSnapshot)` 原子交换,旧 snapshot 存到新 status 的 `suspendedSnapshot` |
| ThreadLocal 数 | 6+ 个(`TransactionSynchronizationManager` 散装) | 1 个(`TxScope.CURRENT` 持有 `TxSnapshot`) |
| wrapper 防御 | callerDepth + wrapperDepth | 无,manager 通过 `status.isCompleted()` 检测 stale state |
| ctx 间接层 | `TransactionAspectSupport` + `TransactionInfo` | 单 `TransactionContext` final class(合并原 ctx 三层) |
| 依赖 | spring-tx / spring-aop / spring-jdbc | 无 Spring 依赖,纯 JDK |
| AOP 实现 | JDK 动态代理 / CGLIB | edap 已有 bean 拦截链 + ASM `TransactionalClassGenerator` |

**关键差异**:
- edap 不分 `ConnectionHolder` 和 `XAResource`——统一为 `TransactionResource`,XA 资源自己实现这个接口即可
- edap 不引入 `@TransactionalEventListener` 这种声明式注解——业务侧直接实现 `Synchronization` 注册到 manager(编程式)
- edap 不用 6 个 ThreadLocal 散装工具,改用单 `TxScope` + 不可变 `TxSnapshot` —— 跨 CL 注入稳定性更好,挂起时一次性原子快照,无双栈对齐维护负担

---

## 附录 B：术语表

| 术语 | 含义 |
|------|------|
| 传播（Propagation） | 业务方法被嵌套调用时，事务如何与外层事务协作 |
| 挂起（Suspend） | 把当前事务压栈保存，临时以非事务或新事务方式运行 |
| 恢复（Resume） | 挂起的事务从栈中弹出恢复执行 |
| 同步点（Synchronization） | 事务生命周期钩子（beforeCommit / afterCommit / afterCompletion） |
| 嵌套（Nested） | 在已有事务内打 savepoint 形成的子边界 |
| 全局事务（Global Transaction） | 跨多个数据源/服务的事务，由协调器统一管理（Seata / XA） |
| 资源（Resource） | 事务管理器持有的物理资源（Connection / TCC 句柄 / XA 资源） |