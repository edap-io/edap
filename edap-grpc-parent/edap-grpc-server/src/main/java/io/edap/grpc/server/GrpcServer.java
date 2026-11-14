/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc.server;

import io.edap.grpc.GrpcMethod;
import io.edap.http.server.HttpServer;
import io.edap.http.server.PathInfoMatcher;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * gRPC 服务端。
 *
 * <p><b>复用 edap 的 HTTP/2 栈</b>：gRPC over HTTP/2 的传输层就是 HTTP/2
 * （RFC 9113），只在语义层加了「消息分帧 + trailers 里的 grpc-status」。
 * 所以这里直接继承 {@link HttpServer}，只是把协议路由器换成 gRPC 的。</p>
 *
 * <p><b>路由表是 {@code path → GrpcMethod} 的普通 map</b>，不复用
 * {@code PathInfoMatcher}：gRPC 的 {@code :path} 是 {@code /pkg.Svc/Method}，
 * 只有精确匹配一种形态，没有通配符；而且 {@link io.edap.grpc.GrpcHandler} 不是
 * {@code HttpHandler}，塞不进 {@code PathInfo.httpHandlers[]} 的槽位。</p>
 *
 * <p>整表替换（{@link #setMethods}）与 HTTP 侧 {@code setPathInfoMatcher} 同构：
 * 写路径由容器在部署/卸载时串行调用，读路径无锁。</p>
 */
public class GrpcServer extends HttpServer {

    /**
     * path → 方法定义。
     *
     * <p>volatile 替换整张表：读者要么看到旧表要么看到新表，不会看到「表被改了一半」
     * 的中间态（{@code HashMap} 的结构修改本身不是线程安全的）。</p>
     */
    private volatile Map<String, GrpcMethod> methods = Collections.emptyMap();

    public GrpcServer() {
        // gRPC 端口上不注册任何 HTTP 路由，但仍然要有一个空的 matcher ——
        // 否则 HTTP/1.1 的请求（比如有人用 curl 打这个端口）会因为 matcher 为 null 而 NPE。
        setPathInfoMatcher(new PathInfoMatcher());
        // ★ 协议路由器必须在 server 启动前挂上 —— Http2Connection 在构造时取一次。
        //   gRPC 的路由表在构造时还是空的也没关系：路由表是之后才被替换的，
        //   路由器每次 select 时都重新读当前表。
        setProtocolRouter(new GrpcProtocolRouter(this));
    }

    /**
     * 查找 gRPC 方法。
     *
     * @param path gRPC 的 {@code :path}
     * @return 方法定义；不存在时返回 null
     */
    public GrpcMethod lookup(String path) {
        return methods.get(path);
    }

    /**
     * 整张替换路由表。
     *
     * @param methods path → 方法定义
     */
    public void setMethods(Map<String, GrpcMethod> methods) {
        this.methods = methods == null || methods.isEmpty()
                ? Collections.emptyMap()
                : new HashMap<>(methods);
    }

    /**
     * @return 当前路由表（只读快照）
     */
    public Map<String, GrpcMethod> getMethods() {
        return methods;
    }
}
