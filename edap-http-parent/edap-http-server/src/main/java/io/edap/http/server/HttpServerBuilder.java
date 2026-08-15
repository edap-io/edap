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
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.handler.FaviconHandler;
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

    private void addPathHandler(String path, HttpHandler handler, HttpHandleOption option, String... methods) {
        if (path.startsWith("*")) {
            PathInfo pathInfo = new PathInfo();
            pathInfo.setMatchPath(path);
            pathInfo.setPath(path);
            pathInfo.setHttpHandlers(new HttpHandler[]{handler});
            pathInfo.setHandlerOption(option);
            pathInfoMatcher.registerPrefixMatcher(pathInfo);
        } else if (path.endsWith("*")) {
            PathInfo pathInfo = new PathInfo();
            pathInfo.setMatchPath(path);
            pathInfo.setPath(path);
            pathInfo.setHttpHandlers(new HttpHandler[]{handler});
            pathInfo.setHandlerOption(option);
            pathInfoMatcher.registerPostfixMatcher(pathInfo);
        } else {
            PathInfo pathInfo = new PathInfo();
            pathInfo.setMatchPath(path);
            pathInfo.setPath(path);
            pathInfo.setHttpHandlers(new HttpHandler[]{handler});
            pathInfo.setHandlerOption(option);
            pathInfo.setFound(true);
            mapping.put(path, pathInfo);
        }
    }

    public HttpServer build() {
        if (mapping.get("/favicon.ico") == null) {
            this.get("/favicon.ico", new FaviconHandler());
        }
        if (mapping.get("/icon.svg") == null) {
            this.get("/icon.svg", new FaviconHandler());
        }
        if (mapping.get("/icon.svg") == null) {
            this.get("/favicon.ico", new FaviconHandler());
        }
        HttpServer server = new HttpServer(pathInfoMatcher);
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
        if (!CollectionUtils.isEmpty(mapping)) {
            Map<FastBufDataRange, PathInfo> serverMapping = new HashMap<>();
            for (Map.Entry<String, PathInfo> entry : mapping.entrySet()) {
                serverMapping.put(HttpFastBufDataRange.from(entry.getKey()), entry.getValue());
            }
            server.setHttpMapping(serverMapping);
        }
        BufPool bufPool = new SimpleFastBufPool();
        server.setBufPool(bufPool);
        int index;
        for (String addr : addrs) {
            index = addr.indexOf(":");
            int port = Integer.parseInt(addr.substring(index+1));
            if (index > 0) {
                server.listen(addr.substring(0, index), port);
            } else {
                server.listen(port);
            }
        }
        return server;
    }

}
