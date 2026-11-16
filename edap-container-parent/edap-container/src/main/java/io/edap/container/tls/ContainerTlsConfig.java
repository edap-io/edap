/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.container.tls;

import io.edap.http.server.tls.TlsConfig;
import io.edap.props.Props;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器侧 TLS 配置 —— 把 {@code container.http.tls.*} 映射成框架的 {@link TlsConfig}。
 *
 * <p><b>配置来源优先级</b>（从高到低），与 {@link TlsConfig} 自己的语义保持一致：</p>
 * <ol>
 *   <li>容器配置 {@code container.http.tls.*}（{@code edap.json5} 里的 {@code container} 子树）；</li>
 *   <li>系统属性 {@code edap.http.tls.*}；</li>
 *   <li>系统属性 {@code javax.net.ssl.*}。</li>
 * </ol>
 *
 * <p>★ 第 2、3 层直接复用 {@link TlsConfig#fromSystemProperties()} —— 它已经实现了
 * 「{@code edap.http.tls.*} 优先于 {@code javax.net.ssl.*}」的回落，
 * 本类只负责让<b>容器配置覆盖它</b>。</p>
 *
 * <p><b>支持的容器配置项</b>（{@code container.} 前缀已由 {@code Props.child("container")} 去掉）：</p>
 * <table border="1">
 *   <tr><th>配置项</th><th>说明</th><th>默认</th></tr>
 *   <tr><td>{@code http.tls.enabled}</td><td>是否起 TLS 端口</td><td>false</td></tr>
 *   <tr><td>{@code http.tls.port}</td><td>TLS 端口（与明文 {@code http.port} <b>分端口</b>）</td><td>8443</td></tr>
 *   <tr><td>{@code http.tls.certificate}</td><td>PEM 证书链路径（{@code fullchain.cer}）</td><td>取系统属性</td></tr>
 *   <tr><td>{@code http.tls.privateKey}</td><td>PEM 私钥路径（{@code www.example.com.key}）</td><td>取系统属性</td></tr>
 *   <tr><td>{@code http.tls.keyStore}</td><td>keystore 路径（相对路径按进程工作目录解析）</td><td>取系统属性</td></tr>
 *   <tr><td>{@code http.tls.keyStorePassword}</td><td>keystore 口令</td><td>取系统属性</td></tr>
 *   <tr><td>{@code http.tls.keyStoreType}</td><td>keystore 类型</td><td>取系统属性，否则 PKCS12</td></tr>
 *   <tr><td>{@code http.tls.keyAlias}</td><td>私钥别名</td><td>取系统属性，否则用 keystore 里第一个</td></tr>
 *   <tr><td>{@code http.tls.keyPassword}</td><td>私钥口令</td><td>取系统属性，否则取 keystore 口令</td></tr>
 *   <tr><td>{@code http.tls.protocols}</td><td>逗号分隔的 TLS 版本</td><td>{@code TLSv1.3,TLSv1.2}</td></tr>
 *   <tr><td>{@code http.tls.alpn}</td><td>逗号分隔的 ALPN 协议</td><td>{@code h2,http/1.1}</td></tr>
 * </table>
 *
 * <p><b>★ 证书两条路，二选一</b>：</p>
 * <ul>
 *   <li><b>PEM</b>（CA 签发证书的原生形态）：配 {@code http.tls.certificate} +
 *       {@code http.tls.privateKey} 两个文件即可 —— 不用先转成 keystore；</li>
 *   <li><b>keystore</b>：配 {@code http.tls.keyStore} + 口令（老路线，仍支持）。</li>
 * </ul>
 * <p>只配了其中一个 ⇒ <b>启动失败</b>（不静默回落），因为证书与私钥必须成对。</p>
 *
 * <p><b>★ ALPN 默认带 {@code h2}</b>：TLS 端口协商出 h2 就是 HTTP/2 over TLS，
 * 这正是「起支持 http2 的服务」的落点 —— 一套证书、两个协议（h2 与 http/1.1）共用同一个端口。</p>
 */
public final class ContainerTlsConfig {

    /** 容器配置里 TLS 段的前缀（相对 {@code container} 子树） */
    private static final String PREFIX = "http.tls.";

    private ContainerTlsConfig() {
    }

    /**
     * 容器是否要起 TLS 端口。
     *
     * @param env 容器的配置子树（{@code edap.getProps().child("container")}）
     */
    public static boolean enabled(Props env) {
        return env != null && env.getBoolean(PREFIX + "enabled", false);
    }

    /**
     * 按容器配置装配 TLS 配置（每一项没配时回落到系统属性）。
     *
     * @param env 容器的配置子树
     * @return TLS 配置；调用方保证 {@link #enabled(Props)} 为 true 时一定有证书可用
     * @throws IllegalStateException PEM 只配了一半（证书与私钥必须成对）
     */
    public static TlsConfig from(Props env) {
        TlsConfig sys = TlsConfig.fromSystemProperties();
        TlsConfig.Builder builder = TlsConfig.builder();

        // PEM 路线优先：certificate + privateKey 成对出现。
        String cert = str(env, "certificate", sys.getCertificatePath());
        String privateKey = str(env, "privateKey", sys.getPrivateKeyPath());
        if (cert != null || privateKey != null) {
            if (cert == null || privateKey == null) {
                throw new IllegalStateException("TLS 证书配置不完整：http.tls.certificate 与 http.tls.privateKey "
                        + "必须成对配置（当前 certificate=" + cert + ", privateKey=" + privateKey + "）");
            }
            builder.certificate(cert).privateKey(privateKey);
        } else {
            String keyStore = str(env, "keyStore", sys.getKeyStorePath());
            if (keyStore != null) {
                builder.keyStore(keyStore, str(env, "keyStorePassword", sys.getKeyStorePassword()));
            }
            String type = str(env, "keyStoreType", sys.getKeyStoreType());
            if (type != null) {
                builder.keyStoreType(type);
            }
            String alias = str(env, "keyAlias", sys.getKeyAlias());
            if (alias != null) {
                builder.keyAlias(alias);
            }
            String keyPwd = str(env, "keyPassword", sys.getKeyPassword());
            if (keyPwd != null) {
                builder.keyPassword(keyPwd);
            }
        }

        String[] protocols = list(env, "protocols", sys.getProtocols());
        if (protocols != null && protocols.length > 0) {
            builder.protocols(protocols);
        }
        String[] alpn = list(env, "alpn", sys.getAlpnProtocols());
        if (alpn != null && alpn.length > 0) {
            builder.alpnProtocols(alpn);
        }
        if (sys.getCipherSuites() != null && sys.getCipherSuites().length > 0) {
            builder.cipherSuites(sys.getCipherSuites());
        }
        return builder.build();
    }

    /**
     * 取一个字符串配置项；容器配置为空时回落到系统属性。
     */
    private static String str(Props env, String key, String fallback) {
        String v = env == null ? null : env.getString(PREFIX + key, null);
        if (v == null || v.trim().isEmpty()) {
            return fallback;
        }
        return v.trim();
    }

    /**
     * 取一个逗号分隔的列表配置项；容器配置为空时回落到系统属性。
     */
    private static String[] list(Props env, String key, String[] fallback) {
        String v = env == null ? null : env.getString(PREFIX + key, null);
        if (v == null || v.trim().isEmpty()) {
            return fallback;
        }
        List<String> list = new ArrayList<>();
        for (String s : v.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                list.add(t);
            }
        }
        return list.isEmpty() ? fallback : list.toArray(new String[0]);
    }
}
