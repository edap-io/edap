/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap;

import io.edap.buffer.BytesBuf;
import io.edap.buffer.FastBuf;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NIO连接的会话类
 * @author louis
 * @date 2019-07-07 22:29
 */
public abstract class NioSession<T> {

    private final AtomicBoolean readLock = new AtomicBoolean();
    private final AtomicBoolean writeLock = new AtomicBoolean();

    /**
     * 该会话所属Server
     */
    private Server server;
    /**
     * 该会话Edap容器的引用
     */
    private Edap edap;
    private Decoder<T, ? extends NioSession> decoder;
    private BufPool bufPool;

    private static int INTERRUPTED = -3;

    private static int UNAVAILABLE = -2;

    /**
     * 最后读取到数据的时间
     */
    private volatile long lastReadTime;
    /**
     * 最后写数据的时间
     */
    private volatile long lastWriteTime;

    private FileDescriptor channelFd;

    private List<String> durations;
    /**
     * 该会话关联的SocketChannel的对象
     */
    private SocketChannel socketChannel;
    /**
     * 该会话关联的ServerSocketChannel的对象
     */
    private ServerSocketChannel serverSocketChannel;
    /**
     * 该会话关联的ServerSoceketChannel的Attachment的对象
     */
    private ServerChannelContext serverChannelContext;

    protected SelectionKey selectionKey;

    protected Selector selector;
    /**
     * 该线程使用的FastBuf的对象
     */
    private FastBuf buf;
    /**
     * 最大支持的管线个数，如果不支持pipeline值为0
     */
    private int maxPipeline;
    /**
     * 已经在处理该连接的线程标记，如果分配线程可以指定线程则将该连接分配到指定线程进行处理
     */
    private String theadTag;

    private static final MethodHandle READ0_MH;
    private static final MethodHandle WRITE0_MH;
    private static final MethodHandle WRITE0_MH2;

    private BytesBuf wbuf;

    static {
        Class<?> fdi;
        try {
            fdi = Class.forName("sun.nio.ch.FileDispatcherImpl");
            Method read0 = getMethod(fdi, "read0", new Class[]{FileDescriptor.class, Long.TYPE, Integer.TYPE});
            READ0_MH = MethodHandles.lookup().unreflect(read0);

            MethodHandle write0Mh = null;
            MethodHandle write0Mh2 = null;
            try {
                Method write0 = getMethod(fdi, "write0", new Class[]{FileDescriptor.class, Long.TYPE, Integer.TYPE});
                write0Mh = MethodHandles.lookup().unreflect(write0);
            } catch (AssertionError var7) {
                Method write0 = getMethod(fdi, "write0", new Class[]{FileDescriptor.class, Long.TYPE, Integer.TYPE, Boolean.TYPE});
                write0Mh2 = MethodHandles.lookup().unreflect(write0);
            }

            WRITE0_MH = write0Mh;
            WRITE0_MH2 = write0Mh2;
        } catch (ClassNotFoundException | IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    public static Method getMethod(Class clazz, String name, Class... args) {
        return getMethod0(clazz, name, args, true);
    }

    public static <V> V getValue(Object obj, String name) {
        Class<?> aClass = obj.getClass();
        for (String n : name.split("/")) {
            Field f = getField(aClass, n);
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

    public static Field getField(Class clazz, String name) {
        return getField0(clazz, name, true);
    }

    static Field getField0(Class clazz, String name, boolean error) {
        try {
            Field field = clazz.getDeclaredField(name);
            setAccessible(field);
            return field;

        } catch (NoSuchFieldException e) {
            Class superclass = clazz.getSuperclass();
            if (superclass != null) {
                Field field = getField0(superclass, name, false);
                if (field != null)
                    return field;
            }
            if (error)
                throw new AssertionError(e);
            return null;
        }
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

    protected NioSession() {
        wbuf = new BytesBuf(new byte[4096]);
    }

    public BytesBuf getWbuf() {
        return wbuf;
    }

    public void setWbuf(BytesBuf wbuf) {
        this.wbuf = wbuf;
    }


    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public void setSocketChannel(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    public ServerSocketChannel getServerSocketChannel() {
        return serverSocketChannel;
    }

    public void setServerSocketChannel(ServerSocketChannel serverSocketChannel) {
        this.serverSocketChannel = serverSocketChannel;
    }

    public ServerChannelContext getServerChannelContext() {
        return serverChannelContext;
    }

    public void setServerChannelContext(ServerChannelContext ctx) {
        this.serverChannelContext = ctx;
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public NioSession setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
        return this;
    }

    public Selector getSelector() {
        return this.selector;
    }

    public NioSession setSelector(Selector selector) {
        this.selector = selector;
        return this;
    }

    public Edap getEdap() {
        return edap;
    }

    public void setEdap(Edap edap) {
        this.edap = edap;
    }

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public long getLastReadTime() {
        return lastReadTime;
    }

    public void setLastReadTime(long lastReadTime) {
        this.lastReadTime = lastReadTime;
    }

    public long getLastWriteTime() {
        return this.lastWriteTime;
    }

    public void setLastWriteTime(long lastWriteTime) {
        this.lastWriteTime = lastWriteTime;
    }

    public static int write0(FileDescriptor fd, long address, int len) throws IOException {
        try {
            if (WRITE0_MH2 == null) {
                return (int) WRITE0_MH.invokeExact(fd, address, len);
            } else {
                return (int) WRITE0_MH2.invokeExact(fd, address, len, false);
            }
        } catch (IOException ioe) {
            throw ioe;
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    public static int read0(FileDescriptor fd, long address, int len) throws IOException {
        try {
            return (int) READ0_MH.invokeExact(fd, address, len);
        } catch (IOException var5) {
            throw var5;
        } catch (Throwable var6) {
            throw new IOException(var6);
        }
    }

    public int fastRead(FastBuf buf) throws IOException {
        if (channelFd == null) {
            return socketChannel.read(buf.byteBuffer());
        }
        try {
//            while (true) {
//                if (readLock.compareAndSet(false, true)) {
//                    break;
//                }
//                if (Thread.interrupted()) {
//                    throw new IOException(new InterruptedException());
//                }
//            }
            return readInternal(buf);
        } finally {
            //readLock.compareAndSet(true, false);
        }
    }

    int readInternal(FastBuf buf) throws IOException {
        int n = read0(channelFd, buf.address(), buf.writeRemain());
        if ((n == INTERRUPTED) && socketChannel.isOpen()) {
            // The system call was interrupted but the channel
            // is still open, so retry
            return 0;
        }
        int ret = (n == UNAVAILABLE?0:n);
        if (ret > 0) {
            buf.wpos(buf.wpos() + ret);
        }
        return ret;
    }

    public int fastWrite(FastBuf buf) throws IOException {
        if (channelFd == null) {
            buf.syncToByteBuffer();
            return socketChannel.write(buf.byteBuffer());
        }
        try {
//            while (true) {
//                if (writeLock.compareAndSet(false, true)) {
//                    break;
//                }
//                if (Thread.interrupted()) {
//                    throw new IOException(new InterruptedException());
//                }
//            }
            return writeInternal(buf);
        } finally {
            //writeLock.compareAndSet(true, false);
        }
    }

    private int writeInternal(FastBuf buf) throws IOException {
        long pos = buf.rpos();
        long lim = buf.limit();
        int len = lim <= pos ? 0 : (int)(lim - pos);
        int res = write0(channelFd, pos, len);
        if (res > 0) {
            buf.rpos(pos + res);
        }
        if ((res == INTERRUPTED) && socketChannel.isOpen()) {
            // The system call was interrupted but the channel
            // is still open, so retry
            return 0;
        }
        res = (res == UNAVAILABLE?0:res);;
        if (res < 0) {

        }
        if (res <= 0 && !socketChannel.isOpen())
            throw new AsynchronousCloseException();

        return res;
    }

    public FileDescriptor getChannelFd() {
        return channelFd;
    }

    public void setChannelFd(FileDescriptor channelFd) {
        this.channelFd = channelFd;
    }

    public abstract void handle(T message);

    public Decoder<T, ? extends NioSession> getDecoder() {
        return decoder;
    }

    public void setDecoder(Decoder<T, ? extends NioSession> decoder) {
        this.decoder = decoder;
    }

    public BufPool getBufPool() {
        return bufPool;
    }

    public void setBufPool(BufPool bufPool) {
        this.bufPool = bufPool;
    }

    /**
     * 该线程使用的FastBuf的对象
     */
    public FastBuf getBuf() {
        return buf;
    }

    public void setBuf(FastBuf buf) {
        this.buf = buf;
    }

    /**
     * 最大支持的管线个数，如果不支持pipeline值为0
     */
    public int getMaxPipeline() {
        return maxPipeline;
    }

    public void setMaxPipeline(int maxPipeline) {
        this.maxPipeline = maxPipeline;
    }

    /**
     * 已经在处理该连接的线程标记，如果分配线程可以指定线程则将该连接分配到指定线程进行处理
     */
    public String getTheadTag() {
        return theadTag;
    }

    public void setTheadTag(String theadTag) {
        this.theadTag = theadTag;
    }

}
