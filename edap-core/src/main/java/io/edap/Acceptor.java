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

package io.edap;

import io.edap.nio.SelectorProvider;

import java.nio.channels.Selector;
import java.util.List;

/**
 * 网络连接事件的处理器
 */
public interface Acceptor {

    /**
     * 该Acceptor实例是否支持Selector的类型
     * @param selectorProvider
     * @return
     */
    boolean isEnable(SelectorProvider selectorProvider);

    /**
     * 开始接受网络连接
     */
    void accept();

    /**
     * 设置该网络连接处理器所属的服务器实例
     * @param server
     */
    void setServer(Server server);

    Server getServer();

    /**
     * 添加监听的列表
     */
    void addAddrs(List<Server.Addr> addrs);

    void stop();
}
