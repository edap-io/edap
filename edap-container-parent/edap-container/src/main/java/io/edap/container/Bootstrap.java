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
import io.edap.container.raft.ContainerRaftBootstrap;
import io.edap.http.server.HttpServerBuilder;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.props.Props;

import java.io.File;

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
        Props props = edap.getProps();
        int managerPort = props.getInt("container.manager.port", 1111);
        builder.listen(managerPort);
        builder.get("/query_app_list", createGetHandler(
                getClassMethod(DeployManager.class, "queryAppList"),
                deployManager));

        builder.get("/deploy_app", createGetHandler(
                getClassMethod(DeployManager.class, "deployApp", String.class, String.class),
                deployManager));

        builder.get("/online_app", createGetHandler(
                getClassMethod(DeployManager.class, "online", String.class, String.class),
                deployManager));

        // 进程级 raft 的四个管理端点。路由必须在 build() 之前静态注册，
        // 而 raft 节点要等容器启动（container.run）才拉起来 —— 所以这里先建启动器、
        // 把端点挂上，节点本身由 Container.start() 触发。
        // ★ 把根配置传进去：启动器要提前知道本机地址，
        //   这样在「管理端口已监听、raft 节点还没起来」的窗口里，
        //   CLAIM 也能应答「我也在启动」—— 否则同时启动的容器互相看不见，
        //   各自自举 ⇒ 出现两个主。
        ContainerRaftBootstrap raft = new ContainerRaftBootstrap(props);
        builder.post("/raft/claim", raft.claimHandler());
        builder.post("/raft/join", raft.joinHandler());
        builder.get("/raft/cluster", raft.clusterHandler());
        builder.post("/raft/propose", raft.proposeHandler());

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
            // 注入进程级 raft 启动器：容器启动时按 container.raft.* 配置自动成组，
            // 使用者不需要写任何注解。没配 container.raft.enabled 就什么都不做。
            container.setRaftBootstrap(raft);
            // 注入 Container 给 DeployManager——能力查询（http/ws/erpc/grpc 路由是否启用）
            // 委托给 Container.capabilities()，DeployManager 不再单独解析 NodeType 配置
            deployManager.setContainer(container);
            container.run(edap);
            log.info("MicroService Container start finished.");
        } catch (Throwable e) {
            // ★ 启动失败 ⇒ 进程退出（非 0），不能「打个日志继续跑」。
            //   典型场景：container.http.tls.* 配了但证书读不出来 —— 端口一个都没 bind，
            //   进程却还活着（edap 的 NIO 线程是非守护线程，main 抛异常并不会让 JVM 退出），
            //   运维看到的现象是「进程在、端口不通」，最难排查。
            //   走 System.exit ⇒ 触发 edap 的 shutdown hook ⇒ 正常收尾。
            log.error("Edap container start fault!", e);
            System.exit(1);
        }
    }


}
