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

package io.edap.http.server.tls;

import io.edap.buffer.FastBuf;
import io.edap.http.server.HttpServerNioSession;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TLS 会话 —— 在 {@link HttpServerNioSession} 之上给整条链路套一层 TLS。
 *
 * <p><b>★ 明文只在两个边界出现</b>：</p>
 * <blockquote>
 * 入站：socket → 密文 → {@code unwrap} → 明文 → {@link #decode}；
 * 出站：{@code writeToChannel} → 明文 → {@code wrap} → 密文 → socket。
 * </blockquote>
 *
 * <p><b>★ 为什么在 {@code writeToChannel} 这一层做加密</b>：HTTP/1.1 与 HTTP/2
 * 两条出站链路最终都汇聚到 {@code session.writeToChannel(buf)}（HTTP/1.1 是
 * {@code THREAD_WRITE_BUF}，HTTP/2 是 {@code Http2FrameWriter} 的帧缓冲），
 * 在这里拦截一次就覆盖了两条链路，不需要分别改。</p>
 *
 * <p><b>★ 写队列里只能放密文</b>：NIO 层的写队列是「socket 写不动时挂起来的字节」，
 * 稍后由 NIO 层原样重写。如果挂的是明文，重写时会把明文当密文发出去 ⇒ TLS 记录损坏。
 * 所以本类把「已加密但没写完」的字节单独放在 {@link #cipherBacklog} 里，
 * 只有真正要交给 NIO 写队列的那一块才是密文。</p>
 *
 * <p><b>★ 握手在读路径上完成</b>：TLS 是「服务端先等 ClientHello」的协议，
 * 所以握手天然由第一次 {@link #fastRead} 触发。握手期间不产生应用层明文，
 * {@link #decode} 直接返回 false。</p>
 *
 * <p><b>★ socket 读缓冲必须与「待解密密文」分开</b>：NIO 层的
 * {@code readInternal} 固定从 {@code buf.address()} 读（不看 {@code wpos}），
 * 所以读 socket 的缓冲每次都要先 {@code reset}（保证 address == wpos）；
 * 而跨 read 还没解完的密文必须另存一份，否则下一次读会把它覆盖掉。</p>
 */
public class TlsHttpServerNioSession extends HttpServerNioSession {

    static final Logger LOG = LoggerManager.getLogger(TlsHttpServerNioSession.class);

    /** socket 直读缓冲大小 */
    private static final int NET_READ_SIZE = 32768;

    /** 待解密密文的初始容量（跨 read 保留） */
    private static final int NET_IN_INIT = 32768;

    /** 单次解密产出的明文上限（TLS 记录明文最大 16384 字节，这里留一倍余量） */
    private static final int PLAIN_SIZE = 32768;

    /** 加密输出的初始容量（明文 + TLS 记录开销） */
    private static final int CIPHER_OUT_INIT = 4096;

    private final SSLEngine engine;

    /** socket 直读缓冲（每次读之前 reset —— readInternal 固定从 address 读） */
    private final FastBuf netReadBuf = new FastBuf(NET_READ_SIZE);

    /** 待解密的密文（跨 read 保留） */
    private byte[] netIn = new byte[NET_IN_INIT];
    private int netInLen;

    /** 已解出但还没交给上层的明文（跨 read 保留） */
    private byte[] plainPending = new byte[0];
    private int plainPendingLen;

    /** 明文暂存（unwrap 的输出） */
    private byte[] plainScratch = new byte[PLAIN_SIZE];

    /** 加密输出缓冲（wrap 的输出） */
    private byte[] cipherScratch = new byte[CIPHER_OUT_INIT];

    /** 已加密但还没写出去的密文（队头在前） */
    private final Deque<byte[]> cipherBacklog = new ArrayDeque<>();

    /** 已经交给 NIO 写队列的那块密文（用于识别「重试路径」） */
    private FastBuf cipherInQueue;

    /** TLS 握手是否已完成 */
    private boolean handshakeDone;

    /** ALPN 协商出来的应用层协议（{@code h2} / {@code http/1.1} / 空串） */
    private String negotiatedProtocol = "";

    /** 调试开关：把密文/明文以 hex 打到日志（{@code -Dedap.http.tls.debug=true}） */
    private final boolean debug = Boolean.getBoolean("edap.http.tls.debug");

    public TlsHttpServerNioSession(TlsContext context) {
        this.engine = context.createEngine();
    }

    /**
     * ALPN 协商出来的应用层协议。
     *
     * <p>握手完成后才有值。{@code "h2"} 表示对端用 HTTP/2，
     * {@code "http/1.1"} 表示 HTTP/1.1，空串表示对端没发 ALPN 扩展。</p>
     */
    public String getNegotiatedProtocol() {
        return negotiatedProtocol;
    }

    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    // ------------------------------------------------------------------
    // 入站：socket → 明文
    // ------------------------------------------------------------------

    /**
     * 读 socket 并解密，把明文写进 {@code buf}。
     *
     * <p>覆写 {@code fastRead} 而不是另开一条读路径 —— NIO 层（{@code DisruptorReadDispatcher}）
     * 只认这一个入口。</p>
     */
    @Override
    public int fastRead(FastBuf buf) throws IOException {
        // 上次没交付完的明文先交出去
        int copied = drainPendingPlain(buf);
        if (copied > 0) {
            return copied;
        }
        if (!fillNetIn()) {
            return 0;
        }
        decrypt();
        return drainPendingPlain(buf);
    }

    /**
     * 把 socket 里的密文读进待解密的缓冲。
     *
     * @return true 表示读到了数据
     */
    private boolean fillNetIn() throws IOException {
        // ★ readInternal 固定从 buf.address() 读 —— 读之前必须 reset，
        //   否则新读到的数据会覆盖还没解完的旧密文。
        netReadBuf.reset();
        int n = super.fastRead(netReadBuf);
        if (n <= 0) {
            return false;
        }
        if (netInLen + n > netIn.length) {
            int cap = netIn.length;
            while (cap < netInLen + n) {
                cap <<= 1;
            }
            byte[] nb = new byte[cap];
            System.arraycopy(netIn, 0, nb, 0, netInLen);
            netIn = nb;
        }
        byte[] tmp = new byte[n];
        netReadBuf.get(tmp);
        System.arraycopy(tmp, 0, netIn, netInLen, n);
        netInLen += n;
        return true;
    }

    /**
     * 把待解密的密文解成明文，放进 {@link #plainPending}。
     */
    private void decrypt() throws IOException {
        int pos = 0;
        while (pos < netInLen) {
            // ★ 必须用 slice()：ByteBuffer.wrap(array, offset, length) 返回的 buffer
            //   position 是 offset 而不是 0 —— 直接用会让「已消费字节数」的记账错位
            //   （一次 read 里有多条 TLS 记录时，pos 会累加出界，netInLen 变负）。
            ByteBuffer src = ByteBuffer.wrap(netIn, pos, netInLen - pos).slice();
            ByteBuffer dst = ByteBuffer.wrap(plainScratch);
            SSLEngineResult result;
            try {
                result = engine.unwrap(src, dst);
            } catch (SSLException e) {
                throw new IOException("TLS 解密失败: " + e.getMessage(), e);
            }
            pos += src.position();
            int produced = dst.position();
            if (debug) {
                LOG.info("[TLS] unwrap status={} hs={} consumed={} produced={} netInLen={} engineHs={}",
                        l -> l.arg(result.getStatus()).arg(result.getHandshakeStatus())
                                .arg(src.position()).arg(produced).arg(netInLen)
                                .arg(engine.getHandshakeStatus()));
            }
            if (produced > 0) {
                appendPlain(plainScratch, produced);
                if (debug) {
                    LOG.info("TLS 明文 {} 字节: {}", l -> l.arg(produced).arg(hex(plainScratch, produced)));
                }
            }

            // ★ 握手推进必须排在「取明文」之后 ——
            //   握手完成的那一刻可能同时带出应用数据（TLS 1.3 客户端的 Finished 与
            //   第一条应用数据可以在同一个 flight 里）。
            if (!handshakeDone) {
                progressHandshake();
            }

            SSLEngineResult.Status status = result.getStatus();
            if (status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                // 密文不完整 —— 剩下的等下一次读
                break;
            }
            if (status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                // 明文缓冲不够 —— 扩容后重试同一段密文
                growPlain();
                continue;
            }
            if (status == SSLEngineResult.Status.CLOSED) {
                break;
            }
            if (produced == 0 && src.position() == 0) {
                // 没有进展 —— 避免死循环
                break;
            }
        }
        // 剩下的密文挪到头部，等下一次读
        if (pos < netInLen) {
            System.arraycopy(netIn, pos, netIn, 0, netInLen - pos);
        }
        netInLen -= pos;
    }

    /**
     * 明文暂存缓冲扩容。
     */
    private void growPlain() {
        plainScratch = new byte[plainScratch.length * 2];
    }

    /**
     * 把明文追加到待交付缓冲。
     */
    private void appendPlain(byte[] data, int len) {
        if (plainPendingLen + len > plainPending.length) {
            int cap = Math.max(plainPending.length * 2, plainPendingLen + len);
            byte[] nb = new byte[cap];
            System.arraycopy(plainPending, 0, nb, 0, plainPendingLen);
            plainPending = nb;
        }
        System.arraycopy(data, 0, plainPending, plainPendingLen, len);
        plainPendingLen += len;
    }

    /**
     * 把待交付的明文拷进上层给的缓冲。
     *
     * @return 实际拷出的字节数
     */
    private int drainPendingPlain(FastBuf out) {
        if (plainPendingLen <= 0) {
            return 0;
        }
        int space = out.writeRemain();
        int n = Math.min(space, plainPendingLen);
        if (n <= 0) {
            return 0;
        }
        int wlen = out.write(plainPending, 0, n);
        if (wlen < n) {
            // FastBuf.write 是静默截断的 —— 理论上不会发生（先判了 writeRemain）
            n = wlen;
        }
        plainPendingLen -= n;
        if (plainPendingLen > 0) {
            System.arraycopy(plainPending, n, plainPending, 0, plainPendingLen);
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 握手
    // ------------------------------------------------------------------

    /**
     * 推进 TLS 握手。
     *
     * <p>服务端握手的形状：收到 ClientHello → 发自己的 flight（ServerHello/Certificate/Finished）
     * → 收到客户端的 Finished。握手数据是<b>明文</b>的（TLS 1.2 及以前的
     * ServerHello/ClientHello 不加密），但走的是同一条 wrap/unwrap 通道。</p>
     *
     * <p><b>★ 握手完成的判定</b>：只有引擎的握手状态变成
     * {@code NOT_HANDSHAKING} / {@code FINISHED} 才算完成。
     * 「还需要 wrap（发自己的 flight）」或「还需要 unwrap（等对端 Finished）」
     * 都<b>不算</b>完成 —— 早一步置位会让 ALPN 读不到值，
     * 也会让应用层数据在握手还没结束时就被送进解码器。</p>
     */
    private void progressHandshake() throws IOException {
        while (true) {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
                continue;
            }
            if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                wrapAndSend();
                continue;
            }
            // NEED_UNWRAP：等对端的数据；NOT_HANDSHAKING / FINISHED：握手结束
            break;
        }
        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        if (debug) {
            LOG.info("[TLS] progressHandshake -> hs={} done={}", l -> l.arg(hs).arg(handshakeDone));
        }
        if (!handshakeDone && (hs == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                || hs == SSLEngineResult.HandshakeStatus.FINISHED)) {
            handshakeDone = true;
            negotiatedProtocol = engine.getApplicationProtocol();
            LOG.info("TLS handshake done, ALPN={}", l -> l.arg(negotiatedProtocol));
        }
    }

    /**
     * 把引擎要求发出的握手数据 wrap 出来并写出去。
     */
    private void wrapAndSend() throws IOException {
        ByteBuffer empty = ByteBuffer.allocate(0);
        while (true) {
            ByteBuffer dst = ByteBuffer.wrap(cipherScratch);
            SSLEngineResult result;
            try {
                result = engine.wrap(empty, dst);
            } catch (SSLException e) {
                throw new IOException("TLS 握手加密失败: " + e.getMessage(), e);
            }
            if (dst.position() > 0) {
                dst.flip();
                byte[] data = new byte[dst.remaining()];
                dst.get(data);
                writeCipher(data);
            }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                growCipher();
                continue;
            }
            if (engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // 出站：明文 → 密文 → socket
    // ------------------------------------------------------------------

    /**
     * ★ 拦截所有出站写入：明文 → 加密 → socket。
     *
     * <p>两种调用来源：</p>
     * <ul>
     *   <li><b>明文路径</b>：HTTP 层（{@code THREAD_WRITE_BUF} 或 HTTP/2 的帧缓冲）
     *       —— 加密后写出，<b>永远返回 true</b>：明文已经被本类接管，
     *       绝不能让它被塞进 NIO 的写队列（那里是「原样重写」的语义）。</li>
     *   <li><b>密文重试路径</b>：NIO 层把写队列头重写一遍 —— 队列里放的是密文，
     *       原样写出即可。</li>
     * </ul>
     */
    @Override
    public boolean writeToChannel(FastBuf buf) throws IOException {
        if (buf != null && buf == getWriteBuf()) {
            // 密文重试路径：NIO 写队列里挂的是已经加密好的字节。
            // ★ 这里不要清 cipherInQueue —— 队列头被摘掉时由 onWriteBufRemoved 统一处理，
            //   提前清掉会让 backlog 永远排不出去（大响应体卡死在这里）。
            return super.writeToChannel(buf);
        }
        // 明文路径
        int len = buf.remain();
        if (len <= 0) {
            // 没有字节要发 —— 不能 wrap（否则会产出一个空的 TLS 记录，白白占带宽）
            return true;
        }
        byte[] plain = new byte[len];
        buf.get(plain);
        buf.clear();
        byte[] cipher = encrypt(plain);
        writeCipher(cipher);
        return true;
    }

    /**
     * 明文 → 密文。
     *
     * <p><b>★ 可能产出多段密文</b>：TLS 1.3 的服务端在握手结束后还有
     * NewSessionTicket 这类后握手消息要发，引擎会先把它 wrap 出来（明文一个字节都不消费），
     * 再 wrap 应用数据。所以这里要把每次 wrap 的产出都攒起来，不能只取最后一段。</p>
     */
    private byte[] encrypt(byte[] plain) throws IOException {
        ByteBuffer src = ByteBuffer.wrap(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 64);
        int guard = 0;
        while (true) {
            int before = src.position();
            ByteBuffer dst = ByteBuffer.wrap(cipherScratch);
            SSLEngineResult result;
            try {
                result = engine.wrap(src, dst);
            } catch (SSLException e) {
                throw new IOException("TLS 加密失败: " + e.getMessage(), e);
            }
            if (dst.position() > 0) {
                dst.flip();
                byte[] chunk = new byte[dst.remaining()];
                dst.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                growCipher();
                continue;
            }
            if (!src.hasRemaining()) {
                break;
            }
            if (src.position() == before) {
                // 引擎没消费明文 —— 多半是先要发后握手消息，再 wrap 一次即可。
                // ★ 不能扩容（扩容会作废这次已经产出的字节）。
                if (++guard > 16) {
                    throw new IOException("TLS 加密无法推进，疑似引擎异常");
                }
                continue;
            }
        }
        return out.toByteArray();
    }

    private void growCipher() {
        cipherScratch = new byte[Math.max(cipherScratch.length * 2, cipherScratch.length + CIPHER_OUT_INIT)];
    }

    /**
     * 把一段密文排进 backlog 并尽量写出去。
     */
    private void writeCipher(byte[] cipher) throws IOException {
        if (cipher == null || cipher.length == 0) {
            return;
        }
        if (debug) {
            LOG.info("TLS 密文 {} 字节: {}", l -> l.arg(cipher.length).arg(hex(cipher, cipher.length)));
        }
        cipherBacklog.addLast(cipher);
        pumpCipher();
    }

    /**
     * 把 backlog 里的密文尽量写到 socket。
     *
     * <p>★ 保序：NIO 写队列里还有没写完的旧密文时，<b>绝不能</b>直接写新密文
     * （TLS 是有序字节流，插队会让记录错乱）。</p>
     */
    private void pumpCipher() throws IOException {
        while (!cipherBacklog.isEmpty() && getWriteBuf() == null) {
            byte[] data = cipherBacklog.pollFirst();
            FastBuf cb = new FastBuf(Math.max(data.length, 64));
            cb.write(data, 0, data.length);
            boolean wrote = super.writeToChannel(cb);
            if (wrote) {
                // 这一块写完了 —— 继续下一块
                continue;
            }
            // socket 写不动 —— 挂到 NIO 写队列，等 OP_WRITE 事件继续写
            cipherInQueue = cb;
            putToWriteQueue(cb);
            enableWriteInterest();
            return;
        }
        if (cipherBacklog.isEmpty() && cipherInQueue == null) {
            disableWriteInterest();
        }
    }

    /**
     * NIO 写队列头被摘掉 —— 继续发 backlog 里剩下的密文。
     *
     * <p><b>★ 为什么要排在 HTTP/2 的 {@code onWriteBufDrained} 之前</b>：
     * backlog 里是<b>比新帧更早</b>的密文。先把它们排出去，新帧才能安全地
     * 接着写 —— 否则 TLS 记录会乱序，对端解密直接失败。</p>
     */
    @Override
    protected void onWriteBufRemoved(FastBuf wbuf) {
        if (wbuf != cipherInQueue) {
            return;
        }
        cipherInQueue = null;
        try {
            pumpCipher();
        } catch (IOException e) {
            LOG.warn("TLS 续写密文失败: ", l -> l.threw(e));
        }
    }

    /**
     * 撤销 {@code OP_WRITE}。
     *
     * <p><b>★ TLS 自己还有没写出去的密文时不能撤</b>：HTTP/2 的帧写完就认为
     * 「发送完毕」并撤销 {@code OP_WRITE}，但那只是把字节交给了 TLS 层，
     * 密文还堵在 backlog 里。撤了 {@code OP_WRITE} 就再也没人来排空它 ——
     * socket 写满后大响应体永远发不出去。</p>
     */
    @Override
    public void disableWriteInterest() {
        if (!cipherBacklog.isEmpty() || cipherInQueue != null) {
            return;
        }
        super.disableWriteInterest();
    }

    // ------------------------------------------------------------------
    // 应用层
    // ------------------------------------------------------------------

    /**
     * 握手没完成之前没有应用层明文 —— 直接返回 false（不产出消息）。
     */
    @Override
    public boolean decode(FastBuf fastBuf, boolean threadSwitch) throws IOException {
        if (!handshakeDone) {
            return false;
        }
        return super.decode(fastBuf, threadSwitch);
    }

    /**
     * 关闭连接前尽力发一个 {@code close_notify}。
     */
    @Override
    public void close() {
        try {
            if (handshakeDone) {
                engine.closeOutbound();
                ByteBuffer empty = ByteBuffer.allocate(0);
                ByteBuffer dst = ByteBuffer.wrap(cipherScratch);
                engine.wrap(empty, dst);
                if (dst.position() > 0) {
                    dst.flip();
                    byte[] data = new byte[dst.remaining()];
                    dst.get(data);
                    FastBuf cb = new FastBuf(Math.max(data.length, 64));
                    cb.write(data, 0, data.length);
                    super.writeToChannel(cb);
                }
            }
        } catch (Throwable t) {
            // close_notify 失败无所谓 —— 连接本来就要关
        }
        super.close();
    }

    private static String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit((data[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(data[i] & 0xf, 16));
        }
        return sb.toString();
    }
}
