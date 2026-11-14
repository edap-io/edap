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
import io.edap.Server;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.DisruptorManager;
import io.edap.nio.NioSession;
import io.edap.nio.NioSessionCloser;
import io.edap.nio.ReadDispatcher;
import io.edap.nio.event.BizEvent;
import io.edap.pool.Pool;
import io.edap.pool.impl.ArrayBlockingQueueMpscPool;
import io.edap.util.EdapTime;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import static io.edap.nio.NioSession.THREAD_WRITE_BUF;
import static io.edap.nio.util.NetUtil.getRemoteAddress;

public class DisruptorReadDispatcher implements ReadDispatcher {

    static Logger LOG = LoggerManager.getLogger(DisruptorReadDispatcher.class);

    private Pool<FastBuf> bbPool;

    private Server        server;
	private FastBuf       readBuf;

    private DisruptorManager<BizEvent> disruptorManager;

    private static EdapTime EDAP_TIME          = EdapTime.instance();


    public DisruptorReadDispatcher(Server server, DisruptorManager<BizEvent> disruptorManager) {
        this.server           = server;
        // ★ 写缓冲池必须线程安全：HTTP/2 的写缓冲可能在 IO 线程借出、
        //   在业务线程归还（服务端流式在别的线程推消息），无锁数组实现会互相踩。
        this.bbPool           = new ArrayBlockingQueueMpscPool<>();
        this.disruptorManager = disruptorManager;

        for (int i = 0;i < 8; i++) {
            bbPool.requite(new FastBuf(NioSession.WRITE_BUFFER_SIZE));
        }
    }

    @Override
    public void dispatch(SelectionKey readKey) {
        NioServerSession nioSession = (NioServerSession)readKey.attachment();
		if (readKey.isReadable()) {
			FastBuf buf = readBuf;
			if (buf == null) {
				// 读缓冲用 NioSession.READ_BUFFER_SIZE（默认 256KB）：必须装得下
				// HTTP/2 最大帧（16384 载荷 + 9 字节帧头 = 16393），否则每个帧跨两次读、
				// 入站累积缓冲每次整体搬移；且一次读能带回更多请求，响应才能批量成一次 socket 写。
				readBuf = new FastBuf(NioSession.READ_BUFFER_SIZE);
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
                    if (!nioSession.decode(buf, false)) {
						return;
					}
                    nioSession.flush(bbPool);
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
                        bbPool.requite(wbuf);
                    }
                }
			} catch (IOException e) {
				closeChannel(readKey, nioSession);
				LOG.warn("channel {} read error ", l -> l.arg(getRemoteAddress(readKey.channel())).arg(e));
			}
		}
    }

    private void closeChannel(SelectionKey readKey, NioServerSession nioSession) {
        NioSessionCloser.close(server, readKey, nioSession);
    }
}
