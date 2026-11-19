package io.edap.erpc.client;

import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcMetadataCodec;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.rpc.RpcMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端测试用的<b>迷你 eRPC 服务端</b> —— 直接说线协议，不依赖 edap-erpc-server。
 *
 * <p>★ <b>为什么不复用 {@code io.edap.erpc.server.ErpcServer}</b>：
 * edap-erpc-server 的<b>测试</b>作用域依赖 edap-erpc-client，反过来再让
 * edap-erpc-client 的测试依赖 edap-erpc-server 会形成 Maven reactor 环。</p>
 *
 * <p>★ <b>这样反而更好</b>：客户端是对着<b>一份独立的协议实现</b>测的 ——
 * 不是自己和自己对表，而是对着「按线格式写的最小实现」测，
 * 能抓出「客户端与服务端对同一个字节的理解不一致」这类问题。</p>
 *
 * <p>支持的行为（{@link Mode}）：</p>
 * <ul>
 *   <li>{@link Mode#ECHO}：解码请求 → value×2 → 编码响应（codec 字节原样抄回）；</li>
 *   <li>{@link Mode#SILENT}：收到请求不回 —— 用于测客户端超时；</li>
 *   <li>{@link Mode#ERROR}：回 ERROR 帧（错误体是编码后的 {@link RpcException}）；</li>
 *   <li>{@link Mode#CLOSE_ON_REQUEST}：收到请求就断开连接 —— 用于测在途调用不挂死。</li>
 * </ul>
 */
public final class MiniErpcServer implements AutoCloseable {

    /** 服务端行为。 */
    public enum Mode {
        ECHO,
        SILENT,
        ERROR,
        CLOSE_ON_REQUEST
    }

    private final ServerSocket serverSocket;
    private final ErpcCodec serializer;
    private final Mode mode;

    /** 收到的请求数（按 methodId 统计）。 */
    private final java.util.Map<Integer, AtomicInteger> hits = new java.util.concurrent.ConcurrentHashMap<>();
    /** 收到的请求总数。 */
    private final AtomicInteger total = new AtomicInteger();
    /** 接受的连接数。 */
    private final AtomicInteger connections = new AtomicInteger();
    /**
     * 最近一个请求帧的 {@code flags} —— 用来断言「客户端把超时代码写进了帧头」。
     */
    private final AtomicInteger lastRequestFlags = new AtomicInteger();
    /**
     * 最近一个请求帧的 {@code traceId} —— 用来断言「客户端拦截器改的 traceId 真的上线」。
     */
    private final AtomicLong lastRequestTraceId = new AtomicLong();
    /**
     * 最近一个请求帧的<b>原始 type 字节</b> —— 用来断言「不带元数据时线上帧字节级不变」。
     */
    private final AtomicInteger lastRequestType = new AtomicInteger();
    /**
     * 最近一个请求帧带来的<b>RPC 元数据</b> —— 没有则是 {@code null}。
     */
    private final AtomicReference<RpcMetadata> lastMetadata = new AtomicReference<>();
    /**
     * 最近一个 AUTH 帧带来的凭据 —— 用来断言「客户端真的握了手」。
     */
    private final AtomicReference<String> lastCredential = new AtomicReference<>();
    /** 收到的 AUTH 帧数（握手次数）。 */
    private final AtomicInteger authCount = new AtomicInteger();
    /** 接受的凭据；{@code null} ⇒ 任何凭据都通过。 */
    private final String expectedCredential;
    /** 当前还开着的连接（测试里可以主动掐掉某一条）。 */
    private final List<Socket> openSockets = new CopyOnWriteArrayList<>();

    private final Thread acceptThread;
    private volatile boolean closed;

    private MiniErpcServer(ServerSocket serverSocket, ErpcCodec serializer, Mode mode,
                            String expectedCredential) {
        this.serverSocket = serverSocket;
        this.serializer = serializer;
        this.mode = mode;
        this.expectedCredential = expectedCredential;
        this.acceptThread = new Thread(this::acceptLoop, "mini-erpc-server-" + serverSocket.getLocalPort());
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /**
     * 起一个服务端（端口随机）。
     */
    public static MiniErpcServer start(ErpcCodec serializer, Mode mode) throws IOException {
        return start(serializer, mode, null);
    }

    /**
     * 起一个服务端（端口随机）—— <b>带连接级鉴权</b>。
     *
     * @param expectedCredential 接受的凭据；{@code null} ⇒ 任何凭据都通过
     */
    public static MiniErpcServer start(ErpcCodec serializer, Mode mode, String expectedCredential)
            throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        return new MiniErpcServer(ss, serializer, mode, expectedCredential);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int totalRequests() {
        return total.get();
    }

    public int requestsFor(int methodId) {
        AtomicInteger c = hits.get(methodId);
        return c == null ? 0 : c.get();
    }

    public int connectionCount() {
        return connections.get();
    }

    /**
     * 最近一个请求帧的 {@code flags}（bit1~bit7 = 7-bit 超时代码，bit0 = 压缩预留）。
     */
    public int lastRequestFlags() {
        return lastRequestFlags.get();
    }

    /**
     * 最近一个请求帧的 {@code traceId}。
     */
    public long lastRequestTraceId() {
        return lastRequestTraceId.get();
    }

    /**
     * 最近一个请求帧的<b>原始 type 字节</b>。
     *
     * <p>★ 用来断言「不带元数据时线上帧与改造前字节级一致」
     * （应当仍然是 {@code REQUEST = 1}，bit7 没被置位）。</p>
     */
    public int lastRequestType() {
        return lastRequestType.get();
    }

    /**
     * 最近一个请求帧带来的元数据；没有则是 {@code null}。
     */
    public RpcMetadata lastMetadata() {
        return lastMetadata.get();
    }

    /**
     * 最近一个 AUTH 帧带来的凭据；没有握手过则是 {@code null}。
     */
    public String lastCredential() {
        return lastCredential.get();
    }

    /**
     * 收到的 AUTH 帧数（= 握手次数）—— 用来断言「同一连接上只握手一次」。
     */
    public int authCount() {
        return authCount.get();
    }

    /**
     * 掐掉当前所有连接（模拟服务端断链）。
     */
    public void dropConnections() {
        for (Socket s : openSockets) {
            try {
                s.close();
            } catch (IOException ignore) {
                // 忽略
            }
        }
        openSockets.clear();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                connections.incrementAndGet();
                openSockets.add(socket);
                Thread t = new Thread(() -> serve(socket), "mini-erpc-conn-" + socket.getPort());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) {
                    // 忽略瞬时的 accept 失败
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            ErpcFrameCodec codec = new ErpcFrameCodec();
            byte[] buf = new byte[4096];
            while (!closed) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                codec.append(buf, 0, n);
                while (codec.hasFrame()) {
                    ErpcFrameHeader h = codec.header();
                    byte[] payload = codec.payload();
                    codec.consume();
                    if (!handle(h, payload, out)) {
                        return;
                    }
                }
            }
        } catch (IOException ignore) {
            // 对端断开
        } finally {
            openSockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignore) {
                // 忽略
            }
        }
    }

    /**
     * 处理一帧。
     *
     * @return false 表示连接应当关闭
     */
    private boolean handle(ErpcFrameHeader h, byte[] payload, OutputStream out) throws IOException {
        // ★ type 字节的 bit7 是「本帧带 RPC 上下文」标志位 ⇒ 取种类要掩掉它
        int kind = ErpcFrameType.kindOf(h.getType());
        if (kind == ErpcFrameType.PING.getValue()) {
            write(out, ErpcFrameType.PONG.getValue(), h, new byte[0]);
            return true;
        }
        if (kind == ErpcFrameType.AUTH.getValue()) {
            // ★ 连接级鉴权握手：凭据对 ⇒ 回空的 RESPONSE；不对 ⇒ 回 ERROR 并关连接
            String credential = payload == null ? ""
                : new String(payload, StandardCharsets.UTF_8);
            authCount.incrementAndGet();
            lastCredential.set(credential);
            if (expectedCredential == null || expectedCredential.equals(credential)) {
                write(out, ErpcFrameType.RESPONSE.getValue(), h, new byte[0]);
                return true;
            }
            write(out, ErpcFrameType.ERROR.getValue(), h, errorPayload(h));
            return false;
        }
        total.incrementAndGet();
        hits.computeIfAbsent(h.getMethodId(), k -> new AtomicInteger()).incrementAndGet();
        lastRequestFlags.set(h.getFlags());
        lastRequestTraceId.set(h.getTraceId());
        lastRequestType.set(h.getType());

        // ★ 内容区可能带一段 RPC 上下文：[上下文段][业务 DTO]
        byte[] dto = payload;
        if (ErpcFrameType.hasContext(h.getType())) {
            int blockLen = ErpcMetadataCodec.blockLength(payload, 0);
            lastMetadata.set(ErpcMetadataCodec.decode(payload,
                ErpcMetadataCodec.LENGTH_PREFIX, blockLen));
            dto = Arrays.copyOfRange(payload,
                ErpcMetadataCodec.LENGTH_PREFIX + blockLen, payload.length);
        } else {
            lastMetadata.set(null);
        }

        switch (mode) {
            case SILENT:
                return true;
            case CLOSE_ON_REQUEST:
                return false;
            case ERROR:
                write(out, ErpcFrameType.ERROR.getValue(), h, errorPayload(h));
                return true;
            case ECHO:
            default:
                ClientDemoDto req = serializer.decode(dto, 0, dto.length, ClientDemoDto.class);
                ClientDemoDto resp = new ClientDemoDto(req.getName(), req.getValue() * 2);
                write(out, ErpcFrameType.RESPONSE.getValue(), h, bytesOf(resp));
                return true;
        }
    }

    /**
     * 错误体：编码后的 {@link RpcException}。
     */
    private byte[] errorPayload(ErpcFrameHeader h) {
        RpcException err = new RpcException();
        err.setStatus(13);
        err.setClazzName("java.lang.IllegalStateException");
        err.setMessage("mini server boom");
        return bytesOf(err);
    }

    /**
     * 编码并把「借来的」载荷拷出来 —— 借来的缓冲下一次编码就会被覆盖。
     */
    private byte[] bytesOf(Object obj) {
        ErpcWriter writer = serializer.encode(obj);
        return writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());
    }

    /**
     * 写一帧（codec 字节从请求帧原样抄回）。
     */
    private void write(OutputStream out, int type, ErpcFrameHeader req, byte[] payload) throws IOException {
        ErpcFrameHeader resp = new ErpcFrameHeader();
        resp.setType(type);
        resp.setCodec(req.getCodec());
        resp.setFlags(0);
        resp.setLength(payload == null ? 0 : payload.length);
        resp.setMethodId(req.getMethodId());
        resp.setServiceId(req.getServiceId());
        resp.setRequestId(req.getRequestId());
        resp.setTraceId(req.getTraceId());
        byte[] header = new byte[ErpcFrameHeader.HEADER_LENGTH];
        resp.writeTo(header, 0);
        out.write(header);
        if (payload != null && payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        dropConnections();
        try {
            serverSocket.close();
        } catch (IOException ignore) {
            // 忽略
        }
    }
}
