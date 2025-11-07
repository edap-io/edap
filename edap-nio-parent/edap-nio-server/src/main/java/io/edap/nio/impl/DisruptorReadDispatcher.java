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

import io.edap.Decoder;
import io.edap.NioServerSession;
import io.edap.nio.ParseResult;
import io.edap.Server;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.DisruptorManager;
import io.edap.nio.ReadDispatcher;
import io.edap.nio.event.BizEvent;
import io.edap.pool.Pool;
import io.edap.pool.impl.ThreadLocalPool;
import io.edap.util.EdapTime;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static io.edap.nio.NioSession.THREAD_WRITE_BUF;
import static io.edap.nio.util.NetUtil.getRemoteAddress;

public class DisruptorReadDispatcher implements ReadDispatcher {

    static Logger LOG = LoggerManager.getLogger(DisruptorReadDispatcher.class);

    private Pool<FastBuf> bbPool;

    private Decoder       decoder;
    private Server        server;
	private FastBuf       readBuf;

    private DisruptorManager<BizEvent> disruptorManager;

    private static boolean  NIO_SESSION_POOLED;
    private static EdapTime EDAP_TIME          = EdapTime.instance();


    public DisruptorReadDispatcher(Server server, DisruptorManager<BizEvent> disruptorManager) {
        this.server           = server;
        this.bbPool           = new ThreadLocalPool<>();
        this.decoder          = server.getDecoder();
        this.disruptorManager = disruptorManager;

        NIO_SESSION_POOLED = server.isNioSesionPooled();
    }

    @Override
    public void dispatch(SelectionKey readKey) {
        NioServerSession nioSession = (NioServerSession)readKey.attachment();
		if (readKey.isReadable()) {
			FastBuf buf = readBuf;
			if (buf == null) {
				readBuf = new FastBuf(16384);
				buf = readBuf;
			}
			try {
				buf.reset();
				int len = nioSession.fastRead(buf);
				if (len < 0) {
					closeChannel(readKey, nioSession);
				} else {
					nioSession.setLastReadTime(EDAP_TIME.currentTimeMillis());
					FastBuf writeBuf = THREAD_WRITE_BUF.get();
					writeBuf.clear();
					while (buf.remain() > 0) {
						ParseResult pr = decoder.decode(buf, nioSession);
						if (!pr.isFinished()) {
							break;
						}
						nioSession.handle(pr.getMessage());
//                    boolean published;
//                    if (nioSession.isAffinityThread()) {
//                        published = disruptorManager.publishEvent(nioSession, (event, sequence) -> {
//                            event.setNioSession(nioSession);
//                            event.setServerChannelContext(nioSession.getServerChannelContext());
//                            event.setBizData(pr);
//                            nioSession.setLastSequence(sequence);
//                        });
//                    } else {
//                        published = disruptorManager.publishEvent(null, (event, sequence) -> {
//                            event.setNioSession(nioSession);
//                            event.setServerChannelContext(nioSession.getServerChannelContext());
//                            event.setBizData(pr);
//                        });
//                    }
//                    LOG.trace("DisruptorManager published {}", l-> l.arg(published));
					}
					FastBuf wbuf = nioSession.getWriteBuf();
					if (wbuf == null) {
						if (!nioSession.writeToChannel(writeBuf)) {
							nioSession.putToWriteQueue(writeBuf);
							FastBuf nbuf = new FastBuf(16384);
							THREAD_WRITE_BUF.set(nbuf);
							nioSession.setSelectionKey(nioSession.getSocketChannel()
									.register(nioSession.getSelector(), SelectionKey.OP_WRITE, nioSession));
						}
					} else {
						if (nioSession.writeToChannel(wbuf)) {
							nioSession.removeWriteBuf(wbuf);
							SelectionKey wkey  = nioSession.getSelectionKey();
							if (wkey != null && wkey.isWritable()) {
								wkey.cancel();
								nioSession.setSelectionKey(null);
							}
						}
					}
				}
			} catch (IOException e) {
				closeChannel(readKey, nioSession);
				LOG.warn("channel {} read error ", l -> l.arg(getRemoteAddress(readKey.channel())).arg(e));
			} finally {
//            if (buf != null) {
//                bbPool.requite(buf);
//            }
			}
		} else if (readKey.isWritable()) {
			FastBuf wbuf = nioSession.getWriteBuf();
			try {
				if (wbuf != null) {
					if (nioSession.writeToChannel(wbuf)) {
						nioSession.removeWriteBuf(wbuf);
					}
				}
			} catch (IOException e) {
				closeChannel(readKey, nioSession);
				LOG.warn("channel {} read error ", l -> l.arg(getRemoteAddress(readKey.channel())).arg(e));
			}
		}
    }

    private void closeChannel(SelectionKey readKey, NioServerSession nioSession) {
        SocketChannel channel = (SocketChannel)readKey.channel();
        try {
            SocketAddress remoteAddr = channel.getRemoteAddress();
            readKey.cancel();
            channel.close();
            if (remoteAddr != null) {
                LOG.info("channel {} closed", l -> l.arg(remoteAddr));
            } else {
                LOG.info("channel {} closed", l -> l.arg(channel));
            }
        } catch (IOException e) {
            LOG.warn("channel {} close error", l -> l.arg(channel).threw(e));
        } finally {
            if (nioSession != null && NIO_SESSION_POOLED) {
                server.getNioSessionPool().requite(nioSession);
            }
        }
    }
}
