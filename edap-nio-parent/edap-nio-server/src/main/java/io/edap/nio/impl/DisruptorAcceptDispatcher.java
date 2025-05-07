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

package io.edap.nio.impl;

import io.edap.Server;
import io.edap.ServerChannelContext;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.AcceptDispatcher;
import io.edap.nio.DisruptorManager;
import io.edap.nio.event.AcceptEvent;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class DisruptorAcceptDispatcher implements AcceptDispatcher {

    private static final Logger LOG = LoggerManager.getLogger(DisruptorAcceptDispatcher.class);

    private DisruptorManager<AcceptEvent> disruptorManager;
    private Server                        server;

    public DisruptorAcceptDispatcher(Server server, DisruptorManager<AcceptEvent> disruptorManager) {
        this.server           = server;
        this.disruptorManager = disruptorManager;
    }

    @Override
    public void dispatch(SelectionKey acceptKey) {
        LOG.info("selectKey {}", l -> l.arg(acceptKey));
        SocketChannel clientChan;
        try {
            clientChan = ((ServerSocketChannel)acceptKey.channel()).accept();
            boolean published = disruptorManager.publishEvent(null, (event, sequence)
                    -> event.setChannel(clientChan)
                    .setServerChannelCtx((ServerChannelContext) acceptKey.attachment()));
            LOG.debug("published {}", l-> l.arg(published));
        } catch (IOException e) {
            LOG.warn("accept error", e);
        }

    }

}
