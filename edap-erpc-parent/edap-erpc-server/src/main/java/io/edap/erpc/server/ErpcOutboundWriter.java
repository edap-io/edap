/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.server;

import io.edap.buffer.FastBuf;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcFrameEncoder;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.NioSession;
import io.edap.pool.Pool;
import io.edap.util.UnsafeUtil;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * eRPC 出站帧写出器。
 *
 * <p><b>结构与 HTTP/2 的 {@code Http2FrameWriter} 同构</b>：帧不各自分配一个
 * {@code byte[]}，而是<b>直接写进一块出站累积缓冲</b>，攒够一次 socket 写再发出去。</p>
 *
 * <p>好处有两个：</p>
 * <ul>
 *   <li><b>一条响应只做 1 次 socket 写</b>（而不是「载荷 → 帧数组 → 写缓冲」两次拷贝）；</li>
 *   <li>少一批中间 {@code byte[]} 分配。</li>
 * </ul>
 *
 * <p><b>★ 线程模型（本类的核心）</b>：</p>
 *
 * <p>业务线程池里的 N 个 worker 都要回帧。旧实现把「追加帧」和「socket 写」放在
 * <b>同一把 {@code synchronized} 锁</b>里，而 socket 写是一次 syscall（~6 µs）
 * ⇒ N 个 worker 在同一把锁上排队（实测每次取锁等 14.5 µs）。</p>
 *
 * <p>本类把它拆成两半：</p>
 * <ul>
 *   <li><b>短锁</b>：只做「帧头 + 载荷 memcpy 进累积缓冲」（~100 ns），<b>锁内绝不碰 socket</b>；</li>
 *   <li><b>写者令牌</b>：同一时刻<b>只有一个线程</b>能进 socket 写。</li>
 * </ul>
 *
 * <p>写者可以是任何 worker：谁抢到令牌谁写（写者在锁外做 syscall，其它 worker 继续追加）。
 * <b>写不全就把残余交给 IO 线程</b>（挂进 NIO 层的写队列，等 {@code OP_WRITE} 事件接着写），
 * 此后 worker 不再抢令牌 —— 只有 IO 线程能写残余。</p>
 *
 * <p><b>★ 为什么是双缓冲（{@code pendingBuf} ↔ {@code writeBuf} 轮换）</b>：
 * 写者把累积缓冲交出去写的时候，必须保证「在飞的 syscall」与「追加者的 memcpy /
 * 扩容搬字节」<b>不在同一块内存上</b>，否则会写出半截帧或撕裂帧。
 * 轮换用「换引用」代替「搬字节」，代价是每条活跃连接最多同时占 2 块缓冲
 * （写完即还池 ⇒ 空闲连接 0 块）。</p>
 *
 * <p><b>★ 保序</b>：session 写队列非空时绝不往 socket 写新字节 ——
 * 队列里是「上次没写完的旧字节」，插新帧会乱序。等队列被 NIO 层写空
 * （{@code removeWriteBuf} 回调）再继续。</p>
 *
 * <p><b>★ 为什么自己管 {@code OP_WRITE}</b>：NIO 层的
 * {@code NioServerSession.flush} 注册 {@code OP_WRITE} 用的是
 * {@code channel.register(selector, OP_WRITE, this)} —— 那会把 interestOps
 * <b>整个替换</b>掉，{@code OP_READ} 被丢弃。结果是 socket 一旦写满，
 * 服务端再也读不到对端的数据，双方死锁。所以这里必须用
 * {@code interestOps | OP_WRITE} 保留 {@code OP_READ}。</p>
 */
public class ErpcOutboundWriter {

    static final Logger LOG = LoggerManager.getLogger(ErpcOutboundWriter.class);

    /**
     * 出站累积缓冲的容量。
     *
     * <p>与 NIO 层的写缓冲池同尺寸（都取自 {@link NioSession#WRITE_BUFFER_SIZE}）——
     * 否则每次响应都要重新分配一块缓冲。</p>
     */
    static final int BUFFER_SIZE = NioSession.WRITE_BUFFER_SIZE;

    // ------------------------------------------------------------------
    // 写者令牌
    // ------------------------------------------------------------------

    /** 写者令牌：空闲（没有人持有）。 */
    private static final int W_IDLE = 0;

    /** 写者令牌：某个 worker 持有，正在写 socket。 */
    private static final int W_WORKER = 1;

    /** 写者令牌：残余已挂进 NIO 写队列 ⇒ 等 IO 线程接着写。 */
    private static final int W_IO = 2;

    private final ErpcServerSession session;

    /**
     * 累积缓冲 —— 帧直接写进来（不分配中间帧数组）。
     *
     * <p>{@code null} 表示手上没有缓冲（追加时从池里借）。</p>
     */
    private FastBuf pendingBuf;

    /** 累积缓冲里已写入、还没发出去的字节数。 */
    private int pendingLen;

    /**
     * 「正在写出」的缓冲 —— <b>写者独占</b>，别的线程碰不到它。
     *
     * <p>与 {@link #pendingBuf} 轮换：写者把 {@code pendingBuf} 换到
     * {@code writeBuf} 上直接写出去（不搬字节），写完的那块换回 {@code pendingBuf}。</p>
     */
    private FastBuf writeBuf;

    /** 写者令牌（见 {@link #W_IDLE} / {@link #W_WORKER} / {@link #W_IO}）。 */
    private int state = W_IDLE;

    /**
     * 是否已经注册过 {@code OP_WRITE} —— <b>由本对象的锁保护</b>。
     *
     * <p>★ 存在的理由：{@code SelectionKey.interestOps(int)} 在值变化时会
     * <b>唤醒 selector</b>（一次 syscall）。若每写完一批就去撤销 {@code OP_WRITE}，
     * 等于每批都白白唤醒一次 selector。<b>只有「残余确实挂进了 NIO 写队列」才注册，
     * 抽干后才撤销</b> —— 正常路径一次都不碰。</p>
     */
    private boolean writeInterestOn;

    /**
     * 写缓冲池（NIO 层传进来）。
     *
     * <p>★ 必须是 {@code volatile}：worker 线程读它、IO 线程写它
     * （{@code ErpcServerSession.flush} 在 IO 线程上被调）。</p>
     */
    private volatile Pool<FastBuf> pool;

    /** 写帧头用的复用对象 —— 只在锁内用。 */
    private final ErpcFrameHeader headerBuffer = new ErpcFrameHeader();

    /**
     * 帧的消费者 —— <b>每个 writer 一个实例</b>（无状态 ⇒ 多线程共用安全）。
     *
     * <p>★ 它让「编码」发生在锁外：编码器把载荷写进它自己的线程本地缓冲，
     * 本方法在锁内把这段字节拷进累积缓冲 ⇒ 载荷只被拷一次。</p>
     */
    private final ErpcFrameEncoder.FrameSink frameSink = this::appendFrame;

    public ErpcOutboundWriter(ErpcServerSession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------
    // ★ 争用诊断（仅诊断用，生产路径会多几次 nanoTime 调用）
    // ------------------------------------------------------------------

    /**
     * ★ <b>诊断计数器</b>：量化「追加锁」与「写者令牌」各自损失多少。
     *
     * <p>改前这两个数是同一把大锁（追加 + syscall 都进去），单次取锁等 ~14.5 µs；
     * 改后追加锁里只剩一次 memcpy（~100 ns），socket 写完全在锁外。</p>
     */
    private static final AtomicLong LOCK_ACQUIRES = new AtomicLong();
    private static final AtomicLong LOCK_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong LOCK_HELD_NANOS = new AtomicLong();
    private static final AtomicLong FLUSH_ACQUIRES = new AtomicLong();
    private static final AtomicLong FLUSH_WAIT_NANOS = new AtomicLong();
    private static final AtomicLong FLUSH_HELD_NANOS = new AtomicLong();
    private static final AtomicLong FLUSH_CALLS = new AtomicLong();
    private static final AtomicLong FLUSH_EMPTY = new AtomicLong();
    private static final AtomicLong TOKEN_CONTENTION = new AtomicLong();
    private static final AtomicLong SOCKET_WRITES = new AtomicLong();
    private static final AtomicLong HANDOFFS = new AtomicLong();

    /**
     * 出站锁的争用统计（诊断用）。
     */
    public static String lockStats() {
        return lockStats(0);
    }

    /**
     * 出站锁的争用统计（诊断用）。
     *
     * @param requests 期间处理的请求数（用于算「每请求」的等待/持有时间）；0 ⇒ 不输出每请求口径
     */
    public static String lockStats(long requests) {
        long acq = LOCK_ACQUIRES.get();
        long wait = LOCK_WAIT_NANOS.get();
        long held = LOCK_HELD_NANOS.get();
        long facq = FLUSH_ACQUIRES.get();
        long fwait = FLUSH_WAIT_NANOS.get();
        long fheld = FLUSH_HELD_NANOS.get();
        long fcalls = FLUSH_CALLS.get();
        long fempty = FLUSH_EMPTY.get();
        StringBuilder sb = new StringBuilder();
        // ★ 单位：wait/held 是「纳秒」，除以次数得到「纳秒/次」—— 不要再多除 1000，
        //   否则整数除法会把 139 ns/次 截成 0，诊断输出就成了摆设。
        sb.append("出站锁: 取锁 ").append(acq).append(" 次, 等待 ")
            .append(wait / 1000_000L).append(" ms (均 ")
            .append(acq == 0 ? 0 : wait / acq).append(" ns), 持有 ")
            .append(held / 1000_000L).append(" ms (均 ")
            .append(acq == 0 ? 0 : held / acq).append(" ns)");
        sb.append("; 写者: 调用 ").append(fcalls).append(" 次 (空转 ").append(fempty)
            .append("), 取锁 ").append(facq).append(" 次, 等待 ")
            .append(fwait / 1000_000L).append(" ms (均 ")
            .append(facq == 0 ? 0 : fwait / facq).append(" ns), 持有 ")
            .append(fheld / 1000_000L).append(" ms (均 ")
            .append(facq == 0 ? 0 : fheld / facq).append(" ns)");
        sb.append("; 令牌争用 ").append(TOKEN_CONTENTION.get()).append(" 次")
            .append(", socket 写 ").append(SOCKET_WRITES.get()).append(" 次")
            .append(", 交残余 ").append(HANDOFFS.get()).append(" 次");
        if (requests > 0) {
            sb.append("; 每请求: 锁等待 ").append(wait / requests)
                .append(" ns, 锁持有 ").append(held / requests).append(" ns");
        }
        return sb.toString();
    }

    /**
     * 「残余交 IO 线程」的次数（诊断用）。
     *
     * <p>★ 测试用它证明「半写那条路真的走到了」—— 否则测试可能是空的。</p>
     */
    public static long handoffCount() {
        return HANDOFFS.get();
    }

    /**
     * 清零出站锁的争用统计（诊断用）。
     */
    public static void resetLockStats() {        LOCK_ACQUIRES.set(0);
        LOCK_WAIT_NANOS.set(0);
        LOCK_HELD_NANOS.set(0);
        FLUSH_ACQUIRES.set(0);
        FLUSH_WAIT_NANOS.set(0);
        FLUSH_HELD_NANOS.set(0);
        FLUSH_CALLS.set(0);
        FLUSH_EMPTY.set(0);
        TOKEN_CONTENTION.set(0);
        SOCKET_WRITES.set(0);
        HANDOFFS.set(0);
    }

    // ------------------------------------------------------------------
    // 帧的产出
    // ------------------------------------------------------------------

    /**
     * 写一个帧 —— 帧头与载荷<b>直接写进累积缓冲</b>。
     *
     * @param type      帧类型（{@link io.edap.erpc.frame.ErpcFrameType#getValue()}）
     * @param codecId   序列化器 id（原样抄回请求帧的 codec）
     * @param methodId  方法号
     * @param serviceId 服务号
     * @param requestId 请求号（响应帧原样回填 ⇒ 客户端据此配对）
     * @param traceId   链路号
     * @param payload   业务载荷（可为 null）
     */
    public void writeFrame(int type, int codecId, int methodId, int serviceId,
                           int requestId, long traceId, byte[] payload) {
        int length = payload == null ? 0 : payload.length;
        boolean mine;
        long t0 = System.nanoTime();
        synchronized (this) {
            long t1 = System.nanoTime();
            LOCK_ACQUIRES.incrementAndGet();
            LOCK_WAIT_NANOS.addAndGet(t1 - t0);
            try {
                headerBuffer.setType(type)
                    .setFlags(0)
                    .setCodec(codecId)
                    .setLength(length)
                    .setMethodId(methodId)
                    .setServiceId(serviceId)
                    .setRequestId(requestId)
                    .setTraceId(traceId);
                mine = appendFrameLocked(headerBuffer, payload, length);
            } finally {
                LOCK_HELD_NANOS.addAndGet(System.nanoTime() - t1);
            }
        }
        if (mine) {
            writeLoop();
        }
    }

    /**
     * 写一个帧 —— <b>传对象</b>（编码在锁外，拷贝在锁内）。
     *
     * <p>★ <b>为什么要这一档</b>：传字节时载荷已经被拷过一次（编码器 → byte[]），
     * 这里再拷一次 ⇒ 载荷在网络上被拷两次。传对象时编码器直接把载荷写进
     * 它自己的缓冲，这里只拷一次 ⇒ 与 gRPC 的「帧头内联」同一形态。</p>
     *
     * @param type      帧类型（{@link io.edap.erpc.frame.ErpcFrameType#getValue()}）
     * @param codecId   序列化器 id（原样抄回请求帧的 codec）
     * @param methodId  方法号
     * @param serviceId 服务号
     * @param requestId 请求号（响应帧原样回填 ⇒ 客户端据此配对）
     * @param traceId   链路号
     * @param serializer 序列化器（编码载荷）
     * @param message   业务对象（还没编码）
     */
    public void writeFrameMessage(int type, int codecId, int methodId, int serviceId,
                                  int requestId, long traceId,
                                  ErpcCodec serializer, Object message) {
        ErpcFrameEncoder.encode(serializer, type, codecId, methodId, serviceId,
            requestId, traceId, message, frameSink);
    }

    /**
     * 帧的消费者 —— 把「帧头 + 载荷」写进累积缓冲。
     *
     * <p>★ <b>载荷数组是借来的</b>（来自序列化器的线程本地缓冲），
     * 本方法在返回前就把它拷走，不持有引用。</p>
     */
    private void appendFrame(ErpcFrameHeader header, byte[] payload, int length) {
        boolean mine;
        long t0 = System.nanoTime();
        synchronized (this) {
            long t1 = System.nanoTime();
            LOCK_ACQUIRES.incrementAndGet();
            LOCK_WAIT_NANOS.addAndGet(t1 - t0);
            try {
                mine = appendFrameLocked(header, payload, length);
            } finally {
                LOCK_HELD_NANOS.addAndGet(System.nanoTime() - t1);
            }
        }
        if (mine) {
            writeLoop();
        }
    }

    /**
     * 把「帧头 + 载荷」追加进累积缓冲（<b>调用方必须持有锁</b>）。
     *
     * @return true 表示「调用方该去当写者」（本线程抢到了写者令牌）
     */
    private boolean appendFrameLocked(ErpcFrameHeader header, byte[] payload, int length) {
        int need = ErpcFrameHeader.HEADER_LENGTH + length;
        FastBuf buf = pendingBufFor(need);
        long p = buf.wpos();
        header.writeTo(buf, p);
        buf.wpos(p + ErpcFrameHeader.HEADER_LENGTH);
        if (length > 0) {
            int n = buf.write(payload, 0, length);
            if (n != length) {
                // pendingBufFor 已经保证装得下 —— 装不下说明缓冲算错了，绝不能静默截断
                throw new IllegalStateException("写缓冲空间不足: 需要 " + length + " 字节，实际写入 " + n);
            }
        }
        pendingLen += need;
        return claimWriterLocked();
    }

    /**
     * 认领写者令牌（<b>调用方必须持有锁</b>）。
     *
     * @return true 表示拿到了令牌（本线程该去写 socket）
     */
    private boolean claimWriterLocked() {
        if (state != W_IDLE) {
            // 已有写者（或残余在 IO 线程手里）⇒ 我不写，字节留在累积缓冲里，
            // 现有写者会在它的循环里顺手带走。
            TOKEN_CONTENTION.incrementAndGet();
            return false;
        }
        state = W_WORKER;
        return true;
    }

    // ------------------------------------------------------------------
    // 写者
    // ------------------------------------------------------------------

    /**
     * 写者循环 —— <b>持有令牌的那个线程</b>在这里把字节写进 socket。
     *
     * <p>★ <b>socket 写（syscall）在锁外</b>：这是本轮改造的核心 ——
     * 旧实现把 syscall 放在锁里，N 个 worker 在同一把锁上排队（每次等 ~14.5 µs）。</p>
     *
     * <p>写不全时：残余（{@code writeBuf} 的 {@code [rpos, limit)}）挂进 NIO 层的写队列，
     * 等 {@code OP_WRITE} 事件由 IO 线程接着写（此时令牌交给 IO 线程，worker 不再抢）。</p>
     */
    private void writeLoop() {
        FastBuf out = null;
        try {
            for (;;) {
                long t0 = System.nanoTime();
                synchronized (this) {
                    long t1 = System.nanoTime();
                    FLUSH_ACQUIRES.incrementAndGet();
                    FLUSH_WAIT_NANOS.addAndGet(t1 - t0);
                    try {
                        // ★ 先看 NIO 写队列，再看有没有待写字节 —— 顺序不能反：
                        //   「队列非空」意味着残余还在队列里等着被写，此时**不能**撤销 OP_WRITE；
                        //   若先判「没有待写字节」就返回，会把 OP_WRITE 撤掉 ⇒ 残余永远没人写 ⇒ 卡死。
                        if (session.getWriteBuf() != null) {
                            // ★ 保序：NIO 写队列里还有上次没写完的旧字节 ⇒ 不能写新字节。
                            //   等 IO 线程把队列抽干（onWriteBufDrained）再回来写。
                            state = W_IDLE;
                            return;
                        }
                        if (pendingLen == 0) {
                            // 没有待写字节 ⇒ 交还令牌（缓冲还给池，空闲连接不占内存）
                            FLUSH_EMPTY.incrementAndGet();
                            releaseIdleBuffersLocked();
                            state = W_IDLE;
                            // 队列空 + 没有待写字节 ⇒ OP_WRITE 可以撤销了（否则 selector 空转）
                            disableWriteInterestLocked();
                            return;
                        }
                        // ★ 双缓冲轮换：把「有数据的那块」换到 writeBuf 直接写出去 ——
                        //   不用把 pendingBuf 整块拷进 writeBuf（每条 64KB 响应少一次整块拷贝）。
                        FastBuf spare = writeBuf;
                        writeBuf = pendingBuf;
                        pendingBuf = spare;
                        pendingLen = 0;
                        out = writeBuf;
                    } finally {
                        FLUSH_HELD_NANOS.addAndGet(System.nanoTime() - t1);
                    }
                }
                // ★ syscall 在锁外：别的线程可以继续往累积缓冲里追加帧
                FLUSH_CALLS.incrementAndGet();
                SOCKET_WRITES.incrementAndGet();
                if (!session.writeToChannel(out)) {
                    // ★ 残余交接 —— **整段在锁内做**（顺序不能反）：
                    //   若先挂队列再切状态，IO 线程可能在状态切换之前就把残余抽干
                    //   （removeWriteBuf → onWriteBufDrained 看到还是 W_WORKER ⇒ 什么都不做），
                    //   状态就此错乱。切状态在前、挂队列在后 ⇒ IO 线程要么看不到这块缓冲，
                    //   要么看到时状态已经切好了。
                    synchronized (this) {
                        // ★ 交接即置 null：那块缓冲归 NIO 层所有（它写完会归还到池），
                        //   这里绝不能再还一次池（同一块缓冲还两次 ⇒ 两条连接互相覆盖）。
                        writeBuf = null;
                        state = W_IO;
                        session.putToWriteQueue(out);
                        writeInterestOn = true;
                        session.enableWriteInterest();
                    }
                    HANDOFFS.incrementAndGet();
                    out = null;
                    return;
                }
                // 整块写完（writeToChannel 内部已把缓冲 clear 掉）
                out = null;
            }
        } catch (IOException e) {
            LOG.warn("erpc flush response failed: ", l -> l.threw(e));
            synchronized (this) {
                // ★ 令牌不能在异常路径泄漏 —— 泄漏了这条连接就再也不会有人写出去
                if (out != null && out == writeBuf) {
                    writeBuf = null;
                }
                state = W_IDLE;
            }
        }
    }

    /**
     * IO 线程的推进钩子 —— NIO 层把写队列头写完之后回调（<b>残余确实被抽干了</b>）。
     *
     * <p>★ 残余被抽干 ⇒ 令牌交给 IO 线程，由它继续把累积缓冲里的新字节写出去。</p>
     */
    public void onWriteBufDrained() {
        boolean mine = false;
        synchronized (this) {
            if (session.getWriteBuf() != null) {
                // 队列里还有别的缓冲 ⇒ 等下一个事件
                return;
            }
            if (state == W_IO) {
                // 残余已写完 ⇒ 接管写权限，继续写累积缓冲里的新字节
                state = W_WORKER;
                mine = true;
            } else if (state == W_IDLE && pendingLen > 0) {
                // 保序期间攒下的字节（写者当时看到写队列非空就交还了令牌）
                state = W_WORKER;
                mine = true;
            } else if (state == W_IDLE) {
                // 没事可干 ⇒ 撤销 OP_WRITE（否则 selector 被「socket 可写」一直唤醒，空转烧 CPU）
                disableWriteInterestLocked();
            }
        }
        if (mine) {
            writeLoop();
        }
    }

    /**
     * 撤销 {@code OP_WRITE}（<b>调用方必须持有锁</b>）。
     *
     * <p>★ 只在「确实注册过」时才真的去改 {@code interestOps} ——
     * {@code interestOps(int)} 在值变化时会唤醒 selector（一次 syscall），
     * 每批都撤一次等于每批白白唤醒一次。</p>
     */
    private void disableWriteInterestLocked() {
        if (!writeInterestOn) {
            return;
        }
        writeInterestOn = false;
        session.disableWriteInterest();
    }

    /**
     * IO 线程的推进钩子 —— 读事件之后调用（{@code ErpcServerSession.flush}）。
     *
     * <p>★ <b>与 {@link #onWriteBufDrained()} 的区别</b>：这里<b>不能</b>接管
     * {@code W_IO} 状态 —— 残余可能还挂在 NIO 写队列里没被抽干，
     * 抢过令牌只会让写者空跑一趟。</p>
     *
     * <p>正常情况下这里什么都不做（字节由 worker 写）；只有「保序期间攒了字节
     * 且队列已空」时才会真正写。</p>
     */
    public void flushToSocket() {
        boolean mine = false;
        synchronized (this) {
            if (state == W_IDLE && pendingLen > 0 && session.getWriteBuf() == null) {
                state = W_WORKER;
                mine = true;
            }
        }
        if (mine) {
            writeLoop();
        }
    }

    // ------------------------------------------------------------------
    // 缓冲
    // ------------------------------------------------------------------

    /**
     * 拿一块装得下 {@code need} 字节的累积缓冲（<b>调用方必须持有锁</b>）。
     *
     * <p>装不下就换一块更大的，并把已累积的字节搬过去 ——
     * 响应必须连续，不能拆成两段写。</p>
     */
    private FastBuf pendingBufFor(int need) {
        if (pendingBuf == null) {
            pendingBuf = borrowBuffer(need);
            return pendingBuf;
        }
        if (pendingBuf.writeRemain() >= need) {
            return pendingBuf;
        }
        // ★ 换缓冲只动累积缓冲 —— 写者独占的是另一块（writeBuf），
        //   所以这里的搬运不会和在飞的 syscall 抢同一块内存。
        FastBuf old = pendingBuf;
        int capacity = old.byteBuffer().capacity();
        long want = Math.max((long) pendingLen + need, (long) capacity * 2L);
        if (want > Integer.MAX_VALUE) {
            want = (long) pendingLen + need;
        }
        FastBuf nb = borrowBuffer((int) want);
        if (pendingLen > 0) {
            // 整块搬运（堆外到堆外，一次 copyMemory）
            UnsafeUtil.copyMemory(old.address(), nb.address(), pendingLen);
            nb.wpos(nb.address() + pendingLen);
        }
        releaseBuffer(old);
        pendingBuf = nb;
        return nb;
    }

    /**
     * 把空闲的缓冲还回池（<b>调用方必须持有锁</b>）。
     *
     * <p>空闲连接不占缓冲内存 —— 写完就还池，下次用再借。</p>
     */
    private void releaseIdleBuffersLocked() {
        if (pendingBuf != null) {
            releaseBuffer(pendingBuf);
            pendingBuf = null;
        }
        if (writeBuf != null) {
            // writeBuf 要么是刚写完的（已被 writeToChannel clear 掉），要么是 null
            releaseBuffer(writeBuf);
            writeBuf = null;
        }
    }

    private FastBuf borrowBuffer(int need) {
        FastBuf b = pool != null ? pool.borrow() : null;
        if (b == null) {
            return new FastBuf(Math.max(BUFFER_SIZE, need));
        }
        if (b.byteBuffer().capacity() < need) {
            // 池里的缓冲比需要的还小 —— 换一块够大的（这块小的还回池里）
            FastBuf small = b;
            b = new FastBuf(need);
            releaseBuffer(small);
            return b;
        }
        b.clear();
        return b;
    }

    private void releaseBuffer(FastBuf b) {
        // 只有**标准尺寸**的缓冲才回池 —— 池里混进大缓冲会让每个借到它的调用方都占着一块大内存
        if (pool != null && b.byteBuffer().capacity() == BUFFER_SIZE) {
            pool.requite(b);
        }
    }

    /**
     * 是否还有没发出去的字节。
     */
    public boolean isPendingEmpty() {
        synchronized (this) {
            return pendingLen == 0 && writeBuf == null;
        }
    }

    public void setPool(Pool<FastBuf> pool) {
        this.pool = pool;
    }
}
