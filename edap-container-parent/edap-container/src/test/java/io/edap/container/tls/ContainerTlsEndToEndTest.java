/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.container.tls;

import io.edap.Edap;
import io.edap.container.AppContext;
import io.edap.container.Capability;
import io.edap.container.Container;
import io.edap.container.SlotEntry;
import io.edap.http.HttpHandleOption;
import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.cache.MethodCache;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.header.ContentTypeHeader;
import io.edap.json.JsonObjectImpl;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.props.Props;
import io.edap.util.UnsafeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器级 TLS + HTTP/2 的端到端测试。
 *
 * <p>验的是四件事：</p>
 * <ol>
 *   <li>配了 {@code container.http.tls.*} ⇒ 容器起一个 TLS 端口，ALPN 协商出 h2
 *       ⇒ 对外提供 <b>HTTP/2 over TLS</b>；</li>
 *   <li>明文端口不受影响（回归）；</li>
 *   <li>两个端口<b>共用同一张路由表</b>（部署/切版本对两个端口同时生效）；</li>
 *   <li>证书不可用 ⇒ <b>启动失败</b>（不静默降级成明文）。</li>
 * </ol>
 *
 * <p><b>为什么用反射注入 Props</b>：{@code Edap} 没有配置 setter，
 * 而 {@code Container.attach()} 是从 {@code edap.getProps().child("container")} 取配置的
 * —— 只能把 Props 塞进 {@code Edap} 的私有字段。手法与 {@code GrpcRoutingTest} 一致。</p>
 *
 * <p><b>为什么用 Unsafe 造 AppContext</b>：AppContext 的构造要真实 EAR 与
 * {@code EdapAppClassLoader}，单测里造不出来；这里要验的只是
 * 「Container 从 CURRENT 槽的 ctx 读 path 表并发布给两个 server」这一件事。</p>
 */
public class ContainerTlsEndToEndTest {

    private static final String BODY = "hello edap container tls";
    private static final String PATH = "/hello";

    /** 测试 keystore 的口令（仓库里的自签证书，公开无风险） */
    private static final String KEYSTORE_PASSWORD = "edap-test";

    private Edap edap;
    private Container container;

    @AfterEach
    void tearDown() throws Exception {
        // 先摘掉 registry 里的假 ctx：Container.stop() 会对 ctx 调 stop()/cancelRelease()，
        // 而 Unsafe 造出来的 ctx 没有内部状态。
        if (container != null) {
            registry(container).clear();
        }
        if (edap != null) {
            edap.stop();
        }
    }

    /**
     * ★ TLS 端口协商出 h2 ⇒ HTTP/2 over TLS 正常返回。
     */
    @Test
    public void tlsPortServesHttp2() throws Exception {
        int httpPort = freePort();
        int tlsPort = freePort();
        startContainer(props(httpPort, tlsPort, pemCertificate(), pemPrivateKey()), httpPort, tlsPort);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustingCa())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = send(client, "https", tlsPort);

        assertEquals(200, response.statusCode(), "状态码不对");
        assertEquals(BODY, response.body(), "响应体不对");
        assertEquals(HttpClient.Version.HTTP_2, response.version(),
                "应当通过 ALPN 协商成 HTTP/2，实际是 " + response.version());
    }

    /**
     * 回归：明文端口不受 TLS 影响。
     */
    @Test
    public void plainPortStillWorks() throws Exception {
        int httpPort = freePort();
        int tlsPort = freePort();
        startContainer(props(httpPort, tlsPort, pemCertificate(), pemPrivateKey()), httpPort, tlsPort);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = send(client, "http", httpPort);

        assertEquals(200, response.statusCode(), "状态码不对");
        assertEquals(BODY, response.body(), "响应体不对");
    }

    /**
     * ★ 两个端口共用同一张路由表 —— 同一个 path 在明文与 TLS 上都能访问。
     */
    @Test
    public void bothPortsShareTheSameRouteTable() throws Exception {
        int httpPort = freePort();
        int tlsPort = freePort();
        startContainer(props(httpPort, tlsPort, pemCertificate(), pemPrivateKey()), httpPort, tlsPort);

        HttpClient plain = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpClient tls = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustingCa())
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        assertEquals(BODY, send(plain, "http", httpPort).body(), "明文端口应能命中路由");
        assertEquals(BODY, send(tls, "https", tlsPort).body(), "TLS 端口应能命中同一张路由表");
    }

    /**
     * 没开 {@code http.tls.enabled} ⇒ 不起 TLS 端口（行为与改造前完全一致）。
     */
    @Test
    public void tlsDisabledMeansNoTlsServer() throws Exception {
        int httpPort = freePort();
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> containerCfg = new HashMap<>();
        Map<String, Object> http = new HashMap<>();
        http.put("port", httpPort);
        containerCfg.put("http", new JsonObjectImpl(http));
        root.put("container", new JsonObjectImpl(containerCfg));

        Edap edap = newEdap(new Props(root));
        Container container = new Container(new File("ignored"), EnumSet.of(Capability.HTTP));
        container.attach(edap);
        assertNull(container.tlsHttpServer(), "没开 container.http.tls.enabled 时不该建 TlsHttpServer");
    }

    /**
     * ★ 证书不可用 ⇒ 启动失败（不静默降级成明文）。
     */
    @Test
    public void brokenCertificateFailsFast() throws Exception {
        int httpPort = freePort();
        int tlsPort = freePort();
        Map<String, Object> tls = new HashMap<>();
        tls.put("enabled", true);
        tls.put("port", tlsPort);
        tls.put("certificate", "/no/such/fullchain.cer");
        tls.put("privateKey", pemPrivateKey());
        Edap edap = newEdap(props(httpPort, tlsPort, null, null, tls));

        Container container = new Container(new File("ignored"), EnumSet.of(Capability.HTTP));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> container.attach(edap));
        assertTrue(e.getMessage().contains("fullchain.cer"),
                "报错信息里应带上出问题的证书路径，实际: " + e.getMessage());
    }

    /**
     * PEM 只配了一半（没有私钥）⇒ 启动失败 —— 证书与私钥必须成对。
     */
    @Test
    public void certificateWithoutPrivateKeyFailsFast() throws Exception {
        int httpPort = freePort();
        int tlsPort = freePort();
        Map<String, Object> tls = new HashMap<>();
        tls.put("enabled", true);
        tls.put("port", tlsPort);
        tls.put("certificate", pemCertificate());
        Edap edap = newEdap(props(httpPort, tlsPort, null, null, tls));

        Container container = new Container(new File("ignored"), EnumSet.of(Capability.HTTP));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> container.attach(edap));
        assertTrue(e.getMessage().contains("privateKey"),
                "报错信息里应指出缺私钥，实际: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // 起容器
    // ------------------------------------------------------------------

    /**
     * 起一个容器：attach（建 HTTP + TLS 两个 server）→ 注册路由 → edap.run()（bind 端口）。
     */
    private void startContainer(Props props, int httpPort, int tlsPort) throws Exception {
        edap = newEdap(props);
        container = new Container(new File("ignored"), EnumSet.of(Capability.HTTP));
        container.attach(edap);

        // 路由：一条 path，一个 GET handler
        PathInfo pathInfo = new PathInfo();
        pathInfo.setPath(PATH);
        pathInfo.setMatchPath(PATH);
        pathInfo.setFound(true);
        pathInfo.setHandlerOption(HttpHandleOption.defaultHttpHandleOption());
        int getIndex = MethodCache.instance().getMethodIndex("GET");
        HttpHandler[] handlers = new HttpHandler[getIndex + 1];
        handlers[getIndex] = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
        };
        pathInfo.setHttpHandlers(handlers);

        Map<FastBufDataRange, PathInfo> table = new HashMap<>();
        table.put(HttpFastBufDataRange.from(PATH), pathInfo);
        container.deployAppRoutes("app1", table);

        // registry 的 CURRENT 槽要有 ctx —— rebuildHttpMapping 只从 CURRENT 槽读 path 表
        AppContext ctx = (AppContext) UnsafeUtil.allocateInstance(AppContext.class);
        registry(container).put("app1", new SlotEntry(null, ctx, null));
        rebuildHttpMapping(container);

        // bind：生产顺序是 edap.run() 先、container.run(edap) 后，
        // 容器自己的 "apps" 组是 container.start() 里 appServerGroup.run() 绑的；
        // 这里用 edap.run() 绑（同一个 ServerGroup.run() 路径），顺便让 edap.stop() 能收端口。
        edap.run();
        awaitListening(httpPort);
        awaitListening(tlsPort);
    }

    /**
     * 造一个带指定配置的 {@link Edap}（反射写私有字段 edapProps）。
     */
    private static Edap newEdap(Props props) throws Exception {
        Edap edap = new Edap();
        Field f = Edap.class.getDeclaredField("edapProps");
        f.setAccessible(true);
        f.set(edap, props);
        return edap;
    }

    /**
     * 组装 {@code container.http.*} 配置。
     */
    private static Props props(int httpPort, int tlsPort, String cert, String key) {
        Map<String, Object> tls = new HashMap<>();
        tls.put("enabled", true);
        tls.put("port", tlsPort);
        if (cert != null) {
            tls.put("certificate", cert);
        }
        if (key != null) {
            tls.put("privateKey", key);
        }
        return props(httpPort, tlsPort, null, null, tls);
    }

    private static Props props(int httpPort, int tlsPort, String cert, String key, Map<String, Object> tls) {
        Map<String, Object> http = new HashMap<>();
        http.put("port", httpPort);
        http.put("tls", new JsonObjectImpl(tls));
        Map<String, Object> containerCfg = new HashMap<>();
        containerCfg.put("http", new JsonObjectImpl(http));
        Map<String, Object> root = new HashMap<>();
        root.put("container", new JsonObjectImpl(containerCfg));
        return new Props(root);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, SlotEntry> registry(Container container) throws Exception {
        Field f = Container.class.getDeclaredField("registry");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, SlotEntry>) f.get(container);
    }

    private static void rebuildHttpMapping(Container container) throws Exception {
        Method m = Container.class.getDeclaredMethod("rebuildHttpMapping");
        m.setAccessible(true);
        m.invoke(container);
    }

    // ------------------------------------------------------------------
    // 客户端
    // ------------------------------------------------------------------

    private static HttpResponse<String> send(HttpClient client, String scheme, int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(scheme + "://127.0.0.1:" + port + PATH))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 客户端信任测试 CA。
     */
    private static SSLContext trustingCa() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(resource("/edap-test-ca.pem"))) {
            keyStore.setCertificateEntry("ca", cf.generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static String pemCertificate() throws Exception {
        return resource("/edap-test-cert-chain.pem").toString();
    }

    private static String pemPrivateKey() throws Exception {
        return resource("/edap-test-key.pem").toString();
    }

    private static Path resource(String name) throws Exception {
        java.net.URL url = ContainerTlsEndToEndTest.class.getResource(name);
        assertNotNull(url, "测试资源不在 classpath 上: " + name);
        return Paths.get(url.toURI());
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (Exception e) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new IllegalStateException("端口 " + port + " 未在 5s 内开始监听");
    }
}
