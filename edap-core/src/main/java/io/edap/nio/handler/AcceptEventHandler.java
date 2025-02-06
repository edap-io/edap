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
import io.edap.NioSession;
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

    private Pool<NioSession> nioSessionPool;

    public AcceptEventHandler(Server server) {
        if (server.isNioSesionPooled()) {
            nioSessionPool = new ThreadLocalPool();
        }
    }

    @Override
    public void onEvent(AcceptEvent event, long sequence, boolean endOfBatch) throws Exception {
        LOG.debug("event:{}, sequence={}, endOfBatch={}",
                l -> l.arg(event.getChannel()).arg(sequence).arg(endOfBatch));
        ServerChannelContext scc = event.getServerChannelCtx();
        SocketChannel sc = event.getChannel();
        sc.configureBlocking(false);
        Server server = event.getServerChannelCtx().getServer();
        NioSession nioSession;
        if (server.isNioSesionPooled()) {
            nioSession = nioSessionPool.borrow();
            if (nioSession == null) {
                nioSession = server.createNioSession();
            }
        } else {
            nioSession = server.createNioSession();
        }
        nioSession.setSocketChannel(sc);
        scc.getIoSelectorManager().registerNioSession(nioSession);
    }
}
