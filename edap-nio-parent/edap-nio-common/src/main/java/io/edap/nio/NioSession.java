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
