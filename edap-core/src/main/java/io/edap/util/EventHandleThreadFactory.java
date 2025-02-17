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

package io.edap.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class EventHandleThreadFactory implements ThreadFactory {

    private String threadName;

    private static final Map<String, AtomicInteger> THREAD_SEQ_MAP = new ConcurrentHashMap<>();

    public EventHandleThreadFactory(String threadName) {
        this.threadName = threadName;
    }

    @Override
    public Thread newThread(Runnable r) {
        AtomicInteger threadSeq = THREAD_SEQ_MAP.get(threadName);
        if (threadSeq == null) {
            threadSeq = new AtomicInteger();
            AtomicInteger oldSeq = THREAD_SEQ_MAP.putIfAbsent(threadName, threadSeq);
            if (oldSeq != null) {
                threadSeq = oldSeq;
            }
        }
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName(threadName  + "-" + threadSeq.getAndAdd(1));
        return t;
    }
}
