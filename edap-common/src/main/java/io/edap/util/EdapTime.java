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

import java.util.List;
import java.util.concurrent.*;

/**
 * 毫秒级快速的时间提供器，利用每毫秒的定时器来定时更新当前的时间戳，来减少
 * "System.currentTimeMillis()"的调用，减少用户态和内核态的转换加快返回当前时间戳的速度。
 * @author louis
 * @date 2019-07-06 16:35
 */
public class EdapTime {

    /**
     * 时间的回调函数
     */
    @FunctionalInterface
    public interface TimeCallback {
        void setTimeMillis(long millis);
    }

    /**
     * 当前的时间戳
     */
    private volatile long current;
    /**
     * 当前秒的时间戳
     */
    private volatile long curSecond;
    /**
     * 定时任务的执行器服务
     */
    private final ScheduledExecutorService schService;

    private final ScheduledFuture scheduledFuture;

    private final List<TimeCallback> secondCallbacks;
    private final List<TimeCallback> millisSecoundCallbacks;

    private EdapTime() {
        secondCallbacks = new CopyOnWriteArrayList<>();
        millisSecoundCallbacks = new CopyOnWriteArrayList<>();
        setCurrent(System.currentTimeMillis());
        schService = Executors.newScheduledThreadPool(2, r -> new Thread(r, "Edap_time_thread"));
        //尽量在每毫秒的开始进行定时任务

        long time = current;
        while (current == time) {
            time = System.currentTimeMillis();
        }
        scheduledFuture = schService.scheduleAtFixedRate(() -> {
            setCurrent(System.currentTimeMillis());
        }, 0, 1000_000, TimeUnit.NANOSECONDS);
        setCurrent(time);
    }

    private void setCurrent(long cur) {
        current = cur;
        if (!CollectionUtils.isEmpty(millisSecoundCallbacks)) {
            millisSecoundCallbacks.forEach(c -> c.setTimeMillis(cur));
        }
        long tmpSecond = cur / 1000;
        if (tmpSecond != curSecond) {
            setSecond(cur);
            curSecond = tmpSecond;
        }
    }

    private void setSecond(long cur) {
        if (!CollectionUtils.isEmpty(secondCallbacks)) {
            secondCallbacks.forEach(c -> c.setTimeMillis(cur));
        }
    }

    public long currentTimeMillis() {
        return current;
    }

    public long nanoTime() {
        return System.nanoTime();
    }

    public void addCallback(TimeCallback callback, long initialDelay, long delay, TimeUnit unit) {
        switch (unit) {
            case NANOSECONDS:
            case MICROSECONDS:
            case MILLISECONDS:
                if (!millisSecoundCallbacks.contains(callback)) {
                    millisSecoundCallbacks.add(callback);
                }
                break;
            case SECONDS:
                if (!secondCallbacks.contains(callback)) {
                    secondCallbacks.add(callback);
                }
                break;
            case MINUTES:
                break;
            case HOURS:
                break;
            case DAYS:
                break;
            default:
                break;
        }
    }

    public static final EdapTime instance() {
        return SingletonHolder.INSTANCE;
    }

    public void shutdown() {
        if (null != scheduledFuture) {
            scheduledFuture.cancel(true);
        }
        if (null != schService) {
            schService.shutdown();
        }
    }

    private static class SingletonHolder {
        private static final EdapTime INSTANCE = new EdapTime();
    }
}
