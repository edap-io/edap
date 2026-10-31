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
2. 再**发消息**（etcd **库**的 Ready 契约不区分消息类型；
   **etcd server v3.4+ 的接线是 leader 先发后存**，见下方说明与 §10）；
3. 再**apply** `committedEntries` 到状态机；
4. 最后 `advance`，让核心层推进 unstable / applied 游标。

> 第 2 步的顺序在 §3.2 被细化：
> **只有「承诺了持久化」的消息**（投票确认、日志追加确认）必须等落盘；
> 复制类消息（`MSG_APP`）可以先发出去 —— 见 §3.2 与 §9.1。
>
> **这里必须区分「库契约」与「server 接线」两层**（2026-10-29 更正，
> 旧版文档在这里写错了）：
>
> | 层 | 约束 | 依据 |
> |---|---|---|
> | **etcd 库**（`go.etcd.io/raft/v3`） | Ready 批次里的 `HardState` / `Entries` 要先持久化再 `Advance`；**复制类消息（`MSG_APP`）的发送时机不设约束**，但**确认类消息（`msgsAfterAppend`）由库自己扣住**，要等 `acceptReady`（= `Advance`）之后才进 Ready | `raft/node.go` 的 Ready 契约；`raft/rawnode.go:176-184` |
> | **etcd server**（v3.4 起） | **leader 先发后存**：`r.applyc <- ap` → `if islead { r.transport.Send(...) }` → `r.storage.Save(...)` | `etcdserver/raft.go:230 / :235 / :240 / :254`（v3.6.0；v3.5.0、v3.4.34 同） |
>
> 源码注释原文（`etcdserver/raft.go:235`）：
>
> ```
> // the leader can write to its disk in parallel with replicating to the followers and then
> // writing to their disks.
> // For more details, check raft thesis 10.2.1
> ```
>
> 也就是说 **etcd server 从 v3.4 起就是「send → save」**（与 dragonboat 同源），
> 并不是「save → send」。
> edap-raft 的 ④（复制消息早发、确认消息等落盘）就是把 **etcd server 层**的这个行为
> 搬了回来 —— Java 移植只移植了 raft **库**、没有移植 etcd **server** 的接线，
> 才把这个行为丢掉了（详见 §10「与 etcd 的对照（实测）」与 §9.8 ④）。

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

#### 本机同硬件对比：3 节点 + 真盘 + 20B 载荷（edap/etcd 侧）· 16B（dragonboat 侧）

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
| 载荷 | **20 字节**（4B clientId + 8B seq + 8B publishedAt）；dragonboat 侧 16 字节，etcd 侧 20 字节 |
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

> ⚠️ **这一批（campaign A/B/C/D）与 §10 的 campaign G 不是同一批，两批不可混用。**
> §10 是后来加的 **etcd 对照**（etcd 库 + etcd server 接线 vs edap-raft），
> 9 轮 × 4 窗口 × 5 变体一起跑；本小节的表只有 edap 与 dragonboat。
> **edap 与 dragonboat 的相对结论在两批里是稳的**（异步路径 1.27~2.58×），
> 但**绝对值有 run-to-run 噪声**，引用时必须写清是哪一批。

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

etcd **库**的 Ready 契约是「`HardState` / `Entries` 持久化之后才能 `Advance`」；
etcd **server v3.4 起的接线是 leader 先发后存**
（`etcdserver/raft.go:230` `r.applyc <- ap` → `:240` `r.transport.Send(...)`
→ `:254` `r.storage.Save(...)`）。
也就是说**同一个 Ready 批次里**，复制消息在落盘之前就发出去了，
「本节点落盘」与「对端落盘」是**重叠**的，不是严格串行的两次 fsync
（2026-10-29 更正：旧版文档在这里写的是「etcd 未落盘不发」，那是**库契约**，
不是 server 的接线，详见 §3.1）。

但 etcd 的**批次之间**仍然是串行的：`Advance` 在 `Save` **之后**才调用
（`etcdserver/raft.go:329`），而 raft 库在应用层调用 `Advance` 之前不会产出下一个 Ready
（`raft/node.go:354`：`if advancec == nil && n.rn.HasReady()`）。
于是**下一批**提案的复制消息要等**本批** fsync 完成后才发得出去 ——
这才是 etcd 的 Ready 周期被 fsync 占住的原因。
（§10.6 的 RTT 分解里 etcd 的「发布 → MSG_APP 发出」p50 ≈ 8.7~13ms，正好是一个落盘周期；
但那个数**不能拿来和 edap 的 ① 直接比** —— §10.6 用探针实测证明了两侧的 ① 不可比。）

复制类消息（`MSG_APP`）**不带任何持久化承诺** —— 它只说「我有这些日志」，
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

etcd **server v3.4+ 与 dragonboat 一样是 send → save**（不是「etcd 保守」）；
**edap-raft 与它们一致**：见 §3.2 的消息投递顺序表。

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

etcd **server v3.4+ 也这么干**（leader 的复制消息在 `Save` 之前发，
`etcdserver/raft.go:240`），但 dragonboat 把它固定在流水线的固定位置上，
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
**etcd server v3.4+ 的 leader 也是复制消息先发、确认消息等落盘**：
复制消息在 `Save` 之前发（`etcdserver/raft.go:240`），
确认类消息由 raft **库**自己扣住 —— `msgsAfterAppend` 要等应用层
`acceptReady`（= `Advance`）之后才进 Ready
（`raft/rawnode.go:176-184`：「The Ready struct contract mandates that Messages
cannot be sent until after Entries are written to stable storage」）。
这一条 edap-raft 是把 etcd **server 层**的行为搬回来的（Java 移植只移植了 raft **库**）。
复制类消息（`MSG_APP`）不带持久化承诺，先发出去让对端的 fsync 与本节点的 fsync 重叠；
确认类消息（`MSG_APP_RESP` / `MSG_VOTE_RESP`）必须等落盘。
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

---

## 10. 与 etcd 的对照（实测）

### 10.1 为什么对照的是 etcd，而不是再测一次 dragonboat

**etcd 才是 edap-raft 的血缘起点。** §2 写得很清楚：edap-raft 是 `go.etcd.io/raft`
的**逐行 Java 移植**，etcd 的单元测试也逐条移植了一遍。

所以 §9 那个「edap-raft 领先 dragonboat 1.34~2.35×」的结论有个前提问题：
dragonboat 是 **raft 核心重写 + 自有驱动架构**（`internal/raft/`、free-order 消息、
独立 apply 流水线），它跟 edap-raft **不共享任何架构前提**。
「edap-raft 比 dragonboat 快」回答不了「edap-raft 的移植路线选对了吗」。

etcd 本体才回答得了这个问题 —— 因为 edap-raft 就是从它移植来的：
**edap-raft 相对 etcd 库 + etcd server 接线，多做对了什么、少做对了什么。**

> **这一节测的是「etcd 的驱动架构 vs edap-raft 的驱动架构」，不是 Go vs Java。**
> 唯一的语言差异（Go / JVM）被更大的架构差异盖住了 ——
> 读结论时不要把它读成「Java 比 Go 快」或「Go 比 Java 快」。

### 10.2 对照的是什么（不是完整的 etcd server）

| 组件 | 用的什么 | 依据 |
|---|---|---|
| raft 核心 | `go.etcd.io/raft/v3` **v3.6.0 原库**（不改一行） | — |
| 存储 | `go.etcd.io/etcd/server/v3` v3.6.0 的 `storage/wal`（真 WAL，一次 `Save` 一次 fsync） | `storage/wal/wal.go` |
| Ready 循环 | etcd server 的顺序：`applyc <- ap` → leader 先 `transport.Send` → `storage.Save` → `Advance` | `etcdserver/raft.go:230 / :240 / :254 / :329` |
| apply | 独立 apply goroutine + `notifyc` 门控 | `etcdserver/server.go:977-997` |
| 传输 | 进程内直投（每对端一条带缓冲 channel + 一个投递 goroutine 调 `peer.Step`） | 见下 |
| **不做** | mvcc / backend / grpc / snapshotter / `AsyncStorageWrites` / etcd 客户端 API | — |

**传输为什么是「异步直投」而不是同步 `Step`**：`raft.Node.Step` 是**阻塞**的
（要等对端 raft run loop 收下消息）。同步调用会让**发送方的 Ready 循环卡在对端的 fsync 上**
—— 测出来的是「假串行」，不是 etcd 的行为。所以每对端一条带缓冲 channel（cap 65536）
+ 一个投递 goroutine 调 `peer.Step(ctx, m)`。这与 etcd 的 rafthttp 流水线一致
（`transport.Send` 本身也是异步的），也与 Java 侧 `direct` 模式语义一致
（`Node.onMessage` 是 `recvc.add(m)`，`recvc` 是无界 `LinkedBlockingQueue`，`recvc.add` 不阻塞
—— `Node.java:111,451`）。

**基准程序**：`edap-raft-parent/edap-raft/src/test/go/etcdbench/main.go`
（Go 1.27.3；与 Java 侧的 `ClusterProposalHarness` 并列放在 `src/test/go/` 下，
不进 Maven 构建）。

### 10.3 两个变体的配置

| 项 | `etcd-aligned` | `etcd`（出厂） |
|---|---|---|
| `CheckQuorum` | false | **true** |
| `PreVote` | false | **true** |
| `MaxSizePerMsg` | 无限（`NO_LIMIT`） | 1MB |
| `MaxInflightMsgs` | 256 | 512（`4096/8`） |
| `ElectionTick` / `HeartbeatTick` | 10 / 1 | 10 / 1 |
| tick 间隔 | 50ms | 100ms |
| 依据 | 与 `ClusterProposalHarness` 的 `RaftConfig` **逐项对齐** | etcd server v3.6 出厂（`bootstrap.go:522-533`、`raft.go:37,40`） |

`etcd-aligned` 是**主对比**（消掉配置差异，只留驱动架构差异）；
`etcd` 是「etcd 按自己默认配置跑是什么样」。

**两个变体的 `MaxUncommittedEntriesSize` 都没设**（= `noLimit`，`etcdserver/raft.go:311-312`），
Java 侧同样没设（`RaftConfig.maxUncommittedEntriesSize` 默认 `NO_LIMIT`）——
**两侧的 L2 背压都是关的**，L2 不构成差异。

### 10.4 口径对齐表

| 维度 | Java（`ClusterProposalHarness`） | Go（`etcdbench`） | 对齐？ |
|---|---|---|---|
| 集群 | 3 节点、单进程、3 副本 | 同 | ✓ |
| 客户端 | 16 个线程，各自维护在途窗口 | 同 | ✓ |
| 载荷 | **20B** = 4B clientId + 8B seq + 8B publishedAt | 同（`binary.BigEndian`） | ✓ |
| 提案目标 | 当前 leader | 同（`rd.SoftState.Lead`） | ✓ |
| 「完成」的定义 | 提案**被提交**（future 在 `completePendingProposals` 完成） | 提案**被提交**（leader 在 Ready 循环里看到 `CommittedEntries`） | ✓ |
| 延迟采样点 | leader **应用**时采样「发布→应用」 | leader 的 apply goroutine 采样 | ✓ |
| 分位数公式 | `ceil(p/100*n)-1` | 同 | ✓ |
| 延迟记录器容量 | 400,000 样本 | 400,000 样本 | ✓（但见 §10.4.3） |
| 传输 | 进程内直投（`recvc` 无界队列） | 进程内直投（每对端 cap 65536 channel） | ✓ |
| 提案被丢 | `RaftException("raft proposal dropped")` | `Propose` 返回 error | ✓ |
| 成员变更条目 | 不计入吞吐 | 不计入（Bootstrap 已把 voter 加进 tracker，再 `ApplyConfChange` 会重置 Progress） | ✓ |
| **背压** | L1 有界队列 2048（异步路径满 → 拒） | **无背压**（`propc` 无缓冲 → `Propose` 阻塞） | ✗ 见 §10.4.1 |
| **存储引擎** | 裸文件追加（单 `wal.bin`，整批一次 write + 一次 `force(true)`） | `storage/wal`（每条 entry 一次 protobuf 序列化 + 分段文件 + 64MB 预分配 + `fdatasync`） | ✗ 见 §10.4.2 |
| **RTT 分解的采样对象** | 只对**窗口里最老的那条**记 ①② | 对**每一条**已提交提案记 ①② | ✗ 见 §10.6 |

#### 10.4.1 背压：三种语义，拒绝率不能直接比

| 实现 | 语义 | 窗口 1024 的拒绝率（实测） |
|---|---|---|
| edap-raft | L1 队列满 → 异步路径**抛 `RaftBusyException` 拒掉**（同步路径 BLOCK 等位置） | **34.92%** |
| dragonboat | 提案队列满 → `ErrSystemBusy` | 43.30% |
| etcd | **没有背压**：`propc` 无缓冲 → `Propose` 阻塞，客户端被顶住 | **0%** |

etcd 的 0% **不是「更健壮」，是「把压力顶回给客户端」**：
`raft.Node.Propose` 会阻塞到 raft run loop 取走消息，
而 run loop 取走 propc **不需要等 fsync**（fsync 在 Ready 循环里），
所以 `Propose` 返回得很快 —— 但整条链路上**没有任何一处会拒绝**。
所以「拒绝率」这一列在三方之间**不是同口径**，只能看各自内部的语义。

#### 10.4.2 存储引擎：etcd 侧更重

`storage/wal` 的 `saveEntry` 对**每一条** entry 做一次 protobuf 序列化
（`pbutil.MustMarshal(e)`），**落在「落盘耗时」的计时之内**；
edap 侧的 `FileRaftStorage.save()` 没有这一层。
⇒ **两侧的「落盘耗时 / 次」不是完全同口径**：etcd 的 fsync 占比被高估、
非落盘开销被低估。§10.5.4 的推导受这一条影响。

#### 10.4.3 延迟样本的启动期偏差（两侧都有）

两侧的延迟记录器容量都是 **400,000 样本**。窗口 256 时 10 秒能产生约 115 万条样本，
窗口 1024 时约 226 万条 —— 所以高窗口下**记录器里只剩启动最初 3.5 秒 / 1.8 秒的样本**。
**高窗口的 p50/p99 是「启动期」的延迟，不是稳态延迟。**
这一条对三方同样成立，不影响横向比较，但**不要把高窗口的绝对值当稳态值引用**。

### 10.5 结果

> **数据批次：campaign G**（2026-10-29 晚，9 轮 × 4 窗口 × 5 变体 = 180 次运行，
> 全部在同一时段内交替完成，轮次奇偶调换变体顺序）。
> **与 §9.1 的表不是同一批**（§9.1 是 campaign A/B/C/D 批次），**两批不可混用**。
> 原始结果在 `/tmp/ab_result_g.txt`，脚本 `/tmp/ab_run_g.sh`，解析器 `/tmp/parse_g.py`
> （`/tmp` 不保证长期保留 —— **下面的表就是完整快照**）。

> **⚠️ 本节的 etcd 基准用的是「异步等提交」的客户端语义，而 etcd server 的真实客户端语义
> 是「同步等应用」**（核实见 §10.9.1）。⇒ **本节「edap 领先 etcd 1.38~1.91×」只在
> 两边都用异步完成点时成立**；真实客户端语义下的对比见 **§10.9**。

#### 10.5.1 提交/s（9 轮中位数）

| 在途窗口（每客户端） | edap-direct | edap-tcp | dragonboat | **etcd-aligned** | etcd | edap / etcd-aligned | edap / dragonboat |
|---|---|---|---|---|---|---|---|
| 1 | 741 | 731 | 361 | 538 | 500 | **1.38×** | 2.05× |
| 16 | 14,475 | 12,532 | 5,621 | 7,575 | 6,842 | **1.91×** | 2.58× |
| 256 | 115,521 | 116,021 | 90,803 | 81,100 | 75,377 | **1.42×** | 1.27× |
| 1024 | 225,756 | 218,932 | 148,914 | **233,152** | 215,323 | **0.97×** | 1.52× |

**窗口 1024 时 `etcd-aligned` 反超 3.3%**（对 `etcd` 出厂配置仍落后 4.8%）。

#### 10.5.2 端到端延迟（发布→提交）

| 在途窗口 | edap-direct p50/p99 | edap-tcp p50/p99 | dragonboat p50/p99 | etcd-aligned p50/p99 | etcd p50/p99 |
|---|---|---|---|---|---|
| 1 | **18.9 / 43.0** | 21.1 / 42.2 | 41.1 / 72.0 | 27.2 / 39.0 | 27.0 / 39.0 |
| 16 | **13.0 / 42.3** | 14.9 / 41.5 | 42.3 / 78.9 | 30.8 / 50.1 | 32.7 / 46.7 |
| 256 | **35.7 / 55.7** | 36.0 / 55.8 | 42.0 / 83.0 | 47.3 / 56.4 | 47.4 / 56.9 |
| 1024 | 64.6 / 96.7 | 66.2 / 106.9 | **48.6 / 101.8** | 61.5 / 70.3 | 63.9 / 72.5 |

（单位 ms。「发布→应用」的 p50：edap-direct 18.9 / 13.6 / 38.4 / 66.2；
etcd-aligned 27.3 / 30.3 / 44.5 / 46.0。）

**p99 上 etcd 在窗口 1 与 1024 更好**（39.0 vs 43.0、70.3 vs 96.7），
**p50 上窗口 1024 的 etcd 也更低**（61.5 vs 64.6）—— 与 §10.5.1 的吞吐反超是同一件事。

#### 10.5.3 拒绝率（口径不同，见 §10.4.1）

| 在途窗口 | edap-direct | edap-tcp | dragonboat | etcd-aligned | etcd |
|---|---|---|---|---|---|
| 1 | 0 | 0 | 0 | 0 | 0 |
| 16 | 0.0007% | 0 | 0 | 0 | 0 |
| 256 | 3.70% | 2.43% | 0 | 0 | 0 |
| 1024 | **34.92%** | 34.46% | 43.30% | 0 | 0 |

窗口 1024 时 edap 的**发布尝试**是 `226,271 + 121,050 ≈ 347K/s`，
其中 35% 在 API 边界就被拒掉了（**根本没进 raft 日志**）；
etcd 的发布是 234K/s、全部进日志并被提交。
**两边的「客户端实际发出的请求数」差 48%**，只看「提交/s」会看不出这一点。

#### 10.5.4 fsync 账单（3 节点合计，9 轮中位数）

| 在途窗口 | edap-direct | edap-tcp | dragonboat | etcd-aligned | etcd |
|---|---|---|---|---|---|
| 1 | 250/s · 9.0 条 · 11.52ms | 253/s · 8.1 条 · 11.24ms | 249/s · 4.3 条 · — | 302/s · 5.4 条 · 9.35ms | 274/s · 5.4 条 · 9.43ms |
| 16 | 253/s · 174.1 条 · 10.90ms | 254/s · 152.2 条 · 10.75ms | 239/s · 70.6 条 · — | 270/s · 84.1 条 · 10.22ms | 250/s · 82.4 条 · 10.38ms |
| 256 | 185/s · 1873.4 条 · 10.23ms | 193/s · 1868.1 条 · 9.77ms | 230/s · 1176.7 条 · — | 181/s · 1343.6 条 · 15.12ms | 171/s · 1334.2 条 · 15.04ms |
| 1024 | 146/s · 4572.4 条 · 9.43ms | 150/s · 4357.9 条 · 9.41ms | 217/s · 2091.7 条 · — | 167/s · 4222.3 条 · 16.12ms | 161/s · 4062.0 条 · 15.74ms |

**模型自查**（`落盘条目/s = fsync/s × 条/次落盘`，`提交/s = 落盘条目/s ÷ 3`）——
20 个格子全部 **|偏差| ≤ 6.6%**；18 个格子在 ±2.8% 以内，
两个超出的都在 **edap-tcp** 上（`w=1` 是 **-6.6%**、`w=256` 是 +3.6%）。
窗口 1 偏差大是因为那一次落盘只带 8.1 条，**fsync/s 与「条/次落盘」两个中位数分开取，
乘起来不等于中位数的乘积** —— 低窗口下这个模型本来就粗。

**推导出的 leader 落盘周期**（`周期 = 3 / fsync/s`，即每节点的 fsync 间隔；
并用「落盘耗时」拆出周期里的落盘与非落盘部分）：

| 在途窗口 | edap-direct 周期 | 其中落盘 | **非落盘** | etcd-aligned 周期 | 其中落盘 | **非落盘** |
|---|---|---|---|---|---|---|
| 1 | 12.0ms | 11.52ms | 0.5ms | 9.9ms | 9.35ms | 0.6ms |
| 16 | 11.9ms | 10.90ms | 1.0ms | 11.1ms | 10.22ms | 0.9ms |
| 256 | 16.2ms | 10.23ms | **6.0ms** | 16.6ms | 15.12ms | 1.5ms |
| 1024 | **20.5ms** | 9.43ms | **11.1ms** | **18.0ms** | 16.12ms | 1.9ms |

> 口径提醒：etcd 的「落盘耗时」里含每条 entry 的 protobuf 序列化（§10.4.2），
> 所以 etcd 的 fsync 占比被高估、非落盘开销被低估 —— 上表对 etcd 是**保守**的。

**这张表是本节最有信息量的一张**：

- 窗口 1/16 时两侧的周期是同一个量级（10~12ms），edap 领先 1.38~1.91× **不是因为落盘更快**，
  而是因为**同样的落盘成本下 edap 装得下更多的在途提案**
  （w=16 时 edap 的 `条/次落盘` 是 etcd 的 2.07 倍）。
- 窗口 256/1024 时 **edap 的周期里出现了 6~11ms 的非落盘开销**，
  而 etcd 的周期几乎全是落盘（89~91%）。**这 11ms 是 edap 在高窗口被反超的直接原因**：
  20.5ms 的周期 × 4572 条 = 223K/s，18.0ms 的周期 × 4222 条 = 235K/s。
- **这 11ms 的成因尚未定位**（见 §10.7 的「未定位」条目）。

### 10.6 RTT 分解：① 的绝对值**不可跨实现比较**

| 在途窗口 | ① 发布→MSG_APP发出 p50（edap-direct） | ① p50（etcd-aligned） | ② MSG_APP发出→提交 p50（edap-direct） | ② p50（etcd-aligned） |
|---|---|---|---|---|
| 1 | 0.14ms | 8.77ms | 16.12ms | 18.08ms |
| 16 | 0.55ms | 8.74ms | 12.58ms | 21.07ms |
| 256 | 9.47ms | 13.13ms | 26.01ms | 31.35ms |
| 1024 | 20.34ms | 11.48ms | 45.70ms | 34.16ms |

**看起来 edap 的 ① 小一个数量级 —— 但这个数不能这么读。**

**探针实验**（`-Dedap.rtt.probe=true`，加在 `ClusterProposalHarness` 上，只加计数器，
不改任何行为）：

| 配置 | 提交/s | ① p50 | 探针独立测的「复制消息里所有条目的年龄」p50 | MSG_APP 发送间隔 p50 | 各节点落盘占用率 |
|---|---|---|---|---|---|
| w=16，**不**开同步探针 | 13,544/s | 0.62ms | **0.630ms** | 10.76ms | 88.8~94.0% |
| w=256，**不**开同步探针 | 110,660/s | 10.07ms | **11.140ms** | 8.19~8.33ms | 58.2~71.1% |
| w=16，**开**同步探针线程 | 7,142/s | **11.64ms** | **11.632ms** | 0.02~0.05ms | 95.7~97.8% |

三点结论：

1. **① 不是采样口径造成的假数**：「复制消息里所有条目的年龄」是**独立于客户端簿记**的
   第二个度量（发布时刻随载荷一起走），它与 ① 逐项吻合（0.630 vs 0.62、11.632 vs 11.64）。
2. **同一个 Java 二进制、同一个配置，① 只因为「多了一个用同步 `propose()` 发提案的线程」
   就从 0.62ms 跳到 11.64ms（18.8×）**，吞吐同时腰斩（13,544 → 7,142/s）。
   ⇒ **① 强烈依赖「谁在什么时候发布提案」与「leader 什么时候组装下一批」的相位关系**，
   而这个相位关系是实现细节（何时通知客户端、提案如何合批、组装是否被 fsync 挡住）决定的。
   edap 的 ① 小，是因为**异步客户端被通知后立刻重发**，而下一轮 Ready 的组装又不受
   正在进行的 fsync 阻塞（落盘在独立线程上）；etcd 的 Ready 循环是**单 goroutine**，
   `Save`（含 fsync）就在这个 goroutine 上，下一轮 Ready 必须等 fsync 结束
   （`raft/node.go:354`）—— 所以 ① 天然就是「一个落盘周期」。
3. ⇒ **跨实现只有「端到端总延迟」可比，① / ② 的绝对值不可比。**
   上表里的 ① / ② 只用于**同一实现内部**做归因。

（探针线程用同步 `propose()`，是因为异步接口不暴露「状态机接收提案」的时刻；
它会打乱相位、扰动吞吐，所以**默认关闭**，只在诊断时开。）

### 10.7 归因

#### 实测（campaign G，9 轮中位数）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **窗口 1/16/256 时 edap 领先 etcd-aligned 1.38× / 1.91× / 1.42×** | §10.5.1 |
| 2 | **窗口 1024 时 etcd-aligned 反超 3.3%**（对 etcd 出厂配置仍落后 4.8%） | §10.5.1 |
| 3 | **低/中窗口的领先不是「落盘更快」**：两侧落盘周期是同一个量级（10~12ms），差在**同一个落盘周期里装多少条**（w=16：edap 174 条/次 vs etcd 84 条/次） | §10.5.4 |
| 4 | **高窗口 edap 的落盘周期里多出 6~11ms 的非落盘开销**（etcd 只有 1.5~1.9ms），这是被反超的直接原因 | §10.5.4 |
| 5 | **etcd 的 p99 在窗口 1 与 1024 更好**（39.0 vs 43.0、70.3 vs 96.7） | §10.5.2 |
| 6 | **背压语义是三种**：edap 拒（34.92%）、dragonboat 拒（43.30%）、etcd 阻塞（0%） | §10.4.1、§10.5.3 |
| 7 | **窗口 1024 时 edap 的「客户端请求数」比 etcd 多 48%**（347K/s vs 234K/s），因为它拒掉了 35%；只看「提交/s」看不出来 | §10.5.3 |
| 8 | **①/② 不可跨实现比较**（探针实测：同一二进制 ① 可差 18×） | §10.6 |

#### 推演（**未验证**，与上面的实测分开）

- **推演 A**：etcd 的 Ready 循环把 `Save`（fsync）放在同一个 goroutine 上，
  批次之间必然串行；edap 把落盘挪到独立线程后，**组装与落盘解耦**。
  如果 edap 能在保持解耦的同时把「下一轮组装」也做得更快，
  高窗口应该能重新拉开 —— **本轮没有验证这一点**。
- **推演 B**：etcd v3.6 有实验性的 `AsyncStorageWrites`（默认关，Ready/Advance 契约不同）。
  打开它之后 etcd 的周期大概会像 edap 一样 —— **本轮没测**。
- **推演 C**：edap 高窗口那 11ms 的非落盘开销，如果来自「提案合批等待」，
  那它应该随窗口增大而线性增长；实测 w=1/16 时只有 0.5~1.0ms、w=256 时 6.0ms、
  w=1024 时 11.1ms —— **形状像但没有直接证据**。

#### 未定位（明确标出来）

- **edap 在窗口 256/1024 的 Ready 周期里那 6~11ms 非落盘开销，具体花在哪，
  本轮没有定位。** 候选：提案合批的等待、apply 线程的交接、`Advance` 的时序、
  或者「组装闸门被上一次 fsync 挡住」。需要单独做一次分阶段计时才能定论。
- **两侧「落盘耗时」的 fsync / 序列化拆分**没有分别计时（etcd 侧 protobuf 序列化混在里面），
  所以 §10.5.4 的推导对 etcd 是保守估计。

### 10.8 小结

1. **etcd 的移植路线没有走错**：把 etcd server 的接线（leader 先发后存）搬回 Java 之后，
   edap-raft 在**低/中窗口**（在途 1/16/256）比 etcd 本体快 **1.38~1.91×**，
   延迟 p50 也更低（w=16：13.0ms vs 30.8ms）。
   （**前提**：两边都用「异步等提交」的客户端语义 —— 换成 etcd server 的真实语义
   「同步等应用」后这个领先基本消失，见 §10.9。）
2. **高窗口（1024）edap 反而落后 3.3%**，原因定位到「edap 的落盘周期里有 6~11ms
   非落盘开销，而 etcd 的周期几乎全是落盘」—— **具体成因未定位**。
3. **edap 独有的背压在两侧都不是免费的**：它让窗口 1024 时 edap 能扛住 48% 更多的客户端请求，
   代价是 35% 的提案被拒（etcd 是阻塞、dragonboat 是拒 43%）。
4. **这一节测的是架构差异，不是语言差异。** Java / Go 的差异在这套对比里被架构差异盖住了，
   不要拿它去论证「Java 比 Go 快」。

> **⚠️ §10.5 的 etcd 数字用的是「异步等提交」的客户端语义，
> 而 etcd server 的真实客户端语义是「同步等应用」** —— 两者的差别见 §10.9。
> 读 §10.5 的结论前先看 §10.9.1。

---

### 10.9 追问：如果 edap-raft 也采用「同步提交」语义呢（campaign H2）

#### 10.9.1 为什么这个问题必须问

**§10.5 的 etcd 基准用的是「异步等提交」的客户端语义 —— 但 etcd server 的真实客户端语义本来就是同步的。**

代码依据（etcd server v3.6.0，**这是核实代码，不是推测**）：

| 环节 | 位置 | 事实 |
|---|---|---|
| 客户端写请求 | `etcdserver/v3_server.go:739-798` `processInternalRaftRequestOnce` | `s.r.Propose(cctx, data)` 之后 `select { case x := <-ch: ... }` —— 等的是 `ch` |
| `ch` 何时被触发 | `etcdserver/server.go:1988` / `:2007` `s.w.Trigger(id, ar)` | 在 **apply 路径**里（`applyEntryNormal`），即**条目已写进 backend** 才唤醒客户端 |
| apply 与 fsync 的先后 | `etcdserver/server.go:977-990` `applyAll` | `applyEntries` → `s.applyWait.Trigger(ep.appliedi)`（:983）→ **`<-apply.notifyc`（:988，等 fsync）在 Trigger 之后** |
| apply 与 fsync 并行 | `etcdserver/raft.go:230` / `:240` / `:254` | Ready 循环里 `r.applyc <- ap`（:230，投递 apply）→ `r.transport.Send(...)`（:240）→ `r.storage.Save(...)`（:254，fsync）：**投递 apply 在 fsync 之前** |

⇒ **etcd 的「应用」不等 fsync**（apply 与 fsync 并行，`applyWait.Trigger` 早于 `<-apply.notifyc`）。
⇒ 所以在 etcd 里，**「等应用」≈「等提交」**（只差一个 apply 协程的调度时间）。

**这条更正的影响**：§10.5 测的「edap-raft 领先 etcd 1.38~1.91×」是**两边都用异步语义**得到的，
而**真实客户端语义（同步等应用）下的对比，§10 缺一块**。本节补上。

#### 10.9.2 实验设计：把「完成点」和「提案入口是否阻塞」拆成两轴

只看「异步等提交」和「同步等应用」两个变体的话，**「完成点变了」和「提案入口阻塞了」两个因素同时变**，
结论无法归因。所以本节做**正交两轴**：

| 变体 | 实现 | 提案入口 | 完成点 | 提案队列满时 |
|---|---|---|---|---|
| `edap-async` | Java `async-commit` | `proposeAsync`（**不阻塞**，入队即返回） | **被提交** | REJECT（抛 `RaftBusyException`） |
| `edap-asyncapply` | Java `async-apply`（**本轮新增**） | `proposeAsync`（**不阻塞**） | **被应用** | REJECT |
| `edap-sync` | Java `sync-commit` | `propose()`（**阻塞**，等 raft 核心收下） | **被应用** | SYNC（阻塞等位置） |
| `etcd-aligned-async` | Go `async` | `raft.Node.Propose`（阻塞到 run loop 取走） | **被提交** | 无背压（阻塞） |
| `etcd-aligned-sync` | Go `sync`（**本轮新增**） | 同上 | **被应用** | 无背压（阻塞） |
| `etcd-async` | Go `async` | 同上 | **被提交** | 无背压（阻塞） |
| `etcd-sync` | Go `sync` | 同上 | **被应用** | 无背压（阻塞） |

`edap-asyncapply` 是**只为了隔离一个变量**而加的：
它和 `edap-sync` 只差「提案入口是否阻塞」这一项（完成点都是「被应用」）；
它和 `edap-async` 只差「完成点」这一项（提案入口都不阻塞）。

**代码改动（只动测试代码，生产代码零改动）**：

- Java `ClusterProposalHarness`：新增 `async-apply` 模式
  （模式解析 `ClusterProposalHarness.java:420-425`，`runSyncClient` 增加 `nonBlockingPropose` 分支 `:846`）。
- Go `etcdbench/main.go`：新增 `sync` 模式
  （`cluster` 增加 `notifyApply []chan notice` + `syncMode`，apply 协程在 sync 分支发通知，
  客户端循环改读 `notifyApply`；CLI 接受 `async` / `sync`）。

**其余口径全部沿用 §10.4**（3 节点 / 16 客户端 / 20B 载荷 / 每客户端在途窗口 1/16/256/1024 /
进程内直投 / `framework+apply` 落盘模式 / etcd-aligned 与 etcd 两套配置）。

#### 10.9.3 结果

> **数据批次：campaign H2**（2026-10-30 早，9 轮 × 4 窗口 × 7 变体 = **252 次运行**，
> 全部在同一时段内交替完成，轮次奇偶调换变体顺序）。
> **与 §10.5 的 campaign G 不是同一批，两批不可混用**。
> 脚本 `/tmp/ab_run_h2.sh`，结果 `/tmp/ab_result_h2.txt`，解析器 `/tmp/parse_h2.py`
> （`/tmp` 不保证长期保留 —— **下面的表就是完整快照**）。

##### 10.9.3.1 提交/s（9 轮中位数）

| 在途窗口 | edap-async | edap-asyncapply | edap-sync | etcd-aligned-async | etcd-aligned-sync | etcd-async | etcd-sync |
|---|---|---|---|---|---|---|---|
| 1 | **822** | 451 | 462 | 558 | 560 | 531 | 537 |
| 16 | **14,561** | 6,630 | 6,605 | 6,508 | 6,050 | 5,829 | 6,601 |
| 256 | **118,696** | 104,127 | 102,321 | 80,970 | 83,101 | 81,985 | 80,855 |
| 1024 | 225,197 | 167,191 | 187,169 | **241,411** | **241,657** | 228,813 | 238,670 |

**同步语义下 edap 相对 etcd 的优势基本消失**（比值，edap 提交/s ÷ etcd 提交/s）：

| 在途窗口 | 异步语义 edap-async / etcd-aligned-async | **同步语义 edap-sync / etcd-aligned-sync** | 同步语义 edap-sync / etcd-sync |
|---|---|---|---|
| 1 | **1.47×** | 0.83× | 0.86× |
| 16 | **2.24×** | 1.09× | 1.00× |
| 256 | **1.47×** | 1.23× | **1.27×** |
| 1024 | 0.93× | 0.78× | 0.78× |

⇒ 异步语义下 edap 的领先是 **1.47~2.24×**；同步语义下变成 **0.78~1.23×**（只有 w=256 还领先）。

##### 10.9.3.2 端到端延迟（发布→应用，ms，9 轮中位数）

| 在途窗口 | edap-async | edap-asyncapply | edap-sync | etcd-aligned-async | etcd-aligned-sync | etcd-async | etcd-sync |
|---|---|---|---|---|---|---|---|
| 1 | **14.77** / 41.40 | 36.76 / 43.62 | 36.10 / 47.04 | 26.39 / 38.36 | 25.79 / 37.13 | 27.28 / 43.26 | 26.57 / 39.09 |
| 16 | **13.85** / 46.07 | 37.37 / 59.44 | 34.41 / 53.78 | 35.49 / 62.09 | 36.59 / 66.48 | 38.34 / 67.72 | 33.92 / 59.69 |
| 256 | **38.05** / 56.04 | 201.74 / 938.71 | 33.70 / 45.25 | 44.58 / 54.69 | 43.84 / 52.05 | 44.56 / 55.11 | 44.48 / 53.12 |
| 1024 | 64.85 / 101.72 | 256.15 / 1068.69 | **42.41** / 63.90 | 46.00 / 59.64 | 44.86 / 57.34 | 44.61 / 57.82 | 44.20 / 57.53 |

（每格 `p50 / p99`。`edap-asyncapply` 在 w=256/1024 的 p99 是 938/1068ms —— 病态配置，见 §10.9.3.3。）

##### 10.9.3.3 拒绝率（%，9 轮中位数）

| 在途窗口 | edap-async | edap-asyncapply | edap-sync | etcd（4 个变体） |
|---|---|---|---|---|
| 1 | 0 | 0 | 0 | 0 |
| 16 | 0 | 0 | 0 | 0 |
| 256 | 2.93 | **83.19** | 0 | 0 |
| 1024 | 35.06 | **83.15** | 0 | 0 |

**`edap-asyncapply` 在 w≥256 是病态配置**：`proposeAsync` 走 REJECT 策略，
而「等应用」让客户端补位变慢 → 队列被打爆 → 83% 的提案在 API 边界被拒
（w=1024 的失败数中位数是 **1,300 万次**）→ fsync 频率塌到 39/s、批次退化。
**这一列的两个 83% 不能当成「async-apply 的正常表现」**，
它说明的是「**异步提案入口 + 慢完成点**」这个组合会把队列打爆。

##### 10.9.3.4 fsync 账单（3 节点合计，9 轮中位数）

| 在途窗口 | edap-async | edap-asyncapply | edap-sync | etcd-aligned-async | etcd-aligned-sync | etcd-async | etcd-sync |
|---|---|---|---|---|---|---|---|
| 1 | 256/s · 9.7 条 · 11.19ms | 249/s · 5.5 条 · 12.00ms | 249/s · 5.6 条 · 11.98ms | 314/s · 5.4 条 · 9.00ms | 315/s · 5.4 条 · 8.78ms | 297/s · 5.4 条 · 9.37ms | 301/s · 5.4 条 · 9.13ms |
| 16 | 243/s · **174.1** 条 · 10.91ms | 225/s · 88.6 条 · 12.68ms | 226/s · 87.7 条 · 12.80ms | 231/s · 83.5 条 · 11.76ms | 220/s · 84.9 条 · 12.51ms | 211/s · 82.9 条 · 12.75ms | 235/s · 84.1 条 · 11.62ms |
| 256 | 185/s · **1,922.0** 条 · 10.10ms | 45/s · 11,433.9 条 · 12.52ms | 230/s · 1,349.4 条 · 11.39ms | 183/s · 1,347.2 条 · 15.06ms | 186/s · 1,349.1 条 · 14.92ms | 184/s · 1,342.0 条 · 15.14ms | 180/s · 1,344.0 条 · 15.38ms |
| 1024 | 150/s · **4,454.4** 条 · 9.38ms | 39/s · 15,110.1 条 · 14.11ms | 195/s · 2,891.1 条 · 11.07ms | 169/s · 4,315.0 条 · 16.04ms | 167/s · 4,346.3 条 · 15.97ms | 168/s · 4,143.3 条 · 15.88ms | 170/s · 4,169.6 条 · 15.91ms |

（`fsync/s · 条/次落盘 · 落盘耗时 ms/次`。）

**这张表是本节最有信息量的一张**：w=16 时 7 个变体的 fsync 频率都在 211~243/s
（**同一个量级**），但 `edap-async` 的批次是 **174.1 条**，
其余 6 个变体全部落在 **82.9~88.6 条** —— **批次小一半，吞吐就小一半**（14,561 vs 6,630）。

##### 10.9.3.5 模型自查

（`落盘条目/s = fsync/s × 条/次落盘`，`提交/s = 落盘条目/s ÷ 3`）

**28 个格子中 26 个 |偏差| ≤ 3.2%**；两个超出的都在 `edap-asyncapply`：
w=256 是 **+64.7%**、w=1024 是 **+17.5%** —— 这正是 §10.9.3.3 说的病态配置
（83% 拒绝让批次与 fsync 频率同时退化，两个中位数分开取、乘起来不等于中位数的乘积）。

#### 10.9.4 归因

##### 实测（campaign H2）

| # | 结论 | 证据 |
|---|---|---|
| 1 | **决定性因素是「完成点」，不是「提案入口是否阻塞」**：`edap-asyncapply`（不阻塞 + 等应用）6,630 vs `edap-sync`（阻塞 + 等应用）6,605 @w=16 → **差 0.4%** | §10.9.3.1 |
| 2 | **但完成点的差别是 2.2×**：`edap-async`（不阻塞 + 等提交）14,561 vs `edap-asyncapply`（不阻塞 + 等应用）6,630 @w=16 | §10.9.3.1 |
| 3 | **直接机理是「批次大小」**：fsync 频率几乎相同（243 vs 225/s），批次 174.1 vs 88.6 条 → 吞吐 14,561 vs 6,630 | §10.9.3.4 |
| 4 | **etcd 侧完成点几乎无影响**：`etcd-aligned-async` 6,508 vs `etcd-aligned-sync` 6,050 @w=16（-7%）；w=1/256/1024 的比值是 0.996 / 0.974 / 0.999 | §10.9.3.1 |
| 5 | **这是多客户端效应**：1 客户端时同步与异步**完全一样**（86/s vs 85/s，延迟 11.61ms vs 11.86ms）；2/4/8/16 客户端差距依次 +4.3 / +8.7 / +10.8 / +13.5ms | 诊断实验（下表） |
| 6 | **etcd 的 apply 不等 fsync**：`applyWait.Trigger`（`server.go:983`）早于 `<-apply.notifyc`（`:988`）；`applyc` 投递（`raft.go:230`）早于 `Save`（`:254`） | §10.9.1 |
| 7 | **第三根轴是「提案队列溢出策略」**：`proposeAsync` → REJECT（深窗口 83% 拒绝）；`propose()` → SYNC（0% 拒绝）；etcd `Propose` → 阻塞（0% 拒绝） | §10.9.3.3 |
| 8 | **edap 相对 etcd 的优势在同步语义下基本消失**（异步 1.47~2.24× → 同步 0.78~1.23×） | §10.9.3.1 |

**诊断实验（1/2/4/8/16 客户端，w=1，Java `async-commit` vs `sync-commit`）**：

| 客户端数 | async 提交/s | async 延迟均值 | sync 提交/s | sync 延迟均值 | 延迟差 | async 条/次落盘 | sync 条/次落盘 |
|---|---|---|---|---|---|---|---|
| 1 | 85 | 11.86ms | 86 | 11.61ms | **~0** | 1.0 | 1.0 |
| 2 | 130 | 15.50ms | 101 | 19.84ms | +4.3ms | 1.6 | 1.3 |
| 4 | 183 | 21.93ms | 131 | 30.60ms | +8.7ms | 2.2 | 1.5 |
| 8 | 369 | 21.80ms | 245 | 32.62ms | +10.8ms | 4.6 | 2.9 |
| 16 | 734 | 21.90ms | 452 | 35.44ms | +13.5ms | 8.8 | 5.7 |

**1 客户端时完全没有差别**（吞吐 85 vs 86、延迟 11.86 vs 11.61ms），
**差别随客户端数单调增长**，而同步态的批次始终是异步态的 ~2/3。
⇒ 效应发生在「多客户端的补位」环节，不是「单条提案的路径延迟」。

##### 推演（**未验证**，与上面的实测分开）

- **推演 A**：edap 的「提交」完成点在**组装块内部**（`Node.java:976` `completePendingProposals(rdCommitted)`
  在 `persistq.add`（:1001）与 `applyq.add`（:995）**之前**），客户端被唤醒后立刻补位，
  新提案在**下一个组装块**开始前就落进 `propc` → 聚成一大批。
  而「应用」完成点在组装块**之外**（apply 线程 → `applyHandler.apply` → `notifyAll`），
  补位时刻相对组装边界**错开** → 提案摊到更多批次。
  这与 §10.6 的探针实验一致（同一个二进制、只加一个探针线程，① 就能差 18.8×，
  因为「谁在什么时候发布提案」与「leader 什么时候组装下一批」的相位关系是实现细节）。
  **本轮没有直接验证这个相位关系。**
- **推演 B**：etcd 侧没有这个效应，是因为 **Go 的 `Propose` 阻塞到 raft run loop 取走消息**，
  而 **run loop 与 Ready 循环是分离的**（run loop 不等 fsync，fsync 在 Ready 循环的 `Save` 里）
  ⇒ 释放→补位的时间窗口总是落在「下一批组装之前」，批次不会裂开。
  Java 的 `proposeAsync` 只是入队（`propc.add`），由**同一个** raft 线程在下一轮迭代顶部排空
  ⇒ 是否能进同一批取决于客户端唤醒时刻与组装边界的相对位置。
  **本轮没有直接验证这一点。**
- **推演 C**：edap 的「应用」点对**本批日志**是要等 fsync 的
  （`Node.java:1182-1194` `splitCommitted`：本批日志里的条目等落盘完成才应用），
  而 etcd 的 apply 与 fsync 并行。⇒ 在 edap 里「等应用」天然比「等提交」多一个 fsync 周期。
  **但本轮没有把这个因素单独隔离出来**（`async-apply` 与 `sync-commit` 的完成点都是「应用」，
  两者都含这个代价，所以 §10.9.4 的 #1 依然成立；
  只是「edap-async vs etcd-async」的比较里它被混在完成点这个大因素里了）。

##### 未定位（明确标出来）

- **Java 侧「提交 → 应用」的真实间隔没有测出来。** harness 里
  「发布→提交」样本是**每个客户端在途队列里最老的那条提案**（`ClusterProposalHarness.java:944-947`
  在 future 完成时采一次），「发布→应用」样本是 **leader 上每一条被应用的普通条目**
  （`:308-330` `ApplyStats.record`）—— **两个总体不同，不能相减**。
  §10.9.4 推演 C 里说的「多一个 fsync 周期」因此**只是代码推断，没有实测支撑**。
- **edap 的「应用」完成点为什么在 Go 侧没有同样的批次退化**（etcd 的完成点换了，吞吐几乎不变），
  本轮没有钉死。§10.9.4 推演 B 是候选解释，但未验证。
- **Go 基准的 `sync` 模式没有「发布→提交」这一行输出**（完成点就是应用，没有第二个采样点），
  Java 侧同理 —— 所以**两个同步变体之间无法比较「提交点延迟」**。

##### 口径警告

1. **延迟记录器 400,000 样本上限**（两侧同）：w=256/1024 时 10 秒产生的样本远超上限，
   记录器里只剩**启动最初 3.5s / 1.8s** 的样本 → **高窗口的 p50/p99 是「启动期」的延迟，不是稳态延迟**。
   （§10.4.3 已说明，此处同样成立。）
2. **`edap-asyncapply` 的 w=256/1024 是病态配置**（83% 拒绝），这一格的吞吐、延迟、
   fsync 账单都带病态特征，**不要拿来当「async-apply 的正常表现」引用**。
3. **拒绝率不是同口径**（§10.4.1）：edap 是「拒」，etcd 是「阻塞」。
   本节 `edap-sync` 与 etcd 各变体的拒绝率都是 0%，
   但含义不同 —— edap-sync 是「阻塞等位置」，etcd 是「阻塞在 `Propose`」。
4. **`edap-asyncapply` 用的是 REJECT 策略**（`proposeAsync` 的默认策略），
   这也是它在深窗口爆掉的原因之一；**本轮没有跑「async-apply + SYNC/BLOCK 策略」的对照组**，
   所以「async-apply 的病态是完成点造成的还是队列策略造成的」**没有完全分离**。

#### 10.9.5 小结

1. **用户的答案：换成同步提交语义，edap-raft 相对 etcd 的优势基本消失。**
   异步语义下 edap/etcd 是 1.47× / 2.24× / 1.47× / 0.93×（w=1/16/256/1024）；
   同步语义下 edap-sync/etcd-sync 是 **0.86× / 1.00× / 1.27× / 0.78×**。
2. **原因不是「同步本身慢」，而是 edap 的「完成点=应用」把批次打散了**：
   同样 fsync 频率（243 vs 225/s）下批次从 174.1 条掉到 88.6 条，
   同样的落盘预算被摊薄 → 吞吐减半、延迟翻倍。
3. **etcd 换不换完成点几乎无所谓**（±7%）—— 因为 **etcd 的 apply 不等 fsync**
   （`applyWait.Trigger` 在 `<-apply.notifyc` 之前），「等应用」在 etcd 里
   几乎等同于「等提交」。
4. **这修正了 §10 的结论范围**：§10 的「edap-raft 领先 etcd 1.38~1.91×」只在
   **两边都用异步完成点**时成立；**在 etcd server 的真实客户端语义（同步等应用）下，
   edap-raft 不再领先**。
   ⇒ **edap-raft 的异步提案 API 不是「锦上添花」，而是它当前能打赢 etcd 的前提条件**。
5. **推论（未实测）**：如果 edap-raft 要在同步语义下追平 etcd，
   要动的不是「提案入口」，而是「**应用完成点的释放时机与批次边界的对齐**」。
   §10.9.4 的推演 A/B 给出了两个候选方向，需要单独做一次相位对齐实验来验证。

---

## 10.10 性能排查：edap-raft 慢在哪里（campaign I / J，2026-10-30）

### 10.10.0 起因与结论摘要

§10.9 的结论是：edap-raft 在 etcd 的真实客户端语义（同步等应用）下不再领先，
而且「完成点=应用」会把落盘批次打散（174.1 条 → 88.6 条，同等 fsync 频率下吞吐减半）。
§10.9.5 的第 5 条把方向写清楚了：**要动的不是「提案入口」，
而是「应用完成点的释放时机与批次边界的对齐」**。

本节就是去做这件事，同时回答「**edap-raft 慢的原因在哪、还有哪里需要优化**」。

**结论（先说结果，每一条后面都有实测数据）**：

| # | 结论 | 证据 |
|---|---|---|
| 1 | leader 状态机线程上 **31.5% 的 CPU 样本花在 JVM 全局延迟队列的线性删除上** —— 根子是 `CompletableFuture.orTimeout` 注册的 `Canceller` 回调 | §10.10.2 |
| 2 | leader 线程另有 **20.5% 花在 `notifyProposalSpace`**（每次提交通知都 `notifyAll`）、**12.5% 在 `completePendingProposals`** | §10.10.2 |
| 3 | 去掉这三项开销后，**w=16 反而慢了 2.26×**（17,079 → 7,560 提交/s） | §10.10.3 |
| 4 | 慢的原因不是「CPU 不够」，而是**批次碎片化**：三个变体的 fsync 频率几乎一样（80.1 / 80.1 / 91.5 次/s），吞吐完全由「条/次落盘」决定（155.0 / 93.8 / 253.2） | §10.10.4 |
| 5 | 批大小是**自维持的正反馈**（上一批通知多少客户端 → 客户端补位 → 下一批多少条），**补位时刻与 leader 排空 `propc` 的相位关系**决定这一批是满批还是碎片批 | §10.10.4 |
| 6 | 修法是**碎片批次合并**（leader 把碎片批的落盘推迟，等客户端补位到齐）：w=1 **+85.4%**、w=16 **+54.0%**、w=256 **+10.9%**、w=1024 −5.3%（噪声内） | §10.10.6 |
| 7 | w=1024 没有提升 —— 那里批次本来就接近满（4,500~5,158 条/次），碎片不是瓶颈，限流的是提案队列满导致的 29~36% 拒绝 | §10.10.6 |
| 8 | 过程中撞上一次**停机挂起**（1/约 100 次运行）：`stop()` 无限期 `join()`，状态机线程停在 `park` 上没醒。根因未定位，已加保险 | §10.10.7 |
| 9 | **剩余可优化项**（按 JFR 占比排序）：`pendingProposals` 的逐条 map 删除（4.8%）、`handleProposals` 的数组拷贝（1.9%）、`Raft.payloadsSize` 每批重算（3.3%） | §10.10.8 |

**一句话**：**edap-raft 的瓶颈不在 raft 协议路径，而在「落盘批次的大小」与「提案完成点的相位」**。
§10.9 说「完成点=应用会把批次打散」，本节找到了这个效应的**机理**
（自维持正反馈 + 相位关系），并给出了修法（碎片批次合并）。

### 10.10.1 方法与变体定义

三个工具，三类证据：

| 工具 | 用途 | 产物 |
|---|---|---|
| **JFR**（`-XX:StartFlightRecording`） | 归因：leader 状态机线程的 CPU 到底花在哪 | `/tmp/raft_async1024.jfr`（1.4MB，2026-10-30 09:48） |
| **leader 侧插桩时间线** | 机理：每一批组装了多少条、什么时候落的盘 | `/tmp/tl_{base,opt,opt2}_16_node*.txt` |
| **campaign A/B**（同批交替、轮次奇偶对调） | 效果：改前改后的吞吐/延迟/拒绝率 | `/tmp/ab_result_i.txt`（campaign I）、`/tmp/campaign_J.txt`（campaign J） |

**变体定义（三个名字在两批 campaign 里都用到，先对齐）**：

| 名字 | 内容 | campaign I | campaign J |
|---|---|---|---|
| **base** | HEAD（未改） | `side=base` | `side=base` |
| **t1** | base + 去掉 `CompletableFuture.orTimeout`（改自维护超时清扫）+ 去掉冗余 `whenComplete` | `side=t1` | —— |
| **t1′** | t1 + `notifyProposalSpace` 只在有等待者时才 `notifyAll` | `side=opt` | —— |
| **t2** | t1′ + `Unstable.hasNextEntries()`（不复制列表判空）+ **碎片批次合并** | —— | `side=opt`（= 工作区） |

> 本节后面统一用 **base / t1 / t1′ / t2** 这四个名字。
> campaign I 的 `side=opt` 就是 t1′，campaign J 的 `side=opt` 就是 t2 —— **不要混**。

### 10.10.2 JFR 归因（实测，不是推测）

录制：3 节点真盘、16 客户端、w=1024、`framework+apply`、`direct` 传输，**改动前（base）的代码**。
被录的线程是 **leader 的状态机线程 `edap-raft-1`**（leader 是单线程，
提案的提交与应用都要经过它，所以它的 CPU 就是整个集群的瓶颈）。

样本数：**1,344 个 `jdk.ExecutionSample`**
（录制窗口 09:48:42.6 ~ 09:49:07.7，约 25s）。

**叶子帧分布（top 16）**：

| 占比 | 样本 | 叶子帧 |
|---|---|---|
| **28.6%** | 384 | `ScheduledThreadPoolExecutor$DelayedWorkQueue.siftDown` |
| **20.5%** | 276 | `io.edap.raft.Node.notifyProposalSpace` |
| **11.7%** | 157 | `io.edap.raft.Node$$Lambda$92.accept`（= `future.whenComplete(...)` 回调） |
| 4.8% | 65 | `ConcurrentHashMap.replaceNode` |
| 4.0% | 54 | `Unsafe.getAndBitwiseAndInt`（AQS 释放） |
| 2.9% | 39 | `ScheduledThreadPoolExecutor$DelayedWorkQueue.remove` |
| 2.9% | 39 | `CompletableFuture.postComplete` |
| 2.4% | 32 | `CompletableFuture.postFire` |
| 2.2% | 29 | `CompletableFuture.complete` |
| 2.0% | 27 | `ConcurrentHashMap.addCount` |
| 1.9% | 25 | `AbstractCollection.toArray` |
| 1.8% | 24 | `io.edap.raft.Raft.payloadsSize` |
| 1.5% | 20 | `io.edap.raft.RaftUtil.limitSize` |

**`io.edap.raft` 锚点（栈里任意位置出现即计入）**：

| 占比 | 帧 |
|---|---|
| 37.9% | `Node.run` |
| 26.3% | `Node.handleProposals` |
| **20.5%** | `Node.notifyProposalSpace` |
| 12.5% | `Node.completePendingProposals` |
| 1.9% | `Node.lambda$proposeAsync$0`（= `whenComplete` 回调） |

#### 10.10.2.1 第一热点：`orTimeout` 的 `Canceller`（31.5%）

**实测调用链（384 个 `siftDown` 样本中 384 个都命中这条链，100%）**：

```
0 java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.siftDown
1 java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.remove
2 java.util.concurrent.ThreadPoolExecutor.remove
3 java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask.cancel
4 java.util.concurrent.CompletableFuture$Canceller.accept
```

**机理**：

- `CompletableFuture.orTimeout(t, unit)` 会往 **JVM 全局共享**的
  `ScheduledThreadPoolExecutor` 延迟队列里注册一个定时任务；
- 提案**正常完成**时（leader 在 `completePendingProposals` 里 `future.complete(null)`），
  JDK 会执行 `Canceller` 回调 → `ScheduledFutureTask.cancel()` →
  `ThreadPoolExecutor.remove()` → **从延迟队列里线性扫描删除这个任务**；
- 延迟队列里堆积的定时任务越多，扫得越久 ⇒ **每条提案付一次 O(在途提案数) 的扫描**，
  整体退化成 **O(n²)**（n = 在途提案数 = 窗口深度）。

**为什么这一项会拖垮 leader**：延迟队列是 **JVM 全局**的，
所有节点、所有 future 的超时任务都在里面；leader 每提交一条提案就要在**自己的状态机线程上**
扫一遍全局队列。**在途窗口 1024 时，leader 一半的 CPU 花在这上面。**

⇒ **修法**：不用 `orTimeout`。改成自维护的超时 —— `PendingProposal` 里记 `deadlineNanos`，
一条守护线程（`edap-raft-timeout-<id>`）每 100ms 扫一遍待完成表
（表里只有「还没提交」的提案，提案一提交就被摘掉，比全局延迟队列短得多）。
**这次改动把全局 O(n²) 变成了本地 O(n)。**

#### 10.10.2.2 第二热点：`notifyProposalSpace`（20.5%）

调用点：`Node.completePendingProposals` → 每提交一条提案就 `notifyProposalSpace()`。
这个方法原来无条件 `notifyAll()`，即使**一个等待者都没有**也要走一遍 AQS 的释放路径
（JFR 里 `Unsafe.getAndBitwiseAndInt` ← `AQS.signalNext` ← `AQS.release` ← `ReentrantLock.unlock`
就是它，4.0%）。

⇒ **修法**：只在 `proposalSpaceWaiters.get() != 0` 时才 `notifyAll`。

#### 10.10.2.3 第三热点：冗余的 `whenComplete`（11.7% + 2.9% + 2.4% + 2.2% ≈ 17.2%）

`proposeAsync` 原来给每个 future 挂了一个 `whenComplete` 回调，
回调里做两件事：把提案从 `pendingProposals` 里摘掉、通知提案空间。
回调本身是 JDK 的 `CompletableFuture` 内部机制（`postComplete` → `postFire` → `accept`），
**每条提案都要付一次**；而且回调在**完成线程**上跑，也就是 leader 的状态机线程。

⇒ **修法**：`completePendingProposals` 里直接摘表，去掉 `whenComplete`。

### 10.10.3 第一轮改动之后：w=16 反而慢了 2.26×（campaign I）

**campaign I**（2026-10-30 10:28~10:45，10s/轮，变体顺序按轮次奇偶对调；
base/t1/opt 每格实际跑了 3~4 轮，etcd 两个参照点跑了 4 轮）：

| w | base 提交/s | t1 提交/s | t1′ 提交/s | t1′/base | base 条/次落盘 | t1′ 条/次落盘 | base p50 | t1′ p50 |
|---|---|---|---|---|---|---|---|---|
| 1 | 719 | 681 | 709 | 0.99 | 8.6 | 8.6 | 20.8ms | 21.2ms |
| 16 | **17,079** | 7,590 | **7,560** | **0.44** | **207.1** | **95.0** | **12.8ms** | **34.8ms** |
| 256 | 119,398 | 103,249 | 101,193 | 0.85 | 1,917.0 | 1,358.6 | 37.5ms | 40.5ms |
| 1024 | 217,900 | 249,710 | **250,209** | **1.15** | 4,632.3 | 5,435.4 | 65.3ms | 63.1ms |

（同一批的 Go 参照点：etcd-aligned-async 在 w=1/16/256/1024 是
554 / 8,205 / 81,726 / 234,930 提交/s。）

**w=16 慢了 2.26×，延迟从 12.8ms 涨到 34.8ms** —— 一次「去掉开销」的优化，
在最重要的中等窗口上是**净负收益**。w=1024 反而快了 14.8%
（那里开销本身就是瓶颈，批次本来就大）。

**这个反常结果就是本节的核心线索**：去掉 CPU 开销怎么会变慢？

### 10.10.4 根因：批次碎片化

#### 10.10.4.1 吞吐由「条/次落盘」决定，不是由 fsync 频率决定

恒等式（3 节点合计口径）：

```
落盘条目/s = 落盘次数/s × 条/次落盘
提交/s     = 落盘条目/s ÷ 3        （3 个节点各落一份同样的日志）
```

**leader 侧插桩（w=16，15.5~15.6s 窗口）**：

| 变体 | 组装轮次 | 非空落盘次数/s | 条/次落盘（非空均值） | 非空批分位 p10/p50/p90 | 落盘条目/s（leader 自己） |
|---|---|---|---|---|---|
| **base**（HEAD，node2） | 2,130 | **80.1** | **155.0** | **3 / 242 / 256** | 12,408 |
| **t1′**（去超时/回调，无碎片合并，node1） | 1,463 | **80.1** | **93.8** | **43 / 83 / 142** | 7,508 |
| **t2**（+碎片批次合并，node3） | 6,568 | **91.5** | **253.2** | **256 / 256 / 256** | 23,178 |

**⇒ fsync 频率在三个变体里几乎一样（80.1 / 80.1 / 91.5 次/s），
吞吐完全由「条/次落盘」决定（155.0 / 93.8 / 253.2）。**
leader 的 CPU 开销不直接决定吞吐 —— 它通过**批次大小**间接决定。

**批大小的分位形状是三种截然不同的分布**：

- **base：双稳态**（3 / 242 / 256）—— 要么只有零星几条，要么就是满批 256；
  中位数 242 意味着**一半以上的批次是满的**。
- **t1′：均匀碎片**（43 / 83 / 142）—— 中位数掉到 83，**批被打散了**。
- **t2：恒满批**（256 / 256 / 256）—— 最小的一批也是满的。

#### 10.10.4.2 批大小是自维持的正反馈

机制（**代码级**）：

1. leader 提交一批条目 → `completePendingProposals` 通知了 **N 个客户端**；
2. 这 N 个客户端各自补一条新提案（窗口模式：提交一条就补一条）；
3. 下一轮 leader 排空 `propc` 时，**能凑到多少条取决于这 N 条新提案
   在 leader 排空 `propc` 之前到了多少**；
4. 凑到得多 → 这一批大 → 这一批提交后通知的客户端更多 → 下一批更大。**正反馈。**

**所以「批大小」不是由系统能力决定的，是由「补位时刻 vs leader 排空 `propc` 的时刻」
这个相位关系决定的。**

- **base 的相位恰好对**：leader 每轮更慢（`orTimeout` 的 Canceller 扫描 + 冗余 `whenComplete`
  + 无条件 `notifyAll` 让它每轮多花几十微秒到几毫秒），
  客户端的补位**来得及在下一轮排空之前到齐** ⇒ 攒出满批。
- **t1′ 把这些开销都去掉之后**：leader 每轮更快，**排空 `propc` 的时刻提前了**，
  客户端的补位**来不及** ⇒ 这一批只拿到零星几条 ⇒ 下一批更小 ⇒ **恶性循环**。

⇒ **「让 leader 更快」这件事，本身会把批次打碎。**

#### 10.10.4.3 修正一条旧结论

上一轮排查（§10.9 之前）里曾经有过一个判断：

> 「base 更快是因为它 CPU 慢、错过补发反而攒出满批」—— 被判为**错误推演**并作废。

**本轮的证据表明：这个方向是对的，只是当时的表述把「因果」讲反了，而且它不稳定。**
准确的说法是：

- **它是一个真实的相位锁效应**，不是错觉：base 的额外开销确实起到了
  「拖慢 leader、给客户端补位留出时间窗」的作用；
- **但它是一个有害的巧合**：base 靠「意外的 CPU 开销」维持满批，
  意味着**批大小不受控**——同一份 base 代码，w=16 五轮分别是
  13,407 / 14,519 / 14,809 / 20,253 / 21,111 提交/s（**±23% 的抖动**），
  批次在「满批」和「碎片」两个稳态之间跳变（p10=3 vs p50=242）。

⇒ **正确做法不是「把开销加回去」，而是显式地控制相位**（下一节）。

### 10.10.5 修复：碎片批次合并

**思路**：既然「批大小」由相位决定，那就**显式地等**——
leader 提交一批之后，如果下一批只攒到零星几条，
**先不落盘**，等客户端把新提案补上来（或者等到一个时间上限），攒够一批再落盘。

**实现**（`Node.java`）：

| 位置 | 改动 |
|---|---|
| `completePendingProposals`（`:1489`） | 改成返回「本次实际通知到的客户端数」 |
| 主循环（`:1058` 附近） | 收到通知数记进 `expectBatchSize`（= 预计会到来的新提案条数） |
| `shouldDeferPersist`（`:1325`） | 判据：**本批条目数 < 上一批通知的客户端数** ⇒ 这是碎片批，推迟落盘 |
| 推迟的实现 | **丢弃这个 Ready**（`readyWithoutAccept()` 是只读的，下一轮重新组装，条目会更多）；消息还在状态机里（`acceptReady` 才会清空它们），下一轮的 Ready 会带上 |
| 参数 | 等待窗口 `DEFER_WAIT_MILLIS`=10ms；总上限 `DEFER_MAX_WAIT_MILLIS`=50ms；`DEFER_MIN_COMMITTED`=2 |

**安全性**（为什么丢弃 Ready 是安全的）：

- `readyWithoutAccept()` **不改动状态机**（只读地组装一份 Ready），
  丢弃它等于「这一轮先不交付」；
- 状态机里的条目、消息都还在（`acceptReady` 才会把它们摘走），
  下一轮重新组装的 Ready 会带上，而且**条目只会更多**；
- 只在 **leader** 上做（跟随者的批次由 leader 的复制消息决定，
  推迟落盘只会推迟自己的确认消息，反过来拖慢 leader 的提交）；
- 带**快照**或**线性一致读状态**的 Ready 不推迟（快照必须先进存储，读确认不能拖）；
- 空批次不推迟（没有日志要写，落盘不要钱）；
- 总等待有 50ms 上限 ⇒ 即使客户端不来补位，也不会一直拖。

**实测效果（leader 侧插桩，t2）**：非空批的分位变成 **256 / 256 / 256** ——
**每一批都是满批**；fsync 频率还从 80.1 涨到 91.5（批次大了，落盘次数自然多一点）。

#### 10.10.5.1 一条被丢弃的中间尝试（记录下来，因为它比不改还差）

碎片合并并不是一次写对的，中间还试过别的路子。
**2026-10-30 12:29 的中间构建（源码已丢失，只剩 `/tmp/classes_fix` 的字节码）
与最终版（`/tmp/classes_opt`，13:45）语义不同** ——
用 `javap -p -c` **归一化常量池索引后逐方法比对**才能看出来：

| | 中间版（`classes_fix`，12:29） | 最终版（`classes_opt`，13:45） |
|---|---|---|
| 主循环 `run` 里多出来的动作 | `rn.getRaft().getCommittedEntries()` → `completePendingProposals(List)`（**把提案的完成点提前到「raft 已提交」，不等 Ready 交付**） | 无此调用 |
| 碎片合并 | **没有**（无 `shouldDeferPersist`、无 `DEFER_*`） | `shouldDeferPersist(Ready, List, List)` + `LockSupport.parkNanos(DEFER_WAIT_MILLIS)` |
| 其余方法 | 与最终版**逐字节相同**（44 个方法里只有 `run` / `join` 不同） | — |

**同一批里的单次实测**（12:29，`base` / `opt` / `fix` 各跑 w=16、w=1024 一次）：

| 变体 | w | 提交/s | 条/次落盘 | 拒绝率 | 在途深度（上限 16,384） |
|---|---|---|---|---|---|
| base | 1024 | 226,562 | 4,742.8 | 35.44% | 13,061 |
| opt（=t1′，无合并） | 1024 | 253,910 | 5,788.8 | 12.96% | 15,297 |
| **fix（完成点提前版）** | 1024 | **122,148** | 2,388.1 | **47.06%** | **3,876** |
| opt（=t1′） | 16 | 7,862 | 95.7 | 0.0008% | 246 |
| **fix（完成点提前版）** | 16 | 10,956 | 148.0 | 0.0000% | 246 |
| base | 16 | 18,244 | 213.4 | 0.0000% | 244 |

⇒ **这条路子被丢弃**：深窗口吞吐只有 base 的 54%（低 46%），
47% 的提案被 REJECT，在途深度从 15,297 掉到 3,876。

**机制未定位（推演，不是实测）**：完成点提前 ⇒ 客户端更早收到完成、更早补位
⇒ L1 提案队列（`maxPendingProposals`=2048）先满 ⇒ 大量提案被 REJECT；
被拒的提案不计入在途，在途深度随之塌陷。
**但「为什么条/次落盘也从 4,742 掉到 2,388」这一步没有实测支撑，不要当成结论引用。**

这条记录与 §10.10 的主结论方向一致：**决定吞吐的是「完成点」和「相位」，
不是「省掉多少 CPU」**——完成点往哪个方向挪，结果可以差 2 倍以上。

（这一批是**单次实测**、机器负载未知，只作为「这条路不行」的证据，不进任何中位数表。）

### 10.10.6 效果：campaign J（base vs t2）

**campaign J**（2026-10-30 18:00~19:00，5 轮 × 4 窗口，
每个窗口内两个变体的顺序按轮次奇偶对调；共 40 格，完成 39 格）：

| w | base 提交/s | **t2 提交/s** | 变化 | base 条/次落盘 | t2 条/次落盘 | base 拒绝% | t2 拒绝% | base p50 | t2 p50 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 727 | **1,348** | **+85.4%** | 8.6 | 15.8 | 0.01 | 0.00 | 20.24ms | **11.96ms** |
| 16 | 14,809 | **22,812** | **+54.0%** | 173.9 | 249.3 | 0.00 | 0.00 | 12.78ms | **11.70ms** |
| 256 | 118,196 | **131,067** | **+10.9%** | 1,968.2 | 1,934.8 | 2.02 | **0.88** | 36.64ms | **30.66ms** |
| 1024 | 220,347 | 208,714 ⚠ | **−5.3%** | 4,500.9 | 5,158.3 | 35.72 | **28.98** | 64.00ms | 62.64ms |

（⚠ w=1024 的 t2 只有 **4 轮**有效数据：第 5 轮那次运行撞上了 §10.10.7 的停机挂起。）

**w=1024 的 −5.3% 是噪声内的**：同一格 base 的五轮是
212,418 / 216,658 / 220,347 / 222,479 / 223,092（±2.4%），
t2 的四轮是 191,298 / 208,441 / 208,987 / 228,029（±9%，样本少）。
**这一格真正的变化是「条/次落盘 +14.6%」和「拒绝率 −19%」**，
吞吐没跟上是因为**深窗口的限流点不在落盘上**：
w=1024 时提案队列（`maxPendingProposals`）先满，
29~36% 的提案被直接拒绝（`proposeAsync` 的 REJECT 策略）。

#### 10.10.6.1 模型自查

（`落盘条目/s = 落盘次数/s × 条/次落盘`，`提交/s = 落盘条目/s ÷ 3`；
`落盘次数/s` 是 3 节点非空 fsync 合计，`条/次落盘` 是 3 节点平均）

| w | 变体 | 落盘/s × 条/次 ÷ 3 | 实测提交/s | 偏差 |
|---|---|---|---|---|
| 1 | base | 254 × 8.6 ÷ 3 = 728 | 727 | +0.1% |
| 1 | t2 | 256 × 15.8 ÷ 3 = 1,348 | 1,348 | 0.0% |
| 16 | base | 256 × 173.9 ÷ 3 = 14,839 | 14,809 | +0.2% |
| 16 | t2 | 274 × 249.3 ÷ 3 = 22,769 | 22,812 | −0.2% |
| 256 | base | 185 × 1,968.2 ÷ 3 = 121,372 | 118,196 | +2.7% |
| 256 | t2 | 206 × 1,934.8 ÷ 3 = 132,856 | 131,067 | +1.4% |
| 1024 | base | 146 × 4,500.9 ÷ 3 = 219,043 | 220,347 | −0.6% |
| 1024 | t2 | 120.5 × 5,158.3 ÷ 3 = 207,258 | 208,714 | −0.7% |

**8 格全部自洽（|偏差| ≤ 2.7%）** ⇒ 三个数（落盘频率、条/次落盘、提交/s）互相印证。

#### 10.10.6.2 交叉验证（两批独立数据互相对照）

| w | campaign H2 的 `edap-async`（§10.9.3.1） | campaign J 的 base | 相对差 |
|---|---|---|---|
| 1 | 822 | 727 | −11.6% |
| 16 | 14,561 | 14,809 | +1.7% |
| 256 | 118,696 | 118,196 | −0.4% |
| 1024 | 225,197 | 220,347 | −2.2% |

**两批完全独立的实验（不同日期、不同脚本）测同一份 base 代码，w=16/256/1024 吻合在 2.2% 以内**
⇒ base 的基准数据可信，campaign J 的 t2 提升不是「跑分漂移」。

### 10.10.7 事故：一次停机挂起（根因未定位，已加保险）

#### 现象

campaign J 的最后一格（`rep5 opt w=1024`）跑完 15s 基准后**不退出**，
主线程卡在 `Node.stop()` → `worker.join()`。
`jstack` 现场（`/tmp/stack_hang.txt`，2026-10-30 19:30，进程已跑 2,527s）：

```
"main" ... at io.edap.raft.Node.stop(Node.java:664)      ← worker.join()
"edap-raft-1" ... WAITING (parking) at io.edap.raft.Node.run(Node.java:1136)   ← LockSupport.park(this)
"edap-raft-apply-1" / "edap-raft-persist-1" / "edap-raft-timeout-1" —— 已消失
"edap-raft-2" / "-3" 及其 apply/persist/timeout 线程 —— 都还在
```

- 停机是**按节点顺序**做的（`ClusterProposalHarness.java:646-653`），
  所以现场是：node1 已经停了一半（apply/persist/timeout 线程都退了，说明 `stopped` 已是 true），
  **但它的状态机线程还停在 `park` 上**；
- node2/node3 还没轮到停，它们的线程都还活着 —— 说明卡住的是**第一个** `m.node.stop()`。

#### 已排除

| 假设 | 排除依据 |
|---|---|
| 卡在推迟落盘的 `parkNanos` 分支 | 栈上是 `Node.java:1136` = `LockSupport.park(this)`（无限期 park），不是 `parkNanos` |
| `deferUntilNanos` 死等 | 同上；且推迟有 50ms 总上限 |
| `stop()` 提前返回（那样就进不到 `join`） | 栈上就是 `join()` |
| 节点与线程错配 | harness 每个 peer 一个 `Node`，线程名带 raft id，`edap-raft-1` 就是 node1 的 |
| 源码与运行代码不一致 | 反编译运行时的 `Node.class`：`stop()` 的 `unpark(worker)`（offset 74-78）与 `join()`（offset 116-120）都在；`run()` 的循环顶检查 `stopped.get()`（offset 55-62）与 `park`（offset 1144）行号与源码一致 |

#### 静态分析的结论（与现场矛盾）

按源码，**这个挂起不可能发生**：

- `stop()` 先 `stopped.compareAndSet(false, true)`，再无条件 `LockSupport.unpark(worker)`；
- 状态机线程的 `park` 之后一定回到循环顶（字节码 offset 1148 → `goto 55` → `stopped.get()`），
  `stopped` 是 volatile 语义 ⇒ **线程一定会看到 true 并退出**；
- `unpark` 的许可（permit）不会丢：许可是一次性的「信号」，
  `unpark` 在线程未 park 时会置位许可，随后的 `park` 立刻返回。

**⇒ 现场说明有一次 `unpark` 没能让线程醒过来**（JVM 层面的许可丢失/唤醒丢失），
**但根因没有定位**。

#### 复现尝试（未成功）

- 加埋点重编译（`stop()` 进出、`run()` 循环退出、park 醒来处打点，
  外加 3s 看门狗打 worker 栈），**跑 60 次（opt 30 + base 30）全部未复现**；
- `jdb` 附着被 macOS 拒（Connection refused）；`jhsdb clhsdb` 能连上但取不到字段值。

#### 已加的保险（这一轮随性能改动一起落地）

| 改动 | 位置 | 作用 |
|---|---|---|
| 状态机线程的 `park` 改成**带超时**（1s） | `Node.java:1179` | 即使一次 `unpark` 丢了，线程最迟 1s 后也会自己醒来重新看一眼 `stopped` ⇒ **挂起变成最多 1s 的延迟** |
| `stop()` 改成**有界等待**：落盘 5s / apply 2s / 超时清扫 1s / 状态机 5s | `Node.java:638` | 停机**永远不会被一个线程拖死**；超时会打印告警**并带上那个线程的栈**，下次再遇到能直接定位 |
| 停机时**先等落盘线程退出** | `Node.java:665` | 调用方 `stop()` 之后就可以安全地 `storage.close()` —— 原来落盘线程还可能在写盘，`close()` 会和一次 fsync 撞上 |
| 回归测试 `nodeStopIsBounded` | `RaftNodeTest.java` | 断言 `stop()` 有界返回，且返回后节点的所有线程都已退出 |

> **代价**：状态机线程从「无限期 park」变成「每秒醒一次」。
> 每秒多一次空转（一轮循环 ≈ 几微秒），对吞吐无可测影响
> （campaign J 之后的复测：w=16 是 20,103~22,381 提交/s，w=1024 是 198,473~207,599，
> 与 campaign J 的 t2 中位数同量级）。

### 10.10.8 剩下的可优化项（按 JFR 占比排序）

**已经做掉的**（本节）：`orTimeout` 的 `Canceller`（31.5%）、无条件 `notifyAll`（20.5%）、
冗余 `whenComplete`（11.7%）、`Unstable.hasNextEntries()` 的整批复制（省一次列表拷贝）。

**还剩下的**（JFR 实测占比，leader 状态机线程）：

| 占比 | 热点 | 位置（实测调用链） | 想法 | 状态 |
|---|---|---|---|---|
| **4.8%** | `ConcurrentHashMap.replaceNode` | `completePendingProposals` → `pendingProposals.remove(...)`（48 样本）；`lambda$proposeAsync$0` → `pendingProposals.remove(...)`（17 样本） | 每提交一条提案就做一次 map 删除。可以改成「一轮结束时按序批量摘除」或用 long 序号 + ring buffer 替代 map | **未做**（收益 ~5%，但会动到提案完成路径，风险要评估） |
| **3.3%** | `Raft.payloadsSize`（24）+ `RaftUtil.limitSize`（20） | 载荷大小在**三个地方各算一遍**：`appendEntry`→`increaseUncommittedSize`（8）、`maybeSendAppend`→`sendAppend`（10）、`Raft.step`（6）；`RaftLog.slice`→`RaftLog.limitSize`（14）、`FileRaftStorage.entries`（5） | 载荷大小可以随 entry 一起带下来，算一次就够 | **未做** |
| **1.9%** | `AbstractCollection.toArray` ← `ArrayList.addAll` | `Node.handleProposals` | 提案排空时每轮新建 `ArrayList` 并 `addAll`；可以改成复用缓冲区 | **未做** |
| **~2.1%** | `RaftLog.slice` / `RaftLog.entries` | 复制日志切片 | 已有 `Unstable.hasNextEntries()` 的先例（不复制列表判空），同样的思路可以推广 | **部分** |

**方向性的两条**（不是「热点」，是结构性的）：

1. **w=1024 的限流点不在落盘上**（§10.10.6）：29~36% 的提案被 `proposeAsync` 的
   REJECT 策略直接拒掉。要提升深窗口，得动**背压策略**（比如改成 BLOCK 或分级拒绝），
   而不是继续优化落盘。
2. **同步语义（`async-apply`）下碎片合并的效果还没测**：
   §10.9 指出「完成点=应用」会把批次打散（174.1 → 88.6 条），
   本节的碎片合并正是冲着这个去的 —— **但只在 `async-commit`（完成点=提交）下测过**。
   `async-apply` 模式下客户端要等应用点才补位，补位更慢，碎片应该更严重，
   **合并的收益可能更大** —— 这是下一步最该做的实验。

### 10.10.9 小结

1. **「慢」的第一个来源是提案 future 的超时/回调机制**，而且是 O(n²) 的：
   `orTimeout` 在 JVM 全局延迟队列里注册定时任务，提案完成时逐条线性删除。
   **leader 线程 31.5% 的 CPU 花在这上面**。已改为自维护的超时清扫。
2. **去掉开销之后 w=16 反而慢了 2.26×**，这才是本轮真正的发现：
   **批大小是自维持的正反馈**，而它由「补位时刻 vs leader 排空 `propc` 的相位关系」决定；
   **让 leader 更快，会把批次打碎**。
   ⇒ **性能优化不能只看 CPU**：在「补位—攒批」这种自维持系统里，
   **变快会改变系统的稳定态**。
3. **正确的修法是显式控制相位**，不是把开销加回去：
   碎片批次合并让非空批从「43 / 83 / 142」变成「256 / 256 / 256」，
   吞吐 w=1 +85.4%、w=16 +54.0%、w=256 +10.9%、w=1024 −5.3%（噪声内）。
4. **§10.9.5 的推论得到验证**：要动的确实是「完成点释放时机与批次边界的对齐」。
   本节把它落成了「leader 等客户端补位到齐再落盘」的具体实现，
   并给出了它的收益曲线。
5. **w=1024 没有提升**，那里的限流点是提案队列满导致的拒绝（29~36%），
   不是落盘批次 —— **下一步该动背压策略，不是落盘**。
6. **一次停机挂起事故已如实记录**（§10.10.7）：根因未定位，
   但已从「可能挂死整个进程」降级为「最多 1s 的延迟」，
   并且下一次再遇到时会把那个线程的栈打进告警日志。

## 10.11 碎片批次合并的判据重做（campaign K → L，2026-10-30）

### 10.11.0 起因与结论摘要

**起因**：§10.10.5 的碎片批次合并在 **campaign J** 里表现很好
（w=1 +85.4%、w=16 +54.0%、w=256 +10.9%），
但 §10.10.5 的判据本身有个**没被验证过的前提**：
「本批条目数 < 上一批通知的客户端数 ⇒ 这是碎片批」。

**campaign K**（三方同批复测，见 §10.11.1）把这个前提证伪了：
在**深窗口（w=1024）**上，新代码（`edap-t2`）比 HEAD（`edap-base`）**慢 6.1%**，
并且**被 etcd-aligned 反超 15%** —— 即「优化后」比「优化前」还差。

本节记录：怎么定位的（§10.11.2）、机理（§10.11.3）、
被否决的修法（§10.11.4）、怎么找信号（§10.11.5）、
最终修法（§10.11.6）、判据 A/B（§10.11.7）、
三方复测（§10.11.8）、模型自查（§10.11.9）、小结（§10.11.10）。

**一句话结论**：碎片合并的判据必须回答「**瓶颈在不在 fsync 上**」，
而不是「批大不大」。判据改成「落盘线程忙时占比」之后，
深窗口的回归**消失**（§10.11.8）：

| 窗口 | campaign K：t2 / base | campaign L：t2 / base | campaign L：t2 / etcd-aligned |
|---|---|---|---|
| 1 | +31.6% | **+70%** | **2.40×** |
| 16 | +41.8% | **+31%** | **2.64×** |
| 256 | +18.0% | **+19%** | **1.68×** |
| 1024 | **−6.1%** | **+13%** | **1.08×**（从 0.87× 反超） |

**但要说清楚「修好了」的边界**（§10.11.10）：
w=1024 的忙时占比落在 **0.32~0.50**，正好跨在阈值 0.5 上，
推迟率在 **3.0%~56.5%** 之间摆动 ⇒ **回归没了，但摆动还在**。
把阈值提到 0.7 也许能一次解决，**这一步尚未实测**（§10.11.10.1）。

### 10.11.1 campaign K：三方同批复测，暴露深窗口回归

**campaign K**（2026-10-30 21:00~22:10，5 轮 × 4 窗口 × 5 变体，
每格 15s，每个窗口内变体顺序按轮次奇偶对调；共 100 格，全部成功）：

变体定义：

| 变体 | 含义 |
|---|---|
| `edap-t2` | 工作区代码（碎片批次合并 + 旧判据） |
| `edap-base` | HEAD 代码（`/tmp/classes_base2` 前置到 classpath），其余配置与 `edap-t2` 完全一致 |
| `dragonboat` | `/tmp/dbbenchdef`，默认 LogDB 配置 |
| `etcd-aligned` | Go，raft v3.6 + etcd server v3.6 接线，配置与 edap 对齐 |
| `etcd` | 同上，但用 etcd server 出厂配置 |

**提交/s（5 轮中位数）**：

| 窗口 | edap-t2 | edap-base | dragonboat | etcd-aligned | etcd | t2/etcd-aligned | t2/dragonboat | t2/base |
|---|---|---|---|---|---|---|---|---|
| 1 | 1,195 | 908 | 330 | 466 | 504 | **2.56×** | 3.62× | **+31.6%** |
| 16 | 21,746 | 15,334 | 5,306 | 6,673 | 6,606 | **3.26×** | 4.10× | **+41.8%** |
| 256 | 140,697 | 119,207 | 86,823 | 83,888 | 78,374 | **1.68×** | 1.62× | **+18.0%** |
| 1024 | 216,529 | **230,647** | 154,035 | **249,778** | 226,248 | **0.87×** | 1.41× | **−6.1%** |

**其它指标（base → t2）**：

| 窗口 | 条/次落盘 | 拒绝率 | p50 |
|---|---|---|---|
| 1 | 12.0 → 15.7 | 0.00 → 0.00 | 13.94 → 12.93ms |
| 16 | 188.5 → 249.6 | 0.00 → 0.00 | 13.58 → 11.97ms |
| 256 | 1,992 → 2,030 | 0.12 → 0.53% | 35.74 → 31.05ms |
| 1024 | 4,551 → 5,677 | 34.88 → **26.85%** | 62.54 → 62.00ms |

**读法（注意口径）**：

- w=1/16/256 上 `edap-t2` 相对 `edap-base` 是**真实提升**（+31.6% / +41.8% / +18.0%），
  与 campaign J 的方向一致。
- w=1024 上 `edap-t2` **低于** `edap-base` 6.1%，也**低于** `etcd-aligned` 15%。
  ⇒ 「碎片合并在深窗口有害」这件事在同批三方对比里第一次被看到。
- 注意别把「拒绝率降了」当成好事：**t2 的拒绝率确实更低**
  （26.85% vs 34.88%），**但它发布的提案总数也更少**
  （217,622/s vs 230,935/s）——
  ⇒ t2 是「**拒绝得少，但也只接得少**」，吞吐反而低 6.1%。
  这正是 §10.11.3 那条「推迟 ⇒ 复制消息被拖住 ⇒ 提交变慢 ⇒ 客户端补位不上」
  的机理在数据上的样子。

### 10.11.2 定位：旧判据在深窗口退化成「几乎每轮都推迟」

**旧判据**（§10.10.5）：`本批条目数 < expect` ⇒ 碎片 ⇒ 推迟落盘。
其中 `expect` = **上一批通知到的客户端数**（通知一个客户端，它就会补一条新提案）。

**探针 6 的做法**（`/private/tmp/classes_probe6`，
在 `Node.run` 的推迟分支里挂计数器，**只加计数器不改行为**）：
统计每个窗口里 —— 组装了多少轮 Ready、其中多少轮被判成「推迟」、
**多少轮真的丢弃了 Ready**、丢弃时那一批有多少条、`expect` 是多少。

**旧判据**（用 `-Dedap.raft.deferMinBusyRatio=0` 复现 ——
阈值取 0 时 `busy < 0` 恒不成立，忙时判据整个失效，等价于旧行为）：

| 窗口 | 组装轮次 | 判成「推迟」 | **真的丢弃 Ready** | 丢弃时平均批 | 丢弃时平均 `expect` |
|---|---|---|---|---|---|
| 16 | 18,146 | 9,431 (52.0%) | 9,431 (52.0%) | 210.0 | 254.6 |
| 256 | 66,028 | 60,433 (91.5%) | **60,427 (91.5%)** | 1,889.9 | 2,820.2 |
| 1024 | 24,008 | 21,245 (88.5%) | **21,183 (88.2%)** | **4,676.5** | **8,292.4** |

**读数**：

1. **深窗口下几乎每一轮组装都被丢弃**：w=1024 是 24,008 轮里丢了 21,183 轮
   （**88.2%**），w=256 是 91.5%。
   ⇒ leader 的组装工作有 **9/10 白做了**。
2. **丢弃时那一批始终不到期望的一半**：w=1024 平均攒到 4,676 条，
   而 `expect` 是 8,292 条 ⇒ **每次都判成「碎片」**。
   这就是判据退化的直接证据：**期望值和实际批量的量级根本不匹配**。
3. **w=16 只有 52% 被丢弃** —— 浅窗口下这个判据大体成立，
   所以浅窗口是净收益（campaign L：t2/off = 2.83×）。

**同一套探针在新判据下**（默认参数）：

| 窗口 | 组装轮次 | 真的丢弃 Ready | 丢弃率 |
|---|---|---|---|
| 16 | 19,365 | 10,176 | 52.5% |
| 256 | 90,613 | 85,817 | 94.7% |
| 1024 | **4,209** | **1,564** | **37.2%** |

⇒ 深窗口的丢弃率从 88.2% 降到 37.2%，
且组装轮次从 24,008 降到 4,209（批攒大了，不必反复重组）。
**浅窗口的丢弃率基本不变**（52.0% → 52.5%，w=256 反而更高，
因为批攒得更大 ⇒ 组装轮次更多）。

**旧判据 vs 新判据的最终对照**（性能用同批三方对比的 5 轮中位数，
推迟率用探针 6）：

| 窗口 | 旧判据丢弃率 | 旧判据吞吐（campaign K，5 轮） | 新判据丢弃率 | 新判据吞吐（campaign L，5 轮） |
|---|---|---|---|---|
| 16 | 52.0% | 21,746 | 52.5% | 21,406（持平） |
| 256 | 91.5% | 140,697 | 94.7% | 141,094（持平） |
| 1024 | **88.2%** | **216,529（回归）** | **37.2%** | **258,109（修复）** |

⇒ **浅窗口两个判据几乎没差别**（丢弃率与吞吐都对得上），
**深窗口差别巨大**。旧判据无法区分这两种情况，所以必须换判据。

### 10.11.3 机理：推迟落盘会把复制消息一起拖住（推演 + 探针佐证）

**推演**（不是实测，标注清楚）：

§10.10.5 的推迟实现是「**丢弃整个 Ready**」。
而 Ready 里除了待落盘的条目，还带着**这一轮要发出去的复制消息**。
于是深窗口下形成这样的正反馈：

```
判成碎片 → 丢弃 Ready → 复制消息被一起拖住（不早发）
        → 对端收不到 → leader 提交变慢 → 客户端补位不上
        → 下一批更少 → 更容易判成碎片 → 继续推迟
```

注意这是**反向**的正反馈：不是「越合并越好」，
而是「推迟 → 提交慢 → 批更小 → 推迟更多」。
§10.10.8 的「消息投递顺序：复制早发」那条优化（MSG_APP 落盘前发）
在深窗口被这个推迟逻辑**部分抵消**掉了。

**探针佐证**（探针 6，§10.11.2）：旧判据在 w=1024 上
**24,008 轮组装里丢弃了 21,183 轮（88.2%）** ——
leader 的组装工作有近 9/10 白做了。
**丢弃时那一批平均只有 4,676 条，而期望值是 8,292 条** ——
**每轮都判成「碎片」，每轮都丢**。
⇒ 代价不在「等了多久」，而在「**拖住了多少条复制消息**」。

### 10.11.4 被否决的修法：绝对条目阈值

**思路（直觉）**：批大到某个程度就别再等了 ——
加一个绝对上限，比如「本批条目数超过 N 就不推迟」。

**实现**（探针 7 构建 `/private/tmp/classes_probe7`，
`edap.raft.deferMaxEntries` 属性，**只加这一条判据，其余不变**）：
变体 = {`none`（当前代码，无绝对阈值）, `off`（`-Dedap.raft.deferWaitMillis=0`，完全关闭合并）,
`n2048`, `n4096`, `n8192`}；
3 轮 × 2 窗口 × 15s，变体顺序按轮次奇偶对调。

**提交/s（3 轮中位数；逐轮原值见括号）**：

| 变体 | w=256 | w=1024 |
|---|---|---|
| `none`（当前代码） | 130,534（130,534 / 133,895 / 126,585） | 247,144（247,144 / 247,544 / 233,433） |
| `off`（关闭合并） | 96,172（97,194 / 95,796 / 96,172） | **252,053**（253,377 / 252,053 / 245,240） |
| `n2048` | 119,051（119,051 / 118,379 / 123,309） | 245,733（245,733 / 245,129 / 246,323） |
| `n4096` | **142,247**（145,126 / 136,295 / 142,247） | 236,116（232,352 / 245,938 / 236,116） |
| `n8192` | 130,975（130,975 / 127,277 / 136,940） | 247,797（247,797 / 243,150 / 250,876） |

**结论**：

1. **在 w=256，绝对阈值最多换来 +9%**（`n4096` 142,247 vs `none` 130,534），
   而**关闭合并是灾难**（96,172，−26%）——
   ⇒ 合并在 w=256 是**必须的**，绝对阈值只是微调。
2. **在 w=1024，任何阈值都不如「不合并」**（最好的 `n8192` 247,797 仍略低于
   `off` 的 252,053），而且**没有单调性**：
   `n4096` 在 w=256 最好、在 w=1024 最差。
3. ⇒ **绝对阈值表达不了「瓶颈在不在 fsync 上」** ——
   它只能表达「批够不够大」，而「批够大」在深窗口恰恰是**常态**
   （丢弃时平均批 4,676 条），所以阈值在深窗口要么不起作用、
   要么作用方向相反。**这正是 §10.11.5 要换信号的理由。**

**一条被显式作废的旧数据**：这一节最初写过一版
「关闭 = 103,070 / 252,331；2048 = 124,495 / 237,551；4096 = 137,120 / 233,546；
8192 = 151,090 / 203,540」。
那批数据来自**一个源码已删除的临时构建**（当时的 `DEFER_MAX_ENTRIES` 实现），
**重跑没有复现**（现在 `n8192` 在 w=1024 是 247,797，不是 203,540）。
⇒ **那组数字作废，以本节表格为准。**
（差别很可能来自当时那个实现把阈值用在了不同的位置；
本轮的结论 —— 「绝对阈值表达不了瓶颈在哪」—— 两版都指向同一个方向。）

### 10.11.5 探针找信号：哪个量能干净地分开深浅窗口

**「落盘忙时占比」的定义**：落盘线程花在「写 + fsync」上的时间 ÷ 墙钟时间。

- 接近 1 ⇒ 落盘线程一直在 fsync ⇒ **系统被 fsync 卡住** ⇒ 合并 fsync 能直接换吞吐；
- 明显小于 1 ⇒ 落盘线程在等网络/等对端 ⇒ 合并 fsync **省不出东西**，
  只会把这一轮的复制消息一起拖住（§10.11.3）。

**探针做法**：在 `shouldDeferPersist` 的判定点采样多个候选信号
（`entries` 本批条目数、`expect` 上一批通知数、`propc` 提案队列长度、
`pending` 在途提案数、`lag` 提交滞后、`busy` 忙时占比）。

**第一次采样（`/tmp/pb_*.log`）有一处必须记下来的坑**：
那版探针把忙时计数器写成了 **`static`**，而窗口起点是**每节点实例**的
—— 3 个节点共享一个累计计数、单节点用自己的窗口去除，
算出来的「占比」最大可以到 3（实测 1.08~2.75）。
⇒ **那批日志里的 `busy` 列全部作废**，只有其它信号可用。

**修正后重采（`/tmp/p5_*/`，同一个构建，4 个窗口各跑一次）**：

| 候选信号 | w=1 | w=16 | w=256 | w=1024 | 能否分开 |
|---|---|---|---|---|---|
| 本批条目数 p50 | 0 | 177 | 2,384 | 4,096 | ✗（见下） |
| `expect`（上一批通知数）p50 | 16 | 256 | 4,096 | 9,697 | ✗（单调变化，但它是**结果**不是**原因**） |
| 提案队列长度 p50 | 0 | 7 | 40 | 25 | ✗ |
| 在途提案数 p50 | 16 | 256 | 3,022 | 10,711 | ✗（同上） |
| 提交滞后 p50 | 16 | 244 | 2,896 | 10,426 | ✗（同上） |
| **落盘忙时占比 p50** | **0.711** | **0.766** | **0.812** | **0.502** | **✓（唯一二值可分的）** |

**读表要点**：

1. 「本批条目数」这一行**不能用新判据下的数**做论据 ——
   新判据在 w=1024 上**不再推迟**，批次自然变大（4,096 > 2,384），
   这是**结果**不是**原因**。要看旧判据下的分布，得看**旧判据那批探针**
   （`/tmp/pb_*.log`）：w=16 / 256 / 1024 的本批条目数 p50 是
   **178 / 1,339 / 1,304** —— **w=256 与 w=1024 几乎一样**，
   这才是「批大小分不开深浅窗口」的原始证据。
2. `expect` / 在途提案数 / 提交滞后都是**深浅窗口连续变化**的量，
   就算能当阈值用，也是「深窗口 ⇒ 队列深」这种**同义反复**，不是因果信号。
3. 只有忙时占比是**二值可分**的，而且分得开的位置（0.71~0.81 vs 0.50）
   正好在 0.5 附近。

**忙时占比可以不用探针、只用已有的 fsync 账单交叉验证**：
harness 报的 `落盘/s` 与 `落盘耗时ms/次` 都是**3 个节点求和**
（`ClusterProposalHarness.java:991-996` 逐节点累加 `getPersistCount()` /
`getPersistNanos()`），所以单节点的忙时占比 ≈

```
(落盘/s ÷ 3) × 落盘耗时ms/次 ÷ 1000
```

用 campaign L 的 `edap-t2` 那一行算：

| 窗口 | 落盘/s | 落盘耗时ms/次 | 账单推出的单节点 busy | 探针实测 busy p50 |
|---|---|---|---|---|
| 1 | 256 | 10.38 | 0.89 | 0.711 |
| 16 | 262 | 9.94 | 0.87 | 0.766 |
| 256 | 202 | 10.36 | 0.70 | 0.812 |
| 1024 | 116 | 9.41 | **0.36** | **0.502** |

⇒ 两条完全不同的路径（探针测量 / fsync 账单反推）**给出一致的结论**：
**w≤256 的落盘线程忙时占比在 0.7~0.9（fsync 是瓶颈），
w=1024 只有 0.36~0.50（瓶颈不在 fsync 上）**。

### 10.11.6 修法：忙时占比判据

**代码改动**（全部在 `edap-raft-parent/edap-raft/src/main/java/io/edap/raft/Node.java`）：

| 位置 | 改动 |
|---|---|
| 新字段 `persistBusyNanos`（`:210`） | `AtomicLong`，落盘线程累加「真正落盘耗时」 |
| 新字段 `busyWindowStartNanos` / `busyWindowStartBusyNanos` / `persistBusyRatio`（`:215`/`:220`/`:228`） | 滑动窗口的起点与占比结果（只有状态机线程读写） |
| `persist(Ready, List<Entry>)`（`:1221`） | 用 `System.nanoTime()` 量出写 + fsync 的真实耗时，累加进 `persistBusyNanos` |
| 新方法 `currentPersistBusyRatio()`（`:1411`） | 1 秒滑动窗口的忙时占比；负数 = 第一个窗口没走完（未知） |
| `shouldDeferPersist`（`:1379`） | 新增判据：`busy >= 0 && busy < DEFER_MIN_BUSY_RATIO` ⇒ **不推迟** |
| 常量 `DEFER_MIN_BUSY_RATIO`（`:1809`） | 系统属性 `edap.raft.deferMinBusyRatio`，默认 **0.5** |
| 常量 `DEFER_BUSY_WINDOW_MILLIS`（`:1823`） | 系统属性 `edap.raft.deferBusyWindowMillis`，默认 **1000** |

**关键点**：`busy < 0`（还没测出来）时**不跳过**推迟 ——
启动首秒属于「未知」，保守起见按旧行为处理。

**探针实测判据分离度**（新判据构建，1000ms 统计窗口）：

| 窗口 | 判定点里仍判「推迟」的比例 | busy p50 | busy < 0.5 的比例 |
|---|---|---|---|
| 256（4 次） | 81.0% / 81.0% / 84.2% / 83.9% | 0.72~0.84 | 3.5%~9.1% |
| 1024（4 次） | **56.0% / 30.2% / 56.5% / 3.0%** | **0.32~0.50** | 35.8%~100% |

**读法（很重要，不要只挑好看的数）**：

- **w=256 上判据工作得很好**：busy 稳定在 0.72~0.84（远高于 0.5），
  推迟率稳定在 81~84% ⇒ **该合并的照合并**。
- **w=1024 上判据在阈值附近摆动**：busy p50 落在 **0.32~0.50**，
  也就是**跨过了 0.5 这条线**；于是推迟率在 **3.0%~56.5%** 之间大幅波动。
  这不是测量噪声掩盖了规律，而是**规律本身就是双峰的**：
  系统在 w=1024 上会落进两个稳定态之一 —— 要么 fsync 忙时占比压在 0.5 以下
  （不推迟），要么压不住（推迟），而**初始条件决定落进哪个**。
  （早期那批探针只采到过其中一个峰：2.73% / 6.63%，都是「不推迟」那一侧。）
- 换句话说：**判据把「该不该合并」从一个连续量变成了一个开关，
  但这个开关在深窗口上并不总是落在同一侧**。
  这也解释了为什么 w=1024 的吞吐在同一批里有 235K~262K 的散布
  （campaign L 的逐轮明细）。

**统计窗口为什么是 1 秒**：500ms 窗口那版探针（`/tmp/p3_1024.log`）
在 w=1024 上残留 **6.63%** 的推迟；改成 1000ms 之后推迟率明显下降，
而 w=256 的收益不变（那批探针里 w=256 的推迟率 83.11% vs 85.30%）。
原因是一次 fsync 十几毫秒，窗口太短测出来的比例会在阈值附近来回跳。
**注意**：这只是把摆动幅度压小，**并没有消除摆动**（见上面的 4 次采样）。

### 10.11.7 判据 A/B（busy 判据 vs 完全关闭推迟）

**5 轮交错同批**（`/tmp/defer_busy2.sh` → `/tmp/defer_busy_ab.txt`，
每轮内两个变体顺序按轮次奇偶对调）：

| 窗口 | busy 判据 | 关闭推迟 | 倍数 | busy 批 | off 批 |
|---|---|---|---|---|---|
| 1 | 1,346 | 750 | **1.79×** | 16 | 9 |
| 16 | 22,697 | 7,787 | **2.91×** | 254 | 95 |
| 256 | 146,579 | 103,374 | **1.42×** | 2,133 | 1,366 |
| 1024 | 235,647 | 247,189 | **0.95×** | 5,226 | 5,780 |

**w=1024 这一格当时判为「噪声」**（不是「修好了」），当时的理由：

- busy 判据在 w=1024 上**已经几乎不推迟**（那一轮探针采到 2.73%，且都在启动首秒），
  此时的 busy 与 off 两种配置的**实际行为几乎相同**，
  0.95× 的差值理应来自噪声；
- 该格逐轮散布 223,557~247,457（±10%），0.95× 落在散布内；
  （off 那一侧同样散布 236,609~265,731，**两边的散布幅度一样大**。）

> **⚠️ 这个理由后来被证伪了**（探针 5 的 4 次重采，§10.11.6）：
> 「2.73%、且都在启动首秒」是**早期那版只采到单个峰的探针**的结果，
> 4 次重采的推迟率是 **56.0% / 30.2% / 56.5% / 3.0%** ——
> **4 次里有 3 次推迟率在 30%~57%**，远不是「几乎不推迟」。
> ⇒ 当时的「行为几乎相同」这个前提**不成立**，
> 0.95× 那一格**不能当作噪声了事**。
> **这一格的最终判定以 campaign L 的同批复测为准**（§10.11.8），
> 不在这里提前下结论。

**参数扫描**（3 轮，`/tmp/defer_tune.sh`）：

| 变体 | w=256 | w=1024 |
|---|---|---|
| A：统计窗口 1000ms | 142,042 | 248,709 |
| B：阈值 0.6 | 132,209 | 239,972 |
| 关闭推迟 | 102,158 | 247,716 |

⇒ 选 A（窗口 1000ms + 阈值 0.5）。
（这批扫描只有 3 轮，且与 §10.11.6 的探针不是同一批构建，
**只能当作粗筛，不能当结论**。）

**回归**：`mvn -o -f pom.xml -pl edap-raft-parent/edap-raft test`
→ **Tests run: 351, Failures: 0, Errors: 0, BUILD SUCCESS**。

### 10.11.8 campaign L：三方同批复测

**campaign L**（2026-10-30 23:00~2026-10-31 00:20，5 轮 × 4 窗口 × 6 变体 = 120 格，全部成功；
每格 15s，每个窗口内变体顺序按轮次奇偶对调）。
脚本 `/tmp/campaign_L.sh` → `/tmp/ab_result_l.txt`，解析器 `/tmp/parse_l.py`。

变体定义：

| 变体 | 含义 |
|---|---|
| `edap-t2` | 工作区代码（碎片批次合并 + **忙时占比判据**） |
| `edap-off` | 同上，但 `-Dedap.raft.deferWaitMillis=0` ⇒ **碎片批次合并整体关闭**（消融） |
| `edap-base` | HEAD 代码（`/tmp/classes_base2` 前置 classpath），其余配置与 `edap-t2` 一致 |
| `dragonboat` | `/tmp/dbbenchdef`，默认 LogDB 配置 |
| `etcd-aligned` | Go，raft v3.6 + etcd server v3.6 接线，配置与 edap 对齐 |
| `etcd` | 同上，但用 etcd server 出厂配置 |

**提交/s（5 轮中位数）**：

| 窗口 | **edap-t2** | edap-off | edap-base | dragonboat | etcd-aligned | etcd | t2/etcd-aligned | t2/dragonboat | t2/base | t2/off |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **1,352** | 771 | 793 | 372 | 563 | 572 | **2.40×** | 3.63× | **1.70×** | **1.75×** |
| 16 | **21,406** | 7,560 | 16,334 | 5,765 | 8,098 | 7,712 | **2.64×** | 3.71× | **1.31×** | **2.83×** |
| 256 | **141,094** | 104,210 | 118,922 | 90,641 | 84,100 | 80,921 | **1.68×** | 1.56× | **1.19×** | **1.35×** |
| 1024 | **258,109** | 248,113 | 228,637 | 154,201 | 239,253 | 220,713 | **1.08×** | 1.67× | **1.13×** | 1.04× |

**其它指标**：

| 窗口 | 变体 | 条/次落盘 | 拒绝率% | p50(ms) | p99(ms) | 落盘/s | 落盘耗时ms/次 |
|---|---|---|---|---|---|---|---|
| 1 | edap-t2 | 15.8 | 0.00 | 12.02 | 25.41 | 256 | 10.38 |
| 1 | edap-base | 9.2 | 0.01 | 16.02 | 43.59 | 245 | 11.75 |
| 16 | edap-t2 | 245.2 | 0.00 | 12.29 | 25.81 | 262 | 9.94 |
| 16 | edap-base | 186.3 | 0.00 | 12.79 | 40.22 | 256 | 10.49 |
| 256 | edap-t2 | 2,099.5 | 0.35 | 29.81 | 53.28 | 202 | 10.36 |
| 256 | edap-base | 2,020.4 | 1.64 | 34.75 | 51.10 | 177 | 10.05 |
| 1024 | edap-t2 | 6,773.0 | 12.96 | 60.05 | 106.65 | 116 | 9.41 |
| 1024 | edap-base | 4,691.8 | 34.36 | 64.02 | 94.45 | 146 | 9.26 |

**结论（对照 campaign K）**：

1. **深窗口的回归被修掉了**。campaign K 里 w=1024 是
   `edap-t2` 216,529 < `edap-base` 230,647 < `etcd-aligned` 249,778（**0.87×**）；
   campaign L 里变成 **258,109 > 248,113 > 239,253**，
   `edap-t2` 在 w=1024 **反超 etcd-aligned 8%**。
   ⇒ §10.11.2~§10.11.6 的定位与修法**在同批三方对比里被验证**。
2. **`edap-t2` 在四个窗口上全面优于 `edap-base`（HEAD）**：
   **+70% / +31% / +19% / +13%**（campaign K 里是 +31.6% / +41.8% / +18.0% / **−6.1%**）。
3. **碎片批次合并的收益在浅窗口最大、向深窗口收敛**：
   `edap-t2 / edap-off` = **1.75× / 2.83× / 1.35× / 1.04×**。
   w=1024 只剩 4% —— 与 §10.11.6 的探针一致（那里 w=1024 的推迟率本身就在
   3%~56% 之间摆动，开关有时关有时开）。
   **注意 `edap-off` 只是关掉「推迟」，不是回到 HEAD** ——
   它仍然带着超时清扫、去冗余 `whenComplete` 等其它改动，
   所以 `edap-off / edap-base` 在 w=16 是 0.46×（**比 HEAD 还慢**），
   这正是 §10.10.3 那个「去掉开销反而更慢」的现象在同批复现。
4. **拒绝率在深窗口大幅下降**：w=1024 从 base 的 **34.36%** 降到 **12.96%**，
   在途深度从 12,842 升到 14,826。
   ⇒ 合并让提交点更密 ⇒ 客户端补位更及时 ⇒ 提案队列不容易满。
   （campaign K 里 t2 的拒绝率是 26.85% —— 那是**旧判据**下的数。）
5. **p99 在深窗口变差，但**「变差的是合并」这个归因是错的**（消融数据）**：
   t2 的 p99 是 106.65ms、base 是 94.45ms，但**关掉合并的 `edap-off` 是 107.98ms
   —— 比开着合并还差**。⇒ 深窗口的 p99 差异**不是碎片批次合并造成的**，
   合并甚至略微改善了 p99（106.65 vs 107.98）。
   真正的差异在别处：见 §10.11.11 的「落盘周期开销」分析 ——
   **edap 在 w=1024 的落盘周期里只有 36% 是 fsync，剩下 16.45ms 是非落盘开销；
   etcd 的周期有 90% 是 fsync，只剩 1.71ms。**
   （顺带修正：p50 的差异主要由 **Little's Law** 决定 ——
   在途深度被基准固定成 16×1024，吞吐越高、平均延迟就越低。
   t2 的 p50 优势（60.05 vs 64.02）**不是「延迟变好」，是「同样深度下干得更快」。**）

### 10.11.9 模型自查

模型（§10.11 沿用）：`落盘条目/s = fsync/s × 条/次落盘`，
`提交/s = 落盘条目/s ÷ 3`（三个副本各存一份）。

**campaign L 全部 24 格（6 变体 × 4 窗口）的偏差**：

| 窗口 | edap-t2 | edap-off | edap-base | dragonboat | etcd-aligned | etcd |
|---|---|---|---|---|---|---|
| 1 | −0.28% | +0.72% | **−5.25%** | −0.59% | +0.71% | +0.70% |
| 16 | +0.04% | **+4.54%** | −2.67% | −1.14% | −0.19% | −0.99% |
| 256 | +0.19% | +0.03% | +0.24% | −0.10% | +0.08% | +0.55% |
| 1024 | +1.46% | −1.37% | −0.13% | **+3.07%** | +0.38% | **+2.99%** |

⇒ **20/24 格 |偏差| ≤ 1.5%**；4 个超过 2% 的格子分别是：
w=1 的 `edap-base`（−5.25%，批只有 9.2 条，中位数相乘的相对误差被放大）、
w=16 的 `edap-off`（+4.54%，同上，批 95.6 条）、
w=1024 的 `dragonboat`（+3.07%）与 `etcd`（+2.99%，批 2,260/4,035 条，
中位数分别取自不同轮次）。**四个格子都能用「中位数相乘」解释，不是账目错误。**

### 10.11.10 小结

1. **campaign K 的深窗口回归是真的，不是噪声**：
   `edap-t2` 在 w=1024 比 HEAD 慢 6.1%、比 etcd-aligned 低 15%。
   同批三方对比才暴露得出来 —— 只跟自己的旧版本比，看到的是「+18%」这类好看的数。
2. **根因是判据退化，不是实现有 bug**（探针 6 实测）：旧判据「本批 < 上一批通知数」
   在深窗口的量级下**几乎恒真** —— w=1024 时 **24,008 个组装轮次里 21,183 轮被判推迟
   （丢弃率 88.2%）**；丢弃时的平均批只有 **4,676 条**，而同一时刻的期望值是 **8,292 条**
   ⇒ 每轮都被判成「碎片」。推迟会**连带拖住 Ready 里的复制消息**，
   提交变慢 → 补位不上 → 批更小 ⇒ **反向自维持正反馈**。
   换成忙时判据后，w=1024 的组装轮次从 **24,008 降到 4,209**（丢弃率 37.2%）。
   （w=16 / w=256 的丢弃率在换判据前后基本不变：52.0%→52.5%、91.5%→94.7%。）
3. **修法是换判据，不是加阈值**：绝对阈值（§10.11.4）分不开深浅窗口；
   换成「**落盘线程忙时占比**」之后，判据回答的是「**瓶颈在不在 fsync 上**」，
   而不是一个和结果相关的量。
4. **忙时占比可以用已有账单交叉验证**：
   `(落盘/s ÷ 3) × 落盘耗时ms/次 ÷ 1000` 与探针实测一致（0.70~0.89 vs 0.36~0.50），
   ⇒ 这个信号**不是只能靠插桩拿到**，以后可以直接从 harness 的输出推。
5. **修好了，但没有修得干净**（探针 5）：w=1024 的忙时占比落在 **0.32~0.50**，
   正好跨在阈值 0.5 上，推迟率在 **3.0%~56.5%** 之间摆动（双峰）。
   ⇒ 阈值 0.5 是一个**「差不多够用但不够稳」**的选择；
   §10.11.6 的 4 次采样显示 w=256 稳定在 0.72~0.84（余量充足），
   于是提出「**把阈值提到 0.7**」的假设 —— **已实测，不采纳**（§10.11.10.1）。
6. **吞吐与尾延迟是对立的，但归因要分开**：
   - **p50 的差异主要是 Little's Law**（在途深度被基准固定成 16×1024，
     吞吐越高平均延迟越低）—— t2 的 p50 优势（w=16：12.79 → 12.29ms）
     **不是「延迟变好」，是「同样队列深度下干得更快」**。
   - **p99 的差异才是真的尾延迟**，而且**不是合并造成的**（消融：off 107.98 ≥ t2 106.65）；
     机制是 edap 的**落盘周期开销**（§10.11.11）。
   **本节的所有数字都是「提交/s 优先」的取舍**；
   如果业务对长尾敏感，要动的是**落盘周期的非落盘开销**，不是合并策略。
7. **未做的**：`async-apply`（完成点=应用）语义下本轮修法**没测过**；
   §10.10.8 已经指出那是下一步该做的实验，现在优先级更高了
   （因为 §10.9.5 说「完成点=应用会把批次打散」，碎片合并正应该在那里收益更大）。
8. **方法论教训：轮数不够会把噪声读成收益。** 探针 8 用 **3 轮**测出
   「thr070 在两个窗口同时白赚（w=256 +3.1%、w=1024 +3.4%）」，
   探针 9 用 **5 轮**复测，w=256 变成 **−10.5%**。
   ⇒ **3 轮不足以分辨 10% 量级差异**；凡是「改一个阈值/一个参数」类的实验，
   至少 5 轮，且**轮次奇偶对调变体顺序**（消顺序偏差）。
   同一条纪律在 campaign L 里也用上了：K → L 的深窗口结论
   （回归被修复）就是靠 5 轮 + 三方同批才定下来的。

#### 10.11.10.1 阈值 0.7：实测了，**不采纳**（探针 8 / 9）

**依据（实测，探针 5）**：w=256 的 busy p50 是 0.72~0.84，w=1024 是 0.32~0.50。
阈值取 0.7 ⇒ w=256 稳定在阈值之上（照合并），w=1024 稳定在阈值之下（一律不合并）。
**预期**：w=1024 的吞吐应该等于或高于 `edap-off` 的 248,113，且**散布收敛**
（不再有 3%~56% 的推迟率摆动）。

**实测（3 轮，探针 8，`/tmp/probe8.txt`）**：

| 窗口 | thr050 | thr070 | off | thr070/thr050 |
|---|---|---|---|---|
| 256 | 125,883 | 129,782 | 95,229 | **1.031×** |
| 1024 | 232,177 | 240,020 | 236,818 | **1.034×** |

⇒ 两个窗口**同时**「白赚」，看起来是个好结果。

**实测（5 轮，探针 9，`/tmp/probe9.txt`，轮次奇偶对调变体顺序）**：

| 窗口 | thr050 | thr070 | off | thr070/thr050 | thr070/off |
|---|---|---|---|---|---|
| 256 | **135,552** | 121,301 | 95,722 | **0.895×（−10.5%）** | 1.267× |
| 1024 | 224,662 | **241,891** | **250,200** | **1.077×（+7.7%）** | 0.967× |

**w=1024 的 thr070 逐轮原值**（看散布）：
234,894 / 241,891 / 244,219 / 241,460 / 244,344
⇒ 中位数 241,891，**极差 3.9%**，确实比 thr050 收敛。

**4 条结论**：

1. **3 轮与 5 轮的结论相反**（w=256：+3.1% → −10.5%）。
   ⇒ 「3 轮白赚」是**轮数不够**，不是收益。
2. **w=256 上 thr070 是净负收益（−10.5%）**：阈值 0.7 把一部分「本该合并」的样本
   判成不合并（§10.11.6 实测 w=256 有 9.1% 的采样 busy < 0.5，
   其中一部分落在 0.5~0.7 之间）⇒ 这一档的收益本来就是靠 0.5 这个阈值吃到的。
3. **w=1024 上 thr070 的 +7.7% 是「搬收益」不是「消摆动」**：
   `thr070/off = 0.967×` —— 阈值 0.7 只是把吞吐从 thr050 挪到接近 off，
   **并没有超过 `edap-off`**（250,200）。
   即阈值 0.7 修掉了一部分「thr050 的合并误判」，代价是 w=256 少了 10.5%；
   **它没有消除判据本身的问题，只是把问题挪到了另一档**。
4. **结论：不采纳阈值 0.7，默认值保持 0.5 不动。**
   campaign L 的默认配置（thr050）结果：w=256 **141,094**、w=1024 **258,109**，
   已经**同时**优于 `edap-off`（104,210 / 248,113）与
   `etcd-aligned`（84,100 / 239,253）。

**下一步该做什么（本轮均未实测）**：真正的问题不是「阈值取多少」，
而是「**判据本身是二值的**」—— 同一时刻要么合并要么不合并，
深窗口在阈值两侧摆动 ⇒ 摆动是**结构性**的，调参只能搬收益。
三个候选方向：

- **迟滞（hysteresis）**：进入合并与退出的阈值不同（如进 0.5 / 出 0.4），消除边界抖动；
- **把判据变成连续量**：不只问「合并/不合并」，而是按 busy 占比**决定合并多少**；
- **换信号**：找一个在深浅窗口**量级差一个数量级**的信号
  （而不是跨在同一区间里），使阈值不那么敏感。

### 10.11.11 w=1024 的尾延迟：真正的差距在「落盘周期的非落盘开销」

**触发**：看 campaign L 的结果时发现「窗口 1024 变慢了」——
吞吐是赢的（258,109，四窗口里最高），但**延迟输了**。
本节把「变慢」拆成三问：**哪一段慢了？是合并造成的吗？机制是什么？**

#### 问一：慢的是哪一段？（拆成 p50 / p99）

| w=1024 | 提交/s | p50 | p99 | p99/p50 |
|---|---|---|---|---|
| edap-t2 | **258,109** | **60.05** | 106.65 | 1.78 |
| edap-off（合并关） | 248,113 | 62.90 | 107.98 | 1.72 |
| edap-base（HEAD） | 228,637 | 64.02 | **94.45** | 1.48 |
| dragonboat | 154,201 | **49.41** | 102.75 | 2.08 |
| etcd-aligned | 239,253 | 61.84 | **70.94** | **1.15** |

- **p50 不是「延迟变好」**：基准把在途深度固定成 16 客户端 × 1024，
  Little's Law ⇒ 平均延迟 ≈ 深度 ÷ 吞吐。
  t2 的 p50 优势（60.05 vs base 64.02）**只是「同样队列深度下干得更快」**，
  不是延迟质量改善。（用实测在途深度校验：14,826 ÷ 258,109 = 57.4ms ≈ 实测 60.05 ✓）
- **p99 是真的尾延迟**，差距很大且**跨实现**：
  **etcd-aligned 70.94ms 比 edap-t2 106.65ms 好 1.50×**，
  而且 **5 轮全稳**（etcd 68.24~74.50，edap-t2 95.10~113.70）。
  ⇒ **etcd 的延迟分布比 edap 紧得多**（p99/p50 = 1.15 vs 1.78）。

> **⚠️ 读上面这两个延迟数之前必须知道：w≥256 的 p50/p99 只覆盖「运行开头」，
> 不是稳态。**
> 延迟记录器是**首 N 采样**（`ClusterProposalHarness.java:457` 容量 **400,000**，
> `ApplyStats.record` 里 `if (i < latencies.length)` 满了就**不再记**，
> 不是环形缓冲），而样本是**每应用一条条目记一个** ⇒
> 覆盖时长 = 400,000 ÷ 提交/s：
>
> | w | edap-t2 | edap-base | etcd-aligned | 覆盖的是 |
> |---|---|---|---|---|
> | 1 | 295.9s | 504.4s | 710.5s | 整轮（15s）✓ |
> | 16 | 18.7s | 24.5s | 49.4s | 整轮 ✓ |
> | 256 | **2.83s** | **3.36s** | **4.76s** | 启动爬坡期 |
> | 1024 | **1.55s** | **1.75s** | **1.67s** | 启动爬坡期 |
>
> ⇒ **w=1024 的 p50/p99 描述的是「选主 + 队列填满 + 批次预热」那 1.5 秒**，
> 不是 15 秒稳态。**本节对 p99 的所有比较都只在这一口径下成立。**
> （**落盘周期开销那组数不受影响** —— `落盘/s` 与 `落盘耗时ms/次` 是整轮 15s 的平均，
> 见 `ClusterProposalHarness.java:990-997`。）
> ⇒ 要测稳态尾延迟，得先把采样器改成环形缓冲或蓄水池抽样（**本轮未做**）。

#### 问二：是合并造成的吗？—— **不是**

消融数据直接否掉这个归因：

| | 合并 | 提交/s | p50 | p99 | p99/p50 |
|---|---|---|---|---|---|
| edap-t2 | 开 | 258,109 | 60.05 | **106.65** | 1.78 |
| edap-off | 关 | 248,113 | 62.90 | **107.98** | 1.72 |

⇒ **关掉合并，p99 反而略差**（107.98 > 106.65）。
深窗口 p99 的差异来自 t2/off 与 HEAD 之间的**其它改动**
（超时清扫 / `notifyProposalSpace` 条件化 / 去冗余 `whenComplete` / 有界 stop），
**不是碎片批次合并**。（§10.11.8 结论 5 已按此更正。）

#### 问三：机制是什么？—— **落盘周期里的非落盘开销**

「落盘周期」= 单节点两次 fsync 之间的间隔 = `3000 ÷ 落盘/s` ms
（`落盘/s` 是 3 个节点求和，见 `ClusterProposalHarness.java:990-997`）。
减去真正的写+fsync 耗时（`落盘耗时ms/次`），剩下的就是**周期里的非落盘开销**：

| w | 变体 | 落盘/s | 周期 | fsync | **非落盘开销** | 占比 | 条/次 | p99/p50 |
|---|---|---|---|---|---|---|---|---|
| 256 | edap-t2 | 202 | 14.85ms | 10.36ms | 4.49ms | 30.2% | 2,099.5 | 1.79 |
| 256 | etcd-aligned | 187 | 16.04ms | 15.11ms | 0.93ms | 5.8% | 1,350.3 | 1.15 |
| **1024** | **edap-t2** | **116** | **25.86ms** | **9.41ms** | **16.45ms** | **63.6%** | 6,773.0 | 1.78 |
| 1024 | edap-off | 135 | 22.22ms | 9.26ms | 12.96ms | 58.3% | 5,438.0 | 1.72 |
| 1024 | edap-base | 146 | 20.55ms | 9.26ms | 11.29ms | 54.9% | 4,691.8 | 1.48 |
| 1024 | dragonboat | 211 | 14.22ms | — | — | — | 2,259.8 | 2.08 |
| **1024** | **etcd-aligned** | **171** | **17.54ms** | **15.83ms** | **1.71ms** | **9.8%** | 4,213.4 | 1.15 |
| 1024 | etcd | 169 | 17.75ms | 15.71ms | 2.04ms | 11.5% | 4,035.3 | 1.16 |

**读表要点**：

1. **etcd 的落盘周期 90% 是 fsync，edap 在 w=1024 只有 36%** ——
   也就是说 **etcd 是被 fsync 卡住的，edap 不是**。
   这与 §10.11.5 的忙时占比探针完全一致（w=1024 edap 的 busy 只有 0.36~0.50，
   所以「合并省不出东西」这个判断是对的）。
2. **edap 的周期开销随窗口深度暴涨**：1.34ms（w=1）→ 1.51（w=16）→ 4.49（w=256）
   → **16.45ms（w=1024）**；同期 etcd 是 0.45 → 0.50 → 0.93 → **1.71ms**。
   **w=1024 这一档 edap 的周期比 etcd 长 8.3ms，而其中 14.7ms 的差距全是「非落盘」部分。**
3. **周期开销与尾延迟同向**（在 edap/etcd 这一族里是单调的）：
   开销 1.71ms → p99/p50 1.15；1.48/1.72/1.78 ↔ 开销 11.29/12.96/16.45ms。
   **这是相关性，不是因果**（三个 edap 变体来自三个不同的构建，彼此有其它差异），
   但方向明确、量级够大，值得当成下一步的靶子。
4. **edap 在 w=1024 用「周期 25.86ms / 批 6,773 条」换到了比 etcd 高 8% 的吞吐**
   （258,109 vs 239,253）—— **但这条吞吐是用尾延迟换的**：
   p99 106.65 vs 70.94。**这个取舍是有意的，不是 bug**；
   但如果业务对长尾敏感，就得反过来。

#### 这不是新问题：§10（campaign G）已经量到过，但一直没定位

§10 里有一条结论：「**高窗口 edap 的落盘周期里多出 6~11ms 非落盘开销
（etcd 只有 1.5~1.9ms），这是被反超的直接原因。成因未定位**
（候选：提案合批等待 / apply 线程交接 / Advance 时序 / 组装闸门被上一次 fsync 挡住）」。

**campaign L 把它量化成了 w=1024 的 16.45ms（占周期 63.6%）**，
并且给出了一个新的、之前没有的判据：**周期开销可以直接从 harness 的
`落盘/s` 和 `落盘耗时ms/次` 算出来**（`3000 ÷ 落盘/s − 落盘耗时ms/次`），
不需要插桩。

#### 下一步（未实测，按性价比排序）

1. **先定位那 16.45ms**：在 `Node.run` 里给「组装 Ready → 交付落盘 → 落盘完成通知」
   这三段各加一个时间戳（探针覆盖层，不动生产代码），
   看 16.45ms 落在哪一段。**候选（沿用 §10 的清单）**：
   提案合批等待 / apply 线程交接 / Advance 时序 / 组装闸门被上一次 fsync 挡住。
   ⇒ 这一步能直接回答「w=1024 为什么慢」。
2. **再做 p99 目标的取舍实验**：既然 p99 由周期开销主导，
   可以试「**减小批次以缩短周期**」（把 `DEFER_MIN_BUSY_RATIO` 调低 / 关掉合并）
   在 w=1024 的 p99 表现 —— **但这会把吞吐换回去**，
   属于**明确的取舍**，不是优化。
3. **不要去调合并的阈值**：§10.11.10.1 已经证明阈值只搬收益，
   而消融已经证明合并不是 p99 的原因。

