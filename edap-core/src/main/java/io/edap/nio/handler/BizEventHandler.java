/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.nio.handler;

import com.lmax.disruptor.EventHandler;
import io.edap.NioSession;
import io.edap.ParseResult;
import io.edap.Server;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.event.BizEvent;
import io.edap.util.CollectionUtils;

import java.util.List;

public class BizEventHandler implements EventHandler<BizEvent>  {

    Logger LOG = LoggerManager.getLogger(BizEventHandler.class);

    public BizEventHandler(Server server) {

    }

    @Override
    public void onEvent(BizEvent event, long sequence, boolean endOfBatch) throws Exception {
        ParseResult pr = event.getBizData();
        LOG.info("event bizData: {}", l -> l.arg(pr.getMessages().size()));
        List<Object> objs = pr.getMessages();
        if (!CollectionUtils.isEmpty(objs)) {
            NioSession nioSession = event.getNioSession();
            for (int i=0;i<objs.size();i++) {
                nioSession.handle(objs.get(i));
            }
        }
    }
}
