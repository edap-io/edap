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
| raft 状态机线程（模块自建，单线程） | **raft 状态的唯一写者**：选举、复制、提交、成员变更 |
| 落盘线程（`frameworkPersist` 时自建） | 一次 `write` + 一次 `fsync`；完成后回灌落盘相关消息 |
| apply 线程（`applyHandler` 非 null 时自建） | 应用已提交条目；完成后推进 `applied` 指针 |
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

> 第 2 步的「entries 未落盘前不能发出去」在 §3.2 被细化：
> **只有「承诺了持久化」的消息**（投票确认、日志追加确认）受这条约束；
> 复制类消息（`MSG_APP`）可以先发出去 —— 见 §3.2 与 §9.1。

### 3.2 三阶段流水线（框架接管落盘 + 独立 apply 线程）

etcd 的 Ready 循环把「落盘 + 发消息 + apply」串在一个应用线程里，一个 Ready 周期
被 fsync 完整占住。edap-raft 把它拆成三个阶段，由两个开关控制（`RaftConfig`）：

| 开关 | 作用 | 默认 |
|---|---|---|
| `frameworkPersist` | 落盘由框架（`Node`）接管，应用层不再调 `storage.save()` | 关（etcd 移植基线不受影响） |
| `applyHandler` | 已提交条目交给**独立的 apply 线程**，Ready 里不再带 `committedEntries` | 关 |

开启后的线程与流水线：

```
        ┌──────────────┐   复制消息(早发)   ┌──────────┐
        │ raft 状态机  │ ─────────────────► │  对端    │
        │   线程       │                    └──────────┘
        │ (唯一写者)   │   MSG_APP_RESP(等落盘) ─────────►
        └──────┬───────┘
               │ 一批已提交条目
               ▼
        ┌──────────────┐   应用完回 MSG_STORAGE_APPLY_RESP
        │  apply 线程  │ ─────────────────────────────────►
        └──────────────┘
               ▲
               │ 一次 write + 一次 fsync
        ┌──────────────┐
        │  落盘线程    │
        └──────────────┘
```

**落盘线程**：状态机线程把「硬状态 + 一批日志」交给它，它做一次 `write` + 一次 `force(true)`，
完成后回灌「落盘相关」的消息（自己投的那一票、自己日志的落盘确认）。
fsync 不再占住状态机线程 —— 提案在 fsync 期间照常攒批。

**apply 线程**：处理已提交条目，处理完发 `MSG_STORAGE_APPLY_RESP` 回状态机线程推进
`applied` 指针。**单向依赖**：apply 线程等状态机线程（成员变更要交给它），
状态机线程不等 apply 线程，因此不会死锁。

**「应用层看到的状态不能比日志更靠前」**：已提交条目分两批 —— 已经落盘的立刻交给
apply 线程；还在这一批日志里的要等落盘完成之后才交。

**消息投递顺序**（这是本轮吞吐提升的最大杠杆，见 §9.1）：

| 消息类型 | 何时发 | 为什么 |
|---|---|---|
| `MSG_APP`（复制）、`MSG_VOTE`（投票请求） | **落盘之前** | 不带任何持久化承诺。先发出去，对端可以立刻开始落盘，与本节点的落盘**重叠** —— 两个 fsync 不串行，端到端延迟少一个落盘周期 |
| `MSG_APP_RESP`（追加确认）、`MSG_VOTE_RESP`（投票确认） | **落盘之后** | 它们的含义是「我已经持久化了这些日志 / 这一票」。提前发出去，对端会以为这些日志已经安全 |

实现上，`Raft.send()` 把确认类消息放进 `msgsAfterAppend`（etcd 原本就有这个通道），
框架落盘模式下由 `RawNode` 摘出来挂在 `PersistTask` 上，落盘完成后再放进下一个 `Ready`。
这条做法与 dragonboat 的 free-order 消息机制同源（§9.1）。

---

## 4. 线协议

帧格式：`[4B length][1B type][payload]`，length = payload 字节数。**全部大端**。

payload 与 `io.edap.raft.protocol.Message` 的字段一一对应（扁平布局），
编解码在 `io.edap.raft.protocol.RaftCodec`。
`context` 字段的长度为 `-1` 表示「没有附加数据」、为 `0` 表示「附加数据是空的」，
两者区分开，编解码之后消息仍可按值比较（`Message.equals`）。

**日志条目的编码是「条件带 requestId」的**（§9.8 ②）：
`index(8) + term(8) + typeFlags(1) + dataLen(4) + [requestId(8)] + data`。
`typeFlags` 的最高位是「这条条目带 requestId」的标志位，只有 requestId ≠ 0 时
那 8 个字节才占位。

> 为什么不做成无条件多 8 字节：绝大多数条目没有对应的客户端请求（同步提案、
> 选举产生的空条目、成员变更）。每条都多背 8 字节会改变流控的分批边界 ——
> `maxSizePerMsg` 正好卡在「一条消息装两条 1000 字节载荷」的边界上，
> 多 8 字节就从装两条变成装一条，吞吐直接掉一半。
>
> **兼容性**：旧节点编出来的条目（typeFlags 最高位为 0）与新格式逐字节相同，
> 所以**旧节点 → 新节点**可以互通；**新节点 → 旧节点**在遇到带 requestId 的条目时
> 会解析失败 —— 滚动升级期间要**先升 follower 再升 leader**，
> 或者把升级窗口放在没有异步提案的时段。

**入站帧累积**：`DisruptorReadDispatcher` 每次读事件前 `buf.reset()`，**上一次没读完的字节会被丢弃**。
所以 `RaftServerSession` 自己维护 per-session 累积缓冲（`RaftFrameReader`），
`RaftDecoder` 只负责「从累积缓冲里切出完整帧」。

这套线格式同时是**基准里 `tcp` 传输模式**用的格式（§9.1）：
`TcpLoopbackTransport` 出站用 `RaftCodec.encode` 编码，
入站用同一个 `RaftFrameReader` 切帧 —— 也就是说基准里跑的就是真实的线协议，
不是「进程内直投对象」那种省掉序列化的特例。

---

## 5. 存储

`Storage` 是**只读 SPI**（`initialState` / `entries` / `term` / `firstIndex` / `lastIndex` / `snapshot`），
写侧由子接口 `PersistentStorage` 承载：

```java
public interface PersistentStorage extends Storage {
    /** 一次落盘：硬状态（可为 null）+ 一批日志条目。实现内部只做一次 force。 */
    void save(HardState st, List<Entry> entries);
    void append(List<Entry> entries);        // 兼容旧调用点
    void setHardState(HardState st);         // 兼容旧调用点
    void applySnapshot(Snapshot snap);
    void compact(long index);
    Snapshot createSnapshot(long index, ConfState cs, byte[] data);
}
```

`RaftConfig.storage` 的类型是 `PersistentStorage`，写侧全部收在 `save()` 这一个方法里。

两套实现：

- `MemoryStorage`：etcd 原版的内存实现，测试与临时节点用。
- `FileRaftStorage`：edap 自带的文件实现，`<dataDir>/` 下两个文件（全部大端序，
  `crc32` 覆盖它前面紧邻的所有字节）：

  | 文件 | 布局 |
  |---|---|
  | `wal.bin` | `magic("EDAPWAL")(7) + version(1)`，然后若干条记录：`length(4) + crc32(4) + payload`，`payload = type(1) + 记录体`；`type=1` 硬状态 `term(8) + vote(8) + commit(8)`，`type=2` 日志条目 `index(8) + term(8) + requestId(8) + entryType(1) + dataLen(4) + data` |
  | `snapshot.bin` | `magic(4) + version(1) + lastIncludedIndex(8) + lastIncludedTerm(8) + confStateLen(4) + confState + dataLen(4) + data + crc32(4)` |

  日志条目记录里那 8 个字节的 `requestId` 是 §9.8 ② 的异步提案用的：
  requestId 随条目复制到各个节点，任何节点都能在条目被提交时找到对应的调用方
  （`requestId = 0` 表示这条条目没有对应的客户端请求）。

  > ⚠️ **WAL 格式变了，不做老数据兼容**：条目记录的定长部分从 22 字节变成 30 字节
  > （`ENTRY_FIXED_LEN`），扫描时 `payloadLen < ENTRY_FIXED_LEN` 就判定记录非法。
  > **升级前写过的 `wal.bin` 读不出来** —— 升级要重建数据目录，或者在旧版本上
  > 先做一次「快照 + 截断」把日志清空。线格式（§4）反而是向后兼容的
  > （旧节点 → 新节点逐字节相同），WAL 不是。

  `confState` 的编码是四组 id 列表（`voters` / `learners` / `votersOutgoing` / `learnersNext`，
  每组 `count(4) + id(8)*n`）加一个 `autoLeave(1)` 标志。

  `wal.bin` 的**第一条条目记录是占位记录**（dummy）：只带 index 与 term、不带数据，
  作用与 `MemoryStorage` 里的 dummy entry 一致 —— 标记被压缩掉的区间的终点，
  使得 `term(index)` 仍能回答压缩点上的 term；真正的日志条目从它后面开始，index 连续递增。

**硬状态与日志在同一个文件里**，所以一次 Ready 只需要**一次 fsync**。
早期版本把硬状态放在 `meta.bin`、日志放在 `log.bin`，每次 Ready 要两次 fsync，
其中一次只写 24 字节 —— 实测那一次占掉了 27%~49% 的 fsync 预算（§9.1）。

**commit 不单独落盘**：硬状态记录只在 term 或 vote 变化时写，commit 搭车写进去
（写时 clamp 到 `lastIndex`），落盘的是它的**陈旧下界**。commit 单调递增，
恢复时 `loadState` 校验 `commit ∈ [committed, lastIndex]`，陈旧值必然落在区间内；
已提交的条目不会被合法 leader 截断，所以不存在「恢复时 commit > lastIndex」。
节点重启后 commit 由 leader 的 AppendEntries 重新建立（raft 论文 §5.4.2），
应用层重复应用由 `RaftConfig.applied` 兜底（与 etcd 同款）。
**term 与 vote 则必须在回复投票请求之前落盘** —— 这条不变式由
「落盘先于消息投递」保证（见 §9.1 的消息投递顺序）。

**冲突截断**可能把旧的硬状态记录一起截掉 → 用 `hardStateDirty` 标志，
截断后下一次写补一条硬状态记录。

`snapshot.bin` 整体重写，走「写 `.tmp` → `FileChannel.force(true)` →
`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`」，避免半写；`wal.bin` 追加写，
冲突截断时先截断再追加，每条记录带 CRC32。

恢复时逐条扫 `wal.bin`，**第一条校验不过的记录及其后全部丢弃**并把文件截断
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

   **新路径的专项测试**（etcd 移植套件不覆盖框架落盘与独立 apply 线程）：

   | 测试 | 断言的东西 |
   |---|---|
   | `NodeFrameworkPersistTest` | 框架落盘模式下 Ready 不带 entries；**消息一定在 `save()` 之后才投递**；确认类消息等落盘、复制类消息可以早发；快照先进存储 |
   | `NodeApplyHandlerTest` | apply 跑在独立线程（不是状态机线程）；条目按序应用；`applied` 指针跟着推进；成员变更在 apply 线程里生效；**应用时对应的日志必须已经落盘** |
   | `FileRaftStorageTest` | 新 WAL 格式的读写、尾部半写截断、冲突截断后 term/vote 不丢、commit 陈旧但合法 |
   | `InteractionStorage` | 测试用的存储实现 `PersistentStorage` 的 `save()` |
   | `NodeBackpressureTest` | 背压三态（BLOCK / REJECT / 丢弃）；`onMessage` 不阻塞 IO 线程；**节点停止时阻塞在满队列上的提案者被唤醒，不死锁**；**无界队列也真的把提案送进队列**（防「静默丢提案」那个 bug） |
   | `NodeAsyncProposalTest` | `proposeAsync` 的完成/失败路径：正常提交、被丢弃、节点停止、超时后摘表、**客户端向 follower 提案也能完成**、带 requestId 的条目编解码与 WAL 存取往返 |
   | `NioRaftTransportTest` | 生产传输层：两个节点互发消息、对端停掉后发送方不崩、`stop()` 后端口能重新绑定 |

   `io.edap.raft.benchmark` 下是独立跑的基准程序（不是 JUnit），命令见 §8.5。

   `io.edap.raft.ClusterProposalHarness` 也是独立跑的基准程序：3 节点集群 + 真实磁盘
   （每节点一份 `FileRaftStorage`），量的是「复制 + 真 fsync」下的端到端吞吐，
   用来和 dragonboat 做同硬件对比（§9.1）。它必须放在 `io.edap.raft` 包里 ——
   `RawNode(Raft)` 是包级私有的。

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
- **`RawNode` 只实现了同步落盘路径**（与 Go 的 `asyncStorageWrites=false` 等价），
  两份上游测试数据未移植。但 `Node` 层的**三阶段流水线**（框架落盘 + 独立 apply 线程，
  见 §3.2）已经实现并有专项测试覆盖 —— 缺的只是 `RawNode` 这一层的异步落盘 API
  与那两份数据。参考实现见 §9.3。
- **`edap-container` 没有把 `Edap` 注册为 bean**，容器场景下应用暂时拿不到宿主 `Edap`；
  `NioRaftTransport` 会自建一组 reactor 线程。这是 `edap-raft` 与容器之间的一个待补接缝。
- **`edap-nio-server` 的写路径在背压下有多个框架级缺陷**（`flush()` 里重新 register 会丢掉 OP_READ、
  `DisruptorReadDispatcher` 的 OP_WRITE 分支不清 OP_WRITE 兴趣）。raft 的响应体很小（几十字节），
  实际触发概率低；且 raft 的响应走的是出站 `SocketPeerClient`，不经过框架的写队列。
- **主循环曾经是「固定优先级轮询」，不等价于 etcd 的 `select`** —— 已修，见 §7.1。
- **出站队列满了会静默丢帧** —— **已修**（2026-10-29）。改之前
  `SocketPeerClient` 的 `outQueue` 是 `ArrayBlockingQueue<byte[]>(1024)`，
  `enqueue` 里 `offer` 失败只打一条 warn 就丢，
  raft 允许丢心跳，但**不能丢 `MsgApp`**（丢了会卡住复制进度），这里没有区分。
  现在改成「一条 FIFO 队列 + 互相独立的预算」：复制类按排队字节数限额、
  控制类按排队帧数限额，并且有计数器（`TransportStats`）。
  **这是潜在缺陷修复，不是性能修复** —— 实测在本机基准负载下修前/修后
  没有可测差异（出站队列从来没满过，两边丢帧都是 0），见 §9.1。
  **中间踩过的坑见 §9.1「生产传输层 `NioRaftTransport`：第一次实测」。**
- **`recvc` 保持 `LinkedBlockingQueue` 不动**：实测（`io.edap.raft.benchmark`，
  本机 macOS aarch64 / 10 CPU）五种方案里 LBQ 在「打满吞吐」和「尾延迟」上都最好，
  vmsmith 式两级 ring + Merger 最差。队列操作本身（offer+poll）只有 ~31ns，
  而瓶颈是 fsync（0.1–10ms），差 4–5 个数量级 —— 换队列没有收益。
  真正该优化的是上面的「主循环优先级」，不是队列。
- **publish 路径上最贵的不是队列**：实测（§8.4）`Node.enqueueProposal` 的三步里，
  队列 offer 只占 0.5μs，`synchronized(lifecycle)` 占 0.3μs，
  **唤醒状态机线程的 unpark 占 1.5μs**。换更快的队列最多省 0.3μs。
- **`Node.propose()` 是同步的** —— **已补异步路径**（2026-10-29）。
  `propose()` 保持原样（阻塞到提案被状态机 step 完，不等提交），
  新增 `proposeAsync()` 返回 `CompletableFuture<Void>`（完成点 = 提交）。
  §8.3 极限表的硬约束因此解除，见 §9.8 ②。

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

§8.1 最后两行是例外：那两行来自 `ClusterProposalHarness`
（`io.edap.raft` 包），是**3 节点集群 + 真实磁盘**，用来和 dragonboat 做同硬件对比
（§9.1）。

### 8.1 分层天花板

| 层 | 怎么测的 | 吞吐 |
|---|---|---|
| raft 逻辑 | `RaftCoreThroughputHarness`：单线程直驱 `RawNode`（`propose → ready → append → advance`），无队列、无线程切换 | 2.97M/s (batch=1) ~ **6.29M/s** (batch=64~4096) |
| Node 驱动链路（同步提案） | `AsyncProposalHarness` 的 `sync-commit` 模式：16 个客户端线程，`fsync=0` | **307K/s**（窗口 ≥ 4 就不涨了，被「等状态机 step 完」卡住） |
| Node 驱动链路（异步提案，在途窗口 1024） | `AsyncProposalHarness`：客户端不等状态机 step，等批量 commit | **2,605,338/s** |
| Node 驱动链路（异步提案，不等提交） | 同上，fire-and-forget（`async` 模式，窗口无限） | **3,254,766/s**（fsync=0）/ **3,713,997/s**（fsync=2ms，一次落盘覆盖 13,596 条） |
| 落盘 | 应用层 fsync | fsync 速率 × batch |
| **3 节点 + 真盘（真 fsync 落盘，在途窗口 1）** | `ClusterProposalHarness`：3 节点各一份 `FileRaftStorage`（真 fsync），16 客户端，16B | **842/s**（异步 + 背压） |
| **3 节点 + 真盘（在途窗口 256）** | 同上 | **119.8K/s** |
| **3 节点 + 真盘（在途窗口 1024）** | 同上 | **234.8K/s** |

> 这三行是**改造前**的数字（376/s / 28.0K/s / 55.5K/s）。
> 中间经过三步：③+④ 落地（§3.2 的三阶段流水线 + 复制消息早发）
> 变成 456/s / 104.8K/s / 192.7K/s；② 提案异步化 + ⑤ 背压落地后
> （2026-10-29）变成 **842/s / 119.8K/s / 234.8K/s** —— 详见 §8.3 与 §9.1。
> §9.1 又用「TCP 回环 + `RaftCodec` 序列化」的传输层重测了一遍，
> 数字是 805/s / 118.2K/s / 222.2K/s —— **传输层只值 0~5%**。

Node 层的阻塞路径比核心层差 20 倍，差的**全是线程交接**（每提案两次 park/unpark ≈ 6.4μs），
不是队列、不是 O(n²)。异步提案把「往返」变成「流水线」，这一层就松开了 ——
**2,605,338/s vs 核心层 6.29M/s，只差 2.4 倍**。

最后一档（3 节点 + 真盘）的天花板由**磁盘的 fsync 速率**决定，不是由提案速率决定：
改造前每次 Ready 两次 fsync（硬状态 `meta.bin` + 日志 `log.bin`），而本机 SSD 的 fsync
上限约 200~250 次/s —— 窗口 256 时日志只分到 112 次/s（其余被只写 24 字节的
meta 吃掉 68 次/s）。吞吐 = 落盘速率 × 每批条数。关掉 meta 写后吞吐上到
39.5K/s —— 详见 §9.1。

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

这一节的两列是**同一个 harness、同一批机器、同一段时间**里跑出来的：

- **阻塞路径** = `AsyncProposalHarness` 的 `sync-commit` 模式（`node.propose()`，
  客户端等状态机 step 完），campaign E；
- **异步路径** = 同一 harness 的 `async-commit` 模式（`node.proposeAsync()`，
  客户端不等状态机 step，等批量 commit 通知），campaign D。

「在途窗口」= 每客户端允许未确认的提案条数。两边都是 16 个客户端、
单节点集群、9 轮取中位数。

> ⚠️ **这一整节之前的两张表都是同一个阻塞路径测出来的** ——
> harness 里的 `fireAndForget = "async".equals(mode)` 与 `mode = "async-commit"`
> 永远不匹配，于是 fire-and-forget 分支一次都没进过（详见 §9.1 开头的更正说明）。
> 修好之后重测，本表才是真正的「阻塞 vs 异步」对照。

| 场景 | 阻塞路径（实测） | 异步路径（实测） | 倍数 |
|---|---|---|---|
| 纯内存 fsync=0，窗口=1 | 132,419/s | 177,826/s | 1.34× |
| 纯内存 fsync=0，窗口=4 | 297,329/s | 563,247/s | 1.89× |
| 纯内存 fsync=0，窗口=16 | 307,221/s | **1,457,476/s** | 4.74× |
| 纯内存 fsync=0，窗口=64 | 303,723/s | **2,135,033/s** | 7.03× |
| 纯内存 fsync=0，窗口=256 | 305,323/s | **1,993,642/s** | 6.53× |
| 纯内存 fsync=0，窗口=1024 | 306,610/s | **2,605,338/s** | **8.50×** |
| fsync=2ms，窗口=1 | 2,131/s | 2,035/s | 0.95× |
| fsync=2ms，窗口=4 | 7,043/s | 7,982/s | 1.13× |
| fsync=2ms，窗口=16 | 26,476/s | 29,966/s | 1.13× |
| fsync=2ms，窗口=64 | 109,598/s | 111,500/s | 1.02× |
| fsync=2ms，窗口=256 | 332,481/s | 406,342/s | 1.22× |
| fsync=2ms，窗口=1024 | 329,339/s | **1,436,212/s** | **4.36×** |
| **3 节点 + 真盘，窗口=1** | **449/s** | **842/s** | 1.87× |
| **3 节点 + 真盘，窗口=16** | **6,738/s** | **14,067/s** | 2.09× |
| **3 节点 + 真盘，窗口=256** | **103,370/s** | **119,832/s** | 1.16× |
| **3 节点 + 真盘，窗口=1024** | **193,685/s** | **234,796/s** | 1.21× |

前 12 行是 `AsyncProposalHarness`（单节点，进程内直投）；
最后 4 行是 `ClusterProposalHarness`（3 节点 + 真盘 + `framework+apply` 落盘），
9 轮取中位数，与 dragonboat 交替跑、同一时段内完成（§9.1）。

**三条结论**：

1. **窗口深度是异步路径的全部杠杆，窗口=1 时异步几乎没有收益**
   （fsync=2ms 时 0.95×，落在轮间波动里 —— 一次往返还是要等）。
   窗口放开后客户端不再等状态机，瓶颈从「往返延迟」挪到「流水线填充速度」。
2. **阻塞路径有一个硬天花板：~307K/s（纯内存）**，窗口放到 4 以上就完全不涨了。
   它被「每次 `propose()` 要等状态机 step 完」这个语义本身卡住
   （一次落盘只盖住 15 条），不是实现 inefficiency。
   异步路径同一台机器能到 **2,605,338/s** —— **8.50×**。
3. **fsync 场景的提升远小于纯内存**，因为瓶颈换成了盘：
   异步路径一次落盘覆盖的条数从 7.4 条涨到 5,629.9 条，
   2ms 的 fsync 被摊薄到 0.36μs/条；但 fsync/s 本身只有 ~250，
   落盘条目/s = 246 × 5,629.9 = 1,384,957 ≈ 实测 1,436,212 ✓。

**再往上还有一档**：完全不等提交（fire-and-forget，`async` 模式，窗口无限）
实测 **3,254,766/s**（fsync=0）/ **3,713,997/s**（fsync=2ms，一次落盘覆盖 13,596 条）。
raft 核心层（单线程直驱 `RawNode`）的天花板是 **6.29M/s** ——
所以 `Node` 这一层的队列 + 交接开销只占 **1.7~2.0 倍**，
而不是旧文档里说的 4 倍（旧数字同样是从有 bug 的 harness 出来的）。

**前提是提案 API 异步化。** 只要保持「客户端等状态机 step 完」这个语义，
307K/s 的天花板就动不了 —— 那 6.4μs 是这个语义本身要求的两次交接。
dragonboat 走的就是异步提案路线（§9.5），单 group 实测 125 万 writes/s。

四条优化的分工（各自的实测/外推依据见 §9.8）：

| 优化 | 解决什么 | 量级 |
|---|---|---|
| ① 一轮扫所有来源 | 饿死、批量合不拢 | **已实测**：fsync 场景 **+22.5%**（3,659 → 4,481/s，9 轮中位数），纯内存 **+1.8%**（持平） |
| ② 提案异步化 | 每提案 6.4μs 的线程往返 | **已实测**（本节）：纯内存 **8.50×**、fsync=2ms **4.36×**（窗口 1024）；3 节点真盘 1.16~2.09× |
| ③ 落盘挪进框架 + 三阶段分离 | fsync 与状态机推进串行 | **已实测**（3 节点真盘）：窗口 1/16/256/1024 分别是 **1.21× / 2.37× / 3.75× / 3.47×** |
| ④ 复制消息早发、确认消息等落盘 | 两个 fsync 串行 | **已实测**：窗口 256 是 **+59%**（66,054 → 104,845/s），窗口 1024 是 **+13%**（170,988 → 192,650/s） |

**③ 的三个子项各自贡献多少**（3 节点真盘，窗口 256 / 1024）：

| 子项 | 窗口 256 | 窗口 1024 |
|---|---|---|
| 起点（meta + log 双 fsync） | 27,975/s | 55,531/s |
| ③a 硬状态与日志合并成一次落盘（无 meta 写） | 53,014/s | 110,207/s |
| ③b+c 框架落盘 + apply 独立线程 + 提案合批 | 66,054/s | 170,988/s |
| ④ 复制消息早发、确认消息等落盘 | **104,845/s** | **192,650/s** |

> 前三行是 2026-10-28 那几轮 campaign 测的（每步落地后单独跑 9 轮），
> ④ 行是 2026-10-29 的 9 轮（与 §9.1 的逐轮原始数据同一批）。
> 跨 campaign 比较有 run-to-run 噪声（窗口 1024 尤其明显：dragonboat 两次测出
> 137,100 与 147,881，差 8%），所以这里的 +59% / +13% 是量级结论，不是精确值。

**④ 是单项收益最大的一个**：它把「本节点 fsync」与「对端 fsync」从串行变成并行 ——
复制消息先发出去，对端立刻开始落盘，本节点同时落自己的盘，两个 fsync 重叠而不是排队。

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

`Node.enqueueProposal` 做三件事：`synchronized(lifecycle)`、
`propc.offer(pm)`、`wakeup.release()`。`PublishPathCostHarness` 把它们拆开量
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
| `propc`（客户端提案） | 应用线程 | 会，但阻塞在等状态机 step（异步路径不阻塞） | 1 客户端纯内存时占 30%（3.4μs / 7.4μs），有 fsync 时占 0.8%（40μs / 5136μs） |
| `recvc`（网络消息） | 1 个 IO 线程（`RaftServer` `setIoThreadCount(1)`）+ 应用线程 | 不会，`offer` 立即返回 | 1000 msg/s × 2.3μs = IO 线程占用 0.23% |
| 出站队列（`SocketPeerClient`） | raft 线程 | **不阻塞 —— 队列满就按预算丢帧** | 复制类（`MSG_APP`）按 **64MB 字节预算**、控制类按 **1024 帧预算**，两条预算分开算；实测本机负载下出站队列**从未满过**（丢帧计数恒为 0），所以「复制停滞一个心跳周期」是**推演出的隐患、不是实测现象**（§9.1） |

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
# 3 节点真盘：   java ... ClusterProposalHarness \
#                    <节点数> <客户端数> <秒> <sync|sync-commit|async|async-commit> \
#                    [每客户端在途上限] [tick毫秒] [数据目录] [落盘模式] [传输方式] \
#                    [提案队列容量] [队列满策略]
#   落盘模式：app（默认，应用层落盘）/ no-meta / app+apply /
#             framework（框架落盘）/ framework+apply（框架落盘 + 独立 apply 线程）
#   传输方式：direct（默认，进程内直投）/ tcp（TCP 回环 + RaftCodec 序列化）/
#             nio（生产传输层：入站 edap-nio / 出站阻塞 Socket）
#   提案队列容量：0 = 不限制（背压关），默认 2048
#   队列满策略：block（默认，同步路径等位置）/ reject（立刻抛 RaftBusyException）
#
# NIO 传输模式必须加 --add-opens：
#   --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
#   --add-opens java.base/sun.nio.ch=ALL-UNNAMED
#   （FastSelectorProvider 反射 sun.nio.ch，JDK17 不加会 InaccessibleObjectException；
#     不加 java.nio 会 UnsafeUtil: BUFFER_ADDRESS_OFFSET unresolved）
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

**这组 README 数字的测试环境是 3 台物理服务器**（22 核 Xeon E5-2696v4、40GE 网卡、
Intel 900P/P3700 NVMe，见 dragonboat 的 `docs/test.md`），不是一台笔记本。
所以下面的对比全部改成本机实测：**同一台机器、同一份磁盘、同一套载荷，
edap-raft 和 dragonboat 在同一时段内各跑一遍**。

#### 本机同硬件对比：3 节点 + 真盘 + 16B 载荷

> ⚠️ **2026-10-29 更正：下面第一张表的数字跑的是阻塞路径，异步分支从未执行过。**
>
> `ClusterProposalHarness.java` 里有一行
> `boolean fireAndForget = "async".equals(mode);`，
> 而 `mode` 的实际取值是 `"async-commit"` —— **恒为 false**。
> 所以那一轮报告的所有数字跑的都是**阻塞 `node.propose()` 路径**，
> fire-and-forget 分支一次都没进过。
> 证据：那一轮的日志里有 `propose 往返 p50=0.052ms`，
> 这个指标只在阻塞分支里才会记录。
>
> 连带的两处文档错误：
> - §8.3 说「该 harness 用 `async-commit` 模式，发布侧本来就走 fire-and-forget
>   （`node.onMessage(MSG_PROP)`）」—— **这是错的**；
> - §9.8 ② 说「② 的收益已经包含在里面」—— **也是错的**，那一轮根本没测到 ②。
>
> 修法：`fireAndForget = mode.startsWith("async")`，并且异步路径改走新的
> `proposeAsync()`（不是 `onMessage`）—— 只有走 `proposeAsync` 才能统计拒绝。
> **本轮（2026-10-29）所有数字都按修好之后的 harness 重测**，
> 下面标「**（2026-10-29 重测）**」的表是新的，旧表保留作为历史记录。

**两边完全一致的条件**：

| 项 | 值 |
|---|---|
| 硬件 | macOS aarch64 / 10 CPU / 64GB / APFS 内置 SSD（系统盘） |
| 集群 | 单进程内 3 节点、3 副本 |
| 客户端 | 16 个客户端线程，各自维护一个「在途窗口」 |
| 载荷 | 16 字节 |
| 时长 | 每轮 10s，每个配置跑 **9 轮取中位数**（三方交替跑、同一时段内完成，轮次奇偶调换顺序） |
| 语义 | 客户端发出提案后**等到自己的提案被提交并应用**才算完成 |

edap-raft 侧用 `ClusterProposalHarness`
（`edap-raft-parent/edap-raft/src/test/java/io/edap/raft/ClusterProposalHarness.java`）：
每个节点一份 `FileRaftStorage`（单文件 WAL `wal.bin`，整批一次 write + 一次 `force(true)`），
落盘模式用 `framework+apply`（框架接管落盘 + 独立 apply 线程，见 §3.2）。

**传输层跑了两个变体** —— 目的就是把「进程内直投、不序列化」这一项便宜消掉，
让两边的传输条件真正对等：

| 变体 | 传输 | 用途 |
|---|---|---|
| `direct` | 进程内直投消息对象，**不序列化、不走网络** | edap-raft 自己的上限 |
| `tcp` | **TCP 回环 + `RaftCodec` 序列化** | 与 dragonboat 的传输条件对等 |

`tcp` 变体的实现是 `src/test/java/io/edap/raft/TcpLoopbackTransport.java`，
出站模型照抄 dragonboat 的 `internal/transport/transport.go`：每个对端一条 TCP 长连接
（`TCP_NODELAY`，dragonboat 那边 Go 的 `net.Dial` 默认也是开的）+ 一个写线程；
写线程从队列里取到第一条消息后，把队列里现有的消息全部拼成一批一次 `write`
（批次上限 64MB，与 dragonboat 的 `MaxMessageBatchSize` 一致）。
线格式用 edap-raft 自己的 `RaftCodec`（`[4B length][1B type][payload]`，一条消息一帧）。

dragonboat 侧用自写基准（`/tmp/dragonboat/dbbenchdef/main.go`，master @ `076c7f6`）：
3 个 `NodeHost`，raft 日志经 LogDB（Pebble，`WriteOptions{Sync:true}`）落盘，
消息走 TCP loopback + protobuf 序列化。**用 dragonboat 的默认 LogDB 配置**
（128MB write buffer）—— 换成 4MB 的 `GetTinyMemLogDBConfig()` 会让 dragonboat
在窗口 256 时慢 23%（90.1K/s → 73.1K/s），所以默认配置是对 dragonboat 有利的一侧。

**结果（2026-10-29 重测）** —— 提交速率 = 客户端观察到自己的提案被提交的速率；
9 轮取中位数；edap 与 dragonboat 交替跑、同一时段内完成，轮次奇偶调换变体顺序。

四个 edap 变体：

| 变体 | 提案 API | 背压 | 传输 |
|---|---|---|---|
| `direct-sync` | 阻塞 `node.propose()` | 有界队列，同步路径阻塞等位置 | 进程内直投 |
| `direct-async` | `node.proposeAsync()` | **有界**（2048），队列满 → `RaftBusyException` | 进程内直投 |
| `direct-async-nobp` | `node.proposeAsync()` | **无界**（背压关掉） | 进程内直投 |
| `tcp-async` | `node.proposeAsync()` | **有界**（2048） | TCP 回环 + `RaftCodec` |

**提交/s（9 轮中位数，括号里是原始区间）**：

| 在途窗口（每客户端） | direct-sync | direct-async | direct-async-nobp | tcp-async | dragonboat | **tcp/dragonboat** |
|---|---|---|---|---|---|---|
| 1 | 449 (409~471) | **842** (631~969) | 811 (716~971) | 805 (564~1,002) | 352 (320~393) | **2.29×** |
| 16 | 6,738 (6,309~7,146) | **14,067** (8,826~20,804) | 15,649 (9,919~20,300) | 13,769 (9,638~20,812) | 5,862 (5,250~5,941) | **2.35×** |
| 256 | 103,370 (101,847~106,636) | 119,832 (114,366~130,215) | **141,754** (104,088~152,814) | 118,186 (114,481~131,653) | 88,320 (85,069~94,587) | **1.34×** |
| 1024 | 193,685 (186,958~203,609) | **234,796** (223,012~246,777) | 173,625 (167,844~185,550) | 222,166 (213,638~239,434) | 152,696 (140,345~167,810) | **1.45×** |

**端到端延迟（发布→应用）与提案拒绝率**：

| 在途窗口 | direct-sync p50/p99 | direct-async p50/p99 | direct-async-nobp p50/p99 | tcp-async p50/p99 | dragonboat p50/p99 | direct-async 拒绝率 | dragonboat 拒绝率 |
|---|---|---|---|---|---|---|---|
| 1 | 36.9 / 48.3 ms | **14.6** / 43.4 ms | 15.2 / 42.0 ms | 15.2 / 42.5 ms | 41.5 / 74.8 ms | 0% | 0% |
| 16 | 34.0 / 44.2 ms | **13.8** / 45.9 ms | 13.2 / 45.7 ms | 14.4 / 44.8 ms | 41.4 / 75.6 ms | 0% | 0% |
| 256 | 33.9 / 43.2 ms | 35.6 / 58.5 ms | **33.6** / 58.3 ms | 36.1 / 55.6 ms | 41.6 / 86.8 ms | 1.16% | 0% |
| 1024 | 40.0 / 61.5 ms | **58.0** / 93.7 ms | 95.0 / 126.8 ms | 62.4 / 94.3 ms | 49.1 / 101.5 ms | 36.15% | 42.6% |

**fsync 账单（9 轮中位数，3 节点合计）**：

| 窗口 | direct-sync | direct-async | direct-async-nobp | tcp-async | dragonboat |
|---|---|---|---|---|---|
| 1 | 234/s · 5.5 条 | 249/s · 10.0 条 | 260/s · 10.2 条 | 259/s · 9.8 条 | 251/s · 4.4 条 |
| 16 | 230/s · 87.9 条 | 241/s · 177.9 条 | 247/s · 185.6 条 | 251/s · 160.7 条 | 245/s · 70.5 条 |
| 256 | 229/s · 1,356.9 条 | 183/s · 2,012.4 条 | 117/s · 3,547.1 条 | 178/s · 2,012.8 条 | 231/s · 1,188.7 条 |
| 1024 | 194/s · 2,974.6 条 | 145/s · 4,848.7 条 | 72/s · 7,797.9 条 | 145/s · 4,608.4 条 | 212/s · 2,161.6 条 |

**模型自查**（`落盘条目/s = fsync/s × 条/次落盘`，`提交/s = 落盘条目/s ÷ 3`）——
`direct-async` 窗口 1024：145/s × 4,848.7 条 ÷ 3 = 234,354 ≈ 实测 234,796 ✓；
dragonboat 窗口 1024：212/s × 2,161.6 条 ÷ 3 = 152,753 ≈ 实测 152,696 ✓。

> 注意 `direct-async-nobp`（背压关）那一列：窗口 1024 时 fsync/s 只有 72，
> 是有界队列那一列（145/s）的一半 —— 无界队列让提案在队列里堆成一坨，
> 一次 Ready 要处理的条目更多，单次落盘更大、次数更少，**落盘本身变成了瓶颈**
> （72/s × 7,797.9 条 ÷ 3 = 187,150 ≈ 实测 173,625 ✓）。

**四个结论**：

1. **异步提案 API 是这一轮最大的单项收益**：窗口 1 时 `direct-async` 是 `direct-sync` 的
   **1.87×**（842/s vs 449/s），p50 从 36.9ms 降到 14.6ms（**-60%**）；
   窗口 16 时是 **2.09×**。窗口放到 256 以上后瓶颈变成盘，倍数收敛到 1.16~1.21×。
   上一版报告的「1.25~1.32×」是阻塞路径测的，**异步 API 之后窗口 1/16 上的领先是 2.3×**。
2. **背压在窗口 1024 上同时提升吞吐与延迟**：有界队列（`direct-async`）比无界队列
   （`direct-async-nobp`）**吞吐高 35%**（234,796 vs 173,625）、**p50 低 39%**
   （58.0ms vs 95.0ms）。无界队列让提案在队列里堆到几千条，
   每一条都要多等好几个提交批次才轮到 —— 这正是上一版 edap p50 64ms 的成因。
3. **拒绝口径与 dragonboat 同量级**：窗口 1024 时 edap 拒 36.15%、dragonboat 拒 42.6%
   （都是「提案根本没进 raft 日志」），窗口 256 以下两边都是 0%。
4. **「进程内直投」这一项便宜依然很小**：`direct-async` → `tcp-async`
   窗口 16 掉 2.1%、窗口 1024 掉 5.4%，往返延迟只多 0.2~4.4ms。
   **瓶颈一直在盘上，不在传输上。**

> **历史数据（阻塞路径，2026-10-29 上午那轮，保留作为记录）** ——
> 那一轮 harness 的 `fireAndForget` 恒为 false，跑的全是阻塞 `node.propose()`：

| 在途窗口（每客户端） | edap `direct` | edap `tcp` | dragonboat | tcp/direct | **tcp/dragonboat** | edap-tcp p50/p99 | dragonboat p50/p99 |
|---|---|---|---|---|---|---|---|
| 1 | 439/s | 424/s | 321/s | 0.97× | **1.32×** | 38 / 58 ms | 47 / 89 ms |
| 16 | 6,538/s | 6,541/s | 5,075/s | 1.00× | **1.29×** | 37 / 57 ms | 45 / 92 ms |
| 256 | 106,575/s | 105,939/s | 85,069/s | 0.99× | **1.25×** | 36 / 48 ms | 46 / 89 ms |
| 1024 | 199,549/s | 196,692/s | 156,349/s | 0.99× | **1.26×** | 64 / 105 ms | 47 / 102 ms |

原始区间（9 轮）：edap-direct 420~474 / 6,179~7,120 / 102,234~106,982 / 198,245~202,331；
edap-tcp 408~462 / 5,728~7,274 / 103,044~106,600 / 191,306~198,857；
dragonboat 307~372 / 4,978~5,411 / 81,649~87,873 / 150,548~168,094。
**每一轮的原始数字见本节的「逐轮原始数据」小节。**

**改造前的同一张表**（重构前是 `meta.bin` + `log.bin` 双 fsync，
消息一律等落盘后才发；2026-10-28 早期 campaign，5 轮中位数，
edap 与 dragonboat 在同一时段内交替测量）：

| 在途窗口（每客户端） | edap-raft（改造前） | dragonboat | 倍数 |
|---|---|---|---|
| 1 | 376/s | 368/s | 0.98×（打平） |
| 16 | 3,023/s | 5,944/s | 0.85× |
| 256 | 27,975/s | 90,139/s | 0.31× |
| 1024 | 55,531/s | 137,100/s | 0.41× |

> 这张表用的是**旧 campaign 的 dragonboat 数** —— 那一轮 dragonboat 与改造前的
> edap 同时段测，对比才公平。dragonboat 后来重测过两轮，绝对值有 6%~13% 的
> run-to-run 波动（窗口 1/16/256 偏低，窗口 1024 偏高），但**倍数是稳的**：
> 1.16~1.30×（10-29 上午，两方各跑一次）→ 1.25~1.37×（`direct`）/
> 1.25~1.32×（`tcp`）（10-29 下午，三方同批，阻塞路径）→
> **2.29× / 2.35× / 1.34× / 1.45×**（10-29 下午晚，异步路径，窗口 1/16/256/1024）。
> 波动来自「窗口 1024 时提案队列打满、随机拒提案」的固有噪声，dragonboat 本身没动。

**历史数据（阻塞路径）的三个结论** —— 已被上面「四个结论」取代，保留作为记录：

1. **edap-raft 走 TCP 回环 + `RaftCodec` 序列化之后，在所有窗口上领先
   dragonboat 1.25~1.32×**。改造前是落后 2.0~3.2×（窗口放开后），现在反过来了。
2. **「进程内直投」这一项便宜几乎不存在**：direct → tcp 只掉 0~3.4%
   （窗口 16 反而持平，窗口 1 的 3.4% 落在轮间波动里）。往返延迟只多 0.1~2.3ms
   —— 一次 fsync 要 11ms，序列化 + 一次回环往返被完全盖住。
   **瓶颈一直在盘上，不在传输上。**
3. **延迟上 edap 仍然全面更低**（窗口 1~256：p50 36~38ms vs 45~47ms，
   p99 48~58ms vs 89~92ms）。只有窗口 1024 时 dragonboat 的 p50 略低
   （47ms vs 64ms）—— 那是**背压**的功劳：dragonboat 拒掉约 42% 的提案，
   被拒的活根本没进 raft 日志，队列就不会被撑满。
   **这一条现在有解了**：edap 加了背压之后，窗口 1024 的 p50 从 95.0ms（无界队列）
   降到 58.0ms（有界队列），但**仍然略高于 dragonboat 的 49.1ms** —— 见 §9.8。

**结构性差异**（改造后）：

| 机制 | edap-raft | dragonboat | 依据 |
|---|---|---|---|
| 每次 Ready 的 fsync | **1 次**：硬状态与日志在同一个 `wal.bin` 里，整批一次 write + 一次 force | **1 次**：硬状态和日志在**同一个 Pebble write batch** 里（`Sync:true`） | 代码：`internal/logdb/db.go:saveRaftState` 一次 `CommitWriteBatch` |
| 落盘的位置 | **框架的落盘线程**（`frameworkPersist` 开关） | engine 的 step worker（同步 `saveRaftState`） | §3.2 |
| 提交 / 应用通知 | **独立 apply 线程** | `commitWorker` / `applyWorker` 是**独立 worker 池** | 代码：`engine.go:commitWorkerMain` / `applyWorkerMain` |
| **复制消息的投递时机** | **落盘之前发**（`MSG_APP`），确认类消息落盘之后发 | **落盘之前发**（`pb.Replicate` / `pb.Ping` 是 free-order 消息） | 代码：dragonboat `node.go:989` `isFreeOrderMessage`；`engine.go:1330-1345` |
| 背压 | **有**（2026-10-29 实现）：`propc` 有界（默认 2048），队列满 → 同步路径 BLOCK/REJECT、异步路径 `RaftBusyException`、传输层路径丢弃计数 | 提案队列满 → `ErrSystemBusy`（窗口 1/16/256 时 0%，窗口 1024 时 42.6% 的提案尝试被拒） | 实测 |

#### 传输层对等对比：edap-raft 也走 TCP 回环 + `RaftCodec`

上一版结论里挂着一个「edap 进程内直投消息对象、不序列化、不走网络，dragonboat 走
TCP + protobuf」的条件差 —— 这一节把 edap-raft 也搬到 TCP 回环上重测，
**把这个便宜消掉**。

**做法**：harness 加一个 `tcp` 传输模式（`ClusterProposalHarness` 的第 9 个参数），
传输层是 `src/test/java/io/edap/raft/TcpLoopbackTransport.java`：

| 维度 | edap-raft `tcp` | dragonboat | 对齐方式 |
|---|---|---|---|
| 承载 | TCP 回环 | TCP 回环 | 同一台机器、127.0.0.1 |
| 序列化 | `RaftCodec`（自写扁平二进制） | protobuf | 都是「一条消息一帧」的变长编码 |
| 连接模型 | 每对端一条长连接，连接上跑写线程 | 每对端一条长连接，连接上跑 goroutine | 一致 |
| 批量 | 写线程把队列里现有的消息拼成一批一次 `write`（上限 64MB） | `pb.MessageBatch`，上限 `MaxMessageBatchSize` = 64MB | 一致 |
| `TCP_NODELAY` | 开 | 开（Go `net.Dial` 默认） | 一致 |
| 提案路径 | 进程内（客户端 → leader） | 进程内（`nodehost.Propose`） | 一致 |

**结果（9 轮中位数，提交/s）**：

| 在途窗口 | edap `direct-async` | edap `tcp-async` | 传输层税 | dragonboat | edap-tcp / dragonboat |
|---|---|---|---|---|---|
| 1 | 842 | 805 | −4.4% | 352 | **2.29×** |
| 16 | 14,067 | 13,769 | −2.1% | 5,862 | **2.35×** |
| 256 | 119,832 | 118,186 | −1.4% | 88,320 | **1.34×** |
| 1024 | 234,796 | 222,166 | −5.4% | 152,696 | **1.45×** |

**传输层税几乎不存在**，可以理解：

1. **往返延迟只多 0.5~4.4ms**（p50：14.62→15.19 / 13.75→14.37 / 35.59→36.13 /
   58.00→62.35 ms）。一次 fsync 要 9~12ms，序列化 + 一次回环往返被完全盖住。
2. **fsync 预算基本不变**：`direct-async` 与 `tcp-async` 的 fsync/s 分别是
   249/259、241/251、183/178、145/145 —— 差在 4% 以内，
   「条/次落盘」也只差 0~3%。说明传输没有把流水线填不满的瓶颈变成瓶颈。
3. **网络本身的量级很小**：窗口 1024 时 `tcp-async` 发出 60,636 帧 / 169 MB（8 秒），
   约 7,600 帧/s、21 MB/s。回环带宽不是瓶颈。

**所以「edap 靠不序列化、不走网络才赢的」这个说法不成立** ——
去掉之后 edap-raft 依然领先 1.34~2.35×。差距在**落盘时机**（复制早发）
与**流水线填充**（提案合批 + 独立 apply 阶段），不在传输层。

**上一版的代价在哪里**：当时 edap 没有背压。窗口 1024 时 dragonboat 拒掉约 40% 的提案
（被拒的活根本没进 raft 日志），edap 全部接了，于是队列被撑满、
端到端 p50 被拉到 64ms（dragonboat 47ms）。**这一项 2026-10-29 已经补上**
（有界提案队列 + 三路径三语义），窗口 1024 的 p50 从 95.0ms（无界队列）
降到 58.0ms（有界队列），见上面的「四个结论」。

#### 生产传输层 `NioRaftTransport`：第一次实测

**它此前从来没有被跑过** —— 全仓库只有类定义与文档提及，没有测试、没有 example。
`NioRaftTransport` 与基准用的 `TcpLoopbackTransport` 有实质差异：

| | `TcpLoopbackTransport`（基准用） | `NioRaftTransport`（生产用） |
|---|---|---|
| 入站 | 进程内直投 | **edap-nio**（Selector + Disruptor，IO 线程 1 个） |
| 出站 | 批量写（一次 `write` 发一批帧） | **一条消息一次 `write` + `flush`**（修之前） |
| 出站队列 | 无界 | **有界 1024，队满丢任何消息**（修之前） |
| 丢帧 | — | **只打一行 `log.warn`，没有计数**（修之前） |

本轮先把它接进 `ClusterProposalHarness`（`nio` 传输模式，三个节点共享一个 `Edap`
实例，端口 `9300+i`），对着代码读出三处**潜在缺陷**（不是实测到的性能问题，
实测见下）：

1. **出站批量写** —— 照抄 `TcpLoopbackTransport`：写线程把队列里现有的帧拼成一批
   一次 `write`（上限 64MB，与 dragonboat 的 `MaxMessageBatchSize` 一致）。
   一条一条写的话每次 `write` 都要一次系统调用。
2. **分级丢弃** —— 丢一条 `MSG_APP` ⇒ follower 收不到这批 ⇒ leader 要等
   **下一个心跳**（基准 tick = 50ms）才发现落后 ⇒ 复制停滞一整个心跳周期。
   改成「复制类（`MSG_APP` / `MSG_APP_RESP` / `MSG_SNAP`）按排队字节数限额，
   控制类（心跳、投票）按排队帧数限额」。
3. **丢帧计数** —— 新增 `TransportStats` 接口（4 个计数器），
   `TcpLoopbackTransport` 与 `NioRaftTransport` 都实现它，基准不依赖测试专用类。

> ⚠️ **第一版实现是「两条队列 + 复制类优先」，实测比修前更慢** ——
> 吞吐掉到 60%、延迟涨到 1.7 倍。根因是**心跳被饿死**：
> leader 一直在复制时复制队列永不为空，控制队列（心跳、投票）永远发不出去，
> follower 收不到心跳就超时 campaigning，集群被选举风暴反复打断。
>
> **最终实现是「一条 FIFO 队列 + 互相独立的预算」**：
> 复制类与控制类共用一条队列（没有饿死问题），
> 「复制类不受控制类挤掉」这个目标由**独立预算**保证 ——
> 复制类只看自己排了多少字节（64MB），控制类只看自己排了多少帧（1024）。
> 判据不能是「整个队列的长度」：复制类堆积也会把队列撑长，
> 那时把心跳丢掉反而会引发选举风暴。

**实测结果**（campaign B / C，两个变体交替跑、同一时段内完成，9 轮取中位数；
B = git HEAD 的 `SocketPeerClient` ＋计数器，C = 修后）：

| 在途窗口 | B `tcp-async` | B `nio-async` | C `tcp-async` | C `nio-async` | 修前/修后（nio） |
|---|---|---|---|---|---|
| 16 | 12,231/s | 13,389/s | 16,308/s | 13,720/s | 1.02× |
| 256 | 116,717/s | 114,910/s | 114,785/s | 113,243/s | 0.99× |
| 1024 | 221,473/s | 222,371/s | 219,289/s | **223,093/s** | 1.00× |

端到端 p50（ms）：窗口 16 是 15.74 → 14.31，窗口 256 是 38.49 → 38.83，
窗口 1024 是 61.44 → 62.26。**两边的丢帧计数都是 0。**

**两个结论，第一个比预期好，第二个比预期差**：

1. **生产传输层不是瓶颈** —— `edap-nio-async`（edap-nio 入站 + 阻塞 Socket 出站）
   与基准专用的 `edap-tcp-async`（进程内 TCP 回环 + `RaftCodec`）**基本持平**：
   窗口 1024 是 223,093/s vs 219,289/s（同一批 campaign C），p50 62.3ms vs 62.9ms。
   「IO 线程只有 1 个」「每读事件一次 `byte[]` 拷贝」「出站是阻塞 Socket」
   这三条差异，**在 fsync 瓶颈下完全被盖住** —— 这和 §9.1 里
   「进程内直投 vs TCP 回环只差 0~5%」是同一个结论的两次独立验证。
2. **那三项修复在本负载下测不出性能差异** —— 修前（B）与修后（C）的差别是
   0.99×~1.02×，落在轮间波动里（窗口 16 的 1.33× 也是噪声：
   B 的区间 8,825~18,670 与 C 的 12,341~22,345 大幅重叠）。
   原因是**出站队列（1024 帧）从来没满过**：窗口 1024 时出站帧率只有
   约 6,000 帧/s，一次一帧的 `write` 也跟得上。

   所以「丢一条 `MSG_APP` ⇒ 复制停滞一个心跳周期（50ms）」是**推演出来的隐患，
   不是实测到的现象** —— 它要等到出站帧率高一个数量级才会发生。
   这三项修复的价值是**消除潜在缺陷**（负载再高时不会丢 `MSG_APP`）
   ＋**现在有计数器可观测**，**不是**性能修复。文档里不应该把它写成性能收益。

#### 改造前的 fsync 预算与批处理（归因过程，保留作为记录）

（3 节点合计；dragonboat 侧的落盘次数是给
`internal/logdb` 的 `saveRaftState` 加计数器测出来的，
每次 `CommitWriteBatch` = 一次 `pebble.Apply(wb, &pebble.WriteOptions{Sync:true})` = 一次 fsync）：

| 窗口 256 | edap-raft | edap-raft（`no-meta`） | dragonboat |
|---|---|---|---|
| fsync/s | **180**（日志 112 + 硬状态 68） | **160** | **232** |
| 其中只写 24 字节的 fsync | **68/s（38%）** | 0 | 0 |
| 条/次落盘 | 781 | 745 | 1,170 |
| **落盘条目/s** | **87.5K** | **119K** | **271K** |
| 提交/s | 27,975 | 39,479 | 90,139 |

| 窗口 1024 | edap-raft | edap-raft（`no-meta`） | dragonboat |
|---|---|---|---|
| fsync/s | 109（日志 80 + 硬状态 29） | 92 | 202 |
| 其中只写 24 字节的 fsync | 29/s（27%） | 0 | 0 |
| 条/次落盘 | 2,140 | 2,039 | 2,058 |
| 落盘条目/s | 171K | 188K | 416K |
| 提交/s | 55,531 | 61,598 | 137,100 |

**edap-raft 的 fsync 预算被硬状态吃掉了多少**（5 轮中位数，3 节点合计）：

| 在途窗口 | 日志 fsync/s | 硬状态 fsync/s | 合计 fsync/s | **meta 占比** | 条/次落盘 |
|---|---|---|---|---|---|
| 1 | 118 | 115 | 233 | **49%** | 10 |
| 16 | 118 | 107 | 225 | **48%** | 78 |
| 64 | 115 | 94 | 209 | **45%** | 270 |
| 256 | 112 | 68 | 180 | **38%** | 781 |
| 1024 | 80 | 29 | 109 | **27%** | 2,140 |

窗口越小、每批条数越少，硬状态那次「只写 24 字节」的 fsync 占的预算就越多 ——
窗口 1~16 时**近一半的 fsync 都在写 24 字节**。

**结论：两边都被同一块盘限制，但 edap-raft 把预算浪费掉了。**
dragonboat 的落盘周期（每节点）稳定在 **12~15ms**，与窗口无关 ——
一个周期就是一次 Pebble Apply；edap-raft 的 Ready 周期是 **25~38ms**
（`no-meta` 变体 13~33ms）—— 因为一个应用线程要在一个周期里串行做完
「落盘 + `advance()` + apply + 转发消息」。
edap-raft 把 **27%~49% 的 fsync 预算花在一次只写 24 字节的 `meta.bin` 上**
（窗口 1024 时 27%，窗口 1~16 时 49%）。

这笔账算得完全对得上：四个配置的「落盘条目/s」之比和「提交/s」之比一致
（窗口 256：271K/87.5K = 3.10× vs 90,139/27,975 = 3.22×；
窗口 1024：416K/171K = 2.43× vs 137,100/55,531 = 2.47×；
关掉 meta 写后 窗口 256：271K/119K = 2.28× vs 90,139/39,479 = 2.28×，
窗口 1024：416K/188K = 2.21× vs 137,100/61,598 = 2.23×）。
**提交速率 = 落盘条目速率 ÷ 3（三个副本各存一份）**，两边都成立。

**验证（① 关掉 meta 写）**：harness 的 `no-meta` 开关省下的 fsync 预算全部还给日志，
吞吐 376 → 593/s（w=1）、3,023 → 7,391/s（w=16）、10,079 → 19,226/s（w=64）、
27,975 → 39,479/s（w=256）、55,531 → 61,598/s（w=1024），**1.1~2.4×**。
**关掉 meta 写之后，窗口 1 和 16 时 edap-raft 反超 dragonboat**
（593 vs 368、7,391 vs 5,944）—— 也就是说**双 fsync 这一项，
就是 edap-raft 在中低窗口落后的全部原因**。

**剩下的差距（窗口 256/1024）全部是 Ready 周期**。
稳态下 `提交/s = 条/次落盘 ÷ Ready 周期`（每节点），两边都精确成立
（edap w=256：745 ÷ 18.8ms = 39.7K ✓；dragonboat：1,170 ÷ 12.9ms = 90.5K ✓）。
而 `fsync/s = 3 ÷ Ready 周期`、`条/次落盘 = 速率 × 周期` —— 前者是后者的推论，
所以「fsync 速率 × 条/次落盘」这个分解是**恒等式，不是原因**，真正的因变量只有一个：

| 窗口 | Ready 周期（每节点） | 倍数 |
|---|---|---|
| 256 | edap 18.8ms vs dragonboat 12.9ms | 1.46× |
| 1024 | edap 32.6ms vs dragonboat 14.9ms | **2.19×** |

edap 的周期拆开（实测）：

| 窗口 | `storage.append()`（含 fsync） | `advance()` + apply + 转发 | 合计 |
|---|---|---|---|
| 256 | 14.9ms | 3.9ms | 18.8ms |
| 1024 | 23.5ms | 9.1ms | 32.6ms |

dragonboat 的周期**就是一次 Pebble Apply**（12.9 / 14.9ms）——
commit / apply 在独立 worker 池里跑，完全不占这个周期。
所以差距 = 「fsync 本身更慢」+ 「串行开销全压在周期里」两段。

**顺带查了存储层的写法**：`FileRaftStorage.append()` 是**逐条**写盘 ——
每条一次 `channel.write()`（`RandomAccessFile` 的 FileChannel 无缓冲 → 一次 pwrite
系统调用）+ 每条一次 `TreeMap.put()` 索引插入 + 每条两次对象分配；
Pebble 则是整批编码成一个 buffer、一次 write + 一次 sync。
单节点探针（2,039 条 × 24B）实测：逐条写 5.22ms vs 整批写 0.62ms（**写阶段省 88%**），
`force(true)` 本身 4.2~4.8ms 与写法无关。
但**3 节点并发把盘打满时，这个差异几乎消失**（逐条 43.4ms vs 整批 42.4ms）——
说明当前的主要矛盾是 fsync，不是写盘粒度。
逐条写是要改的事，但不是当前的第一优先级。

**这正好把 ③ 拆成两个可分别验证的子目标**：
① 硬状态和日志合并成一次落盘（值 1.1~2.4×，中低窗口直接反超）；
② 落盘挪进框架 + commit/apply 拆成独立阶段，把 Ready 周期从 33ms 压到 15ms
（值 1.45~2.2×，高窗口的全部差距）。

#### ④ 消息投递顺序：把两个 fsync 从串行变成并行

**这是本轮单项收益最大的改动**（窗口 256：66,054 → 104,845/s，**+59%**）。

etcd 的 Ready 契约是「entries 未落盘前不能发出去」，于是
「本节点落盘 → 发复制消息 → 对端落盘」是**严格串行**的两次 fsync。
但复制类消息（`MSG_APP`）**不带任何持久化承诺** —— 它只说「我有这些日志」，
不说「我已经把它们存好了」。先发出去，对端立刻开始落盘，
本节点同时落自己的盘，**两个 fsync 重叠而不是排队**，端到端延迟少一个落盘周期。

反过来，**确认类消息（`MSG_APP_RESP` / `MSG_VOTE_RESP`）必须等落盘** ——
它们的意思是「我已经持久化了这些日志 / 这一票」。提前发出去，
对端会以为这些日志已经安全，而本节点可能在下一次 fsync 之前就掉电。

**dragonboat 走的就是这条路**（源码依据）：

| 位置 | 内容 |
|---|---|
| `node.go:989` `isFreeOrderMessage(m)` | `m.Type == pb.Replicate \|\| m.Type == pb.Ping` —— 只有这两类可以先发 |
| `engine.go:1330-1345` | `applySnapshotAndUpdate(...)` → `node.sendReplicateMessages(ud)`（**复制消息先发**）→ `e.logdb.SaveRaftState(nodeUpdates, workerID)`（落盘）→ `processRaftUpdate` → `n.sendMessages(ud.Messages)`（**只发非 free-order 的确认消息**） |
| 源码注释原文 | "see raft thesis section 10.2.1 on details why we send Replicate message before those entries are persisted to disk" |
| `internal/raft/peer.go:210 setFastApply` + `:228 validateUpdate` | committed entries 与 `EntriesToSave` 重叠时 `FastApply=false` —— 与 edap 的「应用层看到的状态不能比日志更靠前」是同一条不变式 |

etcd 是保守的（save → send），dragonboat 是快的（send → save）。
**edap-raft 现在跟 dragonboat 一致**：见 §3.2 的消息投递顺序表。

**改造后的 fsync 账单**（3 节点合计，9 轮中位数，`framework+apply`；dragonboat 侧
的落盘次数是给 `internal/logdb/db.go:saveRaftState` 加计数器测出来的，
每次 `CommitWriteBatch` = 一次 `pebble.Apply(wb, &pebble.WriteOptions{Sync:true})` = 一次 fsync）：

| 指标 | edap-raft w=256 | dragonboat w=256 | edap-raft w=1024 | dragonboat w=1024 |
|---|---|---|---|---|
| fsync/s | **232** | 236 | **197** | 221 |
| 条/次落盘 | **1,354** | 1,160 | **2,964** | 2,047 |
| 落盘条目/s | **314K** | 274K | **584K** | 452K |
| 提交/s | **104,845** | 90,198 | **192,650** | 147,881 |
| 单次落盘耗时 平均 | 11.26ms | — | 11.20ms | — |

模型自查（`落盘条目/s = fsync/s × 条/次落盘`，`提交/s = 落盘条目/s ÷ 3`）：
`232 × 1,354 = 314K ÷ 3 = 104.7K` ≈ 实测提交 104.8K ✓；
`197 × 2,964 = 584K ÷ 3 = 194.6K` ≈ 实测提交 192.7K ✓；
dragonboat 侧 `236 × 1,160 = 274K ÷ 3 = 91.3K` ≈ 90.2K ✓、
`221 × 2,047 = 452K ÷ 3 = 150.8K` ≈ 147.9K ✓。

**fsync 次数两边已经拉平**（窗口 256：edap 232 vs dragonboat 236；
窗口 1024：edap 197 vs dragonboat 221），而 edap 的**条/次落盘反而更多**
（1,354 vs 1,160；2,964 vs 2,047）—— 说明 edap 现在不是「fsync 用得更省」，
而是**每个 fsync 周期里攒的日志更多**（提案合批 + 复制早发让流水线一直满着）。

**仍然不可比的条件**（同硬件了，剩下的差异双方各有占便宜的地方）：

| 维度 | dragonboat | edap-raft | 谁占便宜 |
|---|---|---|---|
| 传输 | TCP loopback + protobuf 序列化 | **进程内直投消息对象，不序列化** | edap-raft |
| 元数据持久化 | LogDB 走 Pebble（和日志同批） | 硬状态与日志同一个 `wal.bin`（同样一次 fsync） | 打平 |
| 背压 | 提案队列满 → `ErrSystemBusy`（窗口 1/16/256 时 **0%** 被拒，窗口 1024 时约 **40%** 的提案尝试被拒） | `propc` 是无界 `LinkedBlockingQueue`，**永不拒绝** | dragonboat |
| 存储引擎 | Pebble（memtable + WAL，批量写） | 裸文件追加（单文件 WAL，整批一次 write） | dragonboat |

> ⚠️ **「背压」这一行的 edap-raft 侧已经过时** —— 2026-10-29 实现了有界提案队列
> + 拒绝语义（§9.8 ⑤），这一行描述的是**改造前**的状态。
> 新的实测见 §9.1 的「（2026-10-29 重测）」表。

> 「传输」这一行的便宜后来被实测掉了 —— 见上面的「传输层对等对比」小节：
> edap-raft 改走 TCP 回环 + `RaftCodec` 之后只掉 0~3.4%（异步路径下是 1.4~5.4%）。
> 剩下的条件差只有**存储引擎**（Pebble 占便宜）——
> **背压**这一条 edap-raft 2026-10-29 也补上了（§9.8 ⑤），
> 窗口 1024 的 p50 从 95.0ms 降到 58.0ms。

> ⚠️ **本小节（④）的表也是阻塞路径测的** —— 104,845/s / 192,650/s 是上一轮
> （10-29 上午）的数字。异步路径 + 背压开之后的对应值是 119,832/s / 234,796/s，
> 见 §9.1 的「（2026-10-29 重测）」表与 §8.3 的极限表。

edap-raft 拿到了「进程内直投」的便宜，dragonboat 拿到了「有背压」的便宜，
**edap-raft 在阻塞路径上领先 1.25~1.32×，在异步路径上领先 1.34~2.35×** ——
差距不在传输层，而在**落盘时机**（复制早发）与**流水线填充**
（提案合批 + 独立 apply 阶段 + 在途窗口深度）。

#### 逐轮原始数据

上面那张表是 9 轮的中位数。下面是**每一轮的原始数字**，中位数与波动都可以自己复核。

**2026-10-29 重测这一批（异步路径 + 背压）** —— 跑法：9 轮连续，每轮内按
窗口 1 → 16 → 256 → 1024 的顺序，每个窗口把五个变体连着跑，
轮次奇偶调换变体顺序（避免固定的顺序偏差）；每轮 10 秒。
采集脚本逐轮抓 harness 与 dragonboat 基准的 stdout（临时脚本与逐轮日志在 `/tmp` 下，
`/tmp` 不保证长期保留 —— **下面的表就是完整快照**）。

**edap-raft（`direct-async`，异步 + 背压开）** —— 单元格 = `提交/s` · `p50 ms` · `p99 ms`：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 631 · 25.70 · 44.25 | 15,891 · 13.36 · 41.32 | 119,361 · 35.59 · 62.95 | 242,622 · 60.57 · 93.72 |
| 2 | 676 · 22.25 · 43.42 | 18,340 · 12.94 · 39.87 | 118,375 · 37.22 · 56.67 | 241,158 · 56.63 · 95.40 |
| 3 | 969 · 14.62 · 47.77 | 9,334 · 26.42 · 59.82 | 129,802 · 33.15 · 63.87 | 241,811 · 57.70 · 94.32 |
| 4 | 903 · 14.15 · 41.04 | 8,874 · 30.22 · 54.00 | 130,215 · 32.94 · 50.84 | 225,749 · 56.89 · 98.58 |
| 5 | 916 · 13.06 · 39.40 | 20,804 · 12.36 · 34.13 | 114,366 · 38.36 · 62.09 | 223,012 · 60.63 · 97.50 |
| 6 | 824 · 15.66 · 40.66 | 20,014 · 12.16 · 39.25 | 119,832 · 36.06 · 59.75 | 229,007 · 59.06 · 88.45 |
| 7 | 723 · 17.06 · 50.08 | 14,067 · 13.75 · 52.56 | 119,195 · 36.46 · 56.29 | 246,777 · 59.12 · 70.82 |
| 8 | 842 · 14.24 · 40.14 | 13,641 · 15.15 · 45.87 | 125,285 · 34.33 · 58.50 | 234,796 · 58.00 · 93.67 |
| 9 | 898 · 14.61 · 47.44 | 8,826 · 29.74 · 56.28 | 126,274 · 33.67 · 50.59 | 233,055 · 56.84 · 77.52 |

**edap-raft（`tcp-async`，异步 + 背压开 + TCP 回环）** —— 单元格同上：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 569 · 30.70 · 41.53 | 13,769 · 14.37 · 43.24 | 116,071 · 38.12 · 56.12 | 231,176 · 61.96 · 99.17 |
| 2 | 949 · 12.96 · 40.08 | 16,586 · 12.47 · 38.59 | 118,186 · 36.13 · 54.24 | 213,638 · 61.35 · 93.39 |
| 3 | 1,002 · 13.21 · 43.98 | 11,749 · 20.90 · 45.34 | 114,481 · 38.00 · 49.62 | 220,947 · 63.67 · 94.73 |
| 4 | 805 · 15.19 · 46.01 | 15,784 · 13.34 · 44.84 | 131,653 · 31.08 · 56.90 | 226,855 · 63.59 · 90.52 |
| 5 | 734 · 20.18 · 40.04 | 20,812 · 11.89 · 38.59 | 121,982 · 35.59 · 66.68 | 222,166 · 59.96 · 112.65 |
| 6 | 564 · 32.00 · 42.46 | 17,201 · 13.03 · 35.39 | 117,679 · 37.20 · 55.57 | 214,725 · 62.35 · 90.04 |
| 7 | 946 · 14.35 · 40.15 | 9,719 · 25.53 · 55.33 | 126,604 · 32.22 · 58.08 | 239,434 · 63.78 · 94.06 |
| 8 | 603 · 27.98 · 46.01 | 10,713 · 22.38 · 46.41 | 120,477 · 36.10 · 53.79 | 220,731 · 63.35 · 94.31 |
| 9 | 999 · 13.77 · 43.83 | 9,638 · 25.72 · 51.46 | 116,875 · 37.30 · 50.74 | 234,476 · 61.05 · 94.46 |

**edap-raft（`direct-async-nobp`，异步 + 背压关）** —— 单元格同上：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 716 · 20.41 · 42.00 | 16,086 · 12.91 · 41.74 | 133,824 · 33.44 · 58.30 | 171,609 · 99.71 · 146.43 |
| 2 | 874 · 13.02 · 39.25 | 13,230 · 16.05 · 46.16 | 118,662 · 37.35 · 52.21 | 180,484 · 93.72 · 117.19 |
| 3 | 897 · 14.89 · 47.21 | 14,212 · 14.52 · 45.99 | 145,276 · 33.38 · 62.24 | 170,160 · 106.48 · 143.63 |
| 4 | 811 · 15.61 · 44.45 | 15,785 · 13.83 · 44.45 | 152,814 · 33.55 · 61.77 | 173,625 · 99.25 · 118.78 |
| 5 | 740 · 20.39 · 41.12 | 20,300 · 12.24 · 36.78 | 149,953 · 31.70 · 63.32 | 167,844 · 105.22 · 136.09 |
| 6 | 938 · 12.77 · 38.52 | 15,766 · 13.00 · 40.40 | 104,088 · 45.46 · 58.26 | 182,183 · 90.22 · 121.41 |
| 7 | 783 · 15.23 · 44.22 | 9,919 · 24.33 · 50.75 | 141,754 · 34.35 · 62.74 | 185,550 · 94.98 · 126.78 |
| 8 | 753 · 19.94 · 41.49 | 15,649 · 13.04 · 45.69 | 143,830 · 33.48 · 52.90 | 183,411 · 93.20 · 120.92 |
| 9 | 971 · 14.07 · 42.20 | 15,253 · 13.17 · 47.88 | 137,217 · 36.42 · 53.62 | 172,196 · 93.81 · 126.77 |

**edap-raft（`direct-sync`，阻塞 `node.propose()`）** —— 单元格同上：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 469 · 36.03 · 42.40 | 7,146 · 32.33 · 44.22 | 104,162 · 32.97 · 41.84 | 189,639 · 39.99 · 79.20 |
| 2 | 471 · 35.98 · 42.20 | 6,738 · 34.81 · 44.16 | 106,173 · 33.32 · 43.54 | 201,113 · 41.86 · 61.52 |
| 3 | 409 · 39.32 · 66.79 | 6,309 · 34.55 · 57.12 | 101,847 · 33.90 · 49.78 | 193,685 · 39.89 · 61.50 |
| 4 | 450 · 36.09 · 53.11 | 6,724 · 33.88 · 54.65 | 103,059 · 34.22 · 43.02 | 186,958 · 39.41 · 57.50 |
| 5 | 454 · 36.18 · 45.34 | 7,067 · 32.85 · 42.80 | 103,370 · 34.70 · 46.26 | 186,962 · 39.28 · 61.70 |
| 6 | 449 · 36.89 · 46.56 | 7,093 · 32.86 · 40.74 | 105,953 · 32.88 · 41.72 | 187,218 · 40.22 · 60.83 |
| 7 | 431 · 38.01 · 52.78 | 6,638 · 34.71 · 50.87 | 102,868 · 34.08 · 46.60 | 203,609 · 41.50 · 76.25 |
| 8 | 428 · 38.38 · 48.32 | 6,968 · 33.97 · 43.32 | 102,062 · 34.66 · 43.22 | 199,766 · 39.82 · 57.42 |
| 9 | 436 · 37.75 · 57.83 | 6,393 · 35.59 · 57.24 | 106,636 · 32.96 · 41.22 | 200,720 · 43.13 · 78.00 |

**dragonboat**（3 节点，默认 LogDB 配置）—— 单元格同上：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 380 · 38.05 · 74.83 | 5,862 · 42.03 · 74.08 | 93,850 · 39.73 · 82.91 | 167,810 · 49.92 · 98.31 |
| 2 | 365 · 40.86 · 68.55 | 5,870 · 41.26 · 73.15 | 91,571 · 40.40 · 86.76 | 156,502 · 51.31 · 106.66 |
| 3 | 320 · 45.53 · 95.27 | 5,874 · 39.98 · 73.66 | 94,587 · 38.51 · 80.33 | 152,696 · 46.46 · 97.00 |
| 4 | 338 · 44.17 · 82.37 | 5,382 · 43.20 · 86.13 | 88,108 · 41.33 · 90.13 | 154,573 · 50.58 · 101.51 |
| 5 | 352 · 41.52 · 68.76 | 5,741 · 41.43 · 73.41 | 88,320 · 44.31 · 83.67 | 148,577 · 51.32 · 110.45 |
| 6 | 393 · 36.90 · 68.38 | 5,869 · 40.53 · 78.46 | 93,072 · 41.62 · 81.24 | 140,345 · 44.52 · 86.85 |
| 7 | 351 · 41.89 · 78.05 | 5,941 · 39.25 · 75.56 | 85,069 · 46.29 · 93.14 | 151,773 · 48.51 · 98.34 |
| 8 | 362 · 41.18 · 73.78 | 5,488 · 43.66 · 78.33 | 87,245 · 42.75 · 87.01 | 145,983 · 49.14 · 108.63 |
| 9 | 324 · 44.57 · 84.18 | 5,250 · 47.08 · 88.91 | 85,459 · 45.56 · 91.09 | 157,467 · 48.80 · 103.52 |

> 窗口 1 / 16 的异步变体波动很大（提交/s 的极差能到 3 倍，p50 也在 12~30ms 之间摆）——
> 这两个窗口下系统几乎是「延迟受限」而不是「吞吐受限」，
> 一次心跳、一轮选举的时序就会决定这一轮的成绩。窗口 256 / 1024 稳定得多。

---

**历史批次（阻塞路径，2026-10-29 上午）** —— 下面这几张表是**修好 mode 解析 bug
之前**的那一批，跑的是 `node.propose()`，保留作为记录。
跑法：9 轮连续，每轮内按 窗口 1 → 16 → 256 → 1024 的顺序，每个窗口把
**edap-direct / edap-tcp / dragonboat 三方连着跑**，轮次奇偶调换三方的先后顺序
（避免固定的顺序偏差）；每轮 10 秒。

**edap-raft（`direct`，进程内直投）** —— 单元格 = `提交/s` · `p50 ms` · `p99 ms`：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 474 · 35.43 · 43.49 | 7,120 · 36.42 · 43.98 | 106,819 · 35.19 · 46.90 | 199,549 · 62.96 · 109.94 |
| 2 | 439 · 37.04 · 56.93 | 6,179 · 39.67 · 63.50 | 106,982 · 35.61 · 43.73 | 198,298 · 60.47 · 101.40 |
| 3 | 433 · 37.54 · 57.83 | 6,618 · 36.78 · 53.45 | 102,234 · 37.12 · 47.00 | 201,169 · 61.12 · 108.12 |
| 4 | 453 · 36.89 · 56.99 | 6,422 · 37.97 · 60.13 | 103,972 · 36.00 · 44.32 | 198,245 · 62.76 · 106.49 |
| 5 | 420 · 38.21 · 60.40 | 6,694 · 36.12 · 51.80 | 106,575 · 35.34 · 43.11 | 199,196 · 60.34 · 110.49 |
| 6 | 437 · 37.41 · 60.18 | 6,283 · 38.52 · 59.16 | 106,759 · 35.96 · 42.82 | 201,653 · 62.48 · 110.36 |
| 7 | 433 · 37.50 · 59.86 | 6,604 · 36.89 · 53.09 | 105,587 · 36.26 · 45.70 | 201,433 · 61.48 · 103.60 |
| 8 | 439 · 36.93 · 58.02 | 6,511 · 37.05 · 58.36 | 106,705 · 35.98 · 46.80 | 199,116 · 61.49 · 106.21 |
| 9 | 451 · 36.98 · 58.45 | 6,538 · 36.72 · 56.06 | 104,887 · 36.46 · 43.51 | 202,331 · 60.56 · 106.67 |

**edap-raft（`tcp`，TCP 回环 + `RaftCodec`）** —— 单元格同上：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 462 · 35.10 · 47.29 | 7,274 · 35.69 · 46.75 | 103,996 · 36.93 · 53.93 | 197,777 · 62.58 · 106.54 |
| 2 | 430 · 38.00 · 57.92 | 6,679 · 36.48 · 60.38 | 105,904 · 35.76 · 47.77 | 197,979 · 63.51 · 100.81 |
| 3 | 426 · 38.21 · 56.48 | 5,728 · 40.15 · 97.01 | 106,110 · 35.90 · 44.97 | 196,692 · 63.79 · 101.82 |
| 4 | 424 · 38.11 · 56.27 | 6,584 · 37.10 · 55.11 | 106,600 · 35.62 · 47.77 | 193,834 · 62.74 · 104.80 |
| 5 | 418 · 38.45 · 58.51 | 6,473 · 37.79 · 55.57 | 104,958 · 36.23 · 48.87 | 198,857 · 63.69 · 102.02 |
| 6 | 414 · 38.52 · 61.40 | 6,541 · 36.87 · 59.29 | 106,252 · 36.12 · 46.86 | 197,744 · 63.30 · 105.97 |
| 7 | 408 · 38.93 · 58.49 | 6,659 · 37.19 · 54.15 | 106,414 · 35.57 · 44.08 | 194,465 · 63.79 · 109.78 |
| 8 | 431 · 37.21 · 62.94 | 6,287 · 38.61 · 60.91 | 103,044 · 36.56 · 50.21 | 195,041 · 65.13 · 107.62 |
| 9 | 414 · 38.66 · 56.95 | 6,309 · 38.93 · 56.58 | 105,939 · 36.11 · 54.45 | 191,306 · 65.49 · 104.46 |

**dragonboat**（3 节点，默认 LogDB 配置）—— 单元格同上：

| 轮次 | 窗口 1 | 窗口 16 | 窗口 256 | 窗口 1024 |
|---|---|---|---|---|
| 1 | 372 · 38.41 · 79.97 | 5,069 · 45.31 · 98.51 | 85,069 · 46.24 · 88.56 | 156,349 · 46.92 · 96.99 |
| 2 | 316 · 47.69 · 88.97 | 5,075 · 46.68 · 97.54 | 81,649 · 47.75 · 93.15 | 152,124 · 46.11 · 104.52 |
| 3 | 319 · 47.30 · 87.04 | 4,994 · 48.25 · 92.25 | 86,177 · 45.17 · 89.35 | 153,526 · 52.89 · 103.09 |
| 4 | 334 · 44.05 · 88.56 | 5,067 · 47.80 · 100.89 | 87,873 · 42.02 · 87.64 | 158,962 · 46.61 · 94.92 |
| 5 | 307 · 48.93 · 93.89 | 5,246 · 44.15 · 90.52 | 83,789 · 47.13 · 93.54 | 160,245 · 47.90 · 101.55 |
| 6 | 321 · 45.71 · 89.58 | 5,411 · 43.26 · 89.39 | 85,102 · 44.24 · 90.35 | 150,548 · 44.17 · 91.90 |
| 7 | 318 · 46.87 · 90.28 | 5,270 · 44.04 · 91.80 | 83,639 · 46.31 · 97.59 | 163,315 · 45.73 · 103.55 |
| 8 | 321 · 46.84 · 92.57 | 4,978 · 47.53 · 94.04 | 82,266 · 47.72 · 89.08 | 168,094 · 47.61 · 101.93 |
| 9 | 322 · 46.17 · 86.88 | 5,194 · 45.48 · 90.52 | 87,248 · 41.87 · 89.22 | 155,085 · 47.16 · 95.54 |

**fsync 账单（历史批次，9 轮中位数）** —— 3 节点合计：

| 侧 | 窗口 | fsync/s | 条/次落盘 |
|---|---|---|---|
| edap-raft `direct` | 1 | 222 | 5.9 |
| edap-raft `direct` | 16 | 224 | 88.5 |
| edap-raft `direct` | 256 | **236** | **1,356** |
| edap-raft `direct` | 1024 | **196** | **3,087** |
| edap-raft `tcp` | 1 | 224 | 5.6 |
| edap-raft `tcp` | 16 | 226 | 87.0 |
| edap-raft `tcp` | 256 | **235** | **1,352** |
| edap-raft `tcp` | 1024 | **191** | **3,033** |
| dragonboat | 1 | 213 | 4.5 |
| dragonboat | 16 | 210 | 74.3 |
| dragonboat | 256 | 222 | 1,145 |
| dragonboat | 1024 | 218 | 2,153 |

**模型自查**（`落盘条目/s = fsync/s × 条/次落盘`，`提交/s = 落盘条目/s ÷ 3`）
—— 12 个格子全部对上，偏差都在 ±2.5% 以内：

| 窗口 | 侧 | 落盘条目/s（模型） | 提交/s（实测） | 偏差 |
|---|---|---|---|---|
| 1 | edap-direct / edap-tcp / dragonboat | 1,310 / 1,254 / 959 | 439 / 424 / 321 | −0.5% / −1.4% / −0.5% |
| 16 | edap-direct / edap-tcp / dragonboat | 19,824 / 19,662 / 15,603 | 6,538 / 6,541 / 5,075 | +1.1% / +0.2% / +2.5% |
| 256 | edap-direct / edap-tcp / dragonboat | 319,992 / 317,650 / 254,257 | 106,575 / 105,939 / 85,069 | +0.1% / −0.1% / −0.4% |
| 1024 | edap-direct / edap-tcp / dragonboat | 605,072 / 579,246 / 469,354 | 199,549 / 196,692 / 156,349 | +1.1% / −1.8% / +0.1% |

**dragonboat 的提案被拒次数**（`ErrSystemBusy` = 提案队列打满；
基准的 `failed` 计数器 = `ErrSystemBusy` + 未完成的提案，后者每轮另有 8K~11K）：

| 窗口 | 1 | 16 | 256 | 1024 |
|---|---|---|---|---|
| 每轮 `ErrSystemBusy` 次数 | 0（9 轮全 0） | 0（9 轮全 0） | 0（9 轮全 0） | 1,012,219 ~ 1,190,956 |
| 拒绝率（被拒 / 总尝试） | 0% | 0% | 0% | 37.4% ~ 43.3%（中位 **41.7%**） |

**怎么读这些数**：

- **窗口 1/16/256 时 dragonboat 一次都没拒过提案**（9 轮全 0），所以这三档是
  「同样提案量、同样全部提交」的直接对比，1.25~1.32× 的差距是干净的。
- **窗口 1024 时 dragonboat 有约 42% 的提案尝试被拒**（提案队列打满）。
  被拒的提案**根本没进 raft 日志** —— 所以 dragonboat 那档的落盘条目/s
  （469K）比 edap-tcp（579K）低了 19%，edap 在窗口 1024 领先得更多，
  但赢的方式是「把 dragonboat 拒掉的活也干了」，不是「同样负载下更快」。
  这正是下一步要补的**背压** —— **2026-10-29 已经补上了**（§9.8 ⑤），
  edap-raft 现在用同样的口径拒绝提案，窗口 1024 的 p50 从 95.0ms 降到 58.0ms。
- **轮间极差**（(max−min)/中位数，窗口 1 / 16 / 256 / 1024）：
  edap-direct 12.3% / 14.4% / 4.5% / 2.0%，edap-tcp 12.7% / 23.6% / 3.4% / 3.8%，
  dragonboat 20.2% / 8.5% / 7.3% / 11.2%。窗口 256/1024 上 edap 明显更稳
  —— 与「edap 的每次落盘批次更大」一致：批次越大，同样的落盘预算
  能摊到的提案越多，对客户端负载波动越不敏感。窗口 16 上 edap-tcp 有一轮
  掉到 5,728（其余 6,300~7,300），把极差拉大了。

#### 纯内存 / 模拟 fsync 的对标（条件不可比，只看量级）

`AsyncProposalHarness` 的 `sync-commit` / `async-commit` 模式同时量吞吐和
**端到端延迟**（提案发布 → 客户端观察到提交）。
下表是 **2026-10-29 重测**的（campaign E = 阻塞基线，campaign D = 异步路径，
单节点，16 客户端，9 轮中位数）：

| 实现 | 场景 | 吞吐 | p50 | 均值 | P99 |
|---|---|---|---|---|---|
| **dragonboat**（README 自报） | 单 group，3 节点 + RocksDB，16B，3 核 | **1,250,000/s** | — | 1.3ms | 2.6ms |
| edap-raft（campaign E） | **阻塞提案**，纯内存，窗口 1 | 132,419/s | 0.12ms | 0.13ms | 0.26ms |
| edap-raft（campaign E） | **阻塞提案**，fsync=2ms，窗口 1 | 2,131/s | 6.53ms | 7.52ms | 16.50ms |
| edap-raft（campaign E） | **阻塞提案**，纯内存，窗口 1024 | 306,610/s | 29.12ms | 29.63ms | 68.94ms |
| edap-raft（campaign D） | **异步提案**，纯内存，窗口 16 | **1,457,476/s** | 0.21ms | 0.24ms | 0.82ms |
| edap-raft（campaign D） | **异步提案**，纯内存，窗口 64 | 2,135,033/s | 0.46ms | 0.57ms | 3.24ms |
| edap-raft（campaign D） | **异步提案**，纯内存，窗口 1024 | **2,605,338/s** | 6.14ms | 7.22ms | 15.29ms |
| edap-raft（campaign D） | **异步提案**，fsync=2ms，窗口 256 | 406,342/s | 9.80ms | 9.57ms | 14.39ms |
| edap-raft（campaign D） | **异步提案**，fsync=2ms，窗口 1024 | 1,436,212/s | 12.78ms | 13.50ms | 22.16ms |

**两条轴上都反超了 dragonboat 的自报数** —— 纯内存窗口 16：
edap 1,457,476/s / p50 0.21ms / P99 0.82ms vs dragonboat 1,250,000/s / 1.3ms / 2.6ms。
**但这组对比的条件不可比，别当结论用**：

| 维度 | dragonboat | edap-raft（本文实测） | 谁占便宜 |
|---|---|---|---|
| 集群 | 3 节点，1.25M/s 是**集群级**吞吐 | **单节点**，节点级吞吐 | edap-raft（少走网络） |
| 存储 | 真 RocksDB（真 fsync） | `MemoryStorage` + `Thread.sleep(2)` **模拟** fsync | edap-raft（模拟不是真盘） |
| 载荷 | 16B | 16B | 打平 |
| 核数 | 3 核 | 10 核 | edap-raft |
| 数据来源 | README 自报，无第三方复现 | 本机实测 | — |

也就是说 **edap-raft 这边全是乐观上界**。真对标的数据在上一节
（本机同硬件、3 节点 + 真盘 + 16B）：那里 edap-raft **领先 1.34~2.35×**
（改造前是落后 2.0~3.2×），而且那条数据是**走 TCP 回环 + `RaftCodec` 序列化**测的
（进程内直投只多 1.4~5.4%）。

> 上一版这张表里有一行「①+②③（外推）~1,200,000/s」——
> 那是**外推值**，现在异步路径真跑出来了：**纯内存窗口 16 就是 1,457,476/s**，
> 外推可以退休了。

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

对比 edap-raft：**现在也是有界的了**（§9.8 ②）：
`propc = new LinkedBlockingQueue<>(maxPendingProposals)`，默认 2048，
与 dragonboat 的 `IncomingProposalQueueLength` 同值。
每次 `offer` 仍然要分配节点、仍然要抢锁 —— 这一点没变，变的是**队列满了会怎样**。

**提案异步**（`request.go:1077 proposalShard.propose`）：`propose()` 立即返回
`*RequestState`，客户端自己 select on `Completed` / `Dropped` / `Timeout`；
`RequestState` 从对象池取、用完 `Release()` 归还。

队列满了返回 `ErrSystemBusy` —— 这是**背压**。

edap-raft 现在是同一套语义，但用 JDK 标准件实现（`CompletableFuture` 而不是
`RequestState` 句柄），三条路径三种行为：

| 路径 | 队列满时 | 为什么 |
|---|---|---|
| `Node.propose()`（同步） | 按 `RaftConfig.proposalFullPolicy`：**BLOCK**（默认，等位置）/ **REJECT**（抛 `RaftBusyException`） | BLOCK 与「没有 leader 就阻塞」的既有契约一致，且不丢提案 |
| `Node.proposeAsync()`（异步） | **永远 REJECT**，future 异常完成，cause = `RaftBusyException` | 异步路径的价值就是不阻塞；拒绝让调用方自己决定重试还是退避 |
| `onMessage(MSG_PROP)`（传输层回调） | **永远丢弃 + 计数 + 限频告警**，绝不阻塞 | 它跑在 NIO 的 IO 线程上，阻塞会把心跳一起卡死 → 选举风暴 |

dragonboat 的 `ErrSystemBusy` 对应的是 edap 的 `RaftBusyException`，
两者都是「提案根本没进 raft 日志」。

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
| 主循环 | **一轮扫所有来源，各排空**（§7.1） | **一轮扫所有来源，各排空** |
| 流水线 | **step / persist / apply 三阶段**（§3.2） | **step / commit / apply 三阶段** |
| 落盘 | **框架的落盘线程**，一次 write + 一次 fsync（`frameworkPersist` 开关） | **engine 批量落盘，跨 shard 一次 fsync** |
| 持久化 | 硬状态 + 日志**同一个 `wal.bin`** = **1 次 fsync** | 硬状态 + 日志**同一个 Pebble write batch** = **1 次 fsync** |
| 提交 / 应用 | **独立 apply 线程**（`applyHandler` 开关） | **`commitWorker` / `applyWorker` 独立 worker 池** |
| apply | **框架的 apply 线程**（或应用层自己做） | **applyWorker 独立阶段，批量** |
| FastApply | 有（`applyUnstableEntries` 恒 true） | 有，默认开 |
| **复制消息的投递时机** | **落盘之前发**（`MSG_APP`），确认类消息落盘之后发 | **落盘之前发**（free-order 消息） |
| 提案 | **同步阻塞**（`propose()`，等状态机 step）+ **异步**（`proposeAsync()`，返回 `CompletableFuture`）；`Node` 内部合批 | **异步**（`RequestState`） |
| 提案队列 | **有界** `LinkedBlockingQueue`（默认 2048） | **有界双缓冲槽位数组** |
| 背压 | **有**：队列满 → 同步路径 BLOCK/REJECT、异步路径 `RaftBusyException`、传输层路径丢弃计数 | **有**：队列满 → `ErrSystemBusy` |
| 提案完成的信号 | **条目带 requestId**，提交时按 requestId 找回 future（requestId 随条目复制，follower 转发也能对上） | `RequestState` 句柄 + `Session` 回调 |
| 传输 | `RaftTransport` SPI（生产用 `NioRaftTransport`：入站 edap-nio / 出站阻塞 Socket）；基准另有一个批量 TCP 回环实现 | **每对端一条长连接 + 批量发送**（`pb.MessageBatch`） |
| 日志追踪 | etcd unstable（O(n²) 复制） | `entryLog` + `inmem`（不拷贝） |

### 9.8 四条可借鉴项（按性价比排序）

**① 主循环改「一轮扫所有来源」** —— **已实现**（见 §7.1）。去掉 `continue`，
让每个来源的 handler 一次排空，解决饿死问题。
实测（OLD / NEW 各 9 轮取中位数，16 客户端）：fsync=2ms **+22.5%**
（3,659 → 4,481 提案/s），纯内存 **+1.8%**（127.5K → 129.8K/s，持平）；
提案洪水从「0 次落盘、0 个提交」变成「86 次落盘、14 个提交」。
这是 dragonboat 相对 etcd 唯一语义更优的地方，也是改动量 / 收益比最高的一条。

**② 提案异步化** —— **已实现**（2026-10-29）。
这是突破「等状态机 step 完」这个语义硬天花板的唯一办法
（阻塞路径在窗口 ≥ 4 时就卡在 307K/s 不涨了，§8.3）。
参照 dragonboat 的 `RequestState` + `Session`：提案返回句柄，
客户端自己等 `Completed` / `Dropped` / `Timeout`。

API：

```java
CompletableFuture<Void> proposeAsync(byte[] data);              // 不限时
CompletableFuture<Void> proposeAsync(byte[] data, long timeoutMillis);
```

用 JDK 的 `CompletableFuture` 而不是 dragonboat 式的 `RequestState` 句柄 ——
Java 有 future，标准件零抽象，`allOf` / `thenApply` / `whenComplete` / `orTimeout`
都是白送的。

**完成通知走「条目带 requestId」**：`Entry` 多一个 `long requestId`（0 = 无请求），
格式是 `(nodeId << 32) | (seq++ & 0xFFFFFFFFL)`，全集群唯一。
条目被提交时，节点按 requestId 从待完成表里摘出对应的 future 并正常完成。

**为什么不用「index → future」**：
1. 客户端向 **follower** 提案时，follower 转发给 leader，本地拿不到 index → future 挂死；
2. 条目被新 leader **截断覆盖**后，index 映射会**误报「提交成功」** —— 这是正确性 bug。
   requestId 随条目复制，截断后新条目的 requestId 不同，不会误配。

**失败的三条路径**：

| 情形 | future 的结果 |
|---|---|
| 提案被 raft 丢弃 | `RaftException("raft proposal dropped")` —— 与同步路径文案一致 |
| 队列满（异步路径永远拒绝） | `RaftBusyException("raft proposal queue is full")` |
| 节点停止 | `RaftException("raft: stopped")` |
| 超时 | `TimeoutException`（`orTimeout`），并从待完成表里摘除 |

**超时是必填项而不是可选项**：提案被截断 / leader 切换导致丢失时，
requestId 永远不会出现在已提交条目里，future 只能靠超时结束。

**代价**：线格式条件多 8 字节（§4，requestId ≠ 0 时才占位），
WAL 条目记录定长部分 22 → 30 字节（§5，**不兼容老数据**）。
吞吐是 fsync 瓶颈不是带宽瓶颈，实测影响在噪声级别（§9.1 的表）。

**实测**（`AsyncProposalHarness`，16 客户端，单节点集群，9 轮中位数；
campaign E ＝ 阻塞 `propose()`，campaign D ＝ `proposeAsync()`，
同一台机器同一批跑的）：

| 在途窗口（每客户端） | 纯内存 fsync=0（阻塞 → 异步） | fsync=2ms（阻塞 → 异步） |
|---|---|---|
| 1 | 132,419 → 177,826/s（1.34×） | 2,131 → 2,035/s（0.95×） |
| 4 | 297,329 → 563,247/s（1.89×） | 7,043 → 7,982/s（1.13×） |
| 16 | 307,221 → **1,457,476/s**（4.74×） | 26,476 → 29,966/s（1.13×） |
| 64 | 303,723 → **2,135,033/s**（7.03×） | 109,598 → 111,500/s（1.02×） |
| 256 | 305,323 → **1,993,642/s**（6.53×） | 332,481 → 406,342/s（1.22×） |
| 1024 | 306,610 → **2,605,338/s**（**8.50×**） | 329,339 → **1,436,212/s**（**4.36×**） |

**窗口深度是全部的杠杆**：窗口=1 时异步和阻塞没区别（都还是一次往返，
fsync=2ms 时 0.95× 落在轮间波动里）；窗口放开后客户端不再等状态机，
瓶颈从「往返延迟」挪到「流水线填充速度」。
**阻塞路径有一个硬天花板 307K/s**（窗口 ≥ 4 就不再涨），
异步路径同一台机器能到 **2,605,338/s** —— **8.50×**。

提升来自**批量变大**，不是单条提案更快：纯内存下一次落盘覆盖的条数
从阻塞路径的 15.0 条涨到异步路径的 441.8 条；
fsync=2ms 时从 1,025.1 条涨到 5,629.9 条（2ms 的 fsync 被摊薄到 0.36μs/条）。

> ⚠️ **这一节的表之前是错的**：老版本测出来的 127K/s → 331K/s 那组数，
> 阻塞列与异步列**跑的是同一条路径**（harness 的 mode 解析有 bug，
> fire-and-forget 分支从未执行过），所以「2.2~2.6×」「72×」全是假的。
> 上表是修好之后重测的。

> **注意这只在调用方本来就要等提交时才有收益**；调用方不等提交的话，
> 同步提案反而更简单。真要上异步提案，还得补上两件事：
> ① 提案被丢弃（不是 leader）要能回传给客户端 —— 同步路径靠 `awaitResult()` 抛异常，
> 异步路径需要一个 `RequestState` 句柄；
> ② **背压** —— 那时 `propc` 还是无界队列，异步提案会把它撑爆。
> **这两件事 2026-10-29 都补上了**（分别是本节的 requestId 方案与 §9.8 ⑤）。

**③ 落盘挪进框架 + 三阶段分离** —— **已实现**（§3.2）。落盘由框架的落盘线程接管，
已提交条目交给独立的 apply 线程。两个开关都是**默认关**，etcd 移植基线一行不改。

**④ 复制消息早发、确认消息等落盘** —— **已实现**（§3.2 / §9.1）。
etcd 的「entries 未落盘前不能发出去」只对**确认类消息**成立；
复制类消息（`MSG_APP`）不带持久化承诺，可以先发，让对端的 fsync 与本节点的 fsync 重叠。
**本轮单项收益最大的一个**：窗口 256 **+59%**（66,054 → 104,845/s）。

**⑤ 背压** —— **已实现**（2026-10-29）。`RaftConfig.maxPendingProposals`
（默认 2048，与 dragonboat 的 `IncomingProposalQueueLength` 同值）+
`RaftConfig.proposalFullPolicy`（BLOCK / REJECT）。
三条路径三种语义见 §9.5 的表。

> **一个必须写下来的坑**：L1（队列长度）这一道闸**只对异步路径有约束力**。
> 同步 `propose()` 阻塞到状态机 step 完，每个调用方最多只有一条提案卡在队列里，
> 队列长度 ≈ 并发调用方数（16），永远碰不到 2048。
> 真正压住尾延迟的是 **L2（`maxUncommittedEntriesSize`，已进日志但未提交的字节数）**
> 与「提案队列有界」这两者的组合。
>
> **L1 + 异步 API 是一对**，缺一个都不成立。
>
> **实测（3 节点真盘，窗口 1024，campaign A，9 轮中位数）**：
>
> | 变体 | 提案队列 | 提交/s | 端到端 p50 | 拒绝率 |
> |---|---|---|---|---|
> | `direct-async` | **有界**（2048） | **234,796** | **58.00ms** | 36.15% |
> | `direct-async-nobp` | 无界（背压关） | 173,625 | 94.98ms | 0% |
>
> 背压关掉时提案在队列里堆成一坨，一批 7,798 条一起进日志，一起等落盘，
> 一起提交，延迟被这一批的规模拖住 —— **吞吐掉 26%、p50 涨 64%**。
> 有界之后队列满就拒（拒绝率 36.15%，dragonboat 同条件 42.6%），
> **吞吐反而更高**（拒绝掉的活根本没进 raft 日志）。
> 窗口 256 时背压刚开始起作用（拒绝率 1.16%），窗口 1/16 时完全不触发。

**③+④ 做完之后的账**（3 节点真盘，窗口 256 / 1024，9 轮中位数）：

| 阶段 | 窗口 256 | 窗口 1024 |
|---|---|---|
| 起点（`meta.bin` + `log.bin` 双 fsync，消息全等落盘） | 27,975/s | 55,531/s |
| ③a 硬状态与日志合并成一次落盘 | 53,014/s | 110,207/s |
| ③b+c 框架落盘 + 独立 apply 线程 + 提案合批 | 66,054/s | 170,988/s |
| ④ 复制消息早发、确认消息等落盘 | **104,845/s** | **192,650/s** |
| ④ 之后换成 TCP 回环 + `RaftCodec` 序列化 | 105,939/s | 196,692/s |
| ②+⑤ 提案异步化 + 背压（2026-10-29） | **119,832/s** | **234,796/s** |
| dragonboat 同条件 | 88,320/s | 152,696/s |

> 最后两行是 2026-10-29 重测的（与上面几行不是同一批 campaign，
> 跨 campaign 有 run-to-run 噪声；dragonboat 那一行是同一批的对照）。

> 前三行是 10-28 那轮 campaign，④ 行与 dragonboat 是 10-29 那轮（见 §8.3 的说明）。
> 最后两行是 10-29 下午的三方同批 campaign（§9.1 的逐轮原始数据）——
> **换成真实传输层只值 0~1.4%**（阻塞路径，倍数稳在 1.25~1.32×）；
> 走异步路径后倍数是 **1.34~2.35×**（§9.1 的重测表）。

**当时的差距在哪**：fsync 次数两边已经拉平（235 vs 222、191 vs 218），
edap 的**条/次落盘反而更多**（1,352 vs 1,145；3,033 vs 2,153）。
当时的杠杆是 **背压**（dragonboat 提案队列满会拒，edap 还是无界队列）
与 **提案 API 异步化**（②）—— 后者会让端到端延迟不再随窗口线性拖长。

> **2026-10-29 更新**：上面这两条（背压、提案异步化）**都已经实现了**，
> 见 §9.8 ② 与 ⑤。§9.1 的表已经按新实现重测过一轮。
> 剩下的差距是**尾延迟**：窗口 1024 时 edap 的 p50（58.0ms）仍高于 dragonboat（49.1ms），
> 原因已经从「没有背压」变成「提案批次规模」与「提交语义」的差异。

