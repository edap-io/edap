/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.util;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author: louis.lu
 * @date : 2019-07-09 17:59
 */
public class ThreadUtil {
    private ThreadUtil() {}

    public static ThreadPoolExecutor createThreadPoolExecutor(final int queueSize,
                                                               final String threadName,
                                                               ThreadFactory threadFactory,
                                                               final RejectedExecutionHandler policy)
    {
        if (threadFactory == null) {
            threadFactory = new DefaultThreadFactory(threadName, true);
        }

        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1 /*core*/,
                1 /*max*/, 5 /*keepalive*/, SECONDS,
                queue, threadFactory, policy);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static ThreadPoolExecutor createThreadPoolExecutor(final int workerMinCount,
                                                              final int workerMaxCount,
                                                              final int queueSize,
                                                              final String threadName,
                                                              ThreadFactory threadFactory,
                                                              final RejectedExecutionHandler policy)
    {
        if (threadFactory == null) {
            threadFactory = new DefaultThreadFactory(threadName, true);
        }
    System.out.println("workerMinCount=" + workerMinCount + ",workerMaxCount=" + workerMaxCount);
        LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueSize);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(workerMinCount /*core*/,
                workerMaxCount /*max*/, 5 /*keepalive*/, SECONDS,
                queue, threadFactory, policy);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static final class DefaultThreadFactory implements ThreadFactory {

        private final String threadName;
        private final boolean daemon;

        public DefaultThreadFactory(String threadName, boolean daemon) {
            this.threadName = threadName;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(daemon);
            return thread;
        }
    }
}
