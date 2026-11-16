package io.edap.erpc.client;

import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** 当前还开着的连接（测试里可以主动掐掉某一条）。 */
    private final List<Socket> openSockets = new CopyOnWriteArrayList<>();

    private final Thread acceptThread;
    private volatile boolean closed;

    private MiniErpcServer(ServerSocket serverSocket, ErpcCodec serializer, Mode mode) {
        this.serverSocket = serverSocket;
        this.serializer = serializer;
        this.mode = mode;
        this.acceptThread = new Thread(this::acceptLoop, "mini-erpc-server-" + serverSocket.getLocalPort());
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /**
     * 起一个服务端（端口随机）。
     */
    public static MiniErpcServer start(ErpcCodec serializer, Mode mode) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress("127.0.0.1", 0));
        return new MiniErpcServer(ss, serializer, mode);
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
        if (h.getType() == ErpcFrameType.PING.getValue()) {
            write(out, ErpcFrameType.PONG.getValue(), h, new byte[0]);
            return true;
        }
        total.incrementAndGet();
        hits.computeIfAbsent(h.getMethodId(), k -> new AtomicInteger()).incrementAndGet();

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
                ClientDemoDto req = serializer.decode(payload, 0, payload.length, ClientDemoDto.class);
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
