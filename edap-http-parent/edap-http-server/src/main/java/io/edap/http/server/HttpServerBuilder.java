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
import io.edap.http.server.cache.PathCache;
import io.edap.pool.SimpleFastBufPool;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

import static io.edap.http.HttpHandleOption.defaultHttpHandleOption;

/**
 */
public class HttpServerBuilder {

    List<String> addrs = new ArrayList<>();

    HttpServer.DecoderType decoderType;

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

    public HttpServerBuilder decoderType(HttpServer.DecoderType decoderType) {
        this.decoderType = decoderType;
        return this;
    }

    public HttpServer.DecoderType getDecoderType() {
        return decoderType;
    }

    private void addPathHandler(String path, HttpHandler handler, HttpHandleOption option, String... methods) {
        PathCache pathCache = PathCache.instance();
        pathCache.registerHandler(path, handler, option, methods);
    }

    public HttpServer build() {
        HttpServer server = new HttpServer();
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
