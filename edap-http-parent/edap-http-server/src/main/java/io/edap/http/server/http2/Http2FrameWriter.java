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
import io.edap.pool.Pool;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * HTTP/2 帧写出器。
 *
 * <p><b>★ 帧原子性</b>：一个帧要么完整写进缓冲，要么一个字节都不写。
 * {@code FastBuf.write(byte[],off,len)} 是<b>静默截断</b>的（写不满不报错），
 * 所以这里先判 {@code writeRemain()} 再写，绝不产生半帧。</p>
 *
 * <p><b>★ 出站链路</b>（为什么不能直接写 socket）：</p>
 *
 * <blockquote>
 * socket 的发送缓冲默认只有 16KB（{@code AcceptEventHandler} 里
 * {@code socket.setSendBufferSize(16 * 1024)}），而 HTTP/2 一个发送窗口就是 64KB。
 * 大响应第一次写<b>必然写不完</b>，必须有一个「写不完也能续写」的机制。
 * </blockquote>
 *
 * <p>本类用三层结构实现它：</p>
 * <ol>
 *   <li><b>帧队列</b>（{@link #frames}）：还没装进写缓冲的整帧；</li>
 *   <li><b>写缓冲</b>（{@link #buffer}）：从池里借的 32KB {@link FastBuf}，
 *       装了若干整帧；</li>
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

    /** 写缓冲大小 —— 与 NIO 层的 THREAD_WRITE_BUF 保持一致 */
    static final int BUFFER_SIZE = 32768;

    private final Http2Connection connection;

    /**
     * 待发送的整帧队列（还没装进写缓冲的帧）。
     */
    private final Deque<byte[]> frames = new ArrayDeque<>();

    /**
     * 当前写缓冲。null 表示手上没有缓冲。
     *
     * <p>一旦交给 NIO 层的写队列就必须置 null —— 那个缓冲归 NIO 层所有，
     * 它写完会归还到池里，不能再被本类使用。</p>
     */
    private FastBuf buffer;

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

    public void writeFrame(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        if (length > 0xffffff) {
            throw new IllegalArgumentException("帧载荷超过 24 位长度上限: " + length);
        }
        byte[] frame = new byte[Http2FrameCodec.FRAME_HEADER_LENGTH + length];
        frame[0] = (byte) ((length >>> 16) & 0xff);
        frame[1] = (byte) ((length >>> 8) & 0xff);
        frame[2] = (byte) (length & 0xff);
        frame[3] = (byte) (type & 0xff);
        frame[4] = (byte) (flags & 0xff);
        frame[5] = (byte) ((streamId >>> 24) & 0x7f);
        frame[6] = (byte) ((streamId >>> 16) & 0xff);
        frame[7] = (byte) ((streamId >>> 8) & 0xff);
        frame[8] = (byte) (streamId & 0xff);
        if (length > 0) {
            System.arraycopy(payload, offset, frame, Http2FrameCodec.FRAME_HEADER_LENGTH, length);
        }
        enqueue(frame);
    }

    public void writeFrame(int type, int flags, int streamId, byte[] payload) {
        writeFrame(type, flags, streamId, payload, 0, payload.length);
    }

    /**
     * 帧入队。
     *
     * <p>只入队，不直接写 socket —— 真正的写出在 {@link #flushToSocket()}，
     * 由连接层在 decode 结束后统一触发。</p>
     */
    private void enqueue(byte[] frame) {
        frames.addLast(frame);
        frameCount++;
    }

    // ------------------------------------------------------------------
    // 出站
    // ------------------------------------------------------------------

    /**
     * 把待发帧尽量写到 socket。
     *
     * @return true 表示全部写完
     */
    boolean flushToSocket() throws IOException {
        HttpServerNioSession session = connection.getSession();
        if (session.getWriteBuf() != null) {
            // 上次没写完的字节还在 NIO 写队列里 —— 必须等它写完才能发新帧（保序）
            enableWriteInterest();
            return false;
        }
        while (true) {
            if (buffer == null) {
                if (frames.isEmpty()) {
                    disableWriteInterest();
                    return true;
                }
                buffer = borrowBuffer();
                fillBuffer();
                if (buffer.remain() == 0) {
                    // 缓冲里一帧都没装下（正常不会发生：最大帧 9+16384 < 32768）
                    releaseBuffer(buffer);
                    buffer = null;
                    continue;
                }
            }
            if (!session.writeToChannel(buffer)) {
                // socket 写不动 —— 残余字节交给 NIO 层的写队列，等 OP_WRITE 事件继续写
                session.putToWriteQueue(buffer);
                buffer = null;
                enableWriteInterest();
                return false;
            }
            // 整块写完（writeToChannel 内部已把缓冲 clear 掉）
            releaseBuffer(buffer);
            buffer = null;
        }
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
     * 把帧队列里的帧尽量装进写缓冲。
     */
    private void fillBuffer() {
        while (!frames.isEmpty()) {
            byte[] frame = frames.peekFirst();
            if (buffer.writeRemain() < frame.length) {
                break;
            }
            frames.pollFirst();
            // writeRemain 已经保证放得下 —— 不会触发 FastBuf.write 的静默截断
            buffer.write(frame, 0, frame.length);
        }
    }

    private FastBuf borrowBuffer() {
        FastBuf b = pool != null ? pool.borrow() : null;
        if (b == null) {
            b = new FastBuf(BUFFER_SIZE);
        }
        b.clear();
        return b;
    }

    private void releaseBuffer(FastBuf b) {
        if (pool != null) {
            pool.requite(b);
        }
    }

    public void setPool(Pool<FastBuf> pool) {
        this.pool = pool;
    }

    /**
     * 是否还有没发出去的字节。
     */
    public boolean isPendingEmpty() {
        return frames.isEmpty() && buffer == null;
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
     * <p>不撤销的话 selector 会一直被「socket 可写」唤醒，空转烧 CPU。</p>
     */
    private void disableWriteInterest() {
        connection.getSession().disableWriteInterest();
    }
}
