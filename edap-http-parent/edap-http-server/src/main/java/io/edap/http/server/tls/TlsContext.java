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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;

/**
 * TLS 上下文 —— 持有 {@link SSLContext}，为每个连接创建 {@link SSLEngine}。
 *
 * <p><b>★ 为什么用 JDK 自带的 {@code SSLEngine} 而不是自己实现 TLS</b>：
 * 实现 {@code javax.net.ssl} SPI（KeyManager / TrustManager / X509 解析 / 密码套件）
 * 的工作量巨大，而且会削弱与浏览器、curl、JDK 客户端的兼容性。
 * {@code SSLEngine} 恰好就是 JDK 为「非阻塞 IO」准备的那套 API ——
 * edap 的 NIO 模型正好是它想要的形态。</p>
 *
 * <p><b>★ 只做服务端</b>：没有实现客户端模式，也没有做客户端证书校验
 * （{@code TrustManager} 传 null ⇒ 不校验对端证书）。</p>
 */
public final class TlsContext {

    static final Logger LOG = LoggerManager.getLogger(TlsContext.class);

    private final SSLContext sslContext;
    private final TlsConfig config;

    private TlsContext(SSLContext sslContext, TlsConfig config) {
        this.sslContext = sslContext;
        this.config = config;
    }

    /**
     * 按配置创建 TLS 上下文。
     *
     * @throws IllegalStateException keystore 不可用 / 证书不可读（配置错误属于启动期致命错误）
     */
    public static TlsContext create(TlsConfig config) {
        String path = config.getKeyStorePath();
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException("TLS 缺少 keystore 配置（"
                    + TlsConfig.PROP_PREFIX + "keyStore 或 javax.net.ssl.keyStore）");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(config.getKeyStoreType());
            char[] storePwd = config.getKeyStorePassword() == null
                    ? new char[0] : config.getKeyStorePassword().toCharArray();
            try (InputStream in = Files.newInputStream(Paths.get(path))) {
                keyStore.load(in, storePwd);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            char[] keyPwd = config.getKeyPassword() == null
                    ? storePwd : config.getKeyPassword().toCharArray();
            kmf.init(keyStore, keyPwd);
            KeyManager[] kms = kmf.getKeyManagers();

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kms, null, null);
            LOG.info("TLS context ready: {}", l -> l.arg(config.toString()));
            return new TlsContext(ctx, config);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TLS 上下文失败: " + e.getMessage(), e);
        }
    }

    public TlsConfig getConfig() {
        return config;
    }

    /**
     * 为一个新连接创建 {@link SSLEngine}（服务端模式 + ALPN）。
     */
    public SSLEngine createEngine() {
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        if (config.getProtocols() != null && config.getProtocols().length > 0) {
            engine.setEnabledProtocols(config.getProtocols());
        }
        if (config.getCipherSuites() != null && config.getCipherSuites().length > 0) {
            engine.setEnabledCipherSuites(config.getCipherSuites());
        }
        SSLParameters params = engine.getSSLParameters();
        // ★ ALPN：RFC 7301。客户端在 TLS 握手里协商应用层协议，
        //   协商结果决定后续用 HTTP/2 还是 HTTP/1.1。
        params.setApplicationProtocols(config.getAlpnProtocols());
        engine.setSSLParameters(params);
        return engine;
    }
}
