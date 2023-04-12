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

/**
 * 服务器端SocketChannel中附加的上下文信息，在服务器启动时有容器添加到ServerSocketChannel
 * 的selectorKey的attachment中
 * @author: louis.lu
 * @date : 2019-07-08 12:04
 */
public class ServerChannelContext {

    private Server server;

    public Server getServer() {
        return this.server;
    }

    public void setServer(Server server) {
        this.server = server;
    }
}

