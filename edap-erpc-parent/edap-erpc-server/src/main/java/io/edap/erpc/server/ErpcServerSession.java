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

import io.edap.NioServerSession;
import io.edap.buffer.FastBuf;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.erpc.frame.ErpcRequest;
import io.edap.erpc.frame.ErpcResponse;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ParseResult;
import io.edap.pool.Pool;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * eRPC 服务端的会话（一条连接）。
 *
 * <p><b>入站</b>：读到的字节先进 {@link ErpcServerDecoder} 的累积缓冲（跨 read 保留
 * —— 底层读分发器每次 read 前会 {@code buf.reset()}，半包必须由上层自己攒），
 * 切出完整帧后交给 {@link ErpcServer} 路由。</p>
 *
 * <p><b>出站</b>：响应帧由业务线程池里的 worker 通过 {@link ErpcOutboundWriter}
 * 直接写进累积缓冲并刷到 socket —— <b>不在 IO 线程上做业务</b>
 * （gRPC 那边「IO 线程直调 handler」是已知缺陷，这里不抄）。</p>
 */
public class ErpcServerSession extends NioServerSession<ErpcRequest> {

    private static final Logger LOG = LoggerManager.getLogger(ErpcServerSession.class);

    private final ErpcServer server;

    /**
     * 分帧器 —— <b>每连接一个</b>（它持有跨 read 的累积缓冲，不能共享）。
     */
    private final ErpcServerDecoder decoder;

    private final ErpcOutboundWriter outboundWriter;

    /**
     * 兴趣位（{@code OP_WRITE}）的更新锁。
     *
     * <p>★ worker 线程（残余交接时要注册 {@code OP_WRITE}）与 IO 线程
     * （可能同时在改 {@code interestOps}）会撞在一起 —— 而
     * {@code interestOps(int)} 是「读-改-写」，并发时<b>会丢更新</b>
     * （OP_WRITE 没注册上 ⇒ 残余永远没人写 ⇒ 连接卡死）。</p>
     */
    private final Object interestLock = new Object();

    public ErpcServerSession(ErpcServer server) {
        this.server = server;
        this.decoder = new ErpcServerDecoder();
        this.outboundWriter = new ErpcOutboundWriter(this);
        setDecoder(this.decoder);
    }

    // ------------------------------------------------------------------
    // 入站
    // ------------------------------------------------------------------

    @Override
    public boolean decode(FastBuf fastBuf, boolean threadSwitch) throws IOException {
        boolean hasMsg = false;
        try {
            for (;;) {
                ParseResult<ErpcRequest> pr = decoder.decode(fastBuf, this);
                if (!pr.isFinished()) {
                    break;
                }
                hasMsg = true;
                handle(pr.getMessage());
            }
        } catch (ErpcProtocolException e) {
            // 不是 eRPC 流量（或协议版本不支持）⇒ 关连接，不能让它逃进 IO 线程
            LOG.warn("erpc protocol error, close channel: ", l -> l.threw(e));
            close();
            return false;
        }
        return hasMsg;
    }

    @Override
    public void handle(ErpcRequest message) throws IOException {
        server.dispatch(this, message);
    }

    // ------------------------------------------------------------------
    // 出站
    // ------------------------------------------------------------------

    /**
     * 回一个响应帧。
     *
     * <p>★ <b>两条路径</b>：</p>
     * <ul>
     *   <li>{@code resp.getResult() != null}（handler 交的是<b>对象</b>）⇒ 走
     *       「延迟编码」：载荷在出站时只被拷一次；</li>
     *   <li>否则用 {@code resp.getPayload()}（handler 自己编码好的字节）⇒ 走老路。</li>
     * </ul>
     */
    public void writeResponse(ErpcRequest req, ErpcResponse resp) {
        RpcException exception = resp.getException();
        if (exception != null) {
            writeErrorFrame(req, exception);
            return;
        }
        Object result = resp.getResult();
        ErpcCodec serializer = result == null ? null : serializerOf(req);
        if (serializer != null) {
            outboundWriter.writeFrameMessage(ErpcFrameType.RESPONSE.getValue(), serializer.id(),
                req.getMethodId(), req.getServiceId(), req.getRequestId(), req.getTraceId(),
                serializer, result);
            return;
        }
        outboundWriter.writeFrame(ErpcFrameType.RESPONSE.getValue(), req.getCodec(),
            req.getMethodId(), req.getServiceId(), req.getRequestId(), req.getTraceId(),
            resp.getPayload());
    }

    /**
     * 解析本帧载荷该用哪个序列化器（帧头 codec 字节 → 注册表）。
     */
    private ErpcCodec serializerOf(ErpcRequest req) {
        ErpcCodec serializer = req.getSerializer();
        if (serializer != null) {
            return serializer;
        }
        serializer = server.codecById(req.getCodec());
        return serializer != null ? serializer : server.defaultCodec();
    }

    /**
     * 回一个 ERROR 帧。
     *
     * @param status  状态码
     * @param message 错误描述
     * @param cause   原始异常（可为 null）
     */
    public void writeError(ErpcRequest req, ErpcStatus status, String message, Throwable cause) {
        RpcException err = new RpcException();
        err.setStatus(status.code());
        err.setClazzName(cause == null ? ErpcStatus.class.getName() : cause.getClass().getName());
        err.setMessage(message);
        if (cause != null) {
            err.setStackElement(ErpcServer.toStackTrace(cause.getStackTrace()));
        }
        writeErrorFrame(req, err);
    }

    /**
     * 回一个 ERROR 帧（载荷是编码后的 {@link RpcException}）。
     *
     * <p>★ <b>错误帧的 codec 原样抄回请求帧</b> ⇒ 客户端一定解得动。
     * 唯一例外：请求帧的 codec 服务端不认识 ⇒ 用服务端自己的默认 codec，
     * 并在错误体里说明原因（客户端会看到 codec 不匹配的错误）。</p>
     */
    private void writeErrorFrame(ErpcRequest req, RpcException err) {
        int codecId = req.getCodec();
        ErpcCodec serializer = server.codecById(codecId);
        if (serializer == null) {
            serializer = server.defaultCodec();
            codecId = serializer.id();
        }
        try {
            outboundWriter.writeFrameMessage(ErpcFrameType.ERROR.getValue(), codecId,
                req.getMethodId(), req.getServiceId(), req.getRequestId(), req.getTraceId(),
                serializer, err);
        } catch (RuntimeException e) {
            // 错误体编码不出来 ⇒ 回一个空载荷的 ERROR 帧（客户端会看到「没有错误体」）
            LOG.warn("erpc encode error frame failed: ", l -> l.threw(e));
            outboundWriter.writeFrame(ErpcFrameType.ERROR.getValue(), codecId,
                req.getMethodId(), req.getServiceId(), req.getRequestId(), req.getTraceId(), null);
        }
    }

    /**
     * 回一个 PONG（帧头字段原样抄回请求帧）。
     */
    public void writePong(ErpcRequest req) {
        outboundWriter.writeFrame(ErpcFrameType.PONG.getValue(), req.getCodec(),
            req.getMethodId(), req.getServiceId(), req.getRequestId(), req.getTraceId(), null);
    }

    // ------------------------------------------------------------------
    // NIO 层钩子
    // ------------------------------------------------------------------

    /**
     * 把待写缓冲刷到 socket。
     *
     * <p>★ 覆写掉基类实现：基类在部分写时会
     * {@code channel.register(selector, OP_WRITE, this)}，把 interestOps 整个替换掉，
     * {@code OP_READ} 丢失 —— socket 一旦写满，服务端再也读不到对端的数据。</p>
     *
     * <p>★ <b>{@code bbPool} 允许为 null</b>：基类的无参 {@code flush()} 会传 null，
     * 直接把池置空会让后面借不到缓冲（每条响应都新分配一块 256KB）。</p>
     */
    @Override
    public void flush(Pool<FastBuf> bbPool) throws IOException {
        if (bbPool != null) {
            outboundWriter.setPool(bbPool);
        }
        outboundWriter.flushToSocket();
    }

    /**
     * NIO 层把写队列头写完之后回调 —— 继续发剩下的帧。
     */
    @Override
    public void removeWriteBuf(FastBuf wbuf) {
        super.removeWriteBuf(wbuf);
        outboundWriter.onWriteBufDrained();
    }

    /**
     * 拿到本 session 的 {@link SelectionKey}。
     *
     * <p>NIO 层只在「首次部分写」时才缓存 selectionKey，而 eRPC 需要在第一次写之前
     * 就拿到它来注册 {@code OP_WRITE}，所以这里从 channel 反查一次并缓存下来。</p>
     */
    public SelectionKey obtainSelectionKey() {
        synchronized (interestLock) {
            return obtainSelectionKeyLocked();
        }
    }

    /**
     * 拿到本 session 的 {@link SelectionKey}（<b>调用方必须持有 {@link #interestLock}</b>）。
     */
    private SelectionKey obtainSelectionKeyLocked() {
        SelectionKey key = getSelectionKey();
        if (key != null) {
            return key;
        }
        SocketChannel ch = getSocketChannel();
        Selector sel = getSelector();
        if (ch == null || sel == null) {
            return null;
        }
        key = ch.keyFor(sel);
        if (key != null) {
            setSelectionKey(key);
        }
        return key;
    }

    /**
     * 注册 {@code OP_WRITE}（<b>保留</b> {@code OP_READ}）。
     */
    public void enableWriteInterest() {
        synchronized (interestLock) {
            SelectionKey key = obtainSelectionKeyLocked();
            if (key == null || !key.isValid()) {
                return;
            }
            int ops = key.interestOps();
            if ((ops & SelectionKey.OP_WRITE) == 0) {
                key.interestOps(ops | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * 撤销 {@code OP_WRITE}。
     *
     * <p>不撤销的话 selector 会被「socket 可写」唤醒，空转烧 CPU。</p>
     */
    public void disableWriteInterest() {
        synchronized (interestLock) {
            SelectionKey key = obtainSelectionKeyLocked();
            if (key == null || !key.isValid()) {
                return;
            }
            int ops = key.interestOps();
            if ((ops & SelectionKey.OP_WRITE) != 0) {
                key.interestOps(ops & ~SelectionKey.OP_WRITE);
            }
        }
    }

    public ErpcOutboundWriter outboundWriter() {
        return outboundWriter;
    }
}
