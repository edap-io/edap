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

package io.edap.http.server;

import io.edap.Decoder;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.http.HttpHandler;
import io.edap.http.HttpNioSession;
import io.edap.http.HttpRequest;
import io.edap.http.PathInfo;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.handler.NotFoundHandler;
import io.edap.http.server.handler.NotSupportMethodHandler;
import io.edap.http.server.rangedecoder.PathDecoder;
import io.edap.nio.codec.FastBufDataRange;

import java.util.Collections;
import java.util.Map;

/**
 */
public class HttpServer extends Server {

    private static WebsocketDecoder WEBSOCKET_DECODER = new WebsocketDecoder();

    static final HttpHandler NOT_SUPPORT_METHO_HANDLER = new NotSupportMethodHandler();
    static final HttpHandler NOT_FOUND_HANDLER = new NotFoundHandler();

    public enum DecoderType {
        NORMAL,
        FAST
    }

    private DecoderType decoderType = DecoderType.NORMAL;

    private static Boolean HEADER_KEY_LOWER_CASE = null;

    /**
     * <p><b>为什么用 volatile Map 而不是 AtomicReference</b>：替换发生在 appLock 内持锁，
     * 不需要 CAS；dispatch 是纯读，volatile load 在 x86 上是单条 load + 编译器 fence，
     * ~1-2ns，无锁无 context switch。Map 替换后旧 map 不可变、reader 要么看到旧要么看到新，
     * 绝不会看到「Map 部分更新」中间态。
     * per-HttpServer 的 PathInfoMatcher —— 持有独立 cache（精确路径）+ routers（通配符路由）。
     * 多 Container 隔离：deploy / undeploy / switchVersion 不会污染其它 Container 的路由表。
     * 旧实现是 static 单例（{@code PathInfoMatcher.instance()} + {@code PathCache.instance()}），
     * 全 JVM 共享，多 Container 互踩。
     */
    private volatile PathInfoMatcher pathInfoMatcher;

    /**
     * per-HttpServer 的 PathDecoder —— 注入 {@link #pathInfoMatcher}。
     * decode() 直接调 {@code pathInfoMatcher.match(dataRange)}，不走 session.getServer() 链。
     */
    private final PathDecoder pathDecoder;

    /**
     * per-HttpServer 的 RangeHttpRequestDecoder —— 注入 {@link #pathDecoder}。
     * 所有 session 共享同一实例（无 per-session 状态），省 GC。
     */
    private final Decoder<HttpRequest, HttpNioSession> requestDecoder;

    /**
     * 默认构造：自建空 PathInfoMatcher。生产路径一般走 {@link #HttpServer(PathInfoMatcher)}，
     * 由 {@link HttpServerBuilder} 在 build() 时注入（HttpServerBuilder 把 wildcard 注册到自己的
     * PathInfoMatcher 上，build() 再把这个 matcher 转交给 HttpServer，保证 wildcard 路由生效）。
     */
    public HttpServer() {
        this.pathDecoder = new PathDecoder();
        this.requestDecoder = new RangeHttpRequestDecoder(pathDecoder);
    }

    public void setDecoderType(DecoderType decoderType) {
        this.decoderType = decoderType;
    }

    /**
     * 整张替换 path → handler 映射。调用方：Container.rebuildHttpMapping()。
     * 写路径在 appLock 内串行；无需再加锁。
     *
     * <p>同步更新 {@link #pathInfoMatcher} 的 cache —— PathDecoder 走 pathInfoMatcher.match()，
     * dispatch 热路径一气呵成（cache 命中直接返回 PathInfo，不绕路）。</p>
     *
     * @param pathInfoMatcher 新的PATH匹配实例。
     */
    public void setPathInfoMatcher(PathInfoMatcher pathInfoMatcher) {
        this.pathInfoMatcher = pathInfoMatcher;
        this.pathDecoder.setPathInfoMatcher(pathInfoMatcher);
    }

    /**
     * dispatch 入口：根据 HTTP path 查 handler。供 RangeHttpRequestDecoder（或其下游）
     * 在 NIO 解析完成后调用。
     *
     * <p>返回 null 表示 404——由调用方兜底（返回 NotFoundHandler）。</p>
     */
    public PathInfo lookup(HttpFastBufDataRange path) {
        if (pathInfoMatcher == null) {
            return null;
        }
        return pathInfoMatcher.match(path);
    }

    @Override
    public void init() {
        super.init();
        setDecoder(requestDecoder);
        System.out.println("HttpDecoder's type: " + decoderType);
    }

    @Override
    public NioServerSession createNioSession() {
        HttpServerNioSession nioSession = new HttpServerNioSession();
        nioSession.setServer(this);
        nioSession.setDecoder(requestDecoder);
        nioSession.setWsDecoder(WEBSOCKET_DECODER);
        nioSession.setBufPool(getBufPool());
        return nioSession;
    }
}
