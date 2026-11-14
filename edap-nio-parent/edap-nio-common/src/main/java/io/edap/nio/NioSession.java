package io.edap.nio;

import io.edap.buffer.FastBuf;

import java.io.FileDescriptor;
import java.lang.reflect.*;
import java.nio.channels.SocketChannel;

import static io.edap.util.ClazzUtil.getField;

public abstract class NioSession implements ThreadAffinity {

    /**
     * 最后读取到数据的时间
     */
    private volatile long lastReadTime;
    /**
     * 最后写数据的时间
     */
    private volatile long lastWriteTime;

    protected FileDescriptor channelFd;

    /**
     * 该会话关联的SocketChannel的对象
     */
    private SocketChannel socketChannel;

    /**
     * 已经在处理该连接的线程标记，如果分配线程可以指定线程则将该连接分配到指定线程进行处理
     */
    private int threadIndex;
    /**
     * 该会话最新的分配序号，方便处理完成后确认是否是队列中最后一个请求
     */
    private volatile long lastSequence;
    private boolean affinityThread;

	public static ThreadLocal<FastBuf> THREAD_WRITE_BUF;

    /**
     * 写缓冲容量 —— 一次 socket 写最多带多少字节。
     *
     * <p><b>★ 为什么不是 32KB</b>：HTTP/2 的最大帧是 16KB，一条 64KB 的响应
     * （含帧头约 65.7KB）要拆成 4 个 DATA 帧。写缓冲只有 32KB 时<b>一个帧都装不下两个</b>
     * —— 于是每条响应变成 4 次 socket 写、4 次「写不动就等 OP_WRITE」的往返。
     * 128KB 刚好把一条 64KB 响应一次写完（实测 64KB 负载下 socket 写从 4.10 次/响应
     * 降到 1.01 次/响应，吞吐 +29%）。</p>
     *
     * <p><b>★ 为什么是 256KB</b>：写缓冲要装得下「一次读事件产出的全部响应」——
     * 读缓冲 256KB 时一次读可能带回 4 条 64KB 响应（合计 256KB），写缓冲小了就得
     * 分两次写。实测 64KB 负载：128KB → 14,626 req/s，256KB → 15,272 req/s。</p>
     *
     * <p>这是<b>池化</b>的写缓冲（见 {@code DisruptorReadDispatcher} 的 bbPool），
     * 不是每连接一份，所以内存占用与并发连接数无关，只与「同时在写的连接数」有关。</p>
     */
    public static final int WRITE_BUFFER_SIZE = Integer.getInteger("edap.writeBufferSize", 262144);

    /**
     * 读缓冲容量 —— 每个 IO 线程一份，一次 socket 读最多带多少字节。
     *
     * <p><b>★ 为什么不是 16KB</b>：HTTP/2 的最大帧载荷是 16,384 字节，加上 9 字节帧头
     * 是 16,393 字节 —— <b>比 16KB 的读缓冲还大 9 字节</b>。于是任何一个最大帧都装不下，
     * 每帧都要跨两次读才凑齐，入站累积缓冲每次都得整体搬移（实测每次读搬 16KB，
     * 等于每个入站字节多搬一次）。</p>
     *
     * <p>更大的读缓冲还有第二个好处：<b>一次读带回多条请求</b>，服务端就能把它们
     * 的响应攒成一次 socket 写（批量）。实测（edap gRPC，32 并发）：</p>
     * <ul>
     *   <li>16KB 读缓冲 → 64KB 负载 10,653 req/s</li>
     *   <li>256KB 读缓冲 → 64KB 负载 14,903 req/s（<b>+40%</b>），
     *       16KB 负载也从 39,698 涨到 41,835</li>
     * </ul>
     *
     * <p>内存占用 = IO 线程数 × 本值，与连接数无关。</p>
     */
    public static final int READ_BUFFER_SIZE = Integer.getInteger("edap.readBufferSize", 262144);

    public static EdapNetIO EDAP_NET_IO;

    static {

		THREAD_WRITE_BUF = ThreadLocal.withInitial(() -> {
			FastBuf buf = new FastBuf(32768);
			return buf;
		});

        EDAP_NET_IO = EdapNetIOFactory.createEdapNetIO();
    }

    private static Method getMethod(Class clazz, String name, Class... args) {
        return getMethod0(clazz, name, args, true);
    }

    private static Method getMethod0(Class clazz, String name, Class[] args, boolean first) {
        try {
            Method method = clazz.getDeclaredMethod(name, args);
            if (!Modifier.isPublic(method.getModifiers()) ||
                    !Modifier.isPublic(method.getDeclaringClass().getModifiers()))
                setAccessible(method);
            return method;

        } catch (NoSuchMethodException e) {
            Class superclass = clazz.getSuperclass();
            if (superclass != null)
                try {
                    Method m = getMethod0(superclass, name, args, false);
                    if (m != null)
                        return m;
                } catch (Exception ignored) {
                }
            if (first)
                throw new AssertionError(e);
            return null;
        }
    }

    public static void setAccessible(AccessibleObject h) {
        h.setAccessible(true);
    }

    public static <V> V getValue(Object obj, String name) throws NoSuchFieldException {
        Class<?> aClass = obj.getClass();
        for (String n : name.split("/")) {
            Field f = getField(aClass, n);
            setAccessible(f);
            try {
                obj = f.get(obj);
                if (obj == null) {
                    return null;
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            aClass = obj.getClass();
        }
        return (V) obj;
    }

    /**
     * 已经在处理该连接的线程标记，如果分配线程可以指定线程则将该连接分配到指定线程进行处理
     */
    public int getThreadIndex() {
        return threadIndex;
    }

    public void setThreadIndex(int theadIndex) {
        this.threadIndex = theadIndex;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    @Override
    public synchronized void setLastSequence(long lastSequence) {
        this.lastSequence = lastSequence;
    }

    public boolean isAffinityThread() {
        return affinityThread;
    }

    public void setAffinityThread(boolean affinityThread) {
        this.affinityThread = affinityThread;
    }
}
