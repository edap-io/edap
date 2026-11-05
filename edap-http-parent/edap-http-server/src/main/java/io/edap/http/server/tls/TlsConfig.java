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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TLS 配置。
 *
 * <p><b>配置来源优先级</b>（从高到低）：</p>
 * <ol>
 *   <li>代码里显式设置（{@link Builder}）；</li>
 *   <li>edap 自有的系统属性 {@code edap.http.tls.*}；</li>
 *   <li>JVM 标准属性 {@code javax.net.ssl.*}。</li>
 * </ol>
 *
 * <p>★ 用 JVM 标准属性做主路径是有意的：TLS 是<b>进程级</b>配置（证书/私钥跟端口无关），
 * 而 {@code javax.net.ssl.keyStore} 是所有 JVM TLS 用户的通用约定 ——
 * 运维改一处配置，JDK 客户端与服务端同时生效。</p>
 *
 * <p>支持的属性：</p>
 * <table border="1">
 *   <tr><th>属性</th><th>说明</th><th>默认</th></tr>
 *   <tr><td>{@code edap.http.tls.keyStore}</td><td>keystore 路径</td><td>取 {@code javax.net.ssl.keyStore}</td></tr>
 *   <tr><td>{@code edap.http.tls.keyStorePassword}</td><td>keystore 口令</td><td>取 {@code javax.net.ssl.keyStorePassword}</td></tr>
 *   <tr><td>{@code edap.http.tls.keyStoreType}</td><td>keystore 类型</td><td>取 {@code javax.net.ssl.keyStoreType}，否则 {@code PKCS12}</td></tr>
 *   <tr><td>{@code edap.http.tls.keyAlias}</td><td>私钥别名</td><td>取 {@code javax.net.ssl.key.alias}，否则用 keystore 里第一个</td></tr>
 *   <tr><td>{@code edap.http.tls.keyPassword}</td><td>私钥口令</td><td>取 keyStorePassword</td></tr>
 *   <tr><td>{@code edap.http.tls.protocols}</td><td>逗号分隔的 TLS 版本</td><td>{@code TLSv1.3,TLSv1.2}</td></tr>
 *   <tr><td>{@code edap.http.tls.alpn}</td><td>逗号分隔的 ALPN 协议</td><td>{@code h2,http/1.1}</td></tr>
 * </table>
 */
public final class TlsConfig {

    /** edap 自有的 TLS 配置属性前缀 */
    public static final String PROP_PREFIX = "edap.http.tls.";

    /** ALPN：HTTP/2 */
    public static final String ALPN_H2 = "h2";
    /** ALPN：HTTP/1.1 */
    public static final String ALPN_HTTP_1_1 = "http/1.1";

    private String keyStorePath;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";
    private String keyAlias;
    private String keyPassword;
    private String[] protocols = {"TLSv1.3", "TLSv1.2"};
    private String[] alpnProtocols = {ALPN_H2, ALPN_HTTP_1_1};
    private String[] cipherSuites;

    TlsConfig() {
    }

    /**
     * 从系统属性装配配置（{@code edap.http.tls.*} → {@code javax.net.ssl.*}）。
     */
    public static TlsConfig fromSystemProperties() {
        TlsConfig config = new TlsConfig();
        config.keyStorePath = firstNonEmpty(System.getProperty(PROP_PREFIX + "keyStore"),
                System.getProperty("javax.net.ssl.keyStore"));
        config.keyStorePassword = firstNonEmpty(System.getProperty(PROP_PREFIX + "keyStorePassword"),
                System.getProperty("javax.net.ssl.keyStorePassword"));
        String type = firstNonEmpty(System.getProperty(PROP_PREFIX + "keyStoreType"),
                System.getProperty("javax.net.ssl.keyStoreType"));
        if (type != null) {
            config.keyStoreType = type;
        }
        config.keyAlias = firstNonEmpty(System.getProperty(PROP_PREFIX + "keyAlias"),
                System.getProperty("javax.net.ssl.key.alias"));
        String keyPwd = System.getProperty(PROP_PREFIX + "keyPassword");
        if (keyPwd != null) {
            config.keyPassword = keyPwd;
        }
        String protos = System.getProperty(PROP_PREFIX + "protocols");
        if (protos != null) {
            config.protocols = split(protos);
        }
        String alpn = System.getProperty(PROP_PREFIX + "alpn");
        if (alpn != null) {
            config.alpnProtocols = split(alpn);
        }
        return config;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    private static String[] split(String value) {
        List<String> list = new ArrayList<>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list.toArray(new String[0]);
    }

    public String getKeyStorePath() {
        return keyStorePath;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public String[] getProtocols() {
        return protocols;
    }

    public String[] getAlpnProtocols() {
        return alpnProtocols;
    }

    public String[] getCipherSuites() {
        return cipherSuites;
    }

    @Override
    public String toString() {
        return "TlsConfig{keyStore=" + keyStorePath
                + ", type=" + keyStoreType
                + ", alias=" + keyAlias
                + ", protocols=" + Arrays.toString(protocols)
                + ", alpn=" + Arrays.toString(alpnProtocols) + '}';
    }

    /**
     * TLS 配置的构造器。
     */
    public static final class Builder {

        private final TlsConfig config = new TlsConfig();

        public Builder keyStore(String path, String password) {
            config.keyStorePath = path;
            config.keyStorePassword = password;
            return this;
        }

        public Builder keyStoreType(String type) {
            config.keyStoreType = type;
            return this;
        }

        public Builder keyAlias(String alias) {
            config.keyAlias = alias;
            return this;
        }

        public Builder keyPassword(String password) {
            config.keyPassword = password;
            return this;
        }

        public Builder protocols(String... protocols) {
            config.protocols = protocols;
            return this;
        }

        public Builder alpnProtocols(String... alpnProtocols) {
            config.alpnProtocols = alpnProtocols;
            return this;
        }

        public Builder cipherSuites(String... cipherSuites) {
            config.cipherSuites = cipherSuites;
            return this;
        }

        public TlsConfig build() {
            return config;
        }
    }
}
