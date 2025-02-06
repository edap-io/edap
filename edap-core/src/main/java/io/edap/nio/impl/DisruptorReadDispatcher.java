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

import io.edap.NioSession;
import io.edap.Server;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.ReadDispatcher;
import io.edap.pool.MpscPool;
import io.edap.pool.Pool;
import io.edap.pool.impl.ArrayBlockingQueueMpscPool;
import io.edap.pool.impl.ThreadLocalPool;

import java.io.IOException;
import java.nio.channels.SelectionKey;

public class DisruptorReadDispatcher implements ReadDispatcher {

    static Logger LOG = LoggerManager.getLogger(DisruptorReadDispatcher.class);

    private Pool<FastBuf> bbPool;

    private MpscPool reqPool;
    private boolean reqMsgPooled;

    public DisruptorReadDispatcher(Server server) {
        bbPool  = new ThreadLocalPool<>();
        reqPool = null;
        if (server != null && server.isReqMsgPooled()) {
            reqPool = new ArrayBlockingQueueMpscPool();
            reqMsgPooled = true;
        } else {
            reqPool = null;
            reqMsgPooled = false;
        }
    }

    @Override
    public void dispatch(SelectionKey readKey) {
        NioSession nioSession = (NioSession)readKey.attachment();
        FastBuf buf = bbPool.borrow();
        try {
            int len = nioSession.fastRead(buf);
            if (len < 0) {

            } else {
                if (reqMsgPooled) {

                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        LOG.debug("SelectionKey {}", l -> l.arg(readKey));
    }
}
