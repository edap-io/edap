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

import java.nio.channels.SocketChannel;

public class AcceptEventHandler implements EventHandler<AcceptEvent> {

    static Logger LOG = LoggerManager.getLogger(AcceptEventHandler.class);

    private static Pool<NioServerSession> NIO_SESSION_POOL;
    private static boolean          NIO_SESSION_POOLED;

    public AcceptEventHandler(Server server) {
        if (server.isNioSesionPooled()) {
            if (server.getNioSessionPool() == null) {
                NIO_SESSION_POOL = new ThreadLocalPool();
                server.setNioSessionPool(NIO_SESSION_POOL);
            } else {
                NIO_SESSION_POOL = server.getNioSessionPool();
            }
            NIO_SESSION_POOLED = true;
        } else {
            NIO_SESSION_POOLED = false;
        }
    }

    @Override
    public void onEvent(AcceptEvent event, long sequence, boolean endOfBatch) throws Exception {
        LOG.trace("event:{}, sequence={}, endOfBatch={}",
                l -> l.arg(event.getChannel()).arg(sequence).arg(endOfBatch));
        ServerChannelContext scc = event.getServerChannelCtx();
        SocketChannel sc = event.getChannel();
        sc.configureBlocking(false);
        NioServerSession nioSession;
        if (NIO_SESSION_POOLED) {
            nioSession = NIO_SESSION_POOL.borrow();
            if (nioSession == null) {
                Server server = event.getServerChannelCtx().getServer();
                nioSession = server.createNioSession();
            }
        } else {
            Server server = event.getServerChannelCtx().getServer();
            nioSession = server.createNioSession();
        }
        nioSession.setSocketChannel(sc);
        nioSession.setChannelFd(NioServerSession.getValue(sc, "fd"));
        scc.getIoSelectorManager().registerNioSession(nioSession);
    }
}
