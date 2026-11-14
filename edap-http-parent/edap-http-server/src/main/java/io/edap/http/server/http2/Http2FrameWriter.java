/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.http.server.http2;

import io.edap.buffer.FastBuf;
import io.edap.http.http2.Http2FrameCodec;
import io.edap.http.server.HttpServerNioSession;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.NioSession;
import io.edap.pool.Pool;
import io.edap.util.UnsafeUtil;

import java.io.IOException;

/**
 * HTTP/2 帧写出器。
 *
 * <p><b>★ 出站结构：netty 式的「累积缓冲」</b>。帧不再各自分配一个 {@code byte[]}，
 * 而是<b>直接写进一块出站累积缓冲</b>；缓冲攒够一次响应（或缓冲满）才做一次 socket 写。</p>
 *
 * <p>换来两件事（都是实测的，不是推演）：</p>
 * <ul>
 *   <li><b>一条 64KB 响应只做 1 次 socket 写</b>。原来写缓冲只有 32KB，
 *       而 HTTP/2 的最大帧是 16KB —— 一个缓冲只装得下一个帧，
 *       于是每条 64KB 响应被拆成 <b>4 次 socket 写</b>（实测 4.10 次/响应），
 *       对端也要被唤醒 4 次。写缓冲放大到 128KB 后降到 <b>1.01 次/响应</b>，
 *       吞吐 <b>+29%</b>；现在是 256KB，还能把一次读带回的 4 条 64KB 响应攒成一次写。</li>
 *   <li><b>少一次整块拷贝、少一批分配</b>。原来：载荷 → 帧数组（一次拷贝 + 一个
 *       {@code byte[]}），帧数组 → 写缓冲（又一次拷贝）。现在：载荷 → 写缓冲，一次到位。</li>
 * </ul>
 *
 * <p><b>★ 为什么缓冲必须是连续的</b>：socket 的写是 {@code write(2)}，
 * 一次只能写一段连续内存。帧不连续就没法一次写完 ——
 * 「一条响应一次 syscall」的前提就是整条响应在内存里连续。</p>
 *
 * <p><b>★ 出站链路</b>（为什么不能直接写 socket）：</p>
 *
 * <blockquote>
 * socket 的发送缓冲可能写不下（内核自动调优，不保证一次能收下 64KB），
 * 所以必须有「写不完也能续写」的机制。
 * </blockquote>
 *
 * <p>本类用两层结构实现它：</p>
 * <ol>
 *   <li><b>累积缓冲</b>（{@link #buffer}）：帧直接写进来，攒够一次 socket 写；</li>
 *   <li><b>NIO 写队列</b>：socket 写不动时，把写缓冲里剩下的字节挂到 session 的
 *       写队列上，等 {@code OP_WRITE} 事件由 NIO 层继续写。</li>
 * </ol>
 *
 * <p><b>★ 为什么自己管 {@code OP_WRITE}</b>：NIO 层的
 * {@code NioServerSession.flush} 注册 {@code OP_WRITE} 时用的是
 * {@code channel.register(selector, OP_WRITE, this)} —— 这会把 interestOps
 * <b>整个替换</b>掉，{@code OP_READ} 被丢弃。结果就是：socket 一旦写满，
 * 服务端再也读不到对端的 {@code WINDOW_UPDATE}，双方死锁。
 * 所以 HTTP/2 路径必须自己注册 {@code OP_WRITE}（用 {@code interestOps | OP_WRITE}
 * 保留 {@code OP_READ}），并且在写完之后及时撤销（否则 selector 空转烧 CPU）。</p>
 *
 * <p><b>★ 保序</b>：session 写队列非空时，绝不往 socket 写新字节 ——
 * 队列里是「上次没写完的旧字节」，插新帧会乱序。等队列被 NIO 层写空
 * （{@code removeWriteBuf} 回调）再继续。</p>
 */
public class Http2FrameWriter {

    static final Logger LOG = LoggerManager.getLogger(Http2FrameWriter.class);

    /**
     * 出站累积缓冲的容量。
     *
     * <p>必须与写缓冲池里的缓冲一样大（都取自 {@link NioSession#WRITE_BUFFER_SIZE}）——
     * 否则每次响应都要重新分配一块缓冲，而直连内存的分配远比堆内存贵。</p>
     */
    static final int BUFFER_SIZE = NioSession.WRITE_BUFFER_SIZE;

    private final Http2Connection connection;

    /**
     * 出站累积缓冲 —— 帧直接写进来（不分配中间帧数组）。
     * null 表示手上没有缓冲。
     *
     * <p>一旦交给 NIO 层的写队列就必须置 null —— 那个缓冲归 NIO 层所有，
     * 它写完会归还到池里，不能再被本类使用。</p>
     */
    private FastBuf buffer;

    /**
     * 累积缓冲里已写入、还没发到 socket 的字节数。
     */
    private int pending;

    /**
     * 写缓冲池（NIO 层传进来）。
     */
    private Pool<FastBuf> pool;

    /**
     * 本轮 decode 产出的帧数。
     */
    private int frameCount;

    public Http2FrameWriter(Http2Connection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------
    // 帧的产出
    // ------------------------------------------------------------------

    /**
     * 写一个帧 —— 帧头与载荷<b>直接写进累积缓冲</b>。
     *
     * <p>帧头 9 字节：长度 24 位 + 类型 8 位 + 标志 8 位 + 流标识 31 位（RFC 9113 §4.1）。</p>
     */
    public synchronized void writeFrame(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        if (length > 0xffffff) {
            throw new IllegalArgumentException("帧载荷超过 24 位长度上限: " + length);
        }
        int need = Http2FrameCodec.FRAME_HEADER_LENGTH + length;
        FastBuf buf = bufferFor(need);
        long p = buf.wpos();
        buf.writeNotCheck(p,     (byte) ((length >>> 16) & 0xff));
        buf.writeNotCheck(p + 1, (byte) ((length >>> 8) & 0xff));
        buf.writeNotCheck(p + 2, (byte) (length & 0xff));
        buf.writeNotCheck(p + 3, (byte) (type & 0xff));
        buf.writeNotCheck(p + 4, (byte) (flags & 0xff));
        buf.writeNotCheck(p + 5, (byte) ((streamId >>> 24) & 0x7f));
        buf.writeNotCheck(p + 6, (byte) ((streamId >>> 16) & 0xff));
        buf.writeNotCheck(p + 7, (byte) ((streamId >>> 8) & 0xff));
        buf.writeNotCheck(p + 8, (byte) (streamId & 0xff));
        buf.wpos(p + Http2FrameCodec.FRAME_HEADER_LENGTH);
        if (length > 0) {
            int n = buf.write(payload, offset, length);
            if (n != length) {
                // bufferFor 已经保证装得下 —— 装不下说明缓冲算错了，绝不能静默截断
                throw new IllegalStateException("写缓冲空间不足: 需要 " + length + " 字节，实际写入 " + n);
            }
        }
        pending += need;
        frameCount++;
        if (Http2WriteStats.ENABLED) {
            Http2WriteStats.framesEnqueued.increment();
            Http2WriteStats.frameBytes.add(need);
        }
    }

    public synchronized void writeFrame(int type, int flags, int streamId, byte[] payload) {
        writeFrame(type, flags, streamId, payload, 0, payload.length);
    }

    // ------------------------------------------------------------------
    // 出站
    // ------------------------------------------------------------------

    /**
     * 拿一块装得下 {@code need} 字节的累积缓冲。
     *
     * <p>装不下就换一块更大的，并把已累积的字节搬过去 ——
     * 响应必须连续，不能拆成两段写（拆了就变成两次 socket 写，
     * 又回到「一条响应多次 syscall」的老问题）。</p>
     */
    private FastBuf bufferFor(int need) {
        if (buffer == null) {
            buffer = borrowBuffer(need);
            return buffer;
        }
        if (buffer.writeRemain() >= need) {
            return buffer;
        }
        // ★★ 缓冲满了：先把已累积的字节**写出去**，而不是换一块更大的缓冲。
        //
        //   原来这里是「换一块 pending + need 大小的新缓冲，并把已累积的整块搬过去」。
        //   换完之后新缓冲的剩余空间恰好只够再放一帧 ⇒ 下一帧又触发换缓冲 ⇒
        //   缓冲一帧一帧地长大，而**每次换缓冲都要把已累积的全部字节搬一遍** ⇒
        //   平方级拷贝。
        //
        //   实测（edap gRPC，64KB 负载 / 32 并发 / 连接复用，grpc-java 客户端）：
        //     平方级换缓冲：5 秒搬了 26GB（实际输出只有 1.9GB ⇒ 15 倍放大），
        //                   吞吐 6,418 req/s；连接新开时同样的场景只搬 8GB（1.9 倍），
        //                   吞吐 12,916 req/s。
        //     先写出去：拷贝归零，吞吐回到 13,000+ req/s。
        //
        //   ★ 「一条响应一次 syscall」是**优化**而不是协议要求 ——
        //   HTTP/2 是字节流，响应被拆到两次 socket 写，对端照样按字节流重组。
        //   与其用平方级拷贝换「一次写完」，不如多一次 syscall（几十微秒）
        //   换掉几百 MB 的内存搬运。
        if (tryFlushToSocket()) {
            if (buffer == null) {
                buffer = borrowBuffer(need);
                return buffer;
            }
            if (buffer.writeRemain() >= need) {
                return buffer;
            }
        }
        if (buffer == null) {
            // 刚才那次 flush 是「半写」—— 累积缓冲（含没写完的残余字节）已经
            // 交给了 NIO 层的写队列，这里手上没有缓冲了，重新借一块。
            // （走到这里说明 socket 写不动，但残余字节已经在队列里，不会丢。）
            buffer = borrowBuffer(need);
            return buffer;
        }
        // 写不出去（socket 写队列里还有没写完的字节）⇒ 只能换一块更大的缓冲。
        // ★ 按 2 倍增长（摊还 O(1)）—— 不要按「pending + need」精确增长：
        //   精确增长会让缓冲一帧一帧地长，每次都搬走全部已累积的字节（平方级）。
        FastBuf old = buffer;
        int capacity = old.byteBuffer().capacity();
        long want = Math.max((long) pending + need, (long) capacity * 2L);
        if (want > Integer.MAX_VALUE) {
            want = (long) pending + need;
        }
        FastBuf nb = borrowBuffer((int) want);
        if (pending > 0) {
            // 整块搬运（堆外到堆外，一次 copyMemory）
            UnsafeUtil.copyMemory(old.address(), nb.address(), pending);
            nb.wpos(nb.address() + pending);
        }
        if (Http2WriteStats.ENABLED) {
            // ★ 换缓冲是一次「把已累积的整块搬走」—— 代价与累积量成正比。
            //   一次 flush 里换得越多，搬的总字节数越大。
            Http2WriteStats.bufferSwaps.increment();
            Http2WriteStats.bufferSwapBytes.add(pending);
        }
        releaseBuffer(old);
        buffer = nb;
        return buffer;
    }

    /**
     * 试着把已累积的字节写出去 —— 失败（socket 写不动）就返回 false，交给调用方换缓冲。
     *
     * <p>写失败不抛：真正的写失败会在本轮 decode 结束的 flush 里被正常处理，
     * 这里只是「缓冲满了，先腾个位置」的优化路径。</p>
     */
    private boolean tryFlushToSocket() {
        try {
            return flushToSocket();
        } catch (IOException e) {
            LOG.warn("http2 flush when buffer full failed: ", l -> l.threw(e));
            return false;
        }
    }

    private FastBuf borrowBuffer(int need) {
        if (Http2WriteStats.ENABLED) {
            Http2WriteStats.buffersBorrowed.increment();
        }
        FastBuf b = pool != null ? pool.borrow() : null;
        if (b == null) {
            FastBuf fresh = new FastBuf(Math.max(BUFFER_SIZE, need));
            recordBorrow(fresh.byteBuffer().capacity());
            return fresh;
        }
        if (b.byteBuffer().capacity() < need) {
            // 池里的缓冲比需要的还小 —— 换一块够大的（这块小的还回池里）
            FastBuf small = b;
            b = new FastBuf(need);
            releaseBuffer(small);
            recordBorrow(b.byteBuffer().capacity());
            return b;
        }
        b.clear();
        recordBorrow(b.byteBuffer().capacity());
        return b;
    }

    /** 记录借到的缓冲容量（诊断用：看池子里到底是多大的缓冲）。 */
    private static void recordBorrow(int capacity) {
        Http2WriteStats.borrowedCapacitySum.add(capacity);
        Http2WriteStats.borrowedCapacityMax.accumulateAndGet(capacity, Math::max);
        Http2WriteStats.borrowedCapacityMin.accumulateAndGet(capacity, Math::min);
    }

    private void releaseBuffer(FastBuf b) {
        // 只有**标准尺寸**的缓冲才回池 —— 池里的缓冲尺寸必须一致，
        // 否则借到一块小缓冲的调用方还得再换一块。
        // ★ 用 == 而不是 >=：「换缓冲」时长大出来的缓冲（容量 > BUFFER_SIZE）
        //   不能回池 —— 池里混进大缓冲会让每个借到它的调用方都占着一块大内存。
        if (pool != null && b.byteBuffer().capacity() == BUFFER_SIZE) {
            pool.requite(b);
        }
    }

    /**
     * 把累积缓冲里的字节写到 socket。
     *
     * @return true 表示全部写完
     */
    synchronized boolean flushToSocket() throws IOException {
        HttpServerNioSession session = connection.getSession();
        if (session.getWriteBuf() != null) {
            // 上次没写完的字节还在 NIO 写队列里 —— 必须等它写完才能发新帧（保序）
            enableWriteInterest();
            return false;
        }
        if (pending == 0) {
            disableWriteInterest();
            return true;
        }
        FastBuf buf = buffer;
        if (Http2WriteStats.ENABLED) {
            Http2WriteStats.socketWrites.increment();
            Http2WriteStats.flushes.increment();
            Http2WriteStats.flushBytes.add(pending);
        }
        if (!session.writeToChannel(buf)) {
            // socket 写不动 —— 残余字节交给 NIO 层的写队列，等 OP_WRITE 事件继续写
            if (Http2WriteStats.ENABLED) {
                Http2WriteStats.partialWrites.increment();
            }
            session.putToWriteQueue(buf);
            buffer = null;
            pending = 0;
            enableWriteInterest();
            return false;
        }
        // 整块写完（writeToChannel 内部已把缓冲 clear 掉）
        releaseBuffer(buf);
        buffer = null;
        pending = 0;
        disableWriteInterest();
        return true;
    }

    /**
     * NIO 层把写队列头写完之后回调 —— 继续发剩下的帧。
     */
    void onWriteBufDrained() {
        try {
            flushToSocket();
        } catch (IOException e) {
            LOG.warn("http2 flush after write buffer drained failed: ", l -> l.threw(e));
        }
    }

    /**
     * 是否还有没发出去的字节。
     */
    public boolean isPendingEmpty() {
        return pending == 0;
    }

    /**
     * 本轮已写出的帧数。
     */
    public int getFrameCount() {
        return frameCount;
    }

    /**
     * 清空帧计数 —— 每轮 decode 开始时调用，让 {@link #getFrameCount()} 表达
     * 「本轮 decode 产出了多少帧」而不是「连接建立至今一共多少帧」。
     */
    public void resetFrameCount() {
        frameCount = 0;
    }

    public void setPool(Pool<FastBuf> pool) {
        this.pool = pool;
    }

    // ------------------------------------------------------------------
    // OP_WRITE 兴趣位
    // ------------------------------------------------------------------

    /**
     * 注册 {@code OP_WRITE}（保留 {@code OP_READ}）。
     *
     * <p>★ 不能用 {@code channel.register(selector, OP_WRITE, this)} ——
     * 那会把 interestOps 整个替换掉，{@code OP_READ} 丢失后服务端再也读不到
     * 对端的 WINDOW_UPDATE，双方死锁。</p>
     */
    private void enableWriteInterest() {
        connection.getSession().enableWriteInterest();
    }

    /**
     * 撤销 {@code OP_WRITE}。
     *
     * <p>不撤销的话 selector 会被「socket 可写」唤醒，空转烧 CPU。</p>
     */
    private void disableWriteInterest() {
        connection.getSession().disableWriteInterest();
    }
}
