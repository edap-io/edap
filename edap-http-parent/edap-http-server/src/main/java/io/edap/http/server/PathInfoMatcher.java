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

package io.edap.http.server;

import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.handler.NotFoundHandler;
import io.edap.http.server.pathrouters.PostfixWildcardPathRouter;
import io.edap.http.server.pathrouters.PrefixWildcardPathRouter;
import io.edap.nio.codec.FastBufDataRange;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class PathInfoMatcher {

    /**
     * 静态 NOT_FOUND_PATH 复用 —— 整个 JVM 共享一个，所有 PathInfoMatcher miss 时统一返回。
     * 内部 {@code found=false} + NotFoundHandler，dispatch 链路兜底用。
     */
    public static final PathInfo NOT_FOUND_PATH;
    static {
        NOT_FOUND_PATH = new PathInfo();
        NOT_FOUND_PATH.setFound(false);
        NOT_FOUND_PATH.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});
    }

    /**
     * 精确路径映射（path → PathInfo）。由 {@link HttpServer#setHttpMapping} 喂入，
     * 整张替换；dispatch 热路径通过 volatile load 读取（~1-2ns，无锁无 context switch）。
     * Map 替换后旧 map 不可变、reader 要么看到旧要么看到新，
     * 绝不会看到「Map 部分更新」中间态。
     *
     * <p>旧实现是 {@code static PathCache CACHE = PathCache.instance()} —— 全 JVM 单例。
     * 多 Container 互踩（deploy / undeploy / switchVersion 都会被其它 Container 看到）。
     * 改成 per-instance 后，每 HttpServer 独立 mapping，多 Container 隔离。</p>
     */
    private volatile Map<FastBufDataRange, PathInfo> cacheRef = Collections.emptyMap();

    /**
     * 通配符路由列表（prefix / postfix wildcard）—— per-instance，与 cache 同样隔离维度。
     * 内部不放具体 PathInfo，业务路径都是精确路径，routers 实际为空；保留结构以备后续通配符路由扩展。
     */
    private final List<PathRouter> routers = new CopyOnWriteArrayList<>();
    private final PostfixWildcardPathRouter postfixRouter = new PostfixWildcardPathRouter();
    private final PrefixWildcardPathRouter  prefixRouter  = new PrefixWildcardPathRouter();

    public PathInfoMatcher() {
        routers.add(prefixRouter);
        routers.add(postfixRouter);
    }

    /**
     * 整张替换 cache。调用方：{@link HttpServer#setHttpMapping}，与 httpMapping 字段同步写入。
     * 写路径在 appLock 内串行；无需再加锁 / CAS，volatile store 即可。
     *
     * @param newMap 新全集；null 视为空映射（清空）
     */
    public void setCache(Map<FastBufDataRange, PathInfo> newMap) {
        this.cacheRef = newMap == null ? Collections.emptyMap() : newMap;
    }

    /**
     * 精确路径 → PathInfo 查表。dispatch 热路径首选（~5ns = volatile load + HashMap.get）。
     * 命中直接返回；miss 走 routers（通配符路由）。
     */
    public PathInfo match(HttpFastBufDataRange dataRange) {
        PathInfo pathInfo = cacheRef.get(dataRange);
        if (pathInfo != null) {
            return pathInfo;
        }
        int size = routers.size();
        PathInfo pi;
        if (size == 0) {
            return NOT_FOUND_PATH;
        }
        String path = dataRange.getString(StandardCharsets.UTF_8);
        for (int i=0;i<size;i++) {
            PathRouter pr = routers.get(i);
            pi = pr.route(path);
            if (pi != null) {
                return pi;
            }
        }
        pathInfo = new PathInfo();
        pathInfo.setPath(path);
        pathInfo.setMatchPath(null);
        pathInfo.setFound(false);
        return pathInfo;
    }

    public void registerPostfixMatcher(PathInfo pathInfo) {
        pathInfo.setFound(true);
        postfixRouter.registerPathInfo(pathInfo);
    }

    public void registerPrefixMatcher(PathInfo pathInfo) {
        pathInfo.setFound(true);
        prefixRouter.registerPathInfo(pathInfo);
    }

    public PathInfo match(String path) {
        // String 重载：在 routers 链里查（精确路径在 cacheRef 里，不走 String 重载避免重复 hash）
        int size = routers.size();
        PathInfo pi;
        if (size == 0) {
            return NOT_FOUND_PATH;
        }
        for (int i=0;i<size;i++) {
            PathRouter pr = routers.get(i);
            pi = pr.route(path);
            if (pi != null) {
                return pi;
            }
        }
        PathInfo pathInfo = new PathInfo();
        pathInfo.setPath(path);
        pathInfo.setMatchPath(null);
        pathInfo.setFound(false);
        return pathInfo;
    }
}
