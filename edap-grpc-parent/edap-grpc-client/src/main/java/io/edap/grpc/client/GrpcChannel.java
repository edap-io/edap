/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc.client;

import io.edap.grpc.GrpcException;
import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackEncoder;
import io.edap.http.http2.hpack.HpackHeader;
import io.edap.rpc.StreamObserver;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC 客户端连接。
 *
 * <p><b>传输层就是 HTTP/2</b>：gRPC over HTTP/2 只在语义层加了「消息分帧 + trailers 里的
 * grpc-status」。所以这里走 <b>HTTP/2 prior knowledge</b> —— 连上就直接发连接前言
 * （{@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n}），不做 h2c 升级探测（gRPC 客户端本来也不做）。</p>
 *
 * <p><b>线程模型</b>：</p>
 * <ul>
 *   <li>一个<b>读线程</b>：读帧 → 按 stream id 分发到各自的 {@link GrpcClientCall}。
 *       同一连接的所有调用共用这一个读线程（HPACK 的解码状态是连接级的）。</li>
 *   <li>请求侧的写入在<b>调用方线程</b>上：帧字节先攒进待写缓冲，由当轮的「刷出者」
 *       线程在锁外做一次 socket 写。</li>
 * </ul>
 *
 * <p><b>三把锁</b>，各管各的，互不嵌套（避免死锁）：</p>
 * <ul>
 *   <li>{@code writeLock} —— 保护 HPACK 编码器状态与「待写缓冲」；</li>
 *   <li>{@code flowLock} —— 保护发送窗口（连接级 + 流级）；</li>
 *   <li>每个 call 自己的 {@code sendLock} —— 保证同一调用的消息按发送顺序上线。</li>
 * </ul>
 *
 * <p><b>写路径：批量 + 锁外 syscall</b>（leader/follower）：</p>
 * <pre>{@code
 *   线程 A: lock → 把帧字节追加进待写缓冲 → 解锁 → 自己当 leader → 锁外 out.write(整批)
 *   线程 B: lock → 把帧字节追加进待写缓冲 → 解锁 → 发现已有 leader → 直接返回
 * }</pre>
 * <p>leader 写完一轮后会回头看缓冲里还有没有别人的字节，有就接着写 ——
 * 所以高并发时多个线程的帧会<b>合并成一次 syscall</b>，而 syscall 又在锁外，
 * 不再像「锁内 write+flush」那样把所有写者串在一条 syscall 上（实测锁内 syscall
 * 占掉了 62% 的墙钟时间）。</p>
 *
 * <p><b>为什么不用 edap-nio-client</b>：它的编译级别是 Java 21，而 edap 主体是 Java 17；
 * 且 edap 的 NIO 是纯服务端框架，没有连接池 / 重连 / 流控管理。</p>
 */
public final class GrpcChannel implements Closeable {

    /** HTTP/2 连接前言（RFC 9113 §3.4） */
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] EMPTY = new byte[0];

    /** SETTINGS_INITIAL_WINDOW_SIZE 的设置项编号（RFC 9113 §6.5.2） */
    private static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;

    /** MAX_FRAME_SIZE 的设置项编号（RFC 9113 §6.5.2） */
    private static final int SETTINGS_MAX_FRAME_SIZE = 0x6;

    /** 单个 DATA 帧的最大载荷（对端通告的 MAX_FRAME_SIZE 默认 16384） */
    private static final int MAX_FRAME_PAYLOAD = 16384;

    /** 初始流控窗口（RFC 9113 §6.9.2） */
    private static final int DEFAULT_WINDOW = 65535;

    /**
     * 本端通告的<b>流级</b>接收窗口（1MB）。
     *
     * <p>65535 太小：一条 64KB 的响应塞不进一个窗口，对端必须等我们归还额度才能发完。</p>
     */
    private static final int LOCAL_WINDOW = 1024 * 1024;

    /**
     * 本端<b>连接级</b>接收窗口的目标值（8MB）。
     *
     * <p>RFC 9113 §6.9.2：连接级窗口初值恒为 65535，<b>不受 SETTINGS_INITIAL_WINDOW_SIZE 影响</b>，
     * 但接收方<b>可以</b>用 {@code WINDOW_UPDATE}（streamId=0）把它撑大 ——
     * grpc-java 两端都撑到 1MB。不撑的话一条连接上只能有 64KB 在途。</p>
     *
     * <p><b>为什么是 8MB</b>：连接级窗口是「一条连接上最多能同时在途多少字节」的天花板。
     * 1MB 时一条 64KB 的响应只允许 <b>16 个</b>并发在途 —— 32 并发下另外 16 个的调用方线程
     * 要等对端把窗口补回来才拿得到自己的响应（实测每条请求平均等 <b>1.13ms</b>，
     * p99 被推到 <b>15ms</b>）。8MB 能装下 128 条 64KB 响应 ⇒ 并发 32 时不再有流控停顿
     * （实测流控等待次数 204,811 → <b>0</b>，p99 15.2ms → <b>2.7ms</b>，
     * 吞吐 15,076 → <b>18,966 req/s</b>）。</p>
     */
    public static final int CONN_WINDOW = Integer.getInteger("edap.grpc.connWindow", 8 * 1024 * 1024);

    /** 收到多少字节后归还<b>连接级</b>接收窗口（攒一半再还，避免小帧过多） */
    private static final int RECV_CREDIT_THRESHOLD = CONN_WINDOW / 2;

    private final String host;
    private final int port;
    private final int callTimeoutMillis;
    private final int connectTimeoutMillis;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private final HpackEncoder encoder = new HpackEncoder();
    private final HpackDecoder decoder = new HpackDecoder();

    /** 客户端发起的流 id 必须是奇数且递增（RFC 9113 §5.1.1） */
    private final AtomicInteger nextStreamId = new AtomicInteger(1);

    /** 在途调用（stream id → call） */
    private final ConcurrentHashMap<Integer, GrpcClientCall> calls = new ConcurrentHashMap<>();

    private final Object writeLock = new Object();
    private final Object flowLock = new Object();

    /**
     * 待写缓冲（帧字节在这里累积）—— 由 {@code writeLock} 保护。
     *
     * <p>帧只在锁内<b>追加</b>，socket 写（syscall）在锁外做 —— 见类注释的 leader/follower 说明。</p>
     */
    private byte[] pending = new byte[16 * 1024];

    /** 待写缓冲里已有的字节数 —— 由 {@code writeLock} 保护 */
    private int pendingLen;

    /** 是否已经有人在刷出待写缓冲 —— 由 {@code writeLock} 保护 */
    private boolean draining;

    /**
     * 刷出用的缓冲 —— 与 {@link #pending} <b>轮换</b>用（双缓冲）。
     *
     * <p>刷出时把两块缓冲对调：待写的字节换到 {@code drainBuf} 直接写出去，
     * 另一块换回来当下次的累积缓冲 —— 这样每条 64KB 请求<b>少一次整块拷贝</b>
     * （原来是把 pending 拷进 drainBuf 再写）。</p>
     */
    private byte[] drainBuf = new byte[16 * 1024];

    /** 写出失败的原因（一旦发生连接就废了）—— 由 {@code writeLock} 保护 */
    private IOException writeFailure;

    /** 连接级发送窗口：对端还能接收多少字节 */
    private int connSendWindow = DEFAULT_WINDOW;

    /** 对端通告的 INITIAL_WINDOW_SIZE（新流的发送窗口初值）—— 由 flowLock 保护 */
    private int peerInitialWindowSize = DEFAULT_WINDOW;

    /**
     * 对端通告的 MAX_FRAME_SIZE（我们发 DATA 帧时不能超过它）。
     *
     * <p>默认 16384，但 grpc-java 通告的是 <b>8192</b> —— 不看对端的值就会发出去
     * 一个 16KB 的帧，对端按 FRAME_SIZE_ERROR 判错。</p>
     */
    private volatile int peerMaxFrameSize = MAX_FRAME_PAYLOAD;

    /** 已收到但还没归还给对端的连接级字节数 */
    private int pendingConnRecvCredit;

    private final Thread readerThread;
    private volatile boolean closed;
    private volatile IOException readerError;

    /**
     * 建立连接（默认调用超时 30 秒、连接超时 10 秒）。
     */
    public GrpcChannel(String host, int port) throws IOException {
        this(host, port, 30_000, 10_000);
    }

    /**
     * 建立连接。
     *
     * @param host              服务端地址
     * @param port              服务端端口
     * @param callTimeoutMillis 调用的默认超时（{@code awaitCompletion} 的兜底值）
     * @param connectTimeoutMillis 连接超时
     */
    public GrpcChannel(String host, int port, int callTimeoutMillis, int connectTimeoutMillis)
            throws IOException {
        this.host = host;
        this.port = port;
        this.callTimeoutMillis = callTimeoutMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
        s.setTcpNoDelay(true);
        // 读线程不设超时 —— 空闲连接是正常的；close() 时关 socket 让读线程醒过来
        s.setSoTimeout(0);
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();

        // 连接前言 + SETTINGS（通告本端的流级接收窗口）+ 连接级窗口扩容 —— 合成一次写
        synchronized (writeLock) {
            appendRawLocked(PREFACE);
            appendFrameLocked(Http2FrameType.SETTINGS, 0, 0, settingsPayload());
            // 主动把连接级接收窗口撑到 1MB（RFC 9113 §6.9.2：连接级窗口不受
            // SETTINGS_INITIAL_WINDOW_SIZE 影响，只能由接收方用 WINDOW_UPDATE 撑大）。
            // 不撑的话一条连接上只能有 64KB 在途 —— 并发再高也没有意义。
            appendFrameLocked(Http2FrameType.WINDOW_UPDATE, 0, 0, intPayload(CONN_WINDOW - DEFAULT_WINDOW));
            draining = true;
        }
        drain();

        this.readerThread = new Thread(this::readLoop, "edap-grpc-client-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    // ------------------------------------------------------------------
    // 调用入口
    // ------------------------------------------------------------------

    /**
     * 新建一次调用（请求侧还没发任何东西）。
     *
     * @param path             gRPC 的 {@code :path}
     * @param reqType          请求消息类型（编码用）
     * @param respType         响应消息类型（解码用）
     * @param responseObserver 响应观察者（可为 null —— 只收集不回调）
     * @return 调用句柄
     */
    public GrpcClientCall newCall(String path, Class<?> reqType, Class<?> respType,
                                  StreamObserver<Object> responseObserver) {
        return newCall(path, reqType, respType, responseObserver, null);
    }

    /**
     * 新建一次调用（带额外的请求元数据）。
     *
     * @param headers 额外的请求头（如鉴权头）；key 会被转成小写
     */
    public GrpcClientCall newCall(String path, Class<?> reqType, Class<?> respType,
                                  StreamObserver<Object> responseObserver,
                                  Map<String, String> headers) {
        if (closed) {
            throw new GrpcException("gRPC 连接已关闭");
        }
        List<HpackHeader> hdrs = new ArrayList<>(9 + (headers == null ? 0 : headers.size()));
        hdrs.add(new HpackHeader(":method", "POST"));
        hdrs.add(new HpackHeader(":path", path));
        hdrs.add(new HpackHeader(":scheme", "http"));
        hdrs.add(new HpackHeader(":authority", host + ":" + port));
        hdrs.add(new HpackHeader("content-type", "application/grpc"));
        hdrs.add(new HpackHeader("te", "trailers"));
        hdrs.add(new HpackHeader("grpc-encoding", "identity"));
        hdrs.add(new HpackHeader("grpc-accept-encoding", "identity"));
        hdrs.add(new HpackHeader("user-agent", "edap-grpc-client"));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                hdrs.add(new HpackHeader(e.getKey().toLowerCase(), e.getValue()));
            }
        }
        // ★ 分配 stream id 与写 HEADERS 必须在同一把锁里 ——
        //   RFC 9113 §5.1.1：客户端新开的流 id 必须**递增**。
        //   并发调用时如果「分配 id」与「写 HEADERS」分开，两条流就可能按
        //   3 → 5 的顺序分配、却按 5 → 3 的顺序上线 —— 对端会判 PROTOCOL_ERROR 并 GOAWAY。
        int streamId;
        GrpcClientCall call;
        boolean leader;
        synchronized (writeLock) {
            streamId = nextStreamId.getAndAdd(2);
            // ★ 取「流级发送窗口初值」与「放进 calls 表」必须在同一把 flowLock 里 ——
            //   否则 SETTINGS 帧可能正好插在两者中间：对端把 INITIAL_WINDOW_SIZE 改成 1MB，
            //   delta 传播只作用于**当时已在表里**的流，而这个新流既拿到了旧的 65535、
            //   又没被 delta 抬上去 → 发满 64KB 就永远等不到流级额度。
            synchronized (flowLock) {
                call = new GrpcClientCall(this, streamId, path, reqType, respType, responseObserver,
                        peerInitialWindowSize);
                calls.put(streamId, call);
            }
            try {
                byte[] block = encoder.encode(hdrs);
                appendFrameLocked(Http2FrameType.HEADERS, Http2Flags.END_HEADERS, streamId, block);
            } catch (RuntimeException e) {
                calls.remove(streamId);
                throw e;
            }
            leader = claimDrainLocked();
        }
        // ★ 帧只是<b>排进了待写缓冲</b> —— 真正的 socket 写在锁外由刷出者做
        if (leader) {
            drain();
        }
        return call;
    }

    /**
     * 一元调用：发一条请求，收一条响应。
     *
     * @return 服务端的响应消息
     * @throws GrpcException 调用失败或超时
     */
    public <Req, Resp> Resp unary(String path, Class<Req> reqType, Class<Resp> respType, Req request) {
        GrpcClientCall call = newCall(path, reqType, respType, null);
        call.sendMessage(request);
        call.halfClose();
        call.awaitCompletion(callTimeoutMillis);
        List<Object> responses = call.responses();
        if (responses.isEmpty()) {
            throw new GrpcException("gRPC 一元调用没有收到响应消息: " + path);
        }
        @SuppressWarnings("unchecked")
        Resp resp = (Resp) responses.get(0);
        return resp;
    }

    /**
     * 一元调用（请求类型从消息实例推导）。
     */
    public <Req, Resp> Resp unary(String path, Class<Resp> respType, Req request) {
        if (request == null) {
            throw new GrpcException("gRPC 请求消息不能为 null: " + path);
        }
        @SuppressWarnings("unchecked")
        Class<Req> reqType = (Class<Req>) request.getClass();
        return unary(path, reqType, respType, request);
    }

    /**
     * 服务端流式调用：发一条请求，响应由观察者逐条收。
     *
     * <p>方法返回时请求已经发完；响应的结束由观察者的 {@code onCompleted} / {@code onError} 通知。</p>
     */
    public <Req, Resp> GrpcClientCall serverStream(String path, Class<Req> reqType, Class<Resp> respType,
                                                    Req request, StreamObserver<Resp> responseObserver) {
        GrpcClientCall call = newCall(path, reqType, respType, adapt(responseObserver));
        call.sendMessage(request);
        call.halfClose();
        return call;
    }

    /**
     * 客户端流式调用：返回请求观察者，调用方用它发消息。
     */
    public <Req, Resp> GrpcClientCall clientStream(String path, Class<Req> reqType, Class<Resp> respType,
                                                    StreamObserver<Resp> responseObserver) {
        return newCall(path, reqType, respType, adapt(responseObserver));
    }

    /**
     * 双向流式调用：返回请求观察者，调用方用它发消息。
     */
    public <Req, Resp> GrpcClientCall bidi(String path, Class<Req> reqType, Class<Resp> respType,
                                            StreamObserver<Resp> responseObserver) {
        return newCall(path, reqType, respType, adapt(responseObserver));
    }

    /**
     * 把带泛型的响应观察者适配成 {@code StreamObserver<Object>}。
     */
    @SuppressWarnings("unchecked")
    private static StreamObserver<Object> adapt(StreamObserver<?> observer) {
        return (StreamObserver<Object>) observer;
    }

    // ------------------------------------------------------------------
    // 写侧
    // ------------------------------------------------------------------

    /**
     * 写请求数据（受流控约束）。
     *
     * @param data 可以为 null（表示只有 END_STREAM 的空 DATA 帧）
     */
    void writeData(GrpcClientCall call, byte[] data, int offset, int length, boolean endStream) {
        if (length <= 0) {
            if (endStream) {
                writeFrame(Http2FrameType.DATA, Http2Flags.END_STREAM, call.streamId(), EMPTY);
            }
            return;
        }
        int sent = 0;
        // ★ 不能超过对端通告的 MAX_FRAME_SIZE（grpc-java 通告 8192）——
        //   超了对方按 FRAME_SIZE_ERROR 判错，整条连接被干掉。
        int maxPayload = Math.min(MAX_FRAME_PAYLOAD, peerMaxFrameSize);
        while (sent < length) {
            int n = acquireSendQuota(call, Math.min(length - sent, maxPayload));
            boolean last = endStream && (sent + n == length);
            // ★ 载荷<b>切片</b>直接写进待写缓冲：不为每个分片 new 一个 byte[] 再拷一遍。
            //   原来一条 64KB 请求要多分配 5 个 16KB 的分片数组（= 82KB 垃圾/请求）。
            writeFrame(Http2FrameType.DATA, last ? Http2Flags.END_STREAM : 0, call.streamId(),
                    data, offset + sent, n);
            sent += n;
        }
    }

    /**
     * 申请发送窗口配额（不够就等 WINDOW_UPDATE）。
     *
     * <p>扣减在写帧之前完成 —— 否则并发的两个写者会同时看到「还有额度」而超发。</p>
     */
    private int acquireSendQuota(GrpcClientCall call, int want) {
        long deadline = System.currentTimeMillis() + callTimeoutMillis;
        synchronized (flowLock) {
            while (true) {
                if (closed) {
                    throw new GrpcException("gRPC 连接已关闭");
                }
                int allowed = Math.min(want, Math.min(connSendWindow, call.sendWindow()));
                if (allowed > 0) {
                    connSendWindow -= allowed;
                    call.consumeSendWindow(allowed);
                    return allowed;
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    throw new GrpcException("gRPC 发送流控窗口等待超时: " + call.path());
                }
                try {
                    long t0 = System.nanoTime();
                    flowLock.wait(Math.min(remain, 100));
                    if (GrpcClientDiag.ENABLED) {
                        GrpcClientDiag.quotaWaitNanos.add(System.nanoTime() - t0);
                        GrpcClientDiag.quotaWaits.increment();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GrpcException("gRPC 发送被中断: " + call.path(), e);
                }
            }
        }
    }

    /**
     * 收到流级 WINDOW_UPDATE —— 放开该流的发送窗口。
     */
    private void increaseStreamSendWindow(GrpcClientCall call, int increment) {
        synchronized (flowLock) {
            call.increaseSendWindow(increment);
            flowLock.notifyAll();
        }
    }

    /**
     * 重置流（取消调用）。
     *
     * <p>★ RST_STREAM 的载荷**必须正好 4 字节**（RFC 9113 §6.4）—— 就是错误码。
     * 写成 8 字节对端会判 FRAME_SIZE_ERROR，而这是**连接级**错误：整条连接被干掉，
     * 其它在途调用全部陪葬。</p>
     */
    void resetStream(int streamId) {
        try {
            writeFrame(Http2FrameType.RST_STREAM, 0, streamId,
                    intPayload(Http2ErrorCode.CANCEL));
        } catch (RuntimeException ignore) {
            // 连接已经断了 —— 没什么可补救的
        }
    }

    /**
     * 排一帧进待写缓冲，并确保有人会把它刷出去。
     *
     * <p>★ <b>锁内只追加字节，不做 syscall</b>：socket 写由当轮的「刷出者」线程在锁外做，
     * 这样别的线程不必排队等一次 syscall（原来「锁内 write+flush」把 62% 的墙钟时间
     * 花在了锁里的 syscall 上）。</p>
     *
     * <p>本方法返回时帧<b>可能还没上线</b>（如果当轮已有刷出者，它会顺手把本帧一起写出去）。
     * 帧的<b>先后顺序</b>由 {@code writeLock} 决定，与上线时机无关。</p>
     */
    private void writeFrame(int type, int flags, int streamId, byte[] payload) {
        writeFrame(type, flags, streamId, payload, 0, payload.length);
    }

    /**
     * 排一帧进待写缓冲，并确保有人会把它刷出去。
     *
     * <p>★ <b>锁内只追加字节，不做 syscall</b>：socket 写由当轮的「刷出者」线程在锁外做，
     * 这样别的线程不必排队等一次 syscall（原来「锁内 write+flush」把 62% 的墙钟时间
     * 花在了锁里的 syscall 上）。</p>
     *
     * <p>本方法返回时帧<b>可能还没上线</b>（如果当轮已有刷出者，它会顺手把本帧一起写出去）。
     * 帧的<b>先后顺序</b>由 {@code writeLock} 决定，与上线时机无关。</p>
     *
     * @param payload 载荷缓冲；{@code offset}/{@code length} 取出其中的一段（不必先拷出来）
     */
    private void writeFrame(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        boolean leader;
        synchronized (writeLock) {
            if (writeFailure != null) {
                throw new GrpcException("gRPC 连接写出失败", writeFailure);
            }
            if (closed) {
                return;                                 // 连接已关 —— 控制帧不必再发
            }
            appendFrameLocked(type, flags, streamId, payload, offset, length);
            leader = claimDrainLocked();
        }
        if (leader) {
            drain();
        }
    }

    /**
     * 认领「刷出者」身份（调用方必须持有 {@code writeLock}）。
     *
     * @return true = 本线程是刷出者，需要在锁外调用 {@link #drain()}
     */
    private boolean claimDrainLocked() {
        if (draining) {
            return false;                               // 已有人在刷 —— 本线程直接返回
        }
        draining = true;
        return true;
    }

    /**
     * 把待写缓冲刷到 socket（<b>调用方不得持有 {@code writeLock}</b>）。
     *
     * <p>刷完一轮后会回头看缓冲里还有没有别人的字节，有就接着写 ——
     * 所以高并发下多个线程排的帧会合并成一次 syscall。</p>
     */
    private void drain() {
        try {
            for (;;) {
                int len;
                byte[] buf;
                synchronized (writeLock) {
                    if (pendingLen == 0) {
                        draining = false;
                        return;
                    }
                    len = pendingLen;
                    // ★ 双缓冲轮换：把「有数据的那块」换到 drainBuf 直接写出去 ——
                    //   不用再把 pending 整块拷进 drainBuf（每条 64KB 请求少一次整块拷贝）。
                    //   换回来的那块当下次的累积缓冲；此时正被写的那块只有本线程在碰，
                    //   别的线程往 pending 里追加不会踩到它。
                    byte[] spare = drainBuf;
                    drainBuf = pending;
                    pending = spare;
                    pendingLen = 0;
                    buf = drainBuf;
                }
                // ★ syscall 在锁外：别的线程可以继续往缓冲里排帧
                //   t0 只在计数打开时才取 —— ENABLED 是编译期常量，关闭时 nanoTime 被消掉
                long t0 = GrpcClientDiag.ENABLED ? System.nanoTime() : 0L;
                out.write(buf, 0, len);
                out.flush();
                if (GrpcClientDiag.ENABLED) {
                    GrpcClientDiag.socketWrites.increment();
                    GrpcClientDiag.socketWriteBytes.add(len);
                    GrpcClientDiag.writeNanos.add(System.nanoTime() - t0);
                }
            }
        } catch (IOException e) {
            synchronized (writeLock) {
                draining = false;
                if (writeFailure == null) {
                    writeFailure = e;
                }
            }
            if (!closed) {
                failAllCalls(new GrpcException("gRPC 帧写出失败", e));
            }
            throw new GrpcException("gRPC 帧写出失败", e);
        }
    }

    /**
     * 追加一帧的字节（调用方必须持有 {@code writeLock}）。
     */
    private void appendFrameLocked(int type, int flags, int streamId, byte[] payload) {
        appendFrameLocked(type, flags, streamId, payload, 0, payload.length);
    }

    /**
     * 追加一帧的字节（调用方必须持有 {@code writeLock}）。
     *
     * @param payload 载荷缓冲
     * @param offset  载荷在 {@code payload} 里的起始位置
     * @param length  载荷长度
     */
    private void appendFrameLocked(int type, int flags, int streamId, byte[] payload, int offset, int length) {
        int need = 9 + length;
        if (pendingLen + need > pending.length) {
            int cap = Math.max(pending.length * 2, pendingLen + need);
            byte[] bigger = new byte[cap];
            System.arraycopy(pending, 0, bigger, 0, pendingLen);
            pending = bigger;
        }
        int off = pendingLen;
        pending[off] = (byte) ((length >>> 16) & 0xFF);
        pending[off + 1] = (byte) ((length >>> 8) & 0xFF);
        pending[off + 2] = (byte) (length & 0xFF);
        pending[off + 3] = (byte) (type & 0xFF);
        pending[off + 4] = (byte) (flags & 0xFF);
        pending[off + 5] = (byte) ((streamId >>> 24) & 0x7F);
        pending[off + 6] = (byte) ((streamId >>> 16) & 0xFF);
        pending[off + 7] = (byte) ((streamId >>> 8) & 0xFF);
        pending[off + 8] = (byte) (streamId & 0xFF);
        if (length > 0) {
            System.arraycopy(payload, offset, pending, off + 9, length);
        }
        pendingLen += need;
    }

    /**
     * 追加一段原始字节（连接前言这种不是帧的东西）—— 调用方必须持有 {@code writeLock}。
     */
    private void appendRawLocked(byte[] bytes) {
        if (pendingLen + bytes.length > pending.length) {
            int cap = Math.max(pending.length * 2, pendingLen + bytes.length);
            byte[] bigger = new byte[cap];
            System.arraycopy(pending, 0, bigger, 0, pendingLen);
            pending = bigger;
        }
        System.arraycopy(bytes, 0, pending, pendingLen, bytes.length);
        pendingLen += bytes.length;
    }

    // ------------------------------------------------------------------
    // 读侧
    // ------------------------------------------------------------------

    private void readLoop() {
        IOException error = null;
        try {
            while (!closed) {
                Frame frame = readFrame();
                if (frame == null) {
                    break;
                }
                dispatch(frame);
            }
        } catch (IOException e) {
            error = e;
        } catch (RuntimeException e) {
            error = new IOException("gRPC 读线程异常: " + e.getMessage(), e);
        }
        readerError = error;
        // 连接断了 —— 所有在途调用都要失败，不能让调用方一直等
        failAllCalls(error == null ? new GrpcException("gRPC 连接已关闭") : error);
    }

    private void dispatch(Frame frame) {
        if (!GrpcClientDiag.ENABLED) {
            dispatch0(frame);
            return;
        }
        long t0 = System.nanoTime();
        try {
            dispatch0(frame);
        } finally {
            GrpcClientDiag.dispatchNanos.add(System.nanoTime() - t0);
        }
    }

    private void dispatch0(Frame frame) {
        switch (frame.type) {
            case Http2FrameType.HEADERS: {
                List<HpackHeader> headers = decoder.decode(frame.payload, 0, frame.payload.length);
                GrpcClientCall call = calls.get(frame.streamId);
                if (call == null) {
                    return;                                     // 未知流 —— 忽略
                }
                if (call.responseHeaders() == null) {
                    call.onHeaders(headers);
                } else {
                    call.onTrailers(headers);
                }
                if (hasFlag(frame.flags, Http2Flags.END_STREAM)) {
                    call.onEndWithoutTrailers();
                }
                break;
            }
            case Http2FrameType.DATA: {
                GrpcClientCall call = calls.get(frame.streamId);
                if (call != null && frame.payload.length > 0) {
                    call.onData(frame.payload, 0, frame.payload.length);
                }
                grantRecvCredit(frame.streamId, frame.payload.length);
                if (hasFlag(frame.flags, Http2Flags.END_STREAM)) {
                    if (call != null) {
                        // 服务端直接结束响应（没有 trailers）—— gRPC 规范不允许
                        call.onEndWithoutTrailers();
                    }
                }
                break;
            }
            case Http2FrameType.SETTINGS: {
                if (!hasFlag(frame.flags, Http2Flags.ACK)) {
                    applyPeerSettings(frame.payload);
                    writeFrame(Http2FrameType.SETTINGS, Http2Flags.ACK, 0, EMPTY);
                }
                break;
            }
            case Http2FrameType.PING: {
                if (!hasFlag(frame.flags, Http2Flags.ACK)) {
                    writeFrame(Http2FrameType.PING, Http2Flags.ACK, 0, frame.payload);
                }
                break;
            }
            case Http2FrameType.WINDOW_UPDATE: {
                int increment = readInt(frame.payload, 0);
                if (frame.streamId == 0) {
                    synchronized (flowLock) {
                        connSendWindow += increment;
                        flowLock.notifyAll();
                    }
                } else {
                    GrpcClientCall call = calls.get(frame.streamId);
                    if (call != null) {
                        increaseStreamSendWindow(call, increment);
                    }
                }
                break;
            }
            case Http2FrameType.RST_STREAM: {
                GrpcClientCall call = calls.get(frame.streamId);
                if (call != null) {
                    call.cancel(new GrpcException("服务端重置了流: " + call.path()));
                }
                break;
            }
            case Http2FrameType.GOAWAY: {
                failAllCalls(new GrpcException("服务端发了 GOAWAY，连接即将关闭"));
                break;
            }
            default:
                // 未知帧类型按 RFC 9113 §4.1 忽略
                break;
        }
    }

    /**
     * 应用对端的 SETTINGS（关心 INITIAL_WINDOW_SIZE 与 MAX_FRAME_SIZE）。
     */
    private void applyPeerSettings(byte[] payload) {
        int newInitial = -1;
        int newMaxFrame = -1;
        for (int i = 0; i + 6 <= payload.length; i += 6) {
            int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            if (id == SETTINGS_INITIAL_WINDOW_SIZE) {
                newInitial = readInt(payload, i + 2);
            } else if (id == SETTINGS_MAX_FRAME_SIZE) {
                newMaxFrame = readInt(payload, i + 2);
            }
        }
        synchronized (flowLock) {
            if (newMaxFrame > 0) {
                // 对端通告的帧上限 —— 我们发 DATA 帧不能超过它（grpc-java 通告 8192）
                peerMaxFrameSize = newMaxFrame;
            }
            if (newInitial < 0 || newInitial == peerInitialWindowSize) {
                return;
            }
            int delta = newInitial - peerInitialWindowSize;
            peerInitialWindowSize = newInitial;
            // RFC 9113 §6.9.2：INITIAL_WINDOW_SIZE 的变化要传播给所有已存在的流
            for (GrpcClientCall call : calls.values()) {
                call.increaseSendWindow(delta);
            }
            flowLock.notifyAll();
        }
    }

    /**
     * 调用结束 —— 从在途表里摘掉。
     */
    void onCallCompleted(GrpcClientCall call) {
        calls.remove(call.streamId());
    }

    /**
     * 流级 credit 的归还阈值（收到这么多字节才归还一次）—— 本端通告的流级接收窗口的一半。
     */
    int streamRecvCreditThreshold() {
        return LOCAL_WINDOW / 2;
    }

    /**
     * 归还接收窗口（收到的 DATA 消费掉了对端的发送窗口）。
     *
     * <p>★ <b>流级 credit 必须按流记账</b>：流级 {@code WINDOW_UPDATE} 只对它指定的那条流有效。
     * 把「连接级的累计字节数」记到触发阈值的那一条流上，其余的流就永远收不满响应 ——
     * 多流并发时表现为随机地有调用卡住不动。</p>
     */
    private void grantRecvCredit(int streamId, int length) {
        if (length <= 0) {
            return;
        }
        int streamCredit = 0;
        int connCredit = 0;
        synchronized (flowLock) {
            GrpcClientCall call = calls.get(streamId);
            if (call != null) {
                streamCredit = call.addRecvCredit(length);
            }
            pendingConnRecvCredit += length;
            if (pendingConnRecvCredit >= RECV_CREDIT_THRESHOLD) {
                connCredit = pendingConnRecvCredit;
                pendingConnRecvCredit = 0;
            }
        }
        if (streamCredit > 0) {
            writeFrame(Http2FrameType.WINDOW_UPDATE, 0, streamId, intPayload(streamCredit));
        }
        if (connCredit > 0) {
            writeFrame(Http2FrameType.WINDOW_UPDATE, 0, 0, intPayload(connCredit));
        }
    }

    private void failAllCalls(Throwable cause) {
        for (GrpcClientCall call : calls.values()) {
            call.cancel(cause);
        }
        calls.clear();
    }

    private Frame readFrame() throws IOException {
        // t0 只在计数打开时才取 —— ENABLED 是编译期常量，关闭时 nanoTime 被消掉
        long t0 = GrpcClientDiag.ENABLED ? System.nanoTime() : 0L;
        byte[] head = new byte[9];
        int read = 0;
        while (read < head.length) {
            int n = in.read(head, read, head.length - read);
            if (n < 0) {
                if (read == 0) {
                    return null;                                // 对端正常关闭
                }
                throw new IOException("gRPC 帧头读取中断: " + read + "/" + head.length);
            }
            read += n;
        }
        int length = ((head[0] & 0xFF) << 16) | ((head[1] & 0xFF) << 8) | (head[2] & 0xFF);
        int type = head[3] & 0xFF;
        int flags = head[4] & 0xFF;
        int streamId = ((head[5] & 0x7F) << 24) | ((head[6] & 0xFF) << 16)
                | ((head[7] & 0xFF) << 8) | (head[8] & 0xFF);
        byte[] payload = length == 0 ? EMPTY : readFully(length);
        if (GrpcClientDiag.ENABLED) {
            GrpcClientDiag.framesRead.increment();
            GrpcClientDiag.bytesRead.add(length + 9);
            GrpcClientDiag.readNanos.add(System.nanoTime() - t0);
        }
        return new Frame(type, flags, streamId, payload);
    }

    private byte[] readFully(int n) throws IOException {
        byte[] data = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(data, read, n - read);
            if (r < 0) {
                throw new IOException("gRPC 帧体读取中断: " + read + "/" + n);
            }
            read += r;
        }
        return data;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 关闭连接（关 socket → 读线程醒来 → 在途调用失败）。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            socket.close();
        } catch (IOException ignore) {
            // 关不上就算了 —— 下面的 failAllCalls 才是关键
        }
        readerThread.interrupt();
        failAllCalls(new GrpcException("gRPC 连接已关闭"));
    }

    public boolean isClosed() {
        return closed;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /**
     * @return 读线程遇到的异常（连接断开时非 null）
     */
    public IOException readerError() {
        return readerError;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }

    private static int readInt(byte[] data, int offset) {
        if (data == null || offset + 4 > data.length) {
            return 0;
        }
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static byte[] intPayload(int value) {
        return new byte[]{
                (byte) ((value >>> 24) & 0xFF),
                (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 8) & 0xFF),
                (byte) (value & 0xFF)};
    }

    /**
     * 本端的 SETTINGS 载荷 —— 只通告 {@code INITIAL_WINDOW_SIZE}（流级接收窗口 1MB）。
     *
     * <p>连接级窗口不在 SETTINGS 里（RFC 9113 §6.9.2：它不受 INITIAL_WINDOW_SIZE 影响），
     * 要靠一帧 {@code WINDOW_UPDATE}(streamId=0) 单独撑大。</p>
     */
    private static byte[] settingsPayload() {
        byte[] payload = new byte[6];
        payload[0] = (byte) ((SETTINGS_INITIAL_WINDOW_SIZE >>> 8) & 0xFF);
        payload[1] = (byte) (SETTINGS_INITIAL_WINDOW_SIZE & 0xFF);
        byte[] value = intPayload(LOCAL_WINDOW);
        System.arraycopy(value, 0, payload, 2, 4);
        return payload;
    }

    /**
     * 一帧。
     */
    private static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }
    }
}
