# edap-raft —— Raft 共识模块设计

`edap-raft-parent` / `edap-raft` 为 edap 补上「节点之间可靠的复制 / 选主 / 提交」原语。

模块**完全独立**：不依赖 `edap-container`，也不依赖 `edap-protobuf` / `edap-json`（帧格式手写），
只依赖 `edap-nio-server`（复用它的 `Server` / `NioServerSession` / `Decoder` / `FastBuf` 传输栈）
和 `edap-log-api`。

---

## 1. 解决的问题

edap 原有的分布式能力是「分片亲和」：相同 shard_key 的请求确定性地路由到同一个节点，
**本地内存即权威，不跨节点同步内存**。这条路线的代价是扩缩容要重哈希、故障转移要迁 shard。

`edap-raft` 补的是另一种形态：**多副本强一致**。同一个 key 的数据在多个节点上都有副本，
任何一副本失效都由 Raft 保证：

- 选主（Leader Election）
- 日志复制（Log Replication）
- 多数派提交（Quorum Commit）
- 状态机 apply
- 日志压缩 + InstallSnapshot
- joint consensus 成员变更
- ReadIndex 线性一致读

两者互补：分片亲和适合「热点资源」，Raft 适合「需要副本与一致性保证的写入」。

---

## 2. 实现路线：etcd raft 的逐行移植

早期版本是照着 Raft 论文手写的一套实现（`RaftNode` + `RaftMessage` + `ClusterConfig`），
自测能跑通，但与论文细节（尤其 §5.4.2 提交规则、joint consensus、ReadIndex）始终有偏差，
而且**没法用成熟的测试来证明正确性**。

因此实现路线改为：**把 etcd 的 `go.etcd.io/raft` 逐行移植成 Java，并把 etcd 的单元测试
用例也用 Java 实现一遍**。etcd 的 raft 库是 Raft 论文的参考实现，配套测试覆盖了
选举、复制、提交、快照、成员变更、线性一致读的全部边界，移植这批测试等于给本模块
上了一道"论文级"的正确性验证。

### 2.1 包映射

etcd 的 Go 包 → edap-raft 的 Java 包：

| etcd Go 包 / 文件 | Java 包 | 说明 |
|---|---|---|
| `raft.go` | `io.edap.raft` | 核心状态机（选举 / 复制 / 提交 / 快照 / 成员变更 / 读） |
| `read_only.go` | `io.edap.raft` | ReadIndex 的确认与推进 |
| `rawnode.go` / `node.go` | `io.edap.raft` | Ready 批次与驱动循环 |
| `raftpb-*.go` | `io.edap.raft.protocol` | `Message` / `Entry` / `HardState` / `Snapshot` / `ConfState` / `ConfChange*` |
| `log.go` / `log_unstable.go` | `io.edap.raft.log` | `RaftLog` / `Unstable` |
| `quorum-*.go` | `io.edap.raft.quorum` | `MajorityConfig` / `JointConfig` / `VoteResult` / `Index` |
| `tracker-*.go` | `io.edap.raft.tracker` | `Config` / `Progress` / `ProgressMap` / `ProgressTracker` / `Inflights` |
| `confchange-*.go` | `io.edap.raft.confchange` | `Changer` / `Restore` |
| `storage.go` | `io.edap.raft.storage` | `Storage` SPI / `MemoryStorage` / `FileRaftStorage` |

### 2.2 移植规约

- **语义 1:1**，不改算法、不加"改进"。etcd 怎么写，Java 就怎么写；
  Go 的 `(value, error)` 返回在 Java 里抛 `RaftException`。
- 命名跟随 etcd：Go 的 `raftLog` → Java 的 `raftLog`，`Voters[0]` → `getIncoming()`，
  `Voters[1]` → `getOutgoing()`。**不要另起别名**，否则无法与 Go 源码逐行对照。
- 线格式**不用 protobuf**：edap 的 `FastBuf` 没有 `readInt/writeInt`，
  `getInt(address)` 还是本机字节序，所以 `io.edap.raft.protocol.RaftCodec` 手写大端字节拼装
  （参照 `edap-mqtt-core` 的 `PublishDecoder` 写法）。
- 测试用 JUnit 5，**etcd 的每个测试用例都要有对应的 Java 实现**，
  Go 的 `datadriven` 测试（读 `testdata/*.txt`）改写成显式断言，不引入测试数据文件。
- 例外：交互式测试（etcd 的 `rafttest` 包 + `interaction_test.go`）**直接复用 etcd 的
  `testdata/*.txt` 原文**。它是一份「命令序列 → 期望输出」的行为基准，
  逐字复制过来，Java 侧跑同一串命令再逐字比对，才能证明两边行为完全一致；
  改写成断言反而丢掉了这层对照。

### 2.3 与 etcd 版本的对应

本移植基于 etcd raft v3.6 系的源码，注意它用的是**新版联合配置**：
`quorum.JointConfig` 是 `[2]MajorityConfig`（`c[0]` = incoming/new，`c[1]` = outgoing/old），
`confchange` 也是新版语义（`LearnersNext` 挂起 learner、`Changer` 同时返回
`Config` 与 `ProgressMap`）。移植时以 `/tmp/etcdraft` 下的 Go 源码为准。

> 「1:1 移植」限定的是**核心层**（选举 / 复制 / 提交 / 快照 / 成员变更 / 读），
> 这部分必须与 etcd 逐行对齐才能继承它的正确性测试基线。
> **驱动层**（§3 的 Ready 循环、落盘顺序、提案语义）不受这个约束，
> 是可以按性能需要重写的一层 —— §8 的性能天花板和 §9 的 dragonboat 对比
> 都落在这层上。

---

## 3. 分层与线程模型

```
        ┌──────────────────────────────────────────────────┐
应用层  │ 应用侧：落盘、发消息、apply 到业务状态机、快照    │
        └──────────────────────────────────────────────────┘
                            ▲
        ┌──────────────────────────────────────────────────┐
驱动层  │ Node / RawNode：Ready 循环、提案、读 future       │
        └──────────────────────────────────────────────────┘
                            ▲
        ┌──────────────────────────────────────────────────┐
核心层  │ Raft：纯状态机（单写者，无线程）                  │
        └──────────────────────────────────────────────────┘
                            ▲
        ┌──────────────────────────────────────────────────┐
传输层  │ RaftTransport（edap-nio-server）→ 对端            │
        └──────────────────────────────────────────────────┘
```

| 线程 | 职责 |
|---|---|
| NIO reactor 线程（框架提供） | 读帧 → 解码 → **只做投递**，不做 raft 逻辑 |
| raft 状态机线程（模块自建，单线程） | **raft 状态的唯一写者**：选举、复制、提交、apply、成员变更 |
| 应用侧定时器 | 按固定节奏调 `node.tick()`，驱动选举超时与心跳（模块不自带定时器） |

> `io.edap.util.EdapTime` 单例有**非 daemon** 线程（`Edap_time_thread`），会阻止 JVM 退出 ——
> raft 不用 `EdapTime` / `IdGenerator`，节点 id 由用户显式指定。

### 3.1 Ready 循环

核心是 etcd 的 Ready 模型：核心层**不碰存储、不发网络包**，只产出 `Ready` 批次：

```
Ready {
    softState        角色/leader 变化
    hardState        term / vote / commit（需落盘）
    entries          待落盘的日志条目
    snapshot         待安装的快照
    committedEntries 待 apply 的已提交条目
    messages         待发出的网络消息
    readStates       已确认领导权的读请求
}
```

驱动层拿到 `Ready` 后按固定顺序处理：

1. **先落盘**（`HardState` → `entries` / `snapshot`），保证回复 RPC 前持久化；
2. 再**发消息**（etcd 的 Ready 契约：entries 未落盘前不能发出去）；
3. 再**apply** `committedEntries` 到状态机；
4. 最后 `advance`，让核心层推进 unstable / applied 游标。

---

## 4. 线协议

帧格式：`[4B length][1B type][payload]`，length = payload 字节数。**全部大端**。

payload 与 `io.edap.raft.protocol.Message` 的字段一一对应（扁平布局），
编解码在 `io.edap.raft.protocol.RaftCodec`。
`context` 字段的长度为 `-1` 表示「没有附加数据」、为 `0` 表示「附加数据是空的」，
两者区分开，编解码之后消息仍可按值比较（`Message.equals`）。

**入站帧累积**：`DisruptorReadDispatcher` 每次读事件前 `buf.reset()`，**上一次没读完的字节会被丢弃**。
所以 `RaftServerSession` 自己维护 per-session 累积缓冲（`RaftFrameReader`），
`RaftDecoder` 只负责「从累积缓冲里切出完整帧」。

---

## 5. 存储

`Storage` 是**只读 SPI**（`initialState` / `entries` / `term` / `firstIndex` / `lastIndex` / `snapshot`），
写侧由驱动层在处理 Ready 时调用。两套实现：

- `MemoryStorage`：etcd 原版的内存实现，测试与临时节点用。
- `FileRaftStorage`：edap 自带的文件实现，`<dataDir>/` 下三个文件（全部大端序，
  `crc32` 覆盖它前面紧邻的所有字节）：

  | 文件 | 布局 |
  |---|---|
  | `meta.bin` | `magic("EDAP")(4) + version(1) + currentTerm(8) + vote(8) + commit(8) + crc32(4)` |
  | `log.bin` | 若干条记录：`length(4) + crc32(4) + payload`，`payload = index(8) + term(8) + type(1) + dataLen(4) + data` |
  | `snapshot.bin` | `magic(4) + version(1) + lastIncludedIndex(8) + lastIncludedTerm(8) + confStateLen(4) + confState + dataLen(4) + data + crc32(4)` |

  `confState` 的编码是四组 id 列表（`voters` / `learners` / `votersOutgoing` / `learnersNext`，
  每组 `count(4) + id(8)*n`）加一个 `autoLeave(1)` 标志。

  `log.bin` 的**第一条记录是占位记录**（dummy）：只带 index 与 term、不带数据，
  作用与 `MemoryStorage` 里的 dummy entry 一致 —— 标记被压缩掉的区间的终点，
  使得 `term(index)` 仍能回答压缩点上的 term；真正的日志条目从它后面开始，index 连续递增。

`meta.bin` 与 `snapshot.bin` 整体重写，走「写 `.tmp` → `FileChannel.force(true)` →
`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`」，避免半写；`log.bin` 追加写，
冲突截断时先截断再追加，每条记录带 CRC32。

恢复时逐条扫 `log.bin`，**第一条校验不过的记录及其后全部丢弃**并把文件截断
（处理尾部半写）；`index <= snapshot.lastIncludedIndex` 的条目忽略；内存建 `index → 偏移` 索引。

**落盘时机**（Raft 安全性）：回复 `REQUEST_VOTE` 前必须持久化 `votedFor`；
回复 `APPEND_ENTRIES` 前必须持久化新增日志条目。

---

## 6. 测试

父 pom 的 surefire 写死 `skipTests=true`，`edap-raft/pom.xml` 覆盖为 `false`。

测试分两层：

1. **etcd 单元测试的 1:1 移植**（主）：
   | etcd 测试文件 | Java 测试类 | 覆盖 |
   |---|---|---|
   | `raft_test.go` | `RaftTest` | 选举、复制、提交、快照、成员变更、ReadIndex |
   | `log_test.go` / `log_unstable_test.go` | `RaftLogTest` / `UnstableTest` | 日志追加、冲突截断、索引检索、快照边界 |
   | `quorum-*-test.go` | `quorum/*Test` | 多数派边界、联合仲裁、投票结果 |
   | `confchange-*-test.go` | `confchange/*Test` | 成员变更校验、joint 进入/退出、恢复 |
   | `tracker-*-test.go` | `tracker/*Test` | `Progress` 状态机、inflight 限流 |
   | `storage_test.go` | `MemoryStorageTest` / `FileRaftStorageTest` | 存取、截断、压缩、快照、重启后数据仍在 |
   | `raft_paper_test.go` | `RaftPaperTest` | 论文 §5.4.1/§5.4.2/§6 的图示场景 |
   | `node_test.go` | `RaftNodeTest` | Ready 交付、节点交互 |
   | `rafttest/` + `interaction_test.go` | `rafttest/*` + `InteractionTest` | 数据驱动交互测试：命令序列 → 期望输出逐字比对 |
2. **edap 侧集成测试**：真实 socket（`edap-nio-server` 传输栈）多节点组集群、
   选主、提案、副本一致。
3. **行为/性能测试**（`NodeProposalFloodTest` + `io.edap.raft.benchmark`）：
   用来把 §7 / §8 里的结论钉死，不依赖具体数字，而是依赖「相对关系」：

   | 测试 | 断言的东西 |
   |---|---|
   | `proposalFloodDoesNotStarveInboundNetworkMessages` | 提案洪水下入站网络消息**不再**被饿死（主循环改成一轮扫所有来源） |
   | `sustainedProposalLoadDoesNotStarveAdvance` | 提案洪水下批次照样能落盘、能提交（`advancec` 不再被饿死） |
   | `concurrentProposalsAreGroupCommitted` | 并发客户端把 fsync 摊薄到 1/6 以上 |
   | `proposeBlockTimeIsDominatedByWaitingForTheStateMachine` | 有 fsync 时 publish 在客户端可见延迟里是噪声 |

   另有 `io.edap.raft.NodeTickTest`：只打 tick、不发网络消息、不主动 campaign，
   节点应当自己选出主（钉住 `Node.tick()` 必须唤醒状态机线程）。

   `io.edap.raft.benchmark` 下是独立跑的基准程序（不是 JUnit），命令见 §8.5。

交互测试的数据文件在 `src/test/resources/testdata/`，逐字复制自 etcd raft v3.6 的
`testdata/`（28 个里的 26 个）。未移植的两个是 `async_storage_writes.txt` 与
`async_storage_writes_append_aba_race.txt`：它们依赖 Go 的 `AsyncStorageWrites`
（落盘与应用分离的异步流水线），Java 侧 `RawNode` 只实现了同步落盘路径
（与 Go 的 `asyncStorageWrites=false` 等价），跑不了这两份数据。
dragonboat 把这条流水线做成了完整的三阶段（见 §9.3），是补齐这两份数据的参考实现。

线格式没有对应的 etcd 测试（etcd 用 protobuf），`protocol/RaftCodecTest` 是 edap 自有的：
逐字段做编解码往返，帧头、长度计算、日志条目、快照与成员配置、读索引响应都覆盖到。

### 运行命令

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn -o -pl edap-raft-parent/edap-raft -am test -Dmaven.javadoc.skip=true
mvn -o -pl edap-raft-parent/edap-raft -am test -Dtest=RaftTest
```

> 运行时需要 `--add-opens java.base/sun.nio.ch=ALL-UNNAMED`（`FastSelectorProvider` /
> `MethodHandleNetIO` 反射 `sun.nio.ch` 内部字段）—— 父 pom 的 surefire `argLine` 已包含。

---

## 7. 已知边界

- **未实现 learner 的完整语义**（本移植已含 learner 的 `Progress` 与 `LearnersNext`，
  但没有 learner 的专用测试集）、leadership transfer 的完整流程。
- **未移植 etcd v3.6 的 `state_trace.go` 状态追踪设施**（默认构建下 Go 侧也是空实现，
  只在 `rafttrace` build tag 下生效），Java 侧没有 `traceConfChangeEvent` 这类钩子。
- **只实现了同步落盘路径**：Java 的 `RawNode` 与 Go 的 `asyncStorageWrites=false` 等价，
  异步落盘（落盘与应用分离的流水线）没有实现，对应的两份上游测试数据未移植。
  参考实现见 §9.3。
- **`edap-container` 没有把 `Edap` 注册为 bean**，容器场景下应用暂时拿不到宿主 `Edap`；
  `NioRaftTransport` 会自建一组 reactor 线程。这是 `edap-raft` 与容器之间的一个待补接缝。
- **`edap-nio-server` 的写路径在背压下有多个框架级缺陷**（`flush()` 里重新 register 会丢掉 OP_READ、
  `DisruptorReadDispatcher` 的 OP_WRITE 分支不清 OP_WRITE 兴趣）。raft 的响应体很小（几十字节），
  实际触发概率低；且 raft 的响应走的是出站 `SocketPeerClient`，不经过框架的写队列。
- **主循环曾经是「固定优先级轮询」，不等价于 etcd 的 `select`** —— 已修，见 §7.1。
- **出站队列满了会静默丢帧**：`SocketPeerClient` 的 `outQueue` 是
  `ArrayBlockingQueue<byte[]>(1024)`，`enqueue` 里 `offer` 失败只打一条 warn 就丢。
  raft 允许丢心跳，但**不能丢 `MsgApp`**（丢了会卡住复制进度），这里没有区分。
- **`recvc` 保持 `LinkedBlockingQueue` 不动**：实测（`io.edap.raft.benchmark`，
  本机 macOS aarch64 / 10 CPU）五种方案里 LBQ 在「打满吞吐」和「尾延迟」上都最好，
  vmsmith 式两级 ring + Merger 最差。队列操作本身（offer+poll）只有 ~31ns，
  而瓶颈是 fsync（0.1–10ms），差 4–5 个数量级 —— 换队列没有收益。
  真正该优化的是上面的「主循环优先级」，不是队列。
- **publish 路径上最贵的不是队列**：实测（§8.4）`Node.enqueueProposal` 的三步里，
  队列 offer 只占 0.5μs，`synchronized(lifecycle)` 占 0.3μs，
  **唤醒状态机线程的 unpark 占 1.5μs**。换更快的队列最多省 0.3μs。
- **`Node.propose()` 是同步的**：阻塞到提案被状态机 step 完，不等提交。
  这决定了提案的往返成本（§8.2 的 6.4μs），也是 §8.3 极限表的硬约束。
  异步提案的参考实现见 §9.5。

### 7.1 已修复：主循环的优先级轮询

原实现是**固定优先级轮询**：每轮依次 poll
`propc → tickc → confc → statusc → advancec → recvc`，命中一条就 `continue` 重来。
etcd 用 Go 的 `select`，多个 case 同时就绪时**随机**挑一个 —— 语义不等价。
后果是提案只要一直涌入，`recvc`（网络消息）和 `advancec`（Ready 推进）就永远轮不到：

- 入站的心跳/复制消息被无限期饿死；
- Ready 合不拢 → 一次落盘都不发生 → raft 什么也不提交。

**已按 dragonboat 的做法改成「一轮扫所有来源」**（§9.4）：每个来源在一轮里被排空，
提案排空受 **1ms 的时间预算**约束（不是条数 —— 单条提案的耗时不可控，
按条数排会让网络消息等上好几百毫秒），
Ready 的组装放在本轮所有 step 之后、紧挨着交付（否则 `acceptReady()` 里的
`clearMessages()` 会抹掉组装之后才产生的消息）。
Ready 只在状态确实变过时重新组装，避免大批量下的重复拷贝。

配套改动：唤醒机制从 `Semaphore` 换成「标志位 + `LockSupport`」。
信号量会累积许可（每条消息 `release()` 一次，消费侧一轮只 `acquire()` 一次），
多余的许可让状态机线程空转；标志位把多次唤醒请求合并成一次，
与 dragonboat `workReady` 的非阻塞 notify 同款。

实测收益（`AsyncProposalHarness` 的 `sync-commit` 模式 = 真实客户端语义：
提案后等提交；本机 macOS aarch64 / 10 CPU，单节点集群，16 客户端，
OLD / NEW 各跑 **9 轮**，取中位数 —— 之前用 3~5 轮区间，噪声太大分不开）：

| 场景 | 改前（OLD）中位数 | 改后（NEW）中位数 | 提升 |
|---|---|---|---|
| fsync=2ms | **3,659 提案/s** | **4,481 提案/s** | **+22.5%** |
| fsync=2ms，每次落盘条数 | 8.4 ~ 9.3 | 8.9 ~ 11.4 | — |
| 纯内存 fsync=0 | **127,524 提案/s** | **129,828 提案/s** | **+1.8%**（持平） |

原始区间（9 轮）：

| 场景 | OLD | NEW |
|---|---|---|
| fsync=2ms | 3,318 ~ 3,994 | 3,539 ~ 5,126 |
| 纯内存 fsync=0 | 124,646 ~ 128,463 | 124,324 ~ 131,107 |

**基线口径说明**：**127K/s 是「改之前」的数，纯内存场景 ① 前后基本没变**
（+1.8%，在机器抖动 ±3% 内）。文档里更早出现过的 157K/s 来自另一次
32/64 客户端的扫描，不是 16 客户端的配置，别混用。

另外一组定性数据（`NodeProposalFloodTest` 的 1 秒提案洪水）：

| 场景 | 改前 | 改后 |
|---|---|---|
| 1 秒提案洪水 | 85 个提案，**1 次落盘、0 个提交** | 85 个提案，**86 次落盘、14 个提交** |

纯内存吞吐基本不变是符合预期的：① 不改每提案的往返成本（客户端仍然等状态机 step），
它解决的是**饿死**和**批量合不拢**。真正的量级提升要靠 §9.8 的 ② 和 ③。

顺带修掉的一个延迟问题：`Node.tick()` 原来不唤醒状态机线程，
tick 只是塞进 `tickc`，要等应用层下一次调 `ready()` 才顺带被看到
（最多一个 100ms 的轮询切片延迟）。现在 tick 直接唤醒状态机线程。
回归测试见 `io.edap.raft.NodeTickTest`。

---

## 8. 性能模型与极限

本节的数字都来自 `edap-raft-parent/edap-raft/src/test/java/io/edap/raft/benchmark/`
与 `NodeProposalFloodTest`，环境是本机 macOS aarch64 / 10 CPU / JDK17 / **单节点集群**
（单节点是为了把「复制路径」这个变量摘出去）。

### 8.1 分层天花板

| 层 | 怎么测的 | 吞吐 |
|---|---|---|
| raft 逻辑 | `RaftCoreThroughputHarness`：单线程直驱 `RawNode`（`propose → ready → append → advance`），无队列、无线程切换 | 2.97M/s (batch=1) ~ **6.29M/s** (batch=64~4096) |
| Node 驱动链路（同步提案） | `AsyncProposalHarness` 的 `sync-commit` 模式：16 个客户端线程，`fsync=0` | **127K/s**（① 前后都一样，与客户端数无关） |
| Node 驱动链路（异步提案，在途窗口 1024） | `AsyncProposalHarness`：客户端不等状态机 step，等批量 commit | **331K/s** |
| Node 驱动链路（异步提案，不等提交） | 同上，fire-and-forget | **1.61M/s** |
| 落盘 | 应用层 fsync | fsync 速率 × batch |

Node 层比核心层差 18 倍，差的**全是线程交接**（每提案两次 park/unpark ≈ 6.4μs），
不是队列、不是 O(n²)。异步提案把「往返」变成「流水线」，这一层就松开了。

### 8.2 加性模型

```
吞吐 = batch / (fsync 延迟 + batch × 每提案固定成本)
                            └── 现状 6.4μs ──┘
```

用实测参数代入校验：fsync=2ms（`Thread.sleep(2)` 实际约 2.5ms）、batch=8.4 ——

```
8.4 / (2.5ms + 8.4 × 6.4μs) = 3,289/s     实测 3,221/s
```

模型与实测吻合到 2%。

### 8.3 极限表

「现状」是当前 `Node` 的实测（① 已落地，见 §7.1）；「② 之后」是**提案异步化实测**
（`AsyncProposalHarness`：客户端不等状态机 step，改成等批量 commit 通知，
在途窗口 = 每客户端允许未确认的提案条数）。

**窗口深度是 ② 的全部杠杆**：窗口=1 时异步提案和同步提案没区别（还是一次往返），
窗口放开后客户端不再等状态机，瓶颈从「往返延迟」挪到「流水线填充速度」。

| 场景 | 现状（① 已落地） | ② 之后（实测） | ②+③ 之后（外推） |
|---|---|---|---|
| 纯内存 fsync=0，窗口=1 | 127K/s | 127K/s（无差别） | — |
| 纯内存 fsync=0，窗口=16 | 127K/s | **276K/s**（2.2×） | — |
| 纯内存 fsync=0，窗口=1024 | 127K/s | **331K/s**（2.6×） | **1.6M/s**（流水线填满，见下） |
| fsync=2ms，窗口=1 | 4.5K/s | 4.5K/s | — |
| fsync=2ms，窗口=16 | 4.5K/s | **41K/s**（9.1×） | — |
| fsync=2ms，窗口=256 | 4.5K/s | **329K/s**（**73×**） | — |
| fsync=2ms，窗口=1024 | 4.5K/s | 314K/s（70×） | — |
| 3 节点 + 真实 NVMe | 1–3K/s | — | **5–20K/s** |

fsync 场景提升远大于纯内存，原因不是「提案更快了」，而是**批量变大了**：
一次落盘覆盖的条数从 12 条涨到 1043 条（`AsyncProposalHarness` 实测），
2ms 的 fsync 被摊薄到 0.3μs/条。

**前提是提案 API 异步化。** 只要保持「客户端等状态机 step 完」这个语义，
127K/s 的天花板就动不了 —— 那 6.4μs 是这个语义本身要求的两次交接，不是实现 inefficiency。
dragonboat 走的就是异步提案路线（§9.5），单 group 实测 125 万 writes/s。

三条优化的分工（各自的实测/外推依据见 §9.8）：

| 优化 | 解决什么 | 量级 |
|---|---|---|
| ① 一轮扫所有来源 | 饿死、批量合不拢 | **已实测**：fsync 场景 **+22.5%**（3,659 → 4,481/s，9 轮中位数），纯内存 **+1.8%**（持平） |
| ② 提案异步化 | 每提案 6.4μs 的线程往返 | **已实测**：纯内存 2.2~2.6×；fsync 场景 9~72×（窗口深度决定） |
| ③ 落盘挪进框架 + 三阶段分离 | fsync 与状态机推进串行 | **外推**：核心层实测天花板，fsync=2ms 时 355K/s @batch=1024、1.17M/s @batch=4096 |

**为什么 ③ 仍然值得做**：② 之后剩下的瓶颈是「Ready 乒乓」—— raft 推进必须等
应用层 `advance()` 才能继续。`AsyncProposalHarness` 的主循环 profile 显示，
fsync=2ms / 窗口=256 时 96% 的时间状态机线程在排空提案，但 Ready 速率只有 315/s
（= 1/fsync），每次 Ready 只能带 1043 条。三阶段分离让 raft 推进不再等 fsync，
Ready 速率和 batch 才能脱钩。

raft 核心层的原始数据（`RaftCoreThroughputHarness`，fsync=2ms，payload 64B）：

| batch | 无 fsync | fsync=2ms |
|---|---|---|
| 1 | 2,972,115/s | 391/s |
| 8 | — | 3,123/s |
| 64 | 6,256,491/s | 24,811/s |
| 256 | — | 96,853/s |
| 1024 | 6,126,251/s | **355,669/s** |
| 4096 | 6,292,821/s | **1,165,995/s** |

fsync=2ms 时 Ready 速率稳定在 ~390/s（= 1/2.5ms），提案速率 = Ready 速率 × batch，
公式成立；batch 越大，Ready 速率略降（batch=4096 时降到 285/s），但提案速率线性涨。
**batch 不再被客户端数封顶**，这才是 ③ 的价值所在。

**batch 不能无限大**，现实约束：

1. 复制延迟：leader 领先 follower 太多会阻塞提案；`maxInflightMsgs = 256`
   （复制侧一次最多 256 条在途）
2. 3 节点时**最慢的 follower 决定吞吐** —— leader 的 batch 再大也没用，
   follower 也要 fsync
3. 内存：一次落盘 batch × 载荷
4. 网络：1M/s × 50B ≈ 50MB/s

实用区间是 **batch 16–64**。

### 8.4 publish 路径分解

`Node.enqueueProposal`（`Node.java:496-507`）做三件事：`synchronized(lifecycle)`、
`propc.add(pm)`、`wakeup.release()`。`PublishPathCostHarness` 把它们拆开量
（8 生产者 / 100 msg/s/producer / 10s）：

| 变体 | p50 | p90 | p99 | p999 | max |
|---|---|---|---|---|---|
| offer only（裸队列） | 0.5μs | 3.4μs | 19.8μs | 126.8μs | 426.3μs |
| monitor + offer | 0.8μs | 3.7μs | 9.1μs | 40.7μs | 1034.3μs |
| + release（许可数 > 0，纯 CAS） | 0.8μs | 3.8μs | 10.1μs | 51.0μs | 174.2μs |
| + release（唤醒停着的线程，unpark） | **2.3μs** | 6.2μs | 16.9μs | 34.0μs | 66.6μs |

**publish 里最贵的是 `wakeup.release()` 的 unpark 系统调用（1.5μs），不是队列。**
队列 offer 只占 0.5μs，monitor 只占 0.3μs。

对比五种队列的 publish 侧延迟（`PublishLatencyHarness`，5 生产者 / 100 msg/s/producer / 15s）：

| 方案 | p50 | p90 | p99 | p999 | max |
|---|---|---|---|---|---|
| LinkedBlockingQueue | 0.6μs | 2.4μs | **16.9μs** | 79.0μs | 87.9μs |
| LBQ + 自旋消费 | 0.3μs | 1.3μs | 4.5μs | 18.5μs | 46.7μs |
| edap-common Disruptor | 0.5μs | 1.8μs | 7.8μs | 34.0μs | 271.1μs |
| vmsmith 两级 | 0.5μs | 1.2μs | 3.2μs | 23.4μs | 133.3μs |
| SPSC ring 一级 fan-in | **0.3μs** | **0.9μs** | **2.7μs** | 19.3μs | 38.6μs |

「MPSC 下 publish 会互相阻塞、换更快的队列能缩短阻塞时间」这个原理**实测成立**
（LBQ 的 publish p99 比 fan-in 差 6 倍）。但它落不到 edap-raft 上：

| 路径 | publisher | publish 阻塞吗 | 客户端看得见吗 |
|---|---|---|---|
| `propc`（客户端提案） | 应用线程 | 会，但阻塞在等状态机 step | 1 客户端纯内存时占 30%（3.4μs / 7.4μs），有 fsync 时占 0.8%（40μs / 5136μs） |
| `recvc`（网络消息） | 1 个 IO 线程（`RaftServer` `setIoThreadCount(1)`）+ 应用线程 | 不会，`offer` 立即返回 | 1000 msg/s × 2.3μs = IO 线程占用 0.23% |
| `outQueue`（出站） | raft 线程 | **不阻塞 —— `offer` 失败直接丢帧** | 要修的是背压，不是速度 |

客户端端到端延迟的分解（`NodeProposalFloodTest`）：

| 场景 | `propose()` 阻塞 p50 | 等提交 p50 |
|---|---|---|
| 1 client，no fsync | 3.4μs | 7.4μs |
| 16 clients，no fsync | 31.5μs | 83.8μs |
| 16 clients，fsync 2ms | 40.2μs | **5136μs** |

### 8.5 跑基准

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
mvn -o -pl edap-raft-parent/edap-raft -am test-compile -Dmaven.javadoc.skip=true
CP="edap-raft-parent/edap-raft/target/classes:edap-raft-parent/edap-raft/target/test-classes:$(cat /tmp/raft-cp.txt)"

# 端到端队列延迟：java ... LatencyHarness <生产者数> <每生产者msg/s> <秒>
# 打满吞吐：      java ... MpscHarness <生产者数> <秒>
# publish 侧：    java ... PublishLatencyHarness <生产者数> <每生产者msg/s> <秒>
# publish 分解：  java ... PublishPathCostHarness <生产者数> <每生产者msg/s> <秒>
# raft 核心：     java ... RaftCoreThroughputHarness <每批条数> <秒> <fsync毫秒> [载荷字节]
# 提案同步/异步： java ... AsyncProposalHarness <客户端数> <秒> <fsync毫秒> \
#                    <sync|sync-commit|async|async-commit> [每客户端在途上限]
```

JMH 单次操作纯成本（offer+poll，无切换）：SPSC ring 9.3ns < Disruptor 23.5ns <
LBQ 30.9ns < vmsmith 两级 47.7ns。

---

## 9. 与 dragonboat 的设计对比

[dragonboat](https://github.com/lni/dragonboat)（lni 维护的 Go 库，43K 行）是目前少数
在 etcd/raft 之外把 Raft 做到工业吞吐的**非 etcd 血统**实现。它的 raft 核心仍然是
etcd/raft 派生（仓库里保留 `internal/raft/NOTICE.etcd` 和 `raft_etcd_test.go` /
`raft_etcd_paper_test.go` 作为正确性基线），但**上层的驱动模型整个重写了**。

本节的目的不是「照抄 dragonboat」，而是给 §7 里的已知边界、§8 里的性能天花板
找一个已被验证过的解法参考，并区分哪些能借鉴、哪些是 edap-raft 场景下拿不到的。

### 9.1 定位：Multi-Group Raft

dragonboat 的定位是**一个进程跑多个独立 Raft 分组**（shard）。README 给的性能：

| 场景 | 吞吐 | 延迟 |
|---|---|---|
| 3 节点 + RocksDB，16B 载荷 | **900 万 writes/s** | 800 万/s 时 P99 < 5ms |
| **单个 Raft group** | **125 万 writes/s** | 平均 1.3ms，P99 2.6ms（3 核） |

README 自己点明了吞吐的来源：

> The number of concurrent active Raft groups affects the overall throughput as
> requests become harder to be batched.

即 **吞吐来自跨 group 批处理**。单 group 的 125 万/s 才是「单条路径」的量级，
edap-raft 是单 group 场景，所以能对标的只有这个数。

#### 与 edap-raft 的对标（实测 vs README 自报）

`AsyncProposalHarness` 的 `sync-commit` / `async-commit` 模式同时量吞吐和
**端到端延迟**（提案发布 → 客户端观察到提交）：

| 实现 | 场景 | 吞吐 | 平均延迟 | P99 |
|---|---|---|---|---|
| **dragonboat**（README 自报） | 单 group，3 节点 + RocksDB，16B，3 核 | **1,250,000/s** | 1.3ms | 2.6ms |
| edap-raft ① 后 | 同步提案，纯内存 | 121,000/s | 0.14ms | 0.29ms |
| edap-raft ① 后 | 同步提案，fsync=2ms | 3,600/s | 4.50ms | 7.95ms |
| edap-raft ①+② | 异步提案，窗口 16，纯内存 | 248,000/s | **0.80ms** | **2.22ms** |
| edap-raft ①+② | 异步提案，窗口 64，纯内存 | 306,000/s | 2.63ms | 8.72ms |
| edap-raft ①+② | 异步提案，窗口 256，fsync=2ms | 317,000/s | 10.73ms | 20.65ms |
| edap-raft ①+②③（外推） | 三阶段分离后 | ~1,200,000/s | — | — |

**延迟轴上 ①+② 已经追平 dragonboat**（窗口 16 的纯内存：p50 0.80ms / P99 2.22ms
vs dragonboat 平均 1.3ms / P99 2.6ms）。
**吞吐轴上 ①+② 还差 4 倍**（300K/s vs 1.25M/s）；③ 做完外推才到同一量级。

**但这组对比的条件不可比，别当结论用**：

| 维度 | dragonboat | edap-raft（本文实测） | 谁占便宜 |
|---|---|---|---|
| 集群 | 3 节点，1.25M/s 是**集群级**吞吐 | **单节点**，节点级吞吐 | edap-raft（少走网络） |
| 存储 | 真 RocksDB（真 fsync） | `MemoryStorage` + `Thread.sleep(2)` **模拟** fsync | edap-raft（模拟不是真盘） |
| 载荷 | 16B | 64B | dragonboat（更小更好批处理） |
| 核数 | 3 核 | 10 核 | edap-raft |
| 数据来源 | README 自报，无第三方复现 | 本机实测 | — |

也就是说 **edap-raft 这边全是乐观上界**。要真对标，得在 3 节点 + 真盘上重测，
现在的数只能说明「差距在 4 倍以内，不是 50 倍」。

**结构性的差距拿不到**：dragonboat 的 900 万/s 靠跨 group 批处理，
edap-raft 是单 group 场景，即使追平 125 万/s 也拿不到那条路。

### 9.2 驱动模型：`Ready` → `Update`

etcd 的 `Ready` 描述的是「raft 内部状态的一次快照」，是单节点、一次性、不可聚合的。
dragonboat 定义了 `raftpb.Update`（`raftpb/update.go:74`）：

```go
type Update struct {
    ShardID, ReplicaID uint64
    State              pb.State
    FastApply          bool
    EntriesToSave      []pb.Entry
    CommittedEntries   []pb.Entry
    MoreCommittedEntries bool
    Snapshot           pb.Snapshot
    ReadyToReads       []pb.ReadIndexResult
    Messages           []pb.Message
    LastApplied        uint64
    UpdateCommit       pb.UpdateCommit
    DroppedEntries     []pb.Entry
    DroppedReadIndexes []pb.ReadIndexResult
    LogQueryResult     pb.LogQueryResult
    LeaderUpdate       pb.LeaderUpdate
}
```

对端是 `Peer.Commit(ud)`（`internal/raft/peer.go:292`）。

**为什么必须换**：Ready 无法跨 shard 聚合；Update 描述的是「一次 step 的产物」，
天然能聚合成一个切片 —— 这是后面批量落盘的前提。

### 9.3 三阶段流水线（`engine.go`）

engine 里跑三个独立 worker 池，各有各的 `workReady` 就绪集合：

| worker | 入口 | 职责 |
|---|---|---|
| **stepWorker** | `engine.go:1230 stepWorkerMain` | 跑 raft 状态机，产生 Update |
| **commitWorker** | `engine.go:1107 commitWorkerMain` | 通知客户端「已提交」 |
| **applyWorker** | `engine.go:1153 applyWorkerMain` | 把条目交给 RSM 执行 |

`engine.go:1304 processSteps` 的执行顺序是整个设计最关键的地方：

```
1. for each ready node:  ud = node.stepNode()                    ① 跑 raft 状态机
2. applySnapshotAndUpdate(updates, nodes, fastApply=true)        ② 先 apply 不等落盘的
3. for each ud: sendReplicateMessages(ud) + readyToRead/...      ③ 复制消息先发
4. e.logdb.SaveRaftState(nodeUpdates, workerID)                   ④ 一次落盘所有 shard
5. applySnapshotAndUpdate(updates, nodes, fastApply=false)       ⑤ 再 apply 等落盘的
6. for each ud: processRaftUpdate(ud) → commitRaftUpdate(ud)     ⑥ 回灌 raft 核心
```

三个要点：

**(a) 跨 shard 批量落盘。** `SaveRaftState(updates []pb.Update, shardID uint64)`
（`internal/logdb/logdb.go:258`）一次原子写入多个 shard 的 raft state + 日志条目，
最后一次 `sync()`。N 个 shard = **1 次 fsync**。这是它 900 万/s 的直接来源，
**也是 edap-raft 单 group 场景拿不到的那部分收益**。

**(b) 复制消息在落盘之前发。** 源码注释直接引用 raft 论文 §10.2.1：

```go
// see raft thesis section 10.2.1 on details why we send Replicate message
// before those entries are persisted to disk
```

etcd 也这么干（entries 未落盘不发），但 dragonboat 把它固定在流水线的固定位置上，
不再由驱动方自由决定顺序。

**(c) FastApply。** `internal/raft/peer.go:210 setFastApply`：已提交条目可以不等 fsync
就交给状态机。默认开，只有当「要 apply 的最后一条比要保存的第一条还新」（即还没落盘）
时才关掉。所以流水线里 `applySnapshotAndUpdate` 被调用**两次**：第一次 `fastApply=true`
处理不等落盘的那部分，第二次 `fastApply=false` 处理必须等落盘的那部分。

这比 etcd v3.5+ 的 `AsyncStorageWrites` 更进一步：etcd 只把「写盘」从 raft 推进里
分离出来，**不跨 group 批量**，也没有独立的 apply 阶段。

### 9.4 主循环：一轮扫所有来源

这是 dragonboat 相对 etcd **唯一语义上更优**（而不是"更复杂"）的地方，
也是 edap-raft 最该借鉴的一条。

`node.go:1161 handleEvents`：

```go
func (n *node) handleEvents() (bool, error) {
    hasEvent := false
    lastApplied := n.updateAppliedIndex()
    if lastApplied != n.confirmedIndex { hasEvent = true }
    if n.hasEntryToApply() { hasEvent = true }
    event, err := n.handleReadIndex();          ...
    event, err = n.handleReceivedMessages();   ...   // 网络消息
    event, err = n.handleConfigChange();       ...
    event, err = n.handleProposals();          ...   // 提案
    event, err = n.handleLeaderTransfer();     ...
    if n.handleSnapshot(lastApplied) { hasEvent = true }
    if n.handleCompaction() { hasEvent = true }
    event, err = n.handleLogQuery();           ...
    n.gc()
    ...
}
```

**每个 handler 一次把队列排空，没有「命中一条就 `continue`」**。
`hasEvent` 只收集「这一轮有没有事」，不影响任何 handler 是否执行。

对照 edap-raft `Node.java:512-621`：

```java
while (running) {
    if (!propc.isEmpty())      { drain(propc);  continue; }   // ← 命中就 continue
    if (!tickc.isEmpty())      { drain(tickc);  continue; }
    ...
    if (!advancec.isEmpty())   { ...;           continue; }
    if (!recvc.isEmpty())      { drain(recvc);  continue; }
    wakeup.acquire();
}
```

etcd 原版用 Go 的 `select`，多 case 同时就绪时**随机**挑一个 → 天然不会饿死。
Java 的固定优先级**语义不等价** —— 提案洪水下 `recvc`（网络消息）与 `advancec`
（Ready 推进）被无限期饿死（§7、§8 的两条实测证据）。

dragonboat 的解法是**最省事的那种**：不需要改成随机/时间片轮询，只要去掉 `continue`，
让所有来源每轮都有机会被排空。

### 9.5 提案路径：双缓冲槽位 + 异步返回

**队列**（`queue.go:24 entryQueue`）：

```go
type entryQueue struct {
    size        uint64
    left        []pb.Entry      // 两个定长切片
    right       []pb.Entry
    leftInWrite bool            // 当前写哪个
    idx         uint64
    mu          sync.Mutex
}

func (q *entryQueue) add(ent pb.Entry) (bool, bool) {
    w := q.targetQueue()
    w[q.idx] = ent
    q.idx++
    return true, false
}

func (q *entryQueue) get(paused bool) []pb.Entry {
    sz := q.idx
    q.idx = 0
    t := q.targetQueue()
    q.leftInWrite = !q.leftInWrite   // 翻页
    return t[:sz]                    // 整批取走，零拷贝
}
```

这是「SPSC ring 思想」在 MPSC 场景下的正确用法：生产者写当前槽，消费者一次取走整批
并翻页。**有界**（`size` 满了 `add` 返回 false）、**零拷贝**、**不阻塞**。

对比 edap-raft：`propc = new LinkedBlockingQueue<>()` —— 无界、每次 add 分配节点、
阻塞式 put。

**提案异步**（`request.go:1077 proposalShard.propose`）：`propose()` 立即返回
`*RequestState`，客户端自己 select on `Completed` / `Dropped` / `Timeout`；
`RequestState` 从对象池取、用完 `Release()` 归还。

队列满了返回 `ErrSystemBusy` —— 这是**背压**，比 edap-raft 无界 LBQ 一直吸收好。

这直接印证了 §8.3 的结论：**edap-raft 的 127K/s 天花板，那 6.4μs 是「客户端等状态机
step 完」这个语义本身要的，不是实现 inefficiency**。dragonboat 能到单 group
125 万/s，前提就是提案 API 是异步的。

### 9.6 其他值得注意的实现

- **没有 etcd 的 O(n²)**：`internal/raft/log.go` 用 `entryLog` + `inmem` 替代 etcd 的
  unstable / committed 双追踪，`entriesToSave()` 返回切片**不拷贝**。
  edap-raft 的 `RawNode.readyWithoutAccept()` 每轮重装 + `Unstable.nextEntries()` 复制整表
  （§7）正是 etcd 那个 O(n²) 的翻版。
- **快照独立处理**：快照由 engine 的 worker pool 单独跑，不占 raft 主循环。
- **副本 / 投票 / witness 三分**：`remotes` / `nonVotings` / `witnesses` 三张表，
  witness 是只投票不存日志的成员。

### 9.7 对照表

| 维度 | edap-raft（etcd 移植） | dragonboat |
|---|---|---|
| 驱动模型 | Ready + Advance | **Update + Commit** |
| 主循环 | 固定优先级轮询，命中一条就 continue | **一轮扫所有来源，各排空** |
| 流水线 | 单线程串行 | **step / commit / apply 三阶段** |
| 落盘 | 应用层同步 `storage.append()` | **engine 批量落盘，跨 shard 一次 fsync** |
| apply | 应用层自己 apply | **applyWorker 独立阶段，批量** |
| FastApply | 有（`applyUnstableEntries` 恒 true） | 有，默认开 |
| 复制消息 | 应用层从 `Ready.getMessages()` 取出发 | **engine 在落盘前统一发** |
| 提案 | **同步阻塞**（等状态机 step） | **异步**（`RequestState`） |
| 提案队列 | 无界 `LinkedBlockingQueue` | **有界双缓冲槽位数组** |
| 背压 | 无 | **队列满 → `ErrSystemBusy`** |
| 日志追踪 | etcd unstable（O(n²) 复制） | `entryLog` + `inmem`（不拷贝） |

### 9.8 三条可借鉴项（按性价比排序）

**① 主循环改「一轮扫所有来源」** —— **已实现**（见 §7.1）。去掉 `continue`，
让每个来源的 handler 一次排空，解决饿死问题。
实测（OLD / NEW 各 9 轮取中位数，16 客户端）：fsync=2ms **+22.5%**
（3,659 → 4,481 提案/s），纯内存 **+1.8%**（127.5K → 129.8K/s，持平）；
提案洪水从「0 次落盘、0 个提交」变成「86 次落盘、14 个提交」。
这是 dragonboat 相对 etcd 唯一语义更优的地方，也是改动量 / 收益比最高的一条。

**② 提案异步化** —— 唯一能突破 127K/s 的路，但要改公共 API（`Node.propose()` 现在
阻塞到状态机 step 完）。可参照 dragonboat 的 `RequestState` + `Session`：提案返回句柄，
客户端自己等 `Completed` / `Dropped` / `Timeout`。

**已实测**（`AsyncProposalHarness`，16 客户端，单节点集群）：

| 在途窗口（每客户端） | 纯内存 fsync=0 | fsync=2ms |
|---|---|---|
| 1 | 127K/s | 4.5K/s |
| 4 | 203K/s | 12.0K/s |
| 16 | **276K/s** | **41.5K/s** |
| 64 | 310K/s | 125K/s |
| 256 | 317K/s | **329K/s** |
| 1024 | **331K/s** | 314K/s |

**窗口深度是全部的杠杆**：窗口=1 时异步和同步没区别（都还是一次往返），
窗口=256 时 fsync 场景提升 **72 倍**（4.6K/s → 329K/s）。
提升来自**批量变大**（一次落盘覆盖 12 条 → 1043 条），不是单条提案更快。

纯内存场景的另外两个数：
- 完全不等提交（fire-and-forget）时流水线能填到 **1.6M/s**（fsync=0）/
  **1.26M/s**（fsync=2ms，一次落盘覆盖 11,042 条）——这是「窗口无限大」的极限。
- raft 核心层（单线程直驱 `RawNode`）的天花板是 **6.3M/s**，
  所以 `Node` 这一层的队列+交接开销约占 4 倍。

**注意这只在调用方本来就要等提交时才有收益**；调用方不等提交的话，
同步提案反而更简单。真要上异步提案，还得补上现在没有的两件事：
① 提案被丢弃（不是 leader）要能回传给客户端 —— 同步路径靠 `awaitResult()` 抛异常，
异步路径需要一个 `RequestState` 句柄；
② **背压** —— 现在 `propc` 是无界队列，异步提案会把它撑爆。

**③ 落盘挪进框架 + 三阶段分离** —— 收益最大、改动也最大：要把 `storage.append()` 从
应用层收回 Node 内部。edap-raft 是单 group 场景，**跨 shard 批量这条拿不到**；
能拿到的是「raft 推进 / 落盘 / apply 三阶段分离」，让 fsync 与状态机推进重叠。
这同时也能补上 §6 提到的两份未移植的 etcd 测试数据
（`async_storage_writes.txt` / `async_storage_writes_append_aba_race.txt`）所依赖的能力。

**为什么 ② 做完 ③ 还要做**：② 之后瓶颈变成「Ready 乒乓」—— raft 推进要等应用层
`advance()`。fsync=2ms 时 Ready 速率被 fsync 锁死在 315/s，每次 Ready 只能带 1043 条，
于是吞吐 = 315 × 1043。三阶段分离让 raft 推进不等 fsync，
Ready 速率和 batch 才能脱钩 —— 核心层实测 batch=4096 时能到 **1.17M/s**。

另外要注意：**③ 依赖 ②**。当前同步提案下 batch 被客户端数封顶在 ~16，
不先做 ②，③ 的「批量落盘」根本攒不出大批次。

