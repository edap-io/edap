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

import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.NioSession;
import io.edap.nio.NioSessionCloser;
import io.edap.nio.ParseResult;
import io.edap.pool.Pool;
import io.edap.util.EdapTime;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.channels.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NIO连接的会话类
 * @author louis
 * @date 2019-07-07 22:29
 */
public abstract class NioServerSession<T> extends NioSession {

    static Logger LOG = LoggerManager.getLogger(NioServerSession.class);

    private static final EdapTime EDAP_TIME = EdapTime.instance();

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
    protected Decoder<T, ? extends NioServerSession> decoder;
    private BufPool bufPool;
    private int monitorIndex;

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

    /**
     * 当前连接持有的 channel —— 同时充当「这条连接是否已经被关闭」的闸门。
     *
     * <p>会话是池化对象：一条连接关闭后会把会话归还对象池，下一条连接可能复用
     * 同一个会话实例。若此时旧连接的「迟到关闭」（例如空闲回收线程与 IO 线程
     * 撞上）再次把会话归还，对象池里就会出现两份同一个会话实例，两条连接共用
     * 一个会话对象 —— 互相踩 socketChannel。</p>
     *
     * <p>用 CAS 把 channel 置空来抢闸门：只有把闸门抢到的那一方才负责归还对象池。
     * 会话被复用给新连接后（{@link #setSocketChannel} 换了 channel），旧连接的
     * 迟到关闭就抢不到闸门，自然不会二次归还。</p>
     */
    private final AtomicReference<SocketChannel> activeChannel = new AtomicReference<>();

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

	private BlockingQueue<FastBuf> writeBufs;

	public boolean writeToChannel(FastBuf buf) throws IOException {
		// 剩余待写字节数。fastWrite 只写 rpos..limit 并返回本次实际写出的字节数，
		// 部分写之后 rpos 已经前移，若这里用整帧长度做比较会导致永远写不完、队列卡死。
		int len = (int)(buf.limit() - buf.rpos());
		int wlen = fastWrite(buf);
		if (wlen >= len) {
			buf.clear();
			return true;
		}

		return false;
	}

    protected NioServerSession() {
    }

    public FastBuf getWriteBuf() {
		if (writeBufs == null) {
			return null;
		}
        return writeBufs.peek();
    }

    public void removeWriteBuf(FastBuf wbuf) {
		if (writeBufs != null) {
			writeBufs.remove(wbuf);
		}
    }


    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public void setSocketChannel(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
        // 连接换了 —— 重新持有闸门，让这条连接成为当前唯一有权归还会话的一方
        this.activeChannel.set(socketChannel);
    }

    /**
     * 抢占「关闭这条连接」的所有权：把闸门从 {@code channel} 置空。
     *
     * <p>返回 true 表示调用方是这条连接的第一个关闭者，应当负责把会话归还对象池；
     * 返回 false 表示这条连接已经被别人关过（或会话已被复用给别的连接），
     * 调用方<b>不能</b>再归还会话。</p>
     *
     * @param channel 本次关闭对应的 channel
     */
    public boolean claimClose(SocketChannel channel) {
        return channel != null && this.activeChannel.compareAndSet(channel, null);
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

    public NioServerSession setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
        return this;
    }

    public Selector getSelector() {
        return this.selector;
    }

    public NioServerSession setSelector(Selector selector) {
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
            return EDAP_NET_IO.write(fd, address, len);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    public static int read0(FileDescriptor fd, long address, int len) throws IOException {
        try {
            return EDAP_NET_IO.read(fd, address, len);
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

    public void flush() throws IOException {
        flush(null);
    }

    public void flush(Pool<FastBuf> bbPool) throws IOException {
        FastBuf wbuf = getWriteBuf();
        FastBuf writeBuf = THREAD_WRITE_BUF.get();
        if (wbuf == null) {
            if (!writeToChannel(writeBuf)) {
                putToWriteQueue(writeBuf);
                FastBuf nbuf = null;
                if (bbPool != null) {
                    nbuf = bbPool.borrow();
                }
                if (nbuf == null) {
                    nbuf = new FastBuf(32768);
                }
                THREAD_WRITE_BUF.set(nbuf);
                setSelectionKey(getSocketChannel()
                        .register(getSelector(), SelectionKey.OP_WRITE, this));
            }
        } else {
            if (writeToChannel(wbuf)) {
                removeWriteBuf(wbuf);
                if (bbPool != null) {
                    bbPool.requite(wbuf);
                }
            }
        }
    }

    public int fastWrite(FastBuf buf) throws IOException {
        if (channelFd == null) {
            buf.syncToByteBuffer();
            int n = socketChannel.write(buf.byteBuffer());
            markWrite(n);
            return n;
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
            int n = writeInternal(buf);
            markWrite(n);
            return n;
        } finally {
            //writeLock.compareAndSet(true, false);
        }
    }

    /**
     * 记一次「真的写出去了字节」的写时间。
     *
     * <p>只有写出字节数 &gt; 0 才记 —— 空写（缓冲区已空、socket 缓冲满写不动）
     * 不代表这条连接还活着，记上去会把空闲回收的判定拖住。</p>
     */
    private void markWrite(int written) {
        if (written > 0) {
            this.lastWriteTime = EDAP_TIME.currentTimeMillis();
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
        if (res <= 0 && !socketChannel.isOpen()) {
            throw new AsynchronousCloseException();
        }

        return res;
    }

    public FileDescriptor getChannelFd() {
        return channelFd;
    }

    public void setChannelFd(FileDescriptor channelFd) {
        this.channelFd = channelFd;
    }

    /**
     * 解码从网络输入的数据，解码成功后，将解码后的数据封装成Message对象，并返回给业务处理线程
     * @param fastBuf
     * @return 是否成功解码消息
     */
    public abstract boolean decode(FastBuf fastBuf, boolean threadSwitch) throws IOException;

    public abstract void handle(T message) throws IOException;

    public Decoder<T, ? extends NioServerSession> getDecoder() {
        return decoder;
    }

    public void setDecoder(Decoder<T, ? extends NioServerSession> decoder) {
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

    public int getMonitorIndex() {
        return monitorIndex;
    }

    public void setMonitorIndex(int monitorIndex) {
        this.monitorIndex = monitorIndex;
    }

	public void putToWriteQueue(FastBuf buf) {
		if (writeBufs == null) {
			writeBufs = new ArrayBlockingQueue(16);
		}
		try {
			writeBufs.put(buf);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

    /**
     * 关闭本会话：取消 selector key、关 channel、必要时把会话归还对象池。
     *
     * <p>与 IO 线程的关连接路径走同一条 {@link NioSessionCloser}，避免两处各关一半
     * （只关 channel 不取消 key → selector 里的 key 还挂着）。</p>
     */
    public void close() {
        NioSessionCloser.close(this.getServer(), this.getSelectionKey(), this);
    }
}
