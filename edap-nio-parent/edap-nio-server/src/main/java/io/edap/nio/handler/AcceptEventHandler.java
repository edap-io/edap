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
import java.net.SocketOption;
import java.net.SocketOptions;
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
		socket.setReceiveBufferSize(16 * 1024);
		socket.setSendBufferSize(16 * 1024);
        socket.setKeepAlive(false);
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
        nioSession.setEdap(scc.getEdap());
        nioSession.setMonitorIndex(scc.getMonitorIndex());
        nioSession.setLastReadTime(EDAP_TIME.currentTimeMillis());

        scc.getIoSelectorManager().registerNioSession(nioSession);
    }
}
