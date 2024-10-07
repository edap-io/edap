/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.container;

import io.edap.Edap;
import io.edap.ServerGroup;
import io.edap.http.HttpServerBuilder;

import java.io.IOException;

/**
 * edap微服务容器的启动程序，容器启动包含容器的管理接口以及部署接口
 */
public class Bootstrap {

    public static void main(String[] args) {
        // 创建Edap容器管理的容器对象
        Edap manager = new Edap();
        ServerGroup serverGroup = new ServerGroup();
        HttpServerBuilder builder = new HttpServerBuilder();
        builder.listen(8080).listen(8081);
        serverGroup.addServer(builder.build());
        serverGroup.setName("edap-manager");
        manager.addServerGroup(serverGroup);

        try {
            manager.run();
        } catch (IOException e) {
            System.err.println("启动失败\n" + e.getMessage());
            e.printStackTrace();
        }
    }
}
