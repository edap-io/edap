/*
 * Copyright 2023 The edap Project
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package io.edap.nio;

import io.edap.NioSession;

/**
 * 负责读写的Selector的管理器，可以针对连接数的量级采取不同的策略进行管理。
 */
public interface ReadWriteSelectorManager {

    /**
     * 项目Selector的管理器中注册一个新的NioSession的客户端连接
     * @param nioSession
     */
    void registerNioSession(NioSession nioSession);

    /**
     * 设置IO读事件的分发器实例
     * @param readDispatcher
     */
    void setReadDispatcher(ReadDispatcher readDispatcher);

    void run();

    void stop();
}
