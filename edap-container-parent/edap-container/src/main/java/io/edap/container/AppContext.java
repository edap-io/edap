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

import io.edap.Server;
import io.edap.container.app.RouterHub;
import io.edap.container.mw.DeployMetaData;

import java.util.Collections;
import java.util.List;

public class AppContext implements Lifecycle {

    private final String             appId;
    private final DeployMetaData     dmd;
    private final EdapAppClassLoader appCL;
    private final Container          container;

    public AppContext(Container container, String appId, String verion, EdapAppClassLoader appCL, DeployMetaData dmd) {
        this.container = container;
        this.appId     = appId;
        this.appCL     = appCL;
        this.dmd       = dmd;
    }

    @Override
    public void start() throws Throwable {

    }

    public DeployMetaData dmd() {
        return dmd;
    }


    public <T> T getBean(String name, Class<T> type) {
        return null;
    }

    public void destroyPartial() {}

    public List<Server> getServers() {
        return Collections.EMPTY_LIST;
    }

    public String version() {

        return null;
    }

    public String appId() {
        return appId;
    }

    /**
     * 路由注册中心（HTTP/WS/eRPC/gRPC 四份 Handler List）。
     * BeanContainer.injectAware 在 RouterHubAware 回调时通过本方法取。
     */
    public RouterHub routers() {
        // 当前 stub 阶段直接返回 null（实际由 RouterHubImpl 在 Phase 2 末段构造并挂在 AppContext 上）
        return null;
    }

    /**
     * 应用 ClassLoader（per-app 隔离，AppContext.stop 时 close）。
     * BeanContainer.appClassLoader() 透传本 CL 给生成 Handler 用（生成 Handler 必须由 appCL 定义）。
     */
    public EdapAppClassLoader appCL() {
        return appCL;
    }

    public Container container() {
        return container;
    }
}
