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

package io.edap.http.server.tls;

import io.edap.NioServerSession;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerNioSession;

/**
 * TLS 版 HTTP 服务端。
 *
 * <p>与 {@link HttpServer} 的唯一区别是<b>会话类型</b>：连接建立后创建的是
 * {@link TlsHttpServerNioSession}，整条链路（读、解密、协议嗅探、HTTP 处理、
 * 加密、写）都在 TLS 之内。</p>
 *
 * <p>★ 端口模型：<b>明文与 TLS 分端口</b>（{@code 8080} 明文 / {@code 8443} TLS），
 * 不做同端口 mux —— 同端口要靠 {@code Connection} 头里的 {@code Upgrade: h2c}
 * 来区分，而 h2c 升级在 TLS 上没有意义（RFC 9113 已经把 h2c 标记为 OBSOLETE）。</p>
 *
 * <p>★ 路由表与明文端口<b>共享</b>：两个 server 用同一个 {@code PathInfoMatcher}
 * 实例，dispatch 语义完全一致（一套 handler，两种传输）。</p>
 */
public class TlsHttpServer extends HttpServer {

    private final TlsContext tlsContext;

    public TlsHttpServer(TlsContext tlsContext) {
        this.tlsContext = tlsContext;
    }

    public TlsContext getTlsContext() {
        return tlsContext;
    }

    @Override
    public NioServerSession createNioSession() {
        return createHttpSession();
    }

    @Override
    protected HttpServerNioSession createHttpSession() {
        TlsHttpServerNioSession nioSession = new TlsHttpServerNioSession(tlsContext);
        initHttpSession(nioSession);
        return nioSession;
    }
}
