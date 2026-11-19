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

import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcFrameDecoder;
import io.edap.grpc.GrpcFrameEncoder;
import io.edap.grpc.GrpcMetadata;
import io.edap.grpc.GrpcStatus;
import io.edap.rpc.RpcContext;
import io.edap.rpc.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一次 gRPC 调用（客户端侧）。
 *
 * <p><b>生命周期</b>：</p>
 * <pre>{@code
 *   GrpcClientCall call = channel.newCall(path, reqType, respType, respObserver);
 *   call.sendMessage(req);        // 请求侧发消息
 *   call.halfClose();             // 请求侧结束（END_STREAM）
 *   call.awaitCompletion(5000);   // 等 trailers 里的 grpc-status
 * }</pre>
 *
 * <p><b>谁在哪个线程上跑</b>：</p>
 * <ul>
 *   <li>请求侧（{@link #sendMessage} / {@link #halfClose}）由调用方的线程驱动 —— 阻塞 socket
 *       的写入就在调用方线程上；</li>
 *   <li>响应侧（消息回调 / trailers）由 {@link GrpcChannel} 的读线程驱动 —— 同一连接只有一个
 *       读线程，按 stream id 分发到各自的 call。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：请求侧按 {@code sendLock} 串行（同一调用的消息必须按发送顺序上线）；
 * 响应侧只有读线程一个写入者，外部读用 volatile 字段。</p>
 */
public final class GrpcClientCall {

    private final GrpcChannel channel;
    private final int streamId;
    private final String path;
    private final Class<?> reqType;
    private final Class<?> respType;

    /** 响应观察者（可为 null —— 只收集不回调）。 */
    private final StreamObserver<Object> responseObserver;

    /**
     * 本次调用的 RPC 上下文 —— deadline / 取消信号都挂在它身上。
     *
     * <p>★ <b>一次调用一个对象</b>：客户端等响应时看的是它，业务回调里拿到的
     * {@code RpcContext.current()} 也是它。</p>
     */
    private final RpcContext context;

    private final GrpcFrameDecoder frameDecoder = new GrpcFrameDecoder();

    /** 调用结束信号（trailers 到达 / 出错）。 */
    private final CountDownLatch completion = new CountDownLatch(1);

    /** 请求侧的串行锁 —— 同一调用的消息必须按发送顺序上线。 */
    private final Object sendLock = new Object();

    /** 收到的响应消息（按到达顺序）。 */
    private final List<Object> responses = new ArrayList<>();

    private volatile GrpcMetadata responseHeaders;
    private volatile GrpcMetadata responseTrailers;
    private volatile GrpcStatus status;
    private volatile String statusMessage;
    private volatile Throwable failure;
    private volatile boolean requestEnded;

    /** 该流的发送窗口（受 GrpcChannel 的 flowLock 保护） */
    private int sendWindow;

    /** 该流已收到但还没归还给对端的字节数（受 GrpcChannel 的 flowLock 保护） */
    private int pendingRecvCredit;

    GrpcClientCall(GrpcChannel channel, int streamId, String path,
                   Class<?> reqType, Class<?> respType, StreamObserver<Object> responseObserver,
                   int initialSendWindow, RpcContext context) {
        this.channel = channel;
        this.streamId = streamId;
        this.path = path;
        this.reqType = reqType;
        this.respType = respType;
        this.responseObserver = responseObserver;
        this.context = context;
        // ★ 流级发送窗口初值 = 对端通告的 SETTINGS_INITIAL_WINDOW_SIZE（RFC 9113 §6.9.2），
        //   不是固定的 65535。grpc-java 默认通告 1MB —— 硬编码 65535 会让客户端
        //   发满 64KB 就永远等不到流级额度（对端的窗口还剩 900KB，根本不会发 WINDOW_UPDATE）。
        this.sendWindow = initialSendWindow;
    }

    // ------------------------------------------------------------------
    // 请求侧
    // ------------------------------------------------------------------

    /**
     * 发一条请求消息。
     *
     * @param message 消息对象（用 {@code @ProtoGrpc.req()} 指定的类型编码）
     */
    public void sendMessage(Object message) {
        if (message == null) {
            throw new GrpcException("gRPC 请求消息不能为 null: " + path);
        }
        // ★ 请求侧由调用方线程驱动 —— 绑上本次调用的上下文，
        //   业务在发消息的线程里也能看到 deadline / 取消信号。
        try (RpcContext.Scope scope = RpcContext.attach(context)) {
            synchronized (sendLock) {
                if (requestEnded) {
                    throw new GrpcException("gRPC 请求侧已经结束，不能再发消息: " + path);
                }
                try {
                    // ★ 帧头内联：省掉「payload byte[] + 帧数组」两次整块拷贝。
                    //   ★ 编码放在锁内 —— 原来编码在锁外、写在线内，
                    //   两个线程并发发消息时「编码顺序」与「上线顺序」可能不一致。
                    GrpcFrameEncoder.encode(reqType == null ? message.getClass() : reqType, message,
                            (buf, offset, length) -> channel.writeData(this, buf, offset, length, false));
                } catch (IOException e) {
                    throw new GrpcException("gRPC 请求写出失败: " + path, e);
                }
            }
        }
    }

    /**
     * 请求侧半关闭（END_STREAM）。
     *
     * <p>一元与服务端流式：发完唯一一条消息后调用；客户端流式与双向流式：发完最后一条后调用。</p>
     */
    public void halfClose() {
        try (RpcContext.Scope scope = RpcContext.attach(context)) {
            synchronized (sendLock) {
                if (requestEnded) {
                    return;
                }
                requestEnded = true;
                channel.writeData(this, null, 0, 0, true);
            }
        }
    }

    /**
     * 请求侧的观察者 —— {@code onNext} 发消息，{@code onCompleted} 半关闭，
     * {@code onError} 取消调用。
     *
     * @return 请求观察者
     */
    public StreamObserver<Object> requestObserver() {
        return new StreamObserver<Object>() {
            @Override
            public void onNext(Object value) {
                sendMessage(value);
            }

            @Override
            public void onError(Throwable t) {
                cancel(t == null ? new GrpcException("gRPC 调用被取消: " + path) : t);
            }

            @Override
            public void onCompleted() {
                halfClose();
            }
        };
    }

    /**
     * 取消调用（发 RST_STREAM）。
     *
     * <p>★ <b>先 trip 取消信号</b>：业务在别处观察 {@code ctx.cancellation()}
     * 时要能立刻看到「被取消了」，而不是等 RST_STREAM 走完网络。</p>
     */
    public void cancel(Throwable cause) {
        failure = cause;
        context.cancellation().cancel(cause == null
                ? new GrpcException("gRPC 调用被取消: " + path) : cause);
        channel.resetStream(streamId);
        complete(GrpcStatus.CANCELLED, cause == null ? null : cause.getMessage());
    }

    // ------------------------------------------------------------------
    // 响应侧
    // ------------------------------------------------------------------

    /**
     * 收到响应头（第一个 HEADERS 帧）。
     *
     * <p><b>Trailers-Only</b>：gRPC 允许服务端只发一个 HEADERS 帧，里面同时带
     * {@code :status 200} 与 {@code grpc-status}（没有独立的 trailers 帧）。</p>
     */
    void onHeaders(List<io.edap.http.http2.hpack.HpackHeader> headers) {
        GrpcMetadata metadata = new GrpcMetadata();
        for (io.edap.http.http2.hpack.HpackHeader h : headers) {
            metadata.add(h.getName(), h.getValue());
        }
        if (responseHeaders == null) {
            responseHeaders = metadata;
            // Trailers-Only：响应头里就带着 grpc-status
            String grpcStatus = metadata.get("grpc-status");
            if (grpcStatus != null) {
                responseTrailers = metadata;
                finishFromStatus(grpcStatus, metadata.get("grpc-message"));
            }
        } else {
            responseTrailers = metadata;
            finishFromStatus(metadata.get("grpc-status"), metadata.get("grpc-message"));
        }
    }

    /**
     * 收到 trailers（第二个 HEADERS 帧）。
     */
    void onTrailers(List<io.edap.http.http2.hpack.HpackHeader> headers) {
        GrpcMetadata metadata = new GrpcMetadata();
        for (io.edap.http.http2.hpack.HpackHeader h : headers) {
            metadata.add(h.getName(), h.getValue());
        }
        responseTrailers = metadata;
        finishFromStatus(metadata.get("grpc-status"), metadata.get("grpc-message"));
    }

    /**
     * 收到响应数据（增量分帧 → 解码 → 回调）。
     */
    void onData(byte[] data, int offset, int length) {
        frameDecoder.feed(data, offset, length, payload -> {
            Object message = GrpcCodec.decode(respType, payload, 0, payload.length);
            synchronized (responses) {
                responses.add(message);
            }
            if (responseObserver != null) {
                // ★ 响应侧回调由读线程驱动 —— 也要绑上本次调用的上下文，
                //   否则业务在回调里 {@code RpcContext.current()} 拿到的是 ROOT。
                try (RpcContext.Scope scope = RpcContext.attach(context)) {
                    responseObserver.onNext(message);
                }
            }
        });
    }

    /**
     * 流结束但没有 trailers —— gRPC 规范要求 trailers 里必须有 grpc-status，
     * 缺了就当作协议错误。
     */
    void onEndWithoutTrailers() {
        if (status == null) {
            complete(GrpcStatus.INTERNAL, "服务端没有返回 grpc-status（trailers 缺失）");
        }
    }

    private void finishFromStatus(String grpcStatus, String grpcMessage) {
        if (grpcStatus == null) {
            complete(GrpcStatus.INTERNAL, "trailers 里没有 grpc-status");
            return;
        }
        int code;
        try {
            code = Integer.parseInt(grpcStatus.trim());
        } catch (NumberFormatException e) {
            complete(GrpcStatus.INTERNAL, "grpc-status 不是合法的数字: " + grpcStatus);
            return;
        }
        GrpcStatus s = GrpcStatus.fromCode(code);
        complete(s, decodePercent(grpcMessage));
    }

    /**
     * 结束调用：记状态、通知观察者、放信号量。
     *
     * <p>★ <b>这是唯一的终态出口</b>：正常结束 / 取消 / 协议错误 / 连接关闭
     * 全都汇到这里 ⇒ 「调用结束」这一段的拦截器只跑一次。</p>
     */
    private void complete(GrpcStatus s, String message) {
        if (status != null) {
            return;                                   // 已经结束过
        }
        status = s;
        statusMessage = message;
        // 调用结束 —— 从在途表里摘掉（否则长连接的 calls map 会无限膨胀，
        // 而且对端每次改 INITIAL_WINDOW_SIZE 都会给一堆早已结束的流传播差值）
        channel.onCallCompleted(this);
        // ★ 调用正常结束 ⇒ 标记完成，之后再 cancel 不再生效（业务在收尾阶段
        //   不该误判「被取消」）。
        context.cancellation().complete();
        Throwable error = s == GrpcStatus.OK ? null
                : new GrpcException("gRPC 调用失败: " + path
                        + " status=" + s + (message == null ? "" : " message=" + message));
        if (responseObserver != null) {
            try (RpcContext.Scope scope = RpcContext.attach(context)) {
                if (s == GrpcStatus.OK) {
                    responseObserver.onCompleted();
                } else {
                    responseObserver.onError(error);
                }
            } catch (Throwable ignore) {
                // 业务自己的回调抛了 —— 不能影响连接的存活性
            }
        }
        // ★ 「调用结束」这一段的拦截器挂在**这里** —— complete() 是唯一的终态出口
        channel.runAfterInterceptors(this, error);
        completion.countDown();
    }

    // ------------------------------------------------------------------
    // 对外查询
    // ------------------------------------------------------------------

    /**
     * 等调用结束。
     *
     * <p>★ <b>实际上限 = min(调用方给的, 本次调用自己的 deadline)</b>：
     * 调用方等 10 秒、但这次调用只剩 1 秒 ⇒ 1 秒后就该放弃 ⇒ 顺带 trip 取消信号
     * （业务侧能观察到「被取消」），而不是傻等满 10 秒。</p>
     *
     * @param timeoutMillis 超时时间
     * @throws GrpcException 调用失败（非 OK 状态）或超时
     */
    public void awaitCompletion(long timeoutMillis) {
        // 没有 deadline 时 remainingMillis() 是 Long.MAX_VALUE ⇒ min 就是调用方给的值
        long limit = Math.min(timeoutMillis, context.remainingMillis());
        try {
            if (!completion.await(limit, TimeUnit.MILLISECONDS)) {
                cancel(new GrpcException("gRPC 调用超时: " + path));
                throw new GrpcException("gRPC 调用超时(" + limit + "ms): " + path);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GrpcException("gRPC 调用被中断: " + path, e);
        }
        GrpcStatus s = status;
        if (s != null && s != GrpcStatus.OK) {
            throw new GrpcException("gRPC 调用失败: " + path + " status=" + s
                    + (statusMessage == null ? "" : " message=" + statusMessage));
        }
    }

    /**
     * @return 最终状态；调用还没结束时为 null
     */
    public GrpcStatus status() {
        return status;
    }

    /**
     * @return 状态描述（trailers 里的 grpc-message）
     */
    public String statusMessage() {
        return statusMessage;
    }

    /**
     * @return 响应头
     */
    public GrpcMetadata responseHeaders() {
        return responseHeaders;
    }

    /**
     * @return trailers
     */
    public GrpcMetadata responseTrailers() {
        return responseTrailers;
    }

    /**
     * @return 收到的响应消息（按到达顺序）
     */
    public List<Object> responses() {
        synchronized (responses) {
            return new ArrayList<>(responses);
        }
    }

    public String path() {
        return path;
    }

    public int streamId() {
        return streamId;
    }

    /**
     * 本次调用的 RPC 上下文 —— deadline / 取消信号 / 属性。
     *
     * @return 本次调用的上下文
     */
    public RpcContext context() {
        return context;
    }

    /**
     * @return 出错时的异常（正常结束为 null）
     */
    public Throwable failure() {
        return failure;
    }

    // ------------------------------------------------------------------
    // 流控窗口（由 GrpcChannel 的 flowLock 保护）
    // ------------------------------------------------------------------

    /**
     * @return 该流还能发送多少字节
     */
    int sendWindow() {
        return sendWindow;
    }

    /**
     * 扣减该流的发送窗口（发送前调用）。
     */
    void consumeSendWindow(int n) {
        sendWindow -= n;
    }

    /**
     * 放开该流的发送窗口（收到 WINDOW_UPDATE 或对端改了 INITIAL_WINDOW_SIZE 时调用）。
     */
    void increaseSendWindow(int n) {
        sendWindow += n;
    }

    /**
     * 收到该流的数据 → 累加待归还给对端的<b>流级</b> credit。
     *
     * <p>★ 必须<b>按流</b>记账：流级 {@code WINDOW_UPDATE} 只对它指定的那条流有效。
     * 如果把连接级的累计字节数记到「触发阈值的那条流」上，其余的流就永远收不满响应 ——
     * 多流并发时表现为随机地有调用卡住不动（等对端的额度，等不到）。</p>
     *
     * <p>攒够「流级接收窗口的一半」才归还，避免每收一个包就发一次 WINDOW_UPDATE。</p>
     *
     * @param length 收到的字节数
     * @return 需要发给对端的流级 WINDOW_UPDATE 增量（0 = 这次不用发）
     */
    int addRecvCredit(int length) {
        pendingRecvCredit += length;
        int threshold = Math.max(1, channel.streamRecvCreditThreshold());
        if (pendingRecvCredit < threshold) {
            return 0;
        }
        int credit = pendingRecvCredit;
        pendingRecvCredit = 0;
        return credit;
    }

    /**
     * {@code grpc-message} 的百分号解码（gRPC 规范要求服务端百分号编码）。
     */
    static String decodePercent(String message) {
        if (message == null || message.indexOf('%') < 0) {
            return message;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '%' && i + 2 < message.length()) {
                int hi = Character.digit(message.charAt(i + 1), 16);
                int lo = Character.digit(message.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(c);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
