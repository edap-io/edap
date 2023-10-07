package io.edap.http;

import io.edap.http.cache.PathCache;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 */
public class HttpServerBuilder {

    List<String> addrs = new ArrayList<>();

    HttpServer.DecoderType decoderType;

    public HttpServerBuilder listen(int port) {
        return listen("", port);
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
        addPathHandler(path, handler, "GET", "POST");
        return this;
    }

    public HttpServerBuilder get(String path, HttpHandler handler) {
        addPathHandler(path, handler, "GET");
        return this;
    }

    public HttpServerBuilder post(String path, HttpHandler handler) {
        addPathHandler(path, handler, "POST");
        return this;
    }

    public HttpServerBuilder put(String path, HttpHandler handler) {
        addPathHandler(path, handler, "PUT");
        return this;
    }

    public HttpServerBuilder delete(String path, HttpHandler handler) {
        addPathHandler(path, handler, "DELETE");
        return this;
    }

    public HttpServerBuilder head(String path, HttpHandler handler) {
        addPathHandler(path, handler, "HEAD");
        return this;
    }

    public HttpServerBuilder trace(String path, HttpHandler handler) {
        addPathHandler(path, handler, "TRACE");
        return this;
    }

    public HttpServerBuilder options(String path, HttpHandler handler) {
        addPathHandler(path, handler, "OPTIONS");
        return this;
    }

    public HttpServerBuilder connect(String path, HttpHandler handler) {
        addPathHandler(path, handler, "CONNECT");
        return this;
    }

    public HttpServerBuilder serve(String path, String method, HttpHandler handler) {
        addPathHandler(path, handler, method);
        return this;
    }

    public HttpServerBuilder decoderType(HttpServer.DecoderType decoderType) {
        this.decoderType = decoderType;
        return this;
    }

    public HttpServer.DecoderType getDecoderType() {
        return decoderType;
    }

    private void addPathHandler(String path, HttpHandler handler, String... methods) {
        PathCache pathCache = PathCache.instance();
        pathCache.registerHandler(path, handler, methods);
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
