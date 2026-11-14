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

package io.edap.nio.handler;

import com.lmax.disruptor.EventHandler;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.ServerChannelContext;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.event.AcceptEvent;
import io.edap.pool.Pool;
import io.edap.pool.impl.ThreadLocalPool;
import io.edap.util.EdapTime;

import java.net.Socket;
import java.nio.channels.SocketChannel;

/**
 * Accept事件的处理器，主要逻辑是获取NioSession，并把SocketChanner和NioSession做关联后，将NioSession注册到IoSelectorManager中。
 */
public class AcceptEventHandler implements EventHandler<AcceptEvent> {

    static Logger LOG = LoggerManager.getLogger(AcceptEventHandler.class);

    private        Server                    server;
    private        boolean                   nioSessionPooled;
    private        Pool<NioServerSession<?>> nioSessionPool;
    private static EdapTime                  EDAP_TIME = EdapTime.instance();

    public AcceptEventHandler(Server server) {
        this.server = server;
        if (server.isNioSesionPooled()) {
            if (server.getNioSessionPool() == null) {
                nioSessionPool = new ThreadLocalPool();
                server.setNioSessionPool(nioSessionPool);
            } else {
                nioSessionPool = server.getNioSessionPool();
            }
            nioSessionPooled = true;
        } else {
            nioSessionPooled = false;
        }
    }

    /**
     * 处理AcceptEvent事件,获取NioSession，并且关联SocketChannel和NioSession并且尝试获取SocketChannel中的文件描述符，
     * 方便NioSession进行快速读写。
     * @param event 事件实例
     * @param sequence disruptor的序号
     * @param endOfBatch 是否为本批次的最后一个事件
     * @throws Exception 处理网络连接时的异常
     */
    @Override
    public void onEvent(AcceptEvent event, long sequence, boolean endOfBatch) throws Exception {
        LOG.trace("event:{}, sequence={}, endOfBatch={}",
                l -> l.arg(event.getChannel()).arg(sequence).arg(endOfBatch));
        ServerChannelContext scc = event.getServerChannelCtx();
        SocketChannel sc = event.getChannel();
        sc.configureBlocking(false);
		Socket socket = sc.socket();
		socket.setReuseAddress(true);
		socket.setTcpNoDelay(true);
		socket.setKeepAlive(false);
		// ★ socket 缓冲：默认<b>不钉死</b>，交给内核自动调优。
		//
		//   显式 setSendBufferSize(16KB) 会把内核的发送缓冲<b>天花板</b>钉死在 16KB
		//   （Linux 上 SO_SNDBUF 直接写 sk_sndbuf，自动调优只能在它之下浮动），
		//   于是「大响应」每一次写都是半写 —— 必须挂写队列等一次 OP_WRITE 事件，
		//   一次 64KB 响应要付 4 次 selector 往返。
		//
		//   实测（edap gRPC 64KB 负载 / 32 并发，单连接）：
		//     钉死 16KB → 5,451 req/s、342 MB/s、每响应 3.98 次半写 + 8.92 次 OP_WRITE
		//     不钉死     → 11,077 req/s、694 MB/s、半写 0 次、OP_WRITE 0 次
		//
		//   交回内核还有个好处：<b>空闲连接不吃内存</b> —— 自动调优只在有流量时长大，
		//   而显式设置是给每条连接无条件预留（百万连接时是灾难）。
		//   需要给内存兜底时用 -Dedap.socket.sendBuffer=<字节> 显式钉。
		int autoSendBuf = Integer.getInteger("edap.socket.sendBuffer", 0);
		if (autoSendBuf > 0) {
			socket.setSendBufferSize(autoSendBuf);
		}
		int autoRecvBuf = Integer.getInteger("edap.socket.recvBuffer", 0);
		if (autoRecvBuf > 0) {
			socket.setReceiveBufferSize(autoRecvBuf);
		}
        NioServerSession<?> nioSession;
        if (nioSessionPooled) {
            nioSession = nioSessionPool.borrow();
            if (nioSession == null) {
                nioSession = server.createNioSession();
            }
        } else {
            nioSession = server.createNioSession();
        }
        nioSession.setSocketChannel(sc);
        nioSession.setChannelFd(NioServerSession.getValue(sc, "fd"));
        nioSession.setServer(server);
        nioSession.setEdap(scc.getEdap());
        nioSession.setMonitorIndex(scc.getMonitorIndex());
        nioSession.setLastReadTime(EDAP_TIME.currentTimeMillis());

        scc.getIoSelectorManager().registerNioSession(nioSession);
    }
}
