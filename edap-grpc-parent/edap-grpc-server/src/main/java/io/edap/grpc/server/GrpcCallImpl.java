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

package io.edap.grpc.server;

import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcFrameDecoder;
import io.edap.grpc.GrpcFrameEncoder;
import io.edap.grpc.GrpcMetadata;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcServerInterceptor;
import io.edap.grpc.GrpcStatus;
import io.edap.grpc.GrpcStatusException;
import io.edap.http.HeaderValue;
import io.edap.http.HttpResponse;
import io.edap.http.ValueHttpRequest;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.server.http2.Http2Stream;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.rpc.InterceptorChain;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcDeadlineExceededException;
import io.edap.rpc.RpcProtocol;
import io.edap.rpc.RpcTimeout;
import io.edap.rpc.StreamObserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次 gRPC 调用的实现。
 *
 * <p><b>响应侧的形状</b>：{@code HEADERS(:status 200, content-type: application/grpc)}
 * → {@code DATA}(每条消息一个 gRPC 帧) → {@code HEADERS(END_STREAM)}（trailers，
 * 里面有 {@code grpc-status} / {@code grpc-message}）。</p>
 *
 * <p><b>两把锁</b>：</p>
 * <ul>
 *   <li>{@code responseLock} —— 保护响应侧（消息与 trailers 在 wire 上的顺序）；</li>
 *   <li>{@code requestLock} —— 保护请求侧（请求消息投递给业务观察者的顺序）。</li>
 * </ul>
 *
 * <p>分成两把是为了避免死锁：业务在自己的线程里调 {@code sendOne}（要 responseLock），
 * 而 IO 线程正在把请求消息投递给业务观察者（要 requestLock）—— 观察者的回调里
 * 完全可能又去调 {@code sendOne}。一把锁的话就是自己等自己。</p>
 */
public class GrpcCallImpl implements GrpcCall {

    static final Logger LOG = LoggerManager.getLogger(GrpcCallImpl.class);

    /**
     * gRPC 规范里的超时头 —— 值是「还剩多久」（最多 8 位数字 + 1 个单位字符）。
     */
    static final String GRPC_TIMEOUT_HEADER = "grpc-timeout";

    /**
     * 链路号头（edap 约定，非 gRPC 规范）。
     */
    static final String TRACE_ID_HEADER = "x-edap-trace-id";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final GrpcMethod method;
    private final Http2Stream stream;
    private final HttpResponse response;
    private final GrpcMetadata requestMetadata;
    private final GrpcMetadata responseMetadata = new GrpcMetadata();
    private final GrpcFrameDecoder frameDecoder = new GrpcFrameDecoder();

    /**
     * 服务端拦截器 —— <b>活视图</b>（{@code CopyOnWriteArrayList}）。
     *
     * <p>一次调用拿到的是当时的迭代快照 ⇒ 注册/卸载不会影响已经在跑的调用。</p>
     */
    private final List<GrpcServerInterceptor> interceptors;

    /**
     * 本次调用的 RPC 上下文 —— <b>每个调用一个对象</b>。
     *
     * <p>★ <b>为什么不用 {@code RpcContext.current()} 现建</b>：deadline、取消信号
     * 必须是同一个对象，业务在 handler 里拿到的和框架定时器用的是同一个。</p>
     */
    private final RpcContext context;

    private final Object responseLock = new Object();
    private final Object requestLock = new Object();

    /** 响应头是否已经写出 */
    private boolean headersWritten;

    /** 调用是否已经结束（trailers 已发出） */
    private boolean closed;

    /** 业务 handler 是否已经被调用过 */
    private boolean handlerInvoked;

    /**
     * 本次调用是否已被拦截器拒绝（业务 handler 不再执行）。
     *
     * <p>volatile：拒绝发生在 IO 线程的 {@code onHeaders} 里，而
     * {@link #invokeHandler()} 可能被后续的 body 投递触发。</p>
     */
    private volatile boolean rejected;

    /** 请求侧是否已经结束 */
    private boolean requestEnded;

    /**
     * 一元 / 服务端流式：完整的一条请求消息（不含 5 字节帧头）。
     */
    private byte[] requestMessage;

    /**
     * 客户端流式 / 双向流式：业务的请求观察者。
     */
    private StreamObserver<Object> requestObserver;

    /**
     * 业务还没交回请求观察者时，收到的请求消息先排队（保持顺序）。
     */
    private final List<byte[]> pendingMessages = new ArrayList<>();

    /**
     * 响应侧的观察者 —— 服务端流式 / 双向流式用它推消息。
     */
    private final StreamObserver<Object> responseObserver = new StreamObserver<Object>() {
        @Override
        public void onNext(Object value) {
            sendOne(value);
        }

        @Override
        public void onError(Throwable t) {
            close(GrpcStatus.UNKNOWN, messageOf(t));
        }

        @Override
        public void onCompleted() {
            close(GrpcStatus.OK, null);
        }
    };

    public GrpcCallImpl(GrpcMethod method, Http2Stream stream, GrpcMetadata requestMetadata) {
        this(method, stream, requestMetadata, Collections.emptyList());
    }

    public GrpcCallImpl(GrpcMethod method, Http2Stream stream, GrpcMetadata requestMetadata,
                        List<GrpcServerInterceptor> interceptors) {
        this.method = method;
        this.stream = stream;
        this.response = stream.getResponse();
        this.requestMetadata = requestMetadata;
        this.interceptors = interceptors == null ? Collections.emptyList() : interceptors;
        this.context = buildContext(method, stream, requestMetadata);
    }

    /**
     * 按请求头建本次调用的上下文。
     *
     * <p>★ <b>线上只传「相对时长」</b>：{@code grpc-timeout} 头里是「还剩多久」，
     * 这里立刻换算成本进程的<b>绝对 nanoTime 时刻</b> ——
     * {@code nanoTime} 跨进程不可比，跨机传绝对时刻没有意义。</p>
     *
     * <p>没有 {@code grpc-timeout} 头（或头非法）⇒ 没有 deadline，
     * 行为与改造前完全一致。</p>
     */
    private static RpcContext buildContext(GrpcMethod method, Http2Stream stream,
                                           GrpcMetadata metadata) {
        long timeoutNanos = RpcTimeout.parseGrpcTimeoutNanos(
            metadata == null ? null : metadata.get(GRPC_TIMEOUT_HEADER));
        return RpcContext.builder()
            .protocol(RpcProtocol.GRPC)
            .method(method.getPath())
            .requestId(stream.getStreamId())
            .traceId(traceIdOf(metadata))
            .deadlineNanos(RpcTimeout.deadlineFromNowNanos(timeoutNanos))
            .build();
    }

    /**
     * 链路号 —— 取 {@code x-edap-trace-id} 头；没有或不是数字就是 0。
     */
    private static long traceIdOf(GrpcMetadata metadata) {
        if (metadata == null) {
            return 0L;
        }
        String value = metadata.get(TRACE_ID_HEADER);
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 拦截器
    // ------------------------------------------------------------------

    /**
     * 跑「请求进来」这一段的拦截器。
     *
     * <p>★ <b>跑在请求头到齐的那一刻</b>（不是等 body 收完）⇒ 鉴权在读 body 之前，
     * 一元调用也能在收 body 前被拒绝。</p>
     *
     * <p>★ <b>上下文已绑定</b>：钩子里 {@code RpcContext.current()} 拿得到本次调用的上下文。</p>
     *
     * @return true = 放行；false = 被拒绝（错误 trailers 已经写出，业务不会执行）
     */
    boolean runBeforeInterceptors() {
        if (interceptors.isEmpty()) {
            return true;
        }
        try (RpcContext.Scope scope = RpcContext.attach(context)) {
            InterceptorChain.before(interceptors, i -> i.beforeCall(this));
            return true;
        } catch (GrpcStatusException e) {
            reject(e.status(), e.getMessage());
            return false;
        } catch (Throwable t) {
            LOG.warn("grpc interceptor beforeCall error: ", l -> l.threw(t));
            reject(GrpcStatus.INTERNAL, messageOf(t));
            return false;
        }
    }

    /**
     * 跑「调用结束」这一段的拦截器（逆序）。
     *
     * <p>★ <b>单个拦截器抛异常不影响后面的</b> —— 收尾钩子不该影响调用结果。
     * 框架只记一条 warn 日志。</p>
     */
    private void runAfterInterceptors(Throwable error) {
        if (interceptors.isEmpty()) {
            return;
        }
        try (RpcContext.Scope scope = RpcContext.attach(context)) {
            InterceptorChain.after(interceptors, i -> {
                try {
                    i.afterCall(this, error);
                } catch (Throwable t) {
                    LOG.warn("grpc interceptor afterCall error: ", l -> l.threw(t));
                }
            });
        }
    }

    /**
     * 拒绝本次调用 —— 业务 handler 不执行，回一个错误 trailers。
     */
    private void reject(GrpcStatus status, String message) {
        rejected = true;
        close(status, message);
    }

    // ------------------------------------------------------------------
    // 请求侧
    // ------------------------------------------------------------------

    /**
     * 收到一块请求体 —— 增量分帧，凑齐一条消息就投递。
     */
    void feed(byte[] data, int offset, int length) throws IOException {
        frameDecoder.feed(data, offset, length, this::onRequestMessage);
    }

    private void onRequestMessage(byte[] payload) {
        if (method.isClientStreaming()) {
            synchronized (requestLock) {
                if (requestObserver != null) {
                    deliverToObserver(requestObserver, payload);
                } else {
                    pendingMessages.add(payload);
                }
            }
            return;
        }
        // 一元 / 服务端流式：只取第一条消息，凑齐就立刻把控制权交给业务
        synchronized (requestLock) {
            if (requestMessage != null) {
                // 多余的消息忽略
                return;
            }
            requestMessage = payload;
        }
        invokeHandler();
    }

    /**
     * 请求侧结束（对端半关闭）。
     */
    void endRequest() {
        StreamObserver<Object> observer = null;
        boolean needInvoke = false;
        synchronized (requestLock) {
            if (requestEnded) {
                return;
            }
            requestEnded = true;
            if (!method.isClientStreaming()) {
                if (!handlerInvoked) {
                    // 一元 / 服务端流式但一条消息都没收到 —— 按一条空消息处理，
                    // 否则业务永远不会被调用，客户端只能等到超时。
                    requestMessage = new byte[0];
                    needInvoke = true;
                }
            } else if (requestObserver != null) {
                observer = requestObserver;
            }
        }
        if (needInvoke) {
            invokeHandler();
        } else if (observer != null) {
            observer.onCompleted();
        }
    }

    /**
     * 把控制权交给业务 handler（只调一次）。
     *
     * <p>★ <b>上下文绑定</b>：业务 handler 体里 {@code RpcContext.current()} 拿到的
     * 就是本次调用的上下文。</p>
     *
     * <p>★ <b>deadline 真的会掐断调用</b>：到期后定时器线程回一个
     * {@code DEADLINE_EXCEEDED} 的 trailers；{@code closed} 保证同一个调用
     * 不会写两次 trailers（业务随后返回时就不再写了）。</p>
     */
    void invokeHandler() {
        synchronized (requestLock) {
            if (handlerInvoked) {
                return;
            }
            handlerInvoked = true;
        }
        // 拦截器已经拒绝了这次调用 ⇒ 业务不执行
        if (rejected) {
            return;
        }
        // 客户端给的 deadline 已经过了 ⇒ 业务根本不该跑
        if (context.isExpired()) {
            context.cancellation().cancel(new RpcDeadlineExceededException("deadline exceeded"));
            close(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
            return;
        }
        // deadline 到期 ⇒ 定时器线程回 DEADLINE_EXCEEDED 的 trailers
        context.onDeadline(() -> {
            context.cancellation().cancel(new RpcDeadlineExceededException("deadline exceeded"));
            close(GrpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
        });
        try (RpcContext.Scope scope = RpcContext.attach(context)) {
            method.getHandler().handle(this);
        } catch (Throwable t) {
            LOG.error("grpc handler error: ", t);
            close(GrpcStatus.UNKNOWN, messageOf(t));
        }
    }

    /**
     * 把一条请求消息投递给业务的请求观察者。
     *
     * <p>★ <b>这里也要绑上下文</b>：IO 线程在 {@code handle()} <b>返回之后</b>
     * 才投递后续消息，那时上下文已经解绑 ⇒ 不绑的话业务观察者里
     * {@code RpcContext.current()} 拿不到本次调用的上下文。</p>
     */
    private void deliverToObserver(StreamObserver<Object> observer, byte[] payload) {
        Object message;
        try {
            message = GrpcCodec.decode(method.getReqType(), payload, 0, payload.length);
        } catch (Throwable t) {
            LOG.error("grpc request message decode error: ", t);
            notifyError(observer, t);
            return;
        }
        try (RpcContext.Scope scope = RpcContext.attach(context)) {
            observer.onNext(message);
        } catch (Throwable t) {
            LOG.error("grpc request message delivery error: ", t);
            notifyError(observer, t);
        }
    }

    /**
     * 通知业务观察者出错（业务自己的 {@code onError} 再抛了就忽略 ——
     * 不能影响连接的存活性）。
     */
    private void notifyError(StreamObserver<Object> observer, Throwable t) {
        try {
            observer.onError(t);
        } catch (Throwable ignore) {
            // 业务自己的 onError 又抛了 —— 忽略
        }
    }

    /**
     * 从 HTTP/2 请求头构造请求元数据。
     */
    static GrpcMetadata metadataOf(ValueHttpRequest request) {
        GrpcMetadata metadata = new GrpcMetadata();
        Map<String, HeaderValue> headers = request.getHeaders();
        if (headers == null) {
            return metadata;
        }
        for (Map.Entry<String, HeaderValue> entry : headers.entrySet()) {
            HeaderValue value = entry.getValue();
            if (value == null) {
                continue;
            }
            metadata.add(entry.getKey(), value.getValue());
        }
        return metadata;
    }

    // ------------------------------------------------------------------
    // 响应侧
    // ------------------------------------------------------------------

    @Override
    public String getMethod() {
        return method.getPath();
    }

    @Override
    public GrpcMetadata metadata() {
        return requestMetadata;
    }

    @Override
    public GrpcMetadata responseMetadata() {
        return responseMetadata;
    }

    @Override
    public boolean isClientStreaming() {
        return method.isClientStreaming();
    }

    @Override
    public boolean isServerStreaming() {
        return method.isServerStreaming();
    }

    @Override
    public byte[] requestBytes() {
        synchronized (requestLock) {
            return requestMessage;
        }
    }

    @Override
    public void sendOne(Object message) {
        synchronized (responseLock) {
            if (closed) {
                throw new GrpcException("gRPC 调用已经结束，不能再发送消息: " + method.getPath());
            }
            ensureHeaders();
            try {
                // ★ 帧头内联：直接编码进 writer 缓冲，省掉「payload byte[] + 帧数组」两次整块拷贝。
                //   HTTP/2 侧会在返回前把它拷进堆外的 FastBuf（借来的数组不得持有）。
                GrpcFrameEncoder.encode(method.getRespType(), message,
                        (buf, offset, length) -> response.chunk(buf, offset, length));
            } catch (IOException e) {
                throw new GrpcException("gRPC 响应写出失败: " + method.getPath(), e);
            }
        }
    }

    @Override
    public StreamObserver<Object> responseObserver() {
        return responseObserver;
    }

    @Override
    public void setRequestObserver(StreamObserver<Object> requestObserver) {
        if (requestObserver == null) {
            return;
        }
        synchronized (requestLock) {
            if (this.requestObserver != null) {
                // 只能交回一次
                return;
            }
            this.requestObserver = requestObserver;
            for (byte[] payload : pendingMessages) {
                deliverToObserver(requestObserver, payload);
            }
            pendingMessages.clear();
            if (requestEnded) {
                requestObserver.onCompleted();
            }
        }
    }

    @Override
    public void close(GrpcStatus status, String message) {
        synchronized (responseLock) {
            if (closed) {
                return;
            }
            closed = true;
            ensureHeaders();
            response.putTrailer("grpc-status", Integer.toString(status.code()));
            if (message != null && !message.isEmpty()) {
                response.putTrailer("grpc-message", percentEncode(message));
            }
            try {
                response.end();
            } catch (IOException e) {
                LOG.error("grpc trailers write error: ", e);
            }
        }
        // ★ 撤掉 deadline 定时器 —— 否则长连接服务会攒下一堆僵尸任务
        context.clearDeadline();
        // 调用结束（正常结束也一样）⇒ 标记完成，之后再 cancel 不再生效
        context.cancellation().complete();
        // ★ 「调用结束」这一段的拦截器挂在**这里**而不是 handler 返回处 ——
        //   服务端流式下 handler 立即返回而响应稍后才发，挂错地方会提前触发。
        //   只跑一次：closed 标记保证第二个进来的 close() 直接返回。
        //   跑在 responseLock 之外 —— 用户代码不该持着响应锁。
        runAfterInterceptors(status == GrpcStatus.OK ? null : new GrpcStatusException(status, message));
    }

    @Override
    public RpcContext context() {
        return context;
    }

    /**
     * 写响应头（只写一次）。
     *
     * <p>{@code :status 200} + {@code content-type: application/grpc} + 业务设置的响应元数据。</p>
     */
    private void ensureHeaders() {
        if (headersWritten) {
            return;
        }
        headersWritten = true;
        response.setStatusCode(200);
        response.contentType(ContentTypeHeader.GRPC);
        for (Map.Entry<String, List<String>> entry : responseMetadata.asMap().entrySet()) {
            for (String value : entry.getValue()) {
                response.header(entry.getKey(), value);
            }
        }
        try {
            response.begin();
        } catch (IOException e) {
            throw new GrpcException("gRPC 响应头写出失败: " + method.getPath(), e);
        }
    }

    /**
     * {@code grpc-message} 的百分号编码（gRPC 规范要求）。
     *
     * <p>可打印 ASCII（0x20~0x7E）原样保留，{@code '%'} 本身与其它字节编码成 {@code %XX}。</p>
     */
    static String percentEncode(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c >= 0x20 && c <= 0x7E && c != '%') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    private static String messageOf(Throwable t) {
        if (t == null) {
            return null;
        }
        String message = t.getMessage();
        return message == null ? t.getClass().getName() : message;
    }
}
