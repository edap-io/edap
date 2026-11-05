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

import io.edap.BufPool;
import io.edap.http.HttpHandleOption;
import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.WSHandler;
import io.edap.http.cache.MethodCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.handler.FaviconHandler;
import io.edap.http.server.tls.TlsConfig;
import io.edap.http.server.tls.TlsContext;
import io.edap.http.server.tls.TlsHttpServer;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.http.HttpHandleOption.defaultHttpHandleOption;

/**
 */
public class HttpServerBuilder {

    List<String> addrs = new ArrayList<>();

    /**
     * TLS 监听的地址列表（{@code "host:port"}）。
     */
    List<String> tlsAddrs = new ArrayList<>();

    /**
     * TLS 配置（null ⇒ 从系统属性装配）。
     */
    private TlsConfig tlsConfig;

    /**
     * HttpServerBuilder 自己持有的 PathInfoMatcher —— wildcard 注册（prefix/postfix *）
     * 都打到这个实例上，build() 时通过 {@code new HttpServer(pathInfoMatcher)} 转交给 HttpServer，
     * 保证 dispatch 链（HttpServer.pathInfoMatcher → PathDecoder）能命中 wildcard。
     * 旧实现走 {@code PathInfoMatcher.instance()} 静态单例，全 JVM 共享，多 Container 互踩。
     */
    private final PathInfoMatcher pathInfoMatcher = new PathInfoMatcher();

    HttpServer.DecoderType decoderType;

    private Map<String, PathInfo> mapping = new HashMap<>();

    public HttpServerBuilder listen(int... ports) {
        if (ports == null) {
            throw new RuntimeException("listen must not null");
        }
        for (int i=0;i<ports.length;i++) {
            listen("", ports[i]);
        }
        return this;
    }

    public HttpServerBuilder listen(String address, int port) {
        String addr = address + ":" + port;
        if (!addrs.contains(addr) && !addrs.contains(":" + port)) {
            addrs.add(addr);
        }
        return this;
    }

    /**
     * 监听一个 TLS 端口。
     *
     * <p>★ 明文与 TLS <b>分端口</b>：不做同端口 mux。</p>
     *
     * @see #buildTls()
     * @see #buildAll()
     */
    public HttpServerBuilder listenTls(int port) {
        return listenTls("", port);
    }

    /**
     * 监听一个 TLS 端口（指定地址）。
     */
    public HttpServerBuilder listenTls(String address, int port) {
        String addr = address + ":" + port;
        if (!tlsAddrs.contains(addr) && !tlsAddrs.contains(":" + port)) {
            tlsAddrs.add(addr);
        }
        return this;
    }

    /**
     * 设置 TLS 配置（keystore / 协议 / ALPN）。
     *
     * <p>不设置时 {@link #buildTls()} 会用 {@link TlsConfig#fromSystemProperties()}
     * 从系统属性装配。</p>
     */
    public HttpServerBuilder tls(TlsConfig config) {
        this.tlsConfig = config;
        return this;
    }

    /**
     * 同时支持GET，POST的请求的HTTP处理器设置
     * @param path 请求的地址
     * @param handler http请求处理器
     * @return
     */
    public HttpServerBuilder req(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), "GET", "POST");
        return this;
    }

    /**
     * 同时支持GET，POST的请求的HTTP处理器设置
     * @param path 请求的地址
     * @param handler http请求处理器
     * @return
     */
    public HttpServerBuilder req(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option,"GET", "POST");
        return this;
    }

    public HttpServerBuilder get(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(),"GET");
        return this;
    }

    public HttpServerBuilder get(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "GET");
        return this;
    }

    public HttpServerBuilder post(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(),"POST");
        return this;
    }

    public HttpServerBuilder post(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option,"POST");
        return this;
    }

    public HttpServerBuilder put(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), "PUT");
        return this;
    }

    public HttpServerBuilder put(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "PUT");
        return this;
    }

    public HttpServerBuilder delete(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), "DELETE");
        return this;
    }

    public HttpServerBuilder delete(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "DELETE");
        return this;
    }

    public HttpServerBuilder head(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), "HEAD");
        return this;
    }

    public HttpServerBuilder head(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "HEAD");
        return this;
    }

    public HttpServerBuilder trace(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(),"TRACE");
        return this;
    }

    public HttpServerBuilder trace(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "TRACE");
        return this;
    }

    public HttpServerBuilder options(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), "OPTIONS");
        return this;
    }

    public HttpServerBuilder options(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "OPTIONS");
        return this;
    }

    public HttpServerBuilder connect(String path, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), "CONNECT");
        return this;
    }

    public HttpServerBuilder connect(String path, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, "CONNECT");
        return this;
    }

    public HttpServerBuilder serve(String path, String method, HttpHandler handler) {
        addPathHandler(path, handler, defaultHttpHandleOption(), method);
        return this;
    }

    public HttpServerBuilder serve(String path, String method, HttpHandler handler, HttpHandleOption option) {
        addPathHandler(path, handler, option, method);
        return this;
    }

    public HttpServerBuilder websocket(String path, WSHandler wsHandler) {
        PathInfo pathInfo = new PathInfo();
        pathInfo.setPath(path);
        pathInfo.setFound(true);
        pathInfo.setWsHandler(wsHandler);
        mapping.put(path, pathInfo);

        return this;
    }

    public HttpServerBuilder decoderType(HttpServer.DecoderType decoderType) {
        this.decoderType = decoderType;
        return this;
    }

    public HttpServer.DecoderType getDecoderType() {
        return decoderType;
    }

    /**
     * path → 已注册的 PathInfo（同一个 path 多次注册不同 method 时合并到同一个 PathInfo）。
     *
     * <p>wildcard 路由（prefix/postfix）在 {@link PathInfoMatcher} 里按 path 字符串去重，
     * 后注册的会被丢弃；而精确路径走 {@link #mapping}，后注册的会整体覆盖前一个。
     * 两种情况下「同一个 path 注册多个 method」都会丢掉先前注册的 handler。
     * 这里按 path 缓存 PathInfo，重复注册时只往里补 method → handler 的槽位。</p>
     */
    private final Map<String, PathInfo> pathInfos = new HashMap<>();

    private void addPathHandler(String path, HttpHandler handler, HttpHandleOption option, String... methods) {
        MethodCache methodCache = MethodCache.instance();
        PathInfo pathInfo = pathInfos.get(path);
        if (pathInfo == null) {
            pathInfo = new PathInfo();
            pathInfo.setMatchPath(path);
            pathInfo.setPath(path);
            pathInfos.put(path, pathInfo);
            if (path.startsWith("*")) {
                pathInfoMatcher.registerPrefixMatcher(pathInfo);
            } else if (path.endsWith("*")) {
                pathInfoMatcher.registerPostfixMatcher(pathInfo);
            } else {
                pathInfo.setFound(true);
                mapping.put(path, pathInfo);
            }
        }
        // httpHandlers[] 按 MethodCache 的全局 method index 索引（dispatch 时用它选 handler），
        // 所以数组长度必须 ≥ methodIndex+1，中间空槽留 null（该 method 没有 handler）。
        HttpHandler[] handlers = pathInfo.getHttpHandlers();
        if (handlers == null) {
            handlers = new HttpHandler[0];
        }
        for (String method : methods) {
            int methodIndex = methodCache.getMethodIndex(method);
            if (handlers.length > methodIndex) {
                handlers[methodIndex] = handler;
            } else {
                HttpHandler[] tmp = new HttpHandler[methodIndex + 1];
                System.arraycopy(handlers, 0, tmp, 0, handlers.length);
                tmp[methodIndex] = handler;
                handlers = tmp;
            }
        }
        pathInfo.setHttpHandlers(handlers);
        pathInfo.setHandlerOption(option);
    }

    public HttpServer build() {
        registerBuiltinHandlers();
        HttpServer server = new HttpServer();
        server.setPathInfoMatcher(pathInfoMatcher);
        applyDecoderType(server);
        applyPathCache();
        BufPool bufPool = new SimpleFastBufPool();
        server.setBufPool(bufPool);
        listenAll(server, addrs);
        return server;
    }

    /**
     * 构建 TLS 版 server（明文/TLS 分端口，两个 server 共享同一张路由表）。
     *
     * <p>没有配置任何 TLS 端口时返回 null。</p>
     */
    public HttpServer buildTls() {
        if (tlsAddrs.isEmpty()) {
            return null;
        }
        registerBuiltinHandlers();
        TlsConfig config = tlsConfig != null ? tlsConfig : TlsConfig.fromSystemProperties();
        TlsContext context = TlsContext.create(config);
        TlsHttpServer server = new TlsHttpServer(context);
        // ★ 与明文 server 共享同一个 PathInfoMatcher —— 一套 handler，两种传输
        server.setPathInfoMatcher(pathInfoMatcher);
        applyDecoderType(server);
        applyPathCache();
        server.setBufPool(new SimpleFastBufPool());
        listenAll(server, tlsAddrs);
        return server;
    }

    /**
     * 构建全部 server（明文 + TLS）。
     *
     * <p>用法：</p>
     * <pre>
     * for (HttpServer s : new HttpServerBuilder()
     *         .get("/hello", handler)
     *         .listen(8080)
     *         .listenTls(8443)
     *         .tls(cfg)
     *         .buildAll()) {
     *     edap.addServer(s);
     * }
     * </pre>
     */
    public List<HttpServer> buildAll() {
        List<HttpServer> servers = new ArrayList<>();
        servers.add(build());
        HttpServer tls = buildTls();
        if (tls != null) {
            servers.add(tls);
        }
        return servers;
    }

    private void registerBuiltinHandlers() {
        if (mapping.get("/favicon.ico") == null) {
            this.get("/favicon.ico", new FaviconHandler());
        }
        if (mapping.get("/icon.svg") == null) {
            this.get("/icon.svg", new FaviconHandler());
        }
        if (mapping.get("/icon.svg") == null) {
            this.get("/favicon.ico", new FaviconHandler());
        }
    }

    private void applyDecoderType(HttpServer server) {
        String httpDecoderType = System.getProperty("edap.http.decoder.type");
        if (!StringUtil.isEmpty(httpDecoderType) && "fast".equalsIgnoreCase(httpDecoderType)) {
            server.setDecoderType(HttpServer.DecoderType.FAST);
        } else {
            if (StringUtil.isEmpty(httpDecoderType) && decoderType != null) {
                server.setDecoderType(decoderType);
            } else {
                server.setDecoderType(HttpServer.DecoderType.NORMAL);
            }
        }
    }

    /**
     * 把精确路径表灌进 {@link PathInfoMatcher} 的 cache。
     *
     * <p><b>双 key 注册</b>：请求侧（{@code PathDecoder.decode}）对<b>解码后</b>的字节算
     * FNV hash，而注册侧若只用原始路径字节算 hash，两者永远对不上 ——
     * 注册 {@code /a%20b} 时 hash 的是 {@code /a%20b}，请求 {@code /a%20b} 时 hash 的是
     * {@code /a b} ⇒ 精确 cache 永远 miss，只能退回通配符路由。
     * 所以这里对每个路径额外注册一份「解码后」的 key。</p>
     *
     * <p><b>冲突处理</b>：解码后的 key 与某个已注册的原始路径相同（例如同时注册了
     * {@code /a%20b} 与 {@code /a b}）时，<b>原始注册优先</b> —— 用 {@code putIfAbsent}，
     * 且先跑一遍全部原始注册再跑解码别名，避免 HashMap 迭代顺序导致结果不确定。</p>
     */
    private void applyPathCache() {
        if (!CollectionUtils.isEmpty(mapping)) {
            Map<FastBufDataRange, PathInfo> serverMapping = new HashMap<>();
            for (Map.Entry<String, PathInfo> entry : mapping.entrySet()) {
                serverMapping.put(HttpFastBufDataRange.from(entry.getKey()), entry.getValue());
            }
            // 第二遍：注册「解码后」的别名
            for (Map.Entry<String, PathInfo> entry : mapping.entrySet()) {
                byte[] decoded = HttpFastBufDataRange.decodePathBytes(entry.getKey());
                if (decoded == null) {
                    // escape 非法/不完整 —— 保持改造前行为（请求时由 PathDecoder 报错）
                    continue;
                }
                serverMapping.putIfAbsent(HttpFastBufDataRange.fromBytes(decoded), entry.getValue());
            }
            pathInfoMatcher.setCache(serverMapping);
        }
    }

    private void listenAll(HttpServer server, List<String> addrs) {
        int index;
        for (String addr : addrs) {
            index = addr.indexOf(":");
            int port = Integer.parseInt(addr.substring(index + 1));
            if (index > 0) {
                server.listen(addr.substring(0, index), port);
            } else {
                server.listen(port);
            }
        }
    }

}
