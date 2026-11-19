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

package io.edap.erpc.client;

import io.edap.erpc.ErpcException;
import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcFrameEncoder;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcMetadataCodec;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.rpc.RpcChannelContext;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcMetadata;
import io.edap.rpc.RpcProtocol;
import io.edap.rpc.RpcTimeout;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * eRPC 客户端的一条<b>长连接</b>（socket）。
 *
 * <p><b>多路复用</b>：一条连接上可以同时有多个请求在途，靠帧头里的 {@code requestId}
 * 配对响应 —— 所以一条连接能吃下并发调用，不需要一条调用一条连接。</p>
 *
 * <p><b>线程模型</b>：</p>
 * <ul>
 *   <li>一个<b>读线程</b>：读帧 → 按 requestId 分发给各自的 {@link PendingCall}。</li>
 *   <li>写入在<b>调用方线程</b>上：帧字节先攒进待写缓冲，由当轮的「刷出者」线程
 *       在锁外做一次 socket 写。</li>
 * </ul>
 *
 * <p><b>写路径：批量 + 锁外 syscall</b>（leader/follower）：</p>
 * <pre>{@code
 *   线程 A: lock → 把帧字节追加进待写缓冲 → 解锁 → 自己当 leader → 锁外 out.write(整批)
 *   线程 B: lock → 把帧字节追加进待写缓冲 → 解锁 → 发现已有 leader → 直接返回
 * }</pre>
 * <p>高并发时多个线程的帧会<b>合并成一次 syscall</b>，而 syscall 又在锁外。</p>
 *
 * <p>★ <b>为什么不用 edap-nio-client</b>：它的编译级别是 Java 21，而 edap 主体是 Java 17；
 * 且 edap 的 NIO 是纯服务端框架，没有连接池 / 重连 / 流控管理。</p>
 */
public final class ErpcChannel implements Closeable {

    private static final byte[] EMPTY = new byte[0];

    /** 待写缓冲的初始容量。 */
    private static final int PENDING_CAPACITY = 16 * 1024;

    /** 读缓冲的初始容量。 */
    private static final int READ_CAPACITY = 16 * 1024;

    private final ErpcNode node;
    private final ErpcCodec serializer;
    private final int connectTimeoutMillis;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    /** 请求号：一条连接内唯一且递增。 */
    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    /** 在途调用（requestId → call）。 */
    private final ConcurrentHashMap<Integer, PendingCall> calls = new ConcurrentHashMap<>();

    private final Object writeLock = new Object();

    /** 待写缓冲（帧字节在这里累积）—— 由 {@code writeLock} 保护。 */
    private byte[] pending = new byte[PENDING_CAPACITY];
    /** 待写缓冲里已有的字节数 —— 由 {@code writeLock} 保护。 */
    private int pendingLen;
    /** 是否已经有人在刷出待写缓冲 —— 由 {@code writeLock} 保护。 */
    private boolean draining;
    /** 刷出用的缓冲 —— 与 {@code pending} 轮换用（双缓冲，避免多一次整块拷贝）。 */
    private byte[] drainBuf = new byte[PENDING_CAPACITY];

    /** 写帧头用的复用对象 —— 只在 {@code writeLock} 内用。 */
    private final ErpcFrameHeader headerBuffer = new ErpcFrameHeader();

    /**
     * 帧的消费者 —— <b>每连接一个实例</b>（无状态 ⇒ 多线程共用安全）。
     *
     * <p>★ 它让「编码」发生在锁外：编码器把载荷写进它自己的线程本地缓冲，
     * 本方法在锁内把这段字节拷进待写缓冲 ⇒ 载荷在网络上只被拷一次。</p>
     */
    private final ErpcFrameEncoder.FrameSink frameSink = this::appendFrame;

    /** 读侧分帧器 —— 只在读线程用。 */
    private final ErpcFrameCodec frameCodec = new ErpcFrameCodec();

    /**
     * ★ <b>本连接</b>的上下文 —— 每连接一个。
     *
     * <p>用来放「连接级」的状态（协商结果、连接级标记……）。
     * <b>连接重建 = 新上下文</b> ⇒ 连接池换连接时标记不会沿用。</p>
     */
    private final RpcChannelContext channelContext = new RpcChannelContext();

    private final Thread readerThread;
    private volatile boolean closed;

    /**
     * 建立连接（不握手）。
     *
     * @param node                目标节点
     * @param serializer          序列化器（解码 ERROR 帧用）
     * @param connectTimeoutMillis 连接超时
     */
    public ErpcChannel(ErpcNode node, ErpcCodec serializer, int connectTimeoutMillis) {
        this(node, serializer, connectTimeoutMillis, null);
    }

    /**
     * 建立连接（可选：建连后做<b>连接级鉴权握手</b>）。
     *
     * @param node                目标节点
     * @param serializer          序列化器（解码 ERROR 帧用）
     * @param connectTimeoutMillis 连接超时
     * @param credential          连接级凭据；{@code null} ⇒ 不握手
     */
    public ErpcChannel(ErpcNode node, ErpcCodec serializer, int connectTimeoutMillis,
                       String credential) {
        this.node = node;
        this.serializer = serializer;
        this.connectTimeoutMillis = connectTimeoutMillis;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(node.getHost(), node.getPort()), connectTimeoutMillis);
            s.setTcpNoDelay(true);
            // 读线程不设超时 —— 空闲连接是正常的；close() 时关 socket 让读线程醒过来
            s.setSoTimeout(0);
        } catch (IOException e) {
            closeQuietly(s);
            throw new ErpcException("eRPC 连接建立失败: " + node.id(), e);
        }
        this.socket = s;
        try {
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
        } catch (IOException e) {
            closeQuietly(s);
            throw new ErpcException("eRPC 连接流获取失败: " + node.id(), e);
        }
        this.readerThread = new Thread(this::readLoop, "edap-erpc-client-reader-" + node.id());
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        // ★ 握手必须在读线程起来<b>之后</b>：应答要靠读线程收
        if (credential != null && !credential.isEmpty()) {
            try {
                authenticate(credential);
            } catch (RuntimeException e) {
                // ★ 握手失败 ⇒ 关连接再抛 —— 连接池据此认为「这个槽位建连失败」，
                //   不会把一条没鉴权成功的连接留在池里。
                close();
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // 调用入口
    // ------------------------------------------------------------------

    /**
     * 发一次一元调用并等响应。
     *
     * @param methodId     方法号
     * @param serviceId    服务号
     * @param payload      业务 DTO 的编码字节
     * @param traceId      链路号
     * @param timeoutMillis 超时
     * @return 响应载荷（业务 DTO 的编码字节）
     */
    public byte[] call(int methodId, int serviceId, byte[] payload, long traceId, long timeoutMillis) {
        return call0(methodId, serviceId, payload, null, traceId, timeoutMillis, null);
    }

    /**
     * 发一次一元调用并等响应 —— <b>传对象</b>（延迟编码）。
     *
     * <p>★ <b>为什么要有这一档</b>：传字节时载荷已经被拷过一次（编码器 → byte[]），
     * 出站再拷一次 ⇒ 载荷在网络上被拷两次。传对象时编码器直接把载荷写进
     * 它自己的缓冲，出站只拷一次 ⇒ 与 gRPC 的「帧头内联」同一形态。</p>
     *
     * @param methodId     方法号
     * @param serviceId    服务号
     * @param message      业务 DTO（还没编码）
     * @param traceId      链路号
     * @param timeoutMillis 超时
     * @return 响应载荷（业务 DTO 的编码字节）
     */
    public byte[] call(int methodId, int serviceId, Object message, long traceId, long timeoutMillis) {
        return call0(methodId, serviceId, null, message, traceId, timeoutMillis, null);
    }

    /**
     * 发一次一元调用并等响应 —— <b>带 RPC 上下文</b>（传对象）。
     *
     * <p>上下文里的 deadline 会被编码进帧头 {@code flags} 的 bit1~bit7
     * ⇒ 服务端能算出自己这边的截止时刻。</p>
     *
     * @param methodId     方法号
     * @param serviceId    服务号
     * @param message      业务 DTO（还没编码）
     * @param traceId      链路号
     * @param timeoutMillis 超时
     * @param context      本次调用的上下文（可为 null）
     * @return 响应载荷（业务 DTO 的编码字节）
     */
    public byte[] call(int methodId, int serviceId, Object message, long traceId,
                       long timeoutMillis, RpcContext context) {
        return call0(methodId, serviceId, null, message, traceId, timeoutMillis, context);
    }

    /**
     * 发一次一元调用并等响应 —— <b>带 RPC 上下文</b>（传字节）。
     *
     * @param methodId     方法号
     * @param serviceId    服务号
     * @param payload      业务 DTO 的编码字节
     * @param traceId      链路号
     * @param timeoutMillis 超时
     * @param context      本次调用的上下文（可为 null）
     * @return 响应载荷（业务 DTO 的编码字节）
     */
    public byte[] call(int methodId, int serviceId, byte[] payload, long traceId,
                       long timeoutMillis, RpcContext context) {
        return call0(methodId, serviceId, payload, null, traceId, timeoutMillis, context);
    }

    private byte[] call0(int methodId, int serviceId, byte[] payload, Object message,
                         long traceId, long timeoutMillis, RpcContext context) {
        if (closed) {
            throw new ErpcException("eRPC 连接已关闭: " + node.id());
        }
        RpcContext ctx = context != null ? context : RpcContext.builder()
            .protocol(RpcProtocol.ERPC)
            .method("erpc:" + methodId)
            .traceId(traceId)
            .timeoutMillis(timeoutMillis)
            .build();
        // deadline 已经过了 ⇒ 帧不发（服务端收到也是白跑）
        if (ctx.isExpired()) {
            ErpcException e = new ErpcException("eRPC 调用已超时: methodId=" + methodId);
            ctx.cancellation().cancel(e);
            throw e;
        }
        int requestId = nextRequestId.getAndIncrement() & 0x7FFFFFFF;
        if (requestId == 0) {
            requestId = nextRequestId.getAndIncrement() & 0x7FFFFFFF;
        }
        // 请求号是传输层分配的 ⇒ 事后补进上下文（业务拿到的就是同一个对象）
        ctx.assignRequestId(requestId);
        // ★ 超时码写进帧头 flags 的 bit1~bit7 —— 服务端据此算自己的截止时刻
        int flags = timeoutFlags(ctx);
        // ★ 本次调用要不要带「可上线的元数据」—— 没有 ⇒ 走老路径，
        //   线上帧与不带元数据时字节级一致（默认路径零开销）。
        boolean withMetadata = ctx.hasMetadata();
        PendingCall call = new PendingCall(requestId, ctx);
        // ★ 先注册后写：响应可能比写操作返回得还快，先写后注册会把它丢掉 ⇒ 调用方永久等待
        calls.put(requestId, call);
        try {
            if (withMetadata) {
                // 上下文段在锁外编码（不占写锁）
                byte[] metaSegment = ErpcMetadataCodec.encode(ctx.metadata());
                if (message != null) {
                    writeFrameMessageWithContext(serializer.id(), methodId, serviceId,
                        requestId, traceId, flags, metaSegment, message);
                } else {
                    writeFrameWithContext(serializer.id(), methodId, serviceId,
                        requestId, traceId, flags, metaSegment,
                        payload, 0, payload == null ? 0 : payload.length);
                }
            } else if (message != null) {
                writeFrameMessage(ErpcFrameType.REQUEST.getValue(), serializer.id(),
                    methodId, serviceId, requestId, traceId, flags, message);
            } else {
                writeFrame(ErpcFrameType.REQUEST.getValue(), serializer.id(),
                    methodId, serviceId, requestId, traceId, flags, payload,
                    0, payload == null ? 0 : payload.length);
            }
        } catch (RuntimeException e) {
            calls.remove(requestId, call);
            throw e;
        }
        // 等响应：上限取「本次超时」与「上下文剩余时间」的较小者
        long waitMillis = Math.min(timeoutMillis, ctx.remainingMillis());
        try {
            return call.future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            calls.remove(requestId, call);
            ErpcException timeout = new ErpcException(
                "eRPC 调用超时: methodId=" + methodId + ", timeout=" + timeoutMillis + "ms");
            ctx.cancellation().cancel(timeout);
            call.future.completeExceptionally(timeout);
            throw timeout;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ErpcException) {
                throw (ErpcException) cause;
            }
            throw new ErpcException("eRPC 调用失败: methodId=" + methodId, cause);
        } catch (InterruptedException e) {
            calls.remove(requestId, call);
            ctx.cancellation().cancel(e);
            Thread.currentThread().interrupt();
            throw new ErpcException("eRPC 调用被中断: methodId=" + methodId, e);
        }
    }

    /**
     * 把上下文的剩余时间编码成帧头 {@code flags} 的 7-bit 超时代码。
     *
     * <p>★ <b>没有 deadline ⇒ 不写</b>（码 0 = 无超时），这样老服务端
     * 读到的还是「无超时」，行为与改造前一致。</p>
     *
     * <p>★ <b>写的是 flags 字节，不是码值</b>：码值要左移一位才落在 bit1~bit7 上
     * —— 由 {@link RpcTimeout#encodeErpcFlags(long)} 一次做完，别在这里手拼。</p>
     */
    private static int timeoutFlags(RpcContext ctx) {
        long remain = ctx.remainingNanos();
        if (remain == Long.MAX_VALUE) {
            return 0;
        }
        if (remain <= 0) {
            return RpcTimeout.encodeErpcFlags(1L);
        }
        return RpcTimeout.encodeErpcFlags(remain);
    }

    /**
     * 本地取消一次在途调用。
     *
     * <p>★ <b>线上没有 CANCEL 帧</b>（eRPC v1 的帧格式不变）⇒ 服务端不会立刻
     * 停下手上的活，它跑完（或撞到自己的 deadline）才回帧。
     * 本地的效果是：调用方立刻拿到失败、上下文的取消信号被 trip
     * （业务代码可以据此提前收手）。</p>
     *
     * @param requestId 请求号（{@link RpcContext#requestId()}）
     * @param cause     取消原因
     * @return true 表示这次调用确实还在途、已被取消
     */
    public boolean cancel(int requestId, Throwable cause) {
        PendingCall call = calls.remove(requestId);
        if (call == null) {
            return false;
        }
        call.context.cancellation().cancel(cause);
        call.future.completeExceptionally(cause);
        return true;
    }

    /**
     * 发一个心跳（PING）并等 PONG。
     */
    public void ping(long timeoutMillis) {
        int requestId = nextRequestId.getAndIncrement() & 0x7FFFFFFF;
        RpcContext ctx = RpcContext.builder()
            .protocol(RpcProtocol.ERPC)
            .method("erpc:ping")
            .requestId(requestId)
            .build();
        PendingCall call = new PendingCall(requestId, ctx);
        calls.put(requestId, call);
        try {
            writeFrame(ErpcFrameType.PING.getValue(), serializer.id(), 0, 0, requestId, 0L,
                0, EMPTY, 0, 0);
        } catch (RuntimeException e) {
            calls.remove(requestId, call);
            throw e;
        }
        try {
            call.future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            calls.remove(requestId, call);
            throw new ErpcException("eRPC 心跳失败: " + node.id(), e);
        }
    }

    // ------------------------------------------------------------------
    // 写侧
    // ------------------------------------------------------------------

    /**
     * 排一帧进待写缓冲，并确保有人会把它刷出去。
     *
     * <p>★ <b>锁内只追加字节，不做 syscall</b>：socket 写由当轮的「刷出者」在锁外做。</p>
     *
     * <p>★ <b>flags 必须每帧显式写</b>：{@code headerBuffer} 是复用的，
     * 不写就会带上上一帧的残留位（请求帧的超时码会漏到 PING/PONG 帧上）。</p>
     */
    private void writeFrame(int type, int codecId, int methodId, int serviceId,
                            int requestId, long traceId, int flags,
                            byte[] payload, int offset, int length) {
        boolean leader;
        synchronized (writeLock) {
            if (closed) {
                throw new ErpcException("eRPC 连接已关闭: " + node.id());
            }
            headerBuffer.setType(type)
                .setFlags(flags)
                .setCodec(codecId)
                .setLength(length)
                .setMethodId(methodId)
                .setServiceId(serviceId)
                .setRequestId(requestId)
                .setTraceId(traceId);
            appendLocked(headerBuffer, payload, offset, length);
            leader = claimDrainLocked();
        }
        if (leader) {
            drain();
        }
    }

    /**
     * 排一帧进待写缓冲 —— <b>传对象</b>（编码在锁外，拷贝在锁内）。
     *
     * <p>★ 编码发生在 {@code writeLock} 之外：编码器把载荷写进它自己的线程本地
     * 缓冲，本方法只负责把那段字节拷进待写缓冲 ⇒ 载荷只被拷一次。</p>
     */
    private void writeFrameMessage(int type, int codecId, int methodId, int serviceId,
                                   int requestId, long traceId, int flags, Object message) {
        ErpcFrameEncoder.encode(serializer, type, codecId, methodId, serviceId,
            requestId, traceId, flags, message, frameSink);
    }

    /**
     * 帧的消费者 —— 把「帧头 + 载荷」写进待写缓冲。
     *
     * <p>★ <b>载荷数组是借来的</b>（来自序列化器的线程本地缓冲），
     * 本方法在返回前就把它拷走，不持有引用。</p>
     */
    private void appendFrame(ErpcFrameHeader header, byte[] payload, int length) {
        boolean leader;
        synchronized (writeLock) {
            if (closed) {
                throw new ErpcException("eRPC 连接已关闭: " + node.id());
            }
            appendLocked(header, payload, 0, length);
            leader = claimDrainLocked();
        }
        if (leader) {
            drain();
        }
    }

    /**
     * 把帧头与载荷追加进待写缓冲（<b>调用方必须持有 {@code writeLock}</b>）。
     */
    private void appendLocked(ErpcFrameHeader header, byte[] payload, int offset, int length) {
        ensurePendingCapacityLocked(ErpcFrameHeader.HEADER_LENGTH + length);
        header.writeTo(pending, pendingLen);
        pendingLen += ErpcFrameHeader.HEADER_LENGTH;
        if (length > 0) {
            System.arraycopy(payload, offset, pending, pendingLen, length);
            pendingLen += length;
        }
    }

    /**
     * 排一帧进待写缓冲 —— 内容区带一段 <b>RPC 上下文</b>（{@code [上下文段][业务 DTO]}）。
     *
     * <p>★ <b>不走 {@link ErpcFrameEncoder}</b>：它只能写「帧头 + 一段载荷」，
     * 而这里要写两段 ⇒ 单独一条写路径（{@code FrameSink} 接口被 benchmark 模块实现，
     * 改签名会波及那边）。</p>
     */
    private void writeFrameWithContext(int codecId, int methodId, int serviceId,
                                       int requestId, long traceId, int flags,
                                       byte[] metaSegment,
                                       byte[] payload, int offset, int length) {
        boolean leader;
        synchronized (writeLock) {
            if (closed) {
                throw new ErpcException("eRPC 连接已关闭: " + node.id());
            }
            headerBuffer.setType(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true))
                .setFlags(flags)
                .setCodec(codecId)
                .setLength(metaSegment.length + length)
                .setMethodId(methodId)
                .setServiceId(serviceId)
                .setRequestId(requestId)
                .setTraceId(traceId);
            appendTwoSegmentsLocked(headerBuffer, metaSegment, payload, offset, length);
            leader = claimDrainLocked();
        }
        if (leader) {
            drain();
        }
    }

    /**
     * 排一帧进待写缓冲 —— 内容区带一段 <b>RPC 上下文</b>，载荷<b>传对象</b>。
     *
     * <p>★ 编码发生在 {@code writeLock} 之外：编码器把载荷写进它自己的线程本地缓冲，
     * 这里只负责把那段字节拷进待写缓冲 ⇒ 载荷只被拷一次。</p>
     */
    private void writeFrameMessageWithContext(int codecId, int methodId, int serviceId,
                                              int requestId, long traceId, int flags,
                                              byte[] metaSegment, Object message) {
        ErpcWriter dto = serializer.encode(message);
        int dtoLen = dto == null ? 0 : dto.size();
        byte[] dtoBuf = dto == null ? EMPTY : dto.buffer();
        boolean leader;
        synchronized (writeLock) {
            if (closed) {
                throw new ErpcException("eRPC 连接已关闭: " + node.id());
            }
            headerBuffer.setType(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true))
                .setFlags(flags)
                .setCodec(codecId)
                .setLength(metaSegment.length + dtoLen)
                .setMethodId(methodId)
                .setServiceId(serviceId)
                .setRequestId(requestId)
                .setTraceId(traceId);
            appendTwoSegmentsLocked(headerBuffer, metaSegment, dtoBuf, 0, dtoLen);
            leader = claimDrainLocked();
        }
        if (leader) {
            drain();
        }
    }

    /**
     * 把帧头与<b>两段</b>载荷追加进待写缓冲（<b>调用方必须持有 {@code writeLock}</b>）。
     *
     * <p>段一 = RPC 上下文段，段二 = 业务 DTO。</p>
     */
    private void appendTwoSegmentsLocked(ErpcFrameHeader header, byte[] seg1,
                                         byte[] seg2, int off2, int len2) {
        ensurePendingCapacityLocked(ErpcFrameHeader.HEADER_LENGTH + seg1.length + len2);
        header.writeTo(pending, pendingLen);
        pendingLen += ErpcFrameHeader.HEADER_LENGTH;
        System.arraycopy(seg1, 0, pending, pendingLen, seg1.length);
        pendingLen += seg1.length;
        if (len2 > 0) {
            System.arraycopy(seg2, off2, pending, pendingLen, len2);
            pendingLen += len2;
        }
    }

    // ------------------------------------------------------------------
    // 连接级鉴权握手
    // ------------------------------------------------------------------

    /**
     * 连接级鉴权握手 —— 发一个 AUTH 帧，等服务端的应答。
     *
     * <p>★ <b>连接级</b>：握手成功 ⇒ 服务端把「已鉴权」记在那条连接上 ⇒
     * <b>同一连接的后续请求免校验</b>（连接不断就不必重复校验）。</p>
     *
     * <p>★ <b>应答复用 RESPONSE / ERROR 帧</b>：不为握手新增响应帧类型 ——
     * 客户端按 {@code requestId} 配对应答即可。</p>
     *
     * <p>★ <b>失败 ⇒ 抛异常</b>：调用方（连接池）据此认为建连失败，不会把
     * 一条没鉴权成功的连接留在池里。</p>
     *
     * @param credential 凭据（UTF-8 字符串）
     */
    private void authenticate(String credential) {
        byte[] payload = credential.getBytes(StandardCharsets.UTF_8);
        int requestId = nextRequestId.getAndIncrement() & 0x7FFFFFFF;
        if (requestId == 0) {
            requestId = nextRequestId.getAndIncrement() & 0x7FFFFFFF;
        }
        RpcContext ctx = RpcContext.builder()
            .protocol(RpcProtocol.ERPC)
            .method("erpc:auth")
            .requestId(requestId)
            .build();
        PendingCall call = new PendingCall(requestId, ctx);
        calls.put(requestId, call);
        try {
            writeFrame(ErpcFrameType.AUTH.getValue(), serializer.id(), 0, 0, requestId, 0L,
                0, payload, 0, payload.length);
        } catch (RuntimeException e) {
            calls.remove(requestId, call);
            throw e;
        }
        try {
            call.future.get(connectTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            calls.remove(requestId, call);
            throw new ErpcException("eRPC 鉴权握手超时: " + node.id()
                + ", timeout=" + connectTimeoutMillis + "ms", e);
        } catch (ExecutionException e) {
            calls.remove(requestId, call);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ErpcException("eRPC 鉴权握手失败: " + node.id(), cause);
        } catch (InterruptedException e) {
            calls.remove(requestId, call);
            Thread.currentThread().interrupt();
            throw new ErpcException("eRPC 鉴权握手被中断: " + node.id(), e);
        }
    }

    /**
     * 认领「刷出者」身份（调用方必须持有 {@code writeLock}）。
     */
    private boolean claimDrainLocked() {
        if (draining) {
            return false;
        }
        draining = true;
        return true;
    }

    /**
     * 把待写缓冲刷到 socket（<b>调用方不得持有 {@code writeLock}</b>）。
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
                    // 双缓冲轮换：把「有数据的那块」换到 drainBuf 直接写出去 ——
                    // 不用再把 pending 整块拷进 drainBuf（每条 64KB 请求少一次整块拷贝）
                    byte[] spare = drainBuf;
                    drainBuf = pending;
                    pending = spare;
                    pendingLen = 0;
                    buf = drainBuf;
                }
                // syscall 在锁外：别的线程可以继续往缓冲里排帧
                out.write(buf, 0, len);
                out.flush();
            }
        } catch (IOException e) {
            synchronized (writeLock) {
                draining = false;
            }
            close();
            failAllCalls(new ErpcException("eRPC 帧写出失败: " + node.id(), e));
            throw new ErpcException("eRPC 帧写出失败: " + node.id(), e);
        }
    }

    private void ensurePendingCapacityLocked(int need) {
        if (pendingLen + need <= pending.length) {
            return;
        }
        int cap = Math.max(pending.length * 2, pendingLen + need);
        byte[] bigger = new byte[cap];
        System.arraycopy(pending, 0, bigger, 0, pendingLen);
        pending = bigger;
    }

    // ------------------------------------------------------------------
    // 读侧
    // ------------------------------------------------------------------

    private void readLoop() {
        byte[] buf = new byte[READ_CAPACITY];
        Throwable error = null;
        try {
            while (!closed) {
                int n = in.read(buf);
                if (n < 0) {
                    break;                                   // 对端正常关闭
                }
                frameCodec.append(buf, 0, n);
                while (frameCodec.hasFrame()) {
                    ErpcFrameHeader h = frameCodec.header();
                    byte[] payload = frameCodec.payload();
                    frameCodec.consume();
                    dispatch(h, payload);
                }
            }
        } catch (IOException e) {
            error = e;
        } catch (RuntimeException e) {
            error = e;
        }
        // ★ 读线程退出 ⇒ 这条连接已经不可用了 ⇒ 必须标记 closed。
        //   不标记的话，连接池会把这条死连接继续派给调用方（isClosed() 还是 false），
        //   调用方会一直等到超时才拿到失败 —— 而池本该在下次取连接时重建。
        closed = true;
        if (error != null) {
            failAllCalls(new ErpcException("eRPC 连接断开: " + node.id(), error));
        } else {
            failAllCalls(new ErpcException("eRPC 连接已关闭: " + node.id()));
        }
    }

    private void dispatch(ErpcFrameHeader h, byte[] payload) {
        int type = h.getType();
        if (type == ErpcFrameType.RESPONSE.getValue()) {
            PendingCall call = calls.remove(h.getRequestId());
            if (call != null) {
                call.future.complete(payload == null ? EMPTY : payload);
            }
            return;
        }
        if (type == ErpcFrameType.ERROR.getValue()) {
            PendingCall call = calls.remove(h.getRequestId());
            if (call != null) {
                call.future.completeExceptionally(decodeError(h, payload));
            }
            return;
        }
        if (type == ErpcFrameType.PING.getValue()) {
            // 服务端发来的心跳 ⇒ 回 PONG（帧头字段原样抄回）
            writeFrame(ErpcFrameType.PONG.getValue(), h.getCodec(),
                h.getMethodId(), h.getServiceId(), h.getRequestId(), h.getTraceId(),
                0, EMPTY, 0, 0);
            return;
        }
        // PONG：心跳响应，忽略
    }

    /**
     * 解 ERROR 帧的载荷（编码后的 {@link RpcException}）。
     */
    private ErpcException decodeError(ErpcFrameHeader h, byte[] payload) {
        int codecId = h.getCodec();
        if (codecId != serializer.id()) {
            return new ErpcException("服务端返回 ERROR 帧，但 codec=" + codecId
                + " 本端不认识（本端是 " + serializer.name() + "）");
        }
        if (payload == null || payload.length == 0) {
            return new ErpcException("服务端返回 ERROR 帧但没有错误体");
        }
        try {
            RpcException err = serializer.decode(payload, 0, payload.length, RpcException.class);
            if (err == null) {
                return new ErpcException("服务端返回 ERROR 帧但错误体为空");
            }
            return new ErpcException("eRPC 调用失败: " + err.getStatusEnum() + " "
                + err.getClazzName() + ": " + err.getMessage());
        } catch (RuntimeException e) {
            return new ErpcException("eRPC 调用失败（错误帧解码失败）", e);
        }
    }

    private void failAllCalls(Throwable cause) {
        for (PendingCall call : calls.values()) {
            // 连接断了 ⇒ 在途调用全部取消（业务代码能观察到取消信号）
            call.context.cancellation().cancel(cause);
            call.future.completeExceptionally(cause);
        }
        calls.clear();
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
        failAllCalls(new ErpcException("eRPC 连接已关闭: " + node.id()));
    }

    public boolean isClosed() {
        return closed;
    }

    public ErpcNode node() {
        return node;
    }

    /**
     * 本连接的上下文（连接级状态）。
     *
     * <p>★ <b>每连接一个实例</b>；连接重建（断链后连接池重建）⇒ <b>新实例</b>，
     * 旧连接上的标记不会沿用。</p>
     *
     * @return 连接级上下文
     */
    public RpcChannelContext channelContext() {
        return channelContext;
    }

    /**
     * 在途调用数（诊断 / 测试用）。
     */
    public int inFlightCount() {
        return calls.size();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignore) {
            // 忽略
        }
    }
}
