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

package io.edap.util.timing;

/**
 * 定时任务句柄。
 *
 * <p>取消是 O(1) 的：只置一个标志位，任务被轮子取出时二次确认。
 * 不维护 "任务 → 桶" 的反向索引 —— 那个索引的维护成本高于收益，
 * 且在 MPSC 队列上"摘除"本身就不是 O(1)。</p>
 */
public interface TimerHandle {

    /**
     * 取消任务。
     *
     * @return true 表示本次调用把任务从"待执行"转成了"已取消"；
     *         已经投递（甚至已开始执行）的任务返回 false
     */
    boolean cancel();

    /**
     * @return 任务是否已被取消
     */
    boolean isCancelled();

    /**
     * @return 到期时刻的墙钟毫秒快照（注册时刻换算），仅供展示
     */
    long deadlineWallMillis();
}
