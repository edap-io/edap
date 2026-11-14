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
import io.edap.grpc.GrpcStatus;
import io.edap.http.HeaderValue;
import io.edap.http.HttpResponse;
import io.edap.http.ValueHttpRequest;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.server.http2.Http2Stream;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.rpc.StreamObserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final GrpcMethod method;
    private final Http2Stream stream;
    private final HttpResponse response;
    private final GrpcMetadata requestMetadata;
    private final GrpcMetadata responseMetadata = new GrpcMetadata();
    private final GrpcFrameDecoder frameDecoder = new GrpcFrameDecoder();

    private final Object responseLock = new Object();
    private final Object requestLock = new Object();

    /** 响应头是否已经写出 */
    private boolean headersWritten;

    /** 调用是否已经结束（trailers 已发出） */
    private boolean closed;

    /** 业务 handler 是否已经被调用过 */
    private boolean handlerInvoked;

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
        this.method = method;
        this.stream = stream;
        this.response = stream.getResponse();
        this.requestMetadata = requestMetadata;
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
     */
    void invokeHandler() {
        synchronized (requestLock) {
            if (handlerInvoked) {
                return;
            }
            handlerInvoked = true;
        }
        try {
            method.getHandler().handle(this);
        } catch (Throwable t) {
            LOG.error("grpc handler error: ", t);
            close(GrpcStatus.UNKNOWN, messageOf(t));
        }
    }

    private void deliverToObserver(StreamObserver<Object> observer, byte[] payload) {
        try {
            observer.onNext(GrpcCodec.decode(method.getReqType(), payload, 0, payload.length));
        } catch (Throwable t) {
            LOG.error("grpc request message decode error: ", t);
            try {
                observer.onError(t);
            } catch (Throwable ignore) {
                // 业务自己的 onError 又抛了 —— 忽略，不能影响连接的存活性
            }
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
