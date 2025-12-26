package io.edap.nio;

import io.edap.buffer.FastBuf;
import io.edap.nio.util.DirectIOLoader2;
import io.edap.nio.util.DirectIoFactory;

import java.io.FileDescriptor;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.channels.SocketChannel;

import static io.edap.nio.util.DirectIoFactory.*;
import static io.edap.util.ClazzUtil.getField;
import static io.edap.util.UnsafeUtil.fieldOffset;
import static io.edap.util.UnsafeUtil.getAndSetObject;

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

    protected static final MethodHandle READ0_MH;
    protected static final MethodHandle WRITE0_MH;
    protected static final MethodHandle WRITE0_MH2;
    protected static final ConstantCallSite READ_CALLSITE;
    protected static final ConstantCallSite WRITE_CALLSITE;
    protected static final ConstantCallSite WRITE_CALLSITE2;

    static {

		THREAD_WRITE_BUF = ThreadLocal.withInitial(() -> {
			FastBuf buf = new FastBuf(16384);
			return buf;
		});

        Class<?> fdi;
        try {
            fdi = Class.forName("sun.nio.ch.FileDispatcherImpl");


            MethodHandles.Lookup lookup = MethodHandles.lookup();

            lookup.in(fdi);
            Method read0 = getMethod(fdi, "read0", new Class[]{FileDescriptor.class, long.class, int.class});
            READ0_MH = lookup.unreflect(read0);
            READ_CALLSITE = new ConstantCallSite(READ0_MH);

            MethodHandle write0Mh = null;
            MethodHandle write0Mh2 = null;
            try {
                Method write0 = getMethod(fdi, "write0", FileDescriptor.class, long.class, int.class);
                write0Mh = lookup.unreflect(write0);
            } catch (AssertionError var7) {
                Method write0 = getMethod(fdi, "write0", FileDescriptor.class, long.class, int.class, boolean.class);
                write0Mh2 = lookup.unreflect(write0);
            }

            WRITE0_MH = write0Mh;
            if (WRITE0_MH != null) {
                WRITE_CALLSITE = new ConstantCallSite(write0Mh);
            } else {
                WRITE_CALLSITE = null;
            }
            WRITE0_MH2 = write0Mh2;
            if (WRITE0_MH2  != null) {
                WRITE_CALLSITE2 = new ConstantCallSite(write0Mh2);
            } else {
                WRITE_CALLSITE2 = null;
            }
        } catch (ClassNotFoundException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
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
