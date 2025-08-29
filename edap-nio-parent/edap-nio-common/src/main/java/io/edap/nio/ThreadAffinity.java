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

package io.edap.nio;

/**
 * 线程亲和性接口，继承该接口的NioSession被分配到一个线程后，直到该队列中没有被一个线程正在处理才会被分配到其他线程。
 */
public interface ThreadAffinity {
    /**
     * 是否想具备线程亲和性
     * @return
     */
    boolean isAffinityThread();

    /**
     * 获取线程数组的下标，方便分配到指定线程
     * @return
     */
    int getThreadIndex();

    /**
     * 设置线程数组的下标
     * @param threadIndex
     */
    void setThreadIndex(int threadIndex);

    /**
     * 设置disruptor队列消息的唯一序号
     * @param sequence
     */
    void setLastSequence(long sequence);
}
