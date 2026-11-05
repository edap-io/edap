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

package io.edap.http.server.test.http2;

import io.edap.Edap;
import io.edap.http.HttpHandler;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import io.edap.http.server.tls.TlsConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 手工验证用的常驻服务 —— 供 {@code scripts/verify-http2.sh} 驱动。
 *
 * <p>起两个端口：明文（h2c prior knowledge / h2c Upgrade / HTTP/1.1）与
 * TLS（ALPN 协商 {@code h2} 或 {@code http/1.1}）。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   java -cp "$CP" io.edap.http.server.test.http2.Http2VerifyServer &lt;plainPort&gt; &lt;tlsPort&gt;
 * </pre>
 *
 * <p>keystore 用仓库里的自签证书 {@code src/test/resources/edap-test-keystore.p12}
 * （由 {@code scripts/gen-dev-keystore.sh} 生成，公开无风险）。</p>
 */
public final class Http2VerifyServer {

    private static final String BODY = "hello edap http2";

    /** 测试 keystore 的口令（仓库里的自签证书，公开无风险） */
    private static final String KEYSTORE_PASSWORD = "edap-test";

    private Http2VerifyServer() {
    }

    public static void main(String[] args) throws Exception {
        int plainPort = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        int tlsPort = args.length > 1 ? Integer.parseInt(args[1]) : 18443;

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
        };
        HttpHandler echo = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(req.getPath() + "|" + req.getMethod());
        };

        HttpServerBuilder builder = new HttpServerBuilder()
                .get("/hello", hello)
                .get("/echo", echo)
                .listen(plainPort)
                .listenTls(tlsPort)
                .tls(TlsConfig.builder()
                        .keyStore(keyStorePath().toString(), KEYSTORE_PASSWORD)
                        .keyStoreType("PKCS12")
                        .build());

        List<HttpServer> servers = builder.buildAll();
        Edap edap = new Edap();
        servers.forEach(edap::addServer);
        edap.run();

        System.out.println("READY plain=" + plainPort + " tls=" + tlsPort);
        System.out.flush();

        // 常驻 —— 由外部脚本 kill
        Thread.currentThread().join();
    }

    private static Path keyStorePath() {
        Path p = Paths.get("src/test/resources/edap-test-keystore.p12");
        if (!Files.exists(p)) {
            throw new IllegalStateException("测试 keystore 不存在: " + p.toAbsolutePath()
                    + "（用 scripts/gen-dev-keystore.sh 生成）");
        }
        return p;
    }
}
