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

import com.lmax.disruptor.*;
import io.edap.*;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ReadDispatcher;
import io.edap.nio.event.BizEvent;
import io.edap.pool.Pool;
import io.edap.pool.impl.ThreadLocalPool;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static io.edap.util.NetUtil.getRemoteAddress;

public class DisruptorReadDispatcher implements ReadDispatcher {

    static Logger LOG = LoggerManager.getLogger(DisruptorReadDispatcher.class);

    private Pool<FastBuf> bbPool;

    private Decoder decoder;
    private Server  server;

    private RingBuffer<BizEvent>[] ringBuffers;

    private volatile int queueSize;

    private int seq = 0;


    public DisruptorReadDispatcher(Server server, RingBuffer<BizEvent>[] ringBuffers) {
        this.server      = server;
        this.bbPool      = new ThreadLocalPool<>();
        this.decoder     = server.getDecoder();
        this.ringBuffers = ringBuffers;
        this.queueSize   = ringBuffers.length;
    }

    @Override
    public void dispatch(SelectionKey readKey) {
        NioSession nioSession = (NioSession)readKey.attachment();
        FastBuf buf = bbPool.borrow();
        if (buf == null) {
            buf = new FastBuf(4096);
        }
        ParseResult pr;
        try {
            buf.reset();
            int len = nioSession.fastRead(buf);
            if (len < 0) {
                closeChannel(readKey, nioSession);
            } else {
                pr = decoder.decode(buf, nioSession);
                if (pr.isFinished()) {
                    int index = seq++%queueSize;
                    boolean published = ringBuffers[index].tryPublishEvent(
                            (event, sequence) -> {
                                event.setNioSession(nioSession);
                                event.setServerChannelContext(nioSession.getServerChannelContext());
                                event.setBizData(pr);
                            });
                    LOG.debug("ringbuffer:{} published {}", l-> l.arg(index).arg(published));
                }
            }
        } catch (IOException e) {
            LOG.warn("channel {} read error ", l -> l.arg(getRemoteAddress(readKey.channel())).arg(e));
        } finally {
            if (buf != null) {
                bbPool.requite(buf);
            }
        }
        LOG.debug("SelectionKey {}", l -> l.arg(readKey));
    }

    private void closeChannel(SelectionKey readKey, NioSession nioSession) {
        SocketChannel channel = (SocketChannel)readKey.channel();
        try {
            String remoteAddr = channel.getRemoteAddress().toString();
            readKey.cancel();
            readKey.channel().close();
            LOG.info("channel {} closed", l -> l.arg(remoteAddr));
        } catch (IOException e) {
            LOG.info("channel {} close error", l -> l.arg(channel));
        } finally {
            if (nioSession != null && server != null && server.isNioSesionPooled()) {
                server.getNioSessionPool().requite(nioSession);
            }
        }
    }
}
