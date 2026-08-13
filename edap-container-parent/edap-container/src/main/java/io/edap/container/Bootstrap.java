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
import io.edap.container.mw.BaseResult;
import io.edap.container.mw.DeployManager;
import io.edap.http.server.HttpServerBuilder;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.File;
import java.io.IOException;

import static io.edap.container.HttpConvertorFactory.createGetHandler;
import static io.edap.launcher.JarLauncher.locateBootJarFile;
import static io.edap.util.ClazzUtil.getClassMethod;

/**
 * edap微服务容器的启动程序，容器启动包含容器的管理接口以及部署接口
 */
public class Bootstrap {

    static Logger log = LoggerManager.getLogger(Bootstrap.class);

    public static void main(String[] args) {
        // 创建Edap容器管理的容器对象
        Edap edap = new Edap();
        ServerGroup managerGroup = new ServerGroup();
        HttpServerBuilder builder = new HttpServerBuilder();
        DeployManager deployManager = new DeployManager();
        deployManager.setEdap(edap);
        builder.listen(1111);
        builder.get("/query_app_list", createGetHandler(
                getClassMethod(DeployManager.class, "queryAppList"),
                deployManager));

        builder.get("/deploy_app", createGetHandler(
                getClassMethod(DeployManager.class, "deployApp", String.class, String.class),
                deployManager));

        managerGroup.addServer(builder.build());
        managerGroup.setName("deploy-manager");
        edap.addServerGroup(managerGroup);

        try {
            log.info("Edap start...");
            edap.run();
            log.info("Edap start finished.");
            log.info("MicroService Container start...");
            File baseDir = locateBootJarFile().getParentFile();
            Container container = new Container(new File(baseDir, "apps"));
            // 注入 Container 给 DeployManager——能力查询（http/ws/erpc/grpc 路由是否启用）
            // 委托给 Container.capabilities()，DeployManager 不再单独解析 NodeType 配置
            deployManager.setContainer(container);
            container.run(edap);
            log.info("MicroService Container start finished.");
        } catch (IOException e) {
            log.error("Edap container start fault!", e);
        }
    }
}
