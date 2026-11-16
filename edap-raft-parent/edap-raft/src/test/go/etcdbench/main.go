// etcd 对照基准：go.etcd.io/raft/v3 v3.6 库 + etcd server v3.6 的接线方式。
//
// 用来和 edap-raft 的 ClusterProposalHarness 做同硬件对比。
//
// 与 Java 侧 io.edap.raft.ClusterProposalHarness 逐项对齐：
//   - 3 节点集群、16 客户端、20 字节载荷（4B clientId + 8B seq + 8B publishedAt）
//   - 客户端向当前 leader 提案，每客户端在途窗口可配
//   - 完成点 = 提案被「提交」（不是被应用）
//   - 延迟样本 = 发布 → leader 应用
//   - 输出格式与 Java 的 report() 一致，同一套解析器直接可用
//
// 接线照抄 etcd server v3.6 的 etcdserver/raft.go：
//   Ready → applyc（无缓冲）→ leader 先发后存（free-order）→ Save（一次 fsync）
//   → notifyc → follower 落盘后发 → Advance
//
// 跑法：
//
//	etcdbench [节点数] [客户端数] [时长秒] [mode] [每客户端在途上限]
//	          [tick毫秒] [变体] [数据目录]
package main

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"go.etcd.io/etcd/server/v3/storage/wal"
	"go.etcd.io/raft/v3"
	"go.etcd.io/raft/v3/raftpb"
	"go.uber.org/zap"
)

const (
	// 载荷长度：与 Java 侧 ClusterProposalHarness.PAYLOAD_LEN 一致
	payloadLen = 20

	// 单次提案的等待上限。raft.Node.Propose 会阻塞到 raft run loop 取走消息，
	// 没有 leader 时会一直阻塞 —— 必须给超时。
	proposeTimeout = 5 * time.Second

	// 每对端投递队列容量（近似无界、保序）
	deliveryQueueCap = 65536

	// 客户端等待提交通知的兜底超时（与 Java 侧 ASYNC_TIMEOUT_MILLIS+5000 对齐）
	notifyTimeout = 35 * time.Second
)

// ---------------------------------------------------------------- 计数器

var (
	published       atomic.Int64
	failed          atomic.Int64
	rejectedBusy    atomic.Int64
	rejectedDropped atomic.Int64
	leaderCommitted atomic.Int64
	sentMessages    atomic.Int64
	persistRounds   atomic.Int64
	persistEntries  atomic.Int64
	persistNanos    atomic.Int64
)

// ---------------------------------------------------------------- 变体

// variant 是一套 raft 配置。etcd-aligned 与 edap-raft 的 harness 逐项对齐，
// 消掉配置差异，只留「驱动架构」这一项差异。
type variant struct {
	name            string
	checkQuorum     bool
	preVote         bool
	maxSizePerMsg   uint64
	maxInflightMsgs int
	tickMillis      int
}

func variants() map[string]variant {
	return map[string]variant{
		// 与 ClusterProposalHarness 的 RaftConfig 对齐：
		// electionTick=10 heartbeatTick=1 tick=50ms，不开 CheckQuorum / PreVote，
		// maxSizePerMsg 无限，maxInflightMsgs=256
		"etcd-aligned": {
			name:            "etcd-aligned",
			checkQuorum:     false,
			preVote:         false,
			maxSizePerMsg:   math.MaxUint64,
			maxInflightMsgs: 256,
			tickMillis:      50,
		},
		// etcd server v3.6 出厂配置：
		// bootstrap.go:522-533 + raft.go:37,40
		// maxSizePerMsg=1MB，maxInflightMsgs=4096/8=512，CheckQuorum=true，
		// PreVote=cfg.PreVote，ElectionTick=10，HeartbeatTick=1，tick=100ms
		"etcd": {
			name:            "etcd",
			checkQuorum:     true,
			preVote:         true,
			maxSizePerMsg:   1 * 1024 * 1024,
			maxInflightMsgs: 512,
			tickMillis:      100,
		},
	}
}

// ---------------------------------------------------------------- 数据结构

type notice struct {
	seq uint64
}

type proposal struct {
	seq         uint64
	publishedAt int64
}

// benchNode 是一个 raft 节点。
type benchNode struct {
	id   uint64
	node raft.Node
	ms   *raft.MemoryStorage
	w    *wal.WAL
	dir  string
	c    *cluster

	// out[peerID] → 投递到该对端的队列（由投递协程消费）
	out map[uint64]chan raftpb.Message

	isLeader atomic.Bool

	// 分节点的落盘计数（用来区分 leader 与 follower 的落盘节奏）
	persistRounds  atomic.Int64
	persistEntries atomic.Int64
}

// cluster 持有全局状态。
type cluster struct {
	nodes  map[uint64]*benchNode
	leader atomic.Uint64

	// notify[cid]：客户端 cid 的提交通知队列
	notify []chan notice

	// notifyApply[cid]：客户端 cid 的「已应用」通知队列。
	// sync 模式用它 —— 对应 etcd server 的 applyWait：
	// 客户端要等到提案被 leader 应用才返回。
	notifyApply []chan notice

	// syncMode：true = 客户端等到「应用」（etcd server 的客户端语义）；
	// false = 客户端等到「提交」（异步提案 API 的语义）。
	syncMode bool

	// 延迟采样
	latApply  latencyRecorder
	latCommit latencyRecorder

	// 延迟分解：把「发布→提交」拆成
	//   ① 发布 → leader 把这批条目发出去（MsgApp）
	//   ② MsgApp 发出 → 提案被提交
	// 用于定位 RTT 到底耗在哪一段。
	latPropToSend latencyRecorder
	latSendToCommit latencyRecorder

	// sendMarks：leader 发 MsgApp 的时刻，按条目 index 排队
	// （MsgApp 携带的条目 index 连续、提交顺序也连续，所以用 FIFO 就够）
	sendMarkMu   sync.Mutex
	sendMarks    []sendMark
	sendMarkHead int

	// inFlight：当前在途（已发布未提交）的提案数，用来判断流水线有没有填满
	inFlight atomic.Int64
	// inFlightSamples：在途深度的采样（每 100ms 一个点）
	inFlightSamples []int64
}

// sendMark 记录一次 MsgApp 发送：它携带的最后一个条目 index + 发送时刻。
type sendMark struct {
	lastIdx uint64
	at      int64
}

// markSend 记一次 MsgApp 发送时刻。
func (c *cluster) markSend(lastIdx uint64, at int64) {
	c.sendMarkMu.Lock()
	c.sendMarks = append(c.sendMarks, sendMark{lastIdx: lastIdx, at: at})
	c.sendMarkMu.Unlock()
}

// lookupSend 找出承载该条目的那次 MsgApp 的发送时刻，并顺手清理已过期的记录。
func (c *cluster) lookupSend(idx uint64) (int64, bool) {
	c.sendMarkMu.Lock()
	defer c.sendMarkMu.Unlock()
	for c.sendMarkHead < len(c.sendMarks) {
		m := c.sendMarks[c.sendMarkHead]
		if m.lastIdx >= idx {
			// 命中：把已经过期（整批都已提交）的前缀丢掉
			if c.sendMarkHead > 0 {
				c.sendMarks = append(c.sendMarks[:0], c.sendMarks[c.sendMarkHead:]...)
				c.sendMarkHead = 0
			}
			return m.at, true
		}
		c.sendMarkHead++
	}
	if c.sendMarkHead > 0 {
		c.sendMarks = c.sendMarks[:0]
		c.sendMarkHead = 0
	}
	return 0, false
}

// applyBatch 是一次 apply 的输入，照 etcd 的 toApply 结构。
type applyBatch struct {
	entries []raftpb.Entry
	notifyc chan struct{}
}

// ---------------------------------------------------------------- 主流程

func main() {
	nodeCount := atoi(arg(0), 3)
	clients := atoi(arg(1), 16)
	durationSec := atoi(arg(2), 10)
	mode := arg(3)
	if mode == "" {
		mode = "async"
	}
	window := atoi(arg(4), 1)
	tickMillis := atoi(arg(5), 0)
	varName := arg(6)
	if varName == "" {
		varName = "etcd-aligned"
	}
	root := arg(7)

	syncMode := false
	switch mode {
	case "async":
	case "sync":
		syncMode = true
	default:
		fmt.Fprintf(os.Stderr, "只支持 async / sync 模式（收到 %q）\n", mode)
		os.Exit(2)
	}
	vs := variants()
	v, ok := vs[varName]
	if !ok {
		fmt.Fprintf(os.Stderr, "未知变体 %q，可选：etcd-aligned / etcd\n", varName)
		os.Exit(2)
	}
	if tickMillis == 0 {
		tickMillis = v.tickMillis
	}

	keep := root != ""
	if !keep {
		d, err := os.MkdirTemp("", "etcdbench")
		if err != nil {
			panic(err)
		}
		root = d
	} else if err := os.MkdirAll(root, 0755); err != nil {
		panic(err)
	}

	c, err := newCluster(nodeCount, clients, root, v, syncMode)
	if err != nil {
		panic(err)
	}

	stopCh := make(chan struct{})
	var wg sync.WaitGroup
	for _, n := range c.nodes {
		wg.Add(1)
		go func(bn *benchNode) {
			defer wg.Done()
			bn.readyLoop(c, stopCh)
		}(n)
		wg.Add(1)
		go func(bn *benchNode) {
			defer wg.Done()
			bn.tickLoop(tickMillis, stopCh)
		}(n)
	}

	// 客户端
	var clientWg sync.WaitGroup
	for cid := 0; cid < clients; cid++ {
		clientWg.Add(1)
		go func(cid int) {
			defer clientWg.Done()
			c.runClient(uint32(cid), window, stopCh)
		}(cid)
	}

	// 在途深度采样：判断流水线有没有被「每客户端在途窗口」填满
	gaugeDone := make(chan struct{})
	go func() {
		defer close(gaugeDone)
		tk := time.NewTicker(100 * time.Millisecond)
		defer tk.Stop()
		for {
			select {
			case <-stopCh:
				return
			case <-tk.C:
				c.inFlightSamples = append(c.inFlightSamples, c.inFlight.Load())
			}
		}
	}()

	time.Sleep(time.Duration(durationSec) * time.Second)
	close(stopCh)
	clientWg.Wait()
	<-gaugeDone
	c.report(mode, varName, nodeCount, clients, durationSec, root, keep, window)

	// 收尾
	for _, n := range c.nodes {
		n.node.Stop()
	}
	time.Sleep(200 * time.Millisecond)
	for _, n := range c.nodes {
		if n.w != nil {
			_ = n.w.Close()
		}
	}
	if !keep {
		_ = os.RemoveAll(root)
	}
}

// ---------------------------------------------------------------- 集群搭建

func newCluster(nodeCount, clients int, root string, v variant, syncMode bool) (*cluster, error) {
	c := &cluster{
		nodes:       make(map[uint64]*benchNode),
		notify:      make([]chan notice, clients),
		notifyApply: make([]chan notice, clients),
		syncMode:    syncMode,
	}
	// 采样容量与 Java 侧一致（ApplyStats(clients, 400_000)）
	c.latApply.samples = make([]int64, 400_000)
	c.latCommit.samples = make([]int64, 400_000)
	c.latPropToSend.samples = make([]int64, 400_000)
	c.latSendToCommit.samples = make([]int64, 400_000)
	for i := range c.notify {
		c.notify[i] = make(chan notice, deliveryQueueCap)
		c.notifyApply[i] = make(chan notice, deliveryQueueCap)
	}

	peers := make([]raft.Peer, 0, nodeCount)
	for i := 1; i <= nodeCount; i++ {
		peers = append(peers, raft.Peer{ID: uint64(i)})
	}

	lg := zap.NewNop()
	for i := 1; i <= nodeCount; i++ {
		id := uint64(i)
		dir := filepath.Join(root, fmt.Sprintf("node-%d", i))
		// wal.Create 要求目录不存在 —— 不能提前建
		w, err := wal.Create(lg, dir, nil)
		if err != nil {
			return nil, fmt.Errorf("wal.Create(%s): %w", dir, err)
		}
		ms := raft.NewMemoryStorage()
		cfg := &raft.Config{
			ID:              id,
			ElectionTick:    10,
			HeartbeatTick:   1,
			Storage:         ms,
			MaxSizePerMsg:   v.maxSizePerMsg,
			MaxInflightMsgs: v.maxInflightMsgs,
			CheckQuorum:     v.checkQuorum,
			PreVote:         v.preVote,
			Logger:          &benchLogger{},
		}
		bn := &benchNode{
			id:  id,
			ms:  ms,
			w:   w,
			dir: dir,
			c:   c,
			out: make(map[uint64]chan raftpb.Message),
		}
		bn.node = raft.StartNode(cfg, peers)
		c.nodes[id] = bn
	}

	// 投递通道：每对端一条队列 + 一个投递协程。
	// raft.Node.Step 是阻塞的（要等对端 raft run loop 收下消息），
	// 同步调用会让发送方的 Ready 循环卡在对端的 fsync 上 ——
	// 那是「假串行」，不是 etcd 的行为（etcd 的 rafthttp 也是异步投递）。
	for _, from := range c.nodes {
		for id, to := range c.nodes {
			if id == from.id {
				continue
			}
			ch := make(chan raftpb.Message, deliveryQueueCap)
			from.out[id] = ch
			go func(target *benchNode, ch chan raftpb.Message) {
				for m := range ch {
					if err := target.node.Step(context.Background(), m); err != nil {
						// 目标节点已停止之类，忽略
					}
				}
			}(to, ch)
		}
	}
	return c, nil
}

// ---------------------------------------------------------------- Ready 循环

// readyLoop 照抄 etcd server v3.6 的 etcdserver/raft.go 的 Ready 循环。
func (bn *benchNode) readyLoop(c *cluster, stopCh <-chan struct{}) {
	// applyc 无缓冲 —— 与 etcd 一致（etcdserver/raft.go:145）
	applyc := make(chan applyBatch)

	// apply 协程：应用已提交条目。
	// etcd 把 ap 投到 applyc 是在 Save 之前，所以 apply 与 fsync 是并行的。
	go func() {
		for {
			select {
			case ab := <-applyc:
				for i := range ab.entries {
					ent := &ab.entries[i]
					if ent.Type != raftpb.EntryNormal {
						// 成员变更条目不计数。Bootstrap 已经把 voter 加进 tracker 了，
						// 再 ApplyConfChange 一次会 ResetState 掉 follower 的 Progress。
						continue
					}
					cid, seq, publishedAt, ok := decodePayload(ent.Data)
					if !ok {
						continue
					}
					if uint64(cid) >= uint64(len(c.notify)) {
						continue
					}
					// 只在 leader 上计数与采样（同一个条目每个节点都会应用一次）
					if bn.id != c.leader.Load() {
						continue
					}
					leaderCommitted.Add(1)
					now := time.Now().UnixNano()
					c.latApply.add(now - publishedAt)
					// sync 模式：通知点在「应用」—— 与 etcd server 的 applyWait 一致。
					// async 模式：通知点在「提交」（notifyCommitted 里发）。
					if c.syncMode {
						c.notifyApply[cid] <- notice{seq: seq}
					}
				}
				<-ab.notifyc
			case <-stopCh:
				return
			}
		}
	}()

	for {
		select {
		case <-stopCh:
			return
		case rd := <-bn.node.Ready():
			// ① 更新 leader 状态（etcdserver/raft.go:196-207）
			if rd.SoftState != nil {
				bn.isLeader.Store(rd.SoftState.RaftState == raft.StateLeader)
				if rd.SoftState.Lead != raft.None {
					c.leader.Store(rd.SoftState.Lead)
				} else {
					c.leader.Store(raft.None)
				}
			}

			// ② 提交通知：在 fsync 之前发（与 etcd 的 applyc 投递同在 Save 之前）
			if len(rd.CommittedEntries) > 0 {
				bn.notifyCommitted(c, rd.CommittedEntries)
			}

			// ③ 把已提交条目交给 apply 协程。
			//    applyc 无缓冲 —— 与 etcd 一致（etcdserver/raft.go:145）。
			//    etcd 把这一步放在 Save 之前（etcdserver/raft.go:231），
			//    所以 apply 与 fsync 是并行的；这里照抄。
			notifyc := make(chan struct{}, 1)
			select {
			case applyc <- applyBatch{entries: rd.CommittedEntries, notifyc: notifyc}:
			case <-stopCh:
				return
			}

			// ④ leader 先发后存（etcd v3.4+ 的 free-order，etcdserver/raft.go:235）
			if bn.isLeader.Load() {
				bn.send(rd.Messages)
			}

			// ⑤ 落盘：一次 Save = 一次 fsync（etcdserver/raft.go:247）
			if len(rd.Entries) > 0 {
				t0 := time.Now()
				if err := bn.w.Save(rd.HardState, rd.Entries); err != nil {
					panic(err)
				}
				persistRounds.Add(1)
				persistEntries.Add(int64(len(rd.Entries)))
				persistNanos.Add(time.Since(t0).Nanoseconds())
				bn.persistRounds.Add(1)
				bn.persistEntries.Add(int64(len(rd.Entries)))
			} else if !raft.IsEmptyHardState(rd.HardState) {
				if err := bn.w.Save(rd.HardState, nil); err != nil {
					panic(err)
				}
				// 硬状态单独落盘也算一次 fsync，但不计入条目统计
				bn.persistRounds.Add(1)
			}
			if err := bn.ms.Append(rd.Entries); err != nil {
				panic(err)
			}
			if err := bn.ms.SetHardState(rd.HardState); err != nil {
				panic(err)
			}

			// ⑥ follower 落盘后发；leader 只需解除 apply 门控
			//    （etcdserver/raft.go:297-323）
			if !bn.isLeader.Load() {
				msgs := rd.Messages
				notifyc <- struct{}{}
				bn.send(msgs)
			} else {
				notifyc <- struct{}{}
			}

			// ⑦ 告诉 raft 可以继续（etcdserver/raft.go:326）
			bn.node.Advance()
		}
	}
}

// notifyCommitted 把已提交条目里的客户端提案通知给对应的客户端。
func (bn *benchNode) notifyCommitted(c *cluster, entries []raftpb.Entry) {
	for i := range entries {
		ent := &entries[i]
		if ent.Type != raftpb.EntryNormal {
			continue
		}
		cid, seq, publishedAt, ok := decodePayload(ent.Data)
		if !ok {
			continue
		}
		if uint64(cid) >= uint64(len(c.notify)) {
			continue
		}
		// 只由 leader 通知（避免 leader 切换时重复通知）
		if bn.id != c.leader.Load() {
			continue
		}
		now := time.Now().UnixNano()
		// 延迟分解：发布 → MsgApp 发出，MsgApp 发出 → 提交
		if sentAt, hit := c.lookupSend(ent.Index); hit {
			c.latPropToSend.add(sentAt - publishedAt)
			c.latSendToCommit.add(now - sentAt)
		}
		if c.syncMode {
			// sync 模式的完成点是「应用」（etcd server 的 applyWait 语义），
			// 通知由 apply 协程发出，这里不能往 c.notify 发（客户端不读它）。
			continue
		}
		select {
		case c.notify[cid] <- notice{seq: seq}:
		default:
			// 通知队列满：不能丢（丢了客户端会永远等不到），
			// 但也不能卡死 raft 推进。这里选择阻塞发送。
			c.notify[cid] <- notice{seq: seq}
		}
	}
}

// send 把消息投递给对端（异步投递，顺序保证）。
func (bn *benchNode) send(msgs []raftpb.Message) {
	for i := range msgs {
		m := msgs[i]
		ch, ok := bn.out[m.To]
		if !ok {
			continue
		}
		ch <- copyMessage(m)
		sentMessages.Add(1)
		if int(m.Type) < len(msgTypes) {
			msgTypes[m.Type].Add(1)
		}
		if m.Type == raftpb.MsgApp {
			msgAppEntries.Add(int64(len(m.Entries)))
			// leader 发出的 MsgApp：记下发送时刻，用来分解 RTT
			if bn.isLeader.Load() && len(m.Entries) > 0 && bn.c != nil {
				bn.c.markSend(m.Entries[len(m.Entries)-1].Index, time.Now().UnixNano())
			}
		}
	}
}

// msgTypes 统计各类 raft 消息的发送条数（排查用）。
// 用定长数组 + atomic，避免多节点 Ready 协程并发写 map 造成数据竞争。
var msgTypes [16]atomic.Int64

// msgAppEntries 统计 MsgApp 里携带的条目总数。
// 与 leaderCommitted 对比可以看出有多少是「重复重发」（etcd 的 raft 库
// 在每次提案后都会 bcastAppend，未被 ack 的条目会被反复重发）。
var msgAppEntries atomic.Int64

func dumpMsgTypes() {
	for i := range msgTypes {
		n := msgTypes[i].Load()
		if n == 0 {
			continue
		}
		fmt.Fprintf(os.Stderr, "  消息 %-16s %d\n", raftpb.MessageType(i).String(), n)
	}
	fmt.Fprintf(os.Stderr, "  MsgApp 携带条目 %d 条（提交 %d 条，倍数 %.2f）\n",
		msgAppEntries.Load(), leaderCommitted.Load(),
		float64(msgAppEntries.Load())/math.Max(1, float64(leaderCommitted.Load())))
}

// copyMessage 深拷贝消息里的条目。
// raft 的 unstable.entries 底层数组会被后续 append 覆写，
// 拿着引用跨 goroutine 投递会出数据竞争。
func copyMessage(m raftpb.Message) raftpb.Message {
	out := m
	if len(m.Entries) > 0 {
		out.Entries = make([]raftpb.Entry, len(m.Entries))
		for i := range m.Entries {
			e := m.Entries[i]
			if len(e.Data) > 0 {
				e.Data = append([]byte(nil), e.Data...)
			}
			out.Entries[i] = e
		}
	}
	return out
}

// tickLoop 按固定间隔驱动 raft 的逻辑时钟。
func (bn *benchNode) tickLoop(tickMillis int, stopCh <-chan struct{}) {
	t := time.NewTicker(time.Duration(tickMillis) * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-stopCh:
			return
		case <-t.C:
			bn.node.Tick()
		}
	}
}

// ---------------------------------------------------------------- 客户端

// runClient 维护每客户端的在途窗口。
//
// 窗口里的每条提案都带自己的 seq；leader 提交后按 seq 精确通知回来，
// 所以不需要「等计数前进」那种模糊匹配。
func (c *cluster) runClient(cid uint32, window int, stopCh <-chan struct{}) {
	seq := uint64(0)
	// pending[seq] = 发布时刻（纳秒）
	pending := make(map[uint64]int64, window)
	order := make([]uint64, 0, window)
	// 复用同一个 timer：time.After 每次都新建一个 35s 的 timer，
	// 高吞吐下会攒几十万个活着的 timer。
	timer := time.NewTimer(notifyTimeout)
	defer timer.Stop()

	for {
		select {
		case <-stopCh:
			return
		default:
		}

		lead := c.leader.Load()
		if lead == raft.None {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		leader, ok := c.nodes[lead]
		if !ok {
			time.Sleep(10 * time.Millisecond)
			continue
		}

		// 补满窗口
		for len(pending) < window {
			seq++
			p := &proposal{seq: seq, publishedAt: time.Now().UnixNano()}
			ctx, cancel := context.WithTimeout(context.Background(), proposeTimeout)
			err := leader.node.Propose(ctx, encodePayload(cid, seq, p.publishedAt))
			cancel()
			if err != nil {
				// 提案没进 raft 日志
				failed.Add(1)
				rejectedDropped.Add(1)
				break
			}
			published.Add(1)
			pending[seq] = p.publishedAt
			order = append(order, seq)
			c.inFlight.Add(1)
		}

		if len(pending) == 0 {
			time.Sleep(time.Millisecond)
			continue
		}

		// 等一条提交通知
		// async 模式：完成点 = 提交（raft 已提交，通知由 notifyCommitted 发出）
		// sync  模式：完成点 = 应用（etcd server 的 applyWait 语义，通知由 apply 协程发出）
		waitCh := c.notify[cid]
		if c.syncMode {
			waitCh = c.notifyApply[cid]
		}
		select {
		case n := <-waitCh:
			if publishedAt, ok := pending[n.seq]; ok {
				if !c.syncMode {
					// 客户端真正等到的点：提案被提交（与 Java 侧 future.get() 同一口径）
					c.latCommit.add(time.Now().UnixNano() - publishedAt)
				}
				// sync 模式的「发布→应用」由 apply 协程采样（避免重复计数）
				delete(pending, n.seq)
				c.inFlight.Add(-1)
			}
			// order 里留着已完成的条目，等它浮到队首时再清理
			if len(order) > 1024 && len(order) > 2*len(pending) {
				compacted := make([]uint64, 0, len(pending))
				for _, s := range order {
					if _, ok := pending[s]; ok {
						compacted = append(compacted, s)
					}
				}
				order = compacted
			}
		case <-timer.C:
			// 队首的提案没等到提交通知 —— 判定为失败
			if len(order) > 0 {
				oldest := order[0]
				order = order[1:]
				if _, ok := pending[oldest]; ok {
					delete(pending, oldest)
					failed.Add(1)
					rejectedDropped.Add(1)
					c.inFlight.Add(-1)
				}
			}
			timer.Reset(notifyTimeout)
		case <-stopCh:
			return
		}
	}
}

// ---------------------------------------------------------------- 载荷

func encodePayload(cid uint32, seq uint64, publishedAt int64) []byte {
	buf := make([]byte, payloadLen)
	binary.BigEndian.PutUint32(buf[0:4], cid)
	binary.BigEndian.PutUint64(buf[4:12], seq)
	binary.BigEndian.PutUint64(buf[12:20], uint64(publishedAt))
	return buf
}

func decodePayload(data []byte) (cid uint32, seq uint64, publishedAt int64, ok bool) {
	if len(data) != payloadLen {
		return 0, 0, 0, false
	}
	cid = binary.BigEndian.Uint32(data[0:4])
	seq = binary.BigEndian.Uint64(data[4:12])
	publishedAt = int64(binary.BigEndian.Uint64(data[12:20]))
	return cid, seq, publishedAt, true
}

// ---------------------------------------------------------------- 统计输出

// report 逐字复刻 Java 侧 ClusterProposalHarness.report() 的行，
// 让同一套解析器能直接吃。
func (c *cluster) report(mode, varName string, nodeCount, clients, durationSec int,
	root string, keep bool, window int) {
	sec := float64(durationSec)
	pr := persistRounds.Load()
	pe := persistEntries.Load()
	batch := 0.0
	if pr > 0 {
		batch = float64(pe) / float64(pr)
	}
	fmt.Printf("mode=%-13s 传输=%-6s 节点=%d 客户端=%-3d  发布 %9.0f/s  提交 %9.0f/s  落盘 %7.0f/s  %7.1f 条/次落盘  转发消息 %9.0f/s\n",
		mode, "direct", nodeCount, clients,
		float64(published.Load())/sec,
		float64(leaderCommitted.Load())/sec,
		float64(pr)/sec,
		batch,
		float64(sentMessages.Load())/sec)
	pn := persistNanos.Load()
	avgMs := 0.0
	if pr > 0 {
		avgMs = float64(pn) / 1e6 / float64(pr)
	}
	fmt.Printf("  落盘耗时 平均 %.2fms/次（共 %d 次）\n", avgMs, pr)
	total := published.Load() + failed.Load()
	rej := 0.0
	if total > 0 {
		rej = 100.0 * float64(failed.Load()) / float64(total)
	}
	fmt.Printf("  失败 %d（队列满 %d / raft 丢弃 %d）拒绝率 %.4f%%\n",
		failed.Load(), rejectedBusy.Load(), rejectedDropped.Load(), rej)
	if s := c.latApply.sorted(); len(s) > 0 {
		fmt.Printf("  端到端延迟(发布→应用) p50=%.2fms p99=%.2fms 均值=%.2fms (样本 %d/%d)\n",
			float64(pct(s, 50))/1e6, float64(pct(s, 99))/1e6, mean(s)/1e6,
			c.latApply.size(), c.latApply.total())
	}
	if s := c.latCommit.sorted(); len(s) > 0 {
		fmt.Printf("  端到端延迟(发布→提交) p50=%.2fms p99=%.2fms 均值=%.2fms (样本 %d/%d)\n",
			float64(pct(s, 50))/1e6, float64(pct(s, 99))/1e6, mean(s)/1e6,
			c.latCommit.size(), c.latCommit.total())
	}
	fmt.Printf("  变体 %s  落盘条目/s %.0f\n", varName, float64(pe)/sec)
	if len(c.inFlightSamples) > 0 {
		sum := int64(0)
		for _, v := range c.inFlightSamples {
			sum += v
		}
		fmt.Printf("  在途深度 平均 %.1f 条（上限 %d）\n",
			float64(sum)/float64(len(c.inFlightSamples)), clients*window)
	}
	if keep {
		fmt.Printf("  数据目录: %s\n", root)
	}
	dumpMsgTypes()
	dumpRttBreakdown(c, sec)
	fmt.Println()
}

// dumpRttBreakdown 把「发布→提交」的 RTT 拆成两段，并打印分节点的落盘节奏。
// 全部打到 stderr —— stdout 的格式要跟 Java 侧的 report() 完全一致，
// 同一套解析器才能直接吃。
func dumpRttBreakdown(c *cluster, sec float64) {
	fmt.Fprintf(os.Stderr, "  [dbg] sendMarks=%d head=%d marks=%v\n", len(c.sendMarks), c.sendMarkHead, c.sendMarks[:min(3, len(c.sendMarks))])
	if s := c.latPropToSend.sorted(); len(s) > 0 {
		fmt.Fprintf(os.Stderr, "  RTT 分解① 发布→MsgApp发出 p50=%.2fms p99=%.2fms 均值=%.2fms\n",
			float64(pct(s, 50))/1e6, float64(pct(s, 99))/1e6, mean(s)/1e6)
	}
	if s := c.latSendToCommit.sorted(); len(s) > 0 {
		fmt.Fprintf(os.Stderr, "  RTT 分解② MsgApp发出→提交 p50=%.2fms p99=%.2fms 均值=%.2fms\n",
			float64(pct(s, 50))/1e6, float64(pct(s, 99))/1e6, mean(s)/1e6)
	}
	for _, n := range c.nodes {
		pr := n.persistRounds.Load()
		pe := n.persistEntries.Load()
		batch := 0.0
		if pr > 0 {
			batch = float64(pe) / float64(pr)
		}
		role := "follower"
		if n.id == c.leader.Load() {
			role = "leader  "
		}
		fmt.Fprintf(os.Stderr, "  节点%d(%s) 落盘 %6.1f 次/s  %7.1f 条/次落盘\n",
			n.id, role, float64(pr)/sec, batch)
	}
}

// ---------------------------------------------------------------- 工具

// latencyRecorder 蓄水池抽样的延迟采样器。
//
// 旧版是「固定容量的数组，满了就丢掉后面的采样」，于是采样只覆盖运行开头：
// 容量 400,000、每应用一条条目记一个 ⇒ 覆盖时长 = 400,000 ÷ 提交/s，
// w=1024 时只有约 1.5s —— 测到的是启动爬坡期，不是稳态，尾延迟因此完全失真。
//
// 改成蓄水池（Algorithm R）之后：池子里的 capacity 个样本是整轮的等概率无偏抽样，
// p50/p99 反映的是整轮分布（含尾延迟）。与 Java 侧的 LatencyRecorder 口径一致。
type latencyRecorder struct {
	mu      sync.Mutex
	samples []int64
	seen    int64
}

func (r *latencyRecorder) add(v int64) {
	if v < 0 {
		v = 0
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	n := r.seen
	r.seen++
	if n < int64(len(r.samples)) {
		r.samples[n] = v
		return
	}
	// 以 capacity/(n+1) 的概率替换掉池子里随机一个槽位
	j := rand.Int63n(n + 1)
	if j < int64(len(r.samples)) {
		r.samples[j] = v
	}
}

// size 抽中的样本数（= min(见过的总数, 容量)）
func (r *latencyRecorder) size() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return int(min(r.seen, int64(len(r.samples))))
}

// total 总共见过的样本数
func (r *latencyRecorder) total() int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.seen
}

func (r *latencyRecorder) sorted() []int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	cnt := int(min(r.seen, int64(len(r.samples))))
	if cnt == 0 {
		return nil
	}
	out := make([]int64, cnt)
	copy(out, r.samples[:cnt])
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

// pct 取分位数（输入已排序）。公式与 Java 侧一致：ceil(p/100*n)-1。
func pct(sorted []int64, p float64) int64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(math.Ceil(p/100.0*float64(len(sorted)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func mean(values []int64) float64 {
	if len(values) == 0 {
		return 0
	}
	var sum int64
	for _, v := range values {
		sum += v
	}
	return float64(sum) / float64(len(values))
}

func atoi(s string, def int) int {
	if s == "" {
		return def
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return v
}

func arg(i int) string {
	if len(os.Args) > i+1 {
		return os.Args[i+1]
	}
	return ""
}

// benchLogger 是个安静的 raft 日志器。
type benchLogger struct{}

func (l *benchLogger) Debug(v ...interface{})                 {}
func (l *benchLogger) Debugf(format string, v ...interface{}) {}
func (l *benchLogger) Info(v ...interface{})                  {}
func (l *benchLogger) Infof(format string, v ...interface{})  {}
func (l *benchLogger) Warning(v ...interface{})               {}
func (l *benchLogger) Warningf(format string, v ...interface{}) {
}

func (l *benchLogger) Error(v ...interface{}) {
	fmt.Fprintln(os.Stderr, v...)
}

func (l *benchLogger) Errorf(format string, v ...interface{}) {
	fmt.Fprintf(os.Stderr, format+"\n", v...)
}

func (l *benchLogger) Fatal(v ...interface{}) {
	fmt.Fprintln(os.Stderr, v...)
	os.Exit(1)
}

func (l *benchLogger) Fatalf(format string, v ...interface{}) {
	fmt.Fprintf(os.Stderr, format+"\n", v...)
	os.Exit(1)
}

func (l *benchLogger) Panic(v ...interface{}) {
	fmt.Fprintln(os.Stderr, v...)
	panic(v)
}

func (l *benchLogger) Panicf(format string, v ...interface{}) {
	s := fmt.Sprintf(format, v...)
	fmt.Fprintln(os.Stderr, s)
	panic(s)
}
