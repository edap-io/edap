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
import io.edap.container.mw.DeployManager;
import io.edap.http.server.HttpServerBuilder;

import java.io.IOException;
import java.lang.reflect.Method;

import static io.edap.container.HttpConvertorFactory.createGetHandler;
import static io.edap.util.ClazzUtil.getClassMethod;

/**
 * edap微服务容器的启动程序，容器启动包含容器的管理接口以及部署接口
 */
public class Bootstrap {

    public static void main(String[] args) {
        // 创建Edap容器管理的容器对象
        Edap manager = new Edap();
        ServerGroup serverGroup = new ServerGroup();
        HttpServerBuilder builder = new HttpServerBuilder();
        DeployManager deployManager = new DeployManager();
        builder.listen(1111);
        Method[] ms = DeployManager.class.getDeclaredMethods();
        for (Method m : ms) {
            System.out.println("method: " + m.getName());
        }
        builder.get("/microServiceList", createGetHandler(
                getClassMethod(DeployManager.class, "deployMicroService", String.class, String.class),
                deployManager));

        serverGroup.addServer(builder.build());
        serverGroup.setName("deploy-manager");
        manager.addServerGroup(serverGroup);

        try {
            manager.run();
        } catch (IOException e) {
            System.err.println("启动失败\n" + e.getMessage());
            e.printStackTrace();
        }
    }
}
