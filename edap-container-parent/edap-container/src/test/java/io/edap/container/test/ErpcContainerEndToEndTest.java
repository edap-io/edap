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

package io.edap.container.test;

import io.edap.Edap;
import io.edap.container.AppContext;
import io.edap.container.app.asm.HandlerAsmGenerator;
import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcMethodIds;
import io.edap.erpc.client.ErpcClient;
import io.edap.erpc.client.ErpcClientOptions;
import io.edap.erpc.client.ErpcNode;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.server.ErpcServer;
import io.edap.protobuf.annotation.ProtoField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器接入的端到端测试 —— <b>真起服务端、真连 socket</b>。
 *
 * <p>验证的是「容器用 ASM 生成的 eRPC handler」能不能真的被 eRPC 客户端调到：</p>
 * <pre>
 *   ASM 生成的 ErpcHandler ──► ErpcServer.setMethods ──► socket ──► ErpcClient.invoke
 * </pre>
 *
 * <p>methodId 用 {@link ErpcMethodIds#hashKey(String, long)} 算 —— 客户端与容器
 * 各算一遍必须一样（这是 eRPC methodId 的核心约束：纯函数）。</p>
 */
public class ErpcContainerEndToEndTest {

    /** proto 服务名（proto package + "." + service 名）。 */
    private static final String SERVICE_NAME = "demo.v1.DemoService";

    public static class DemoRequest {
        @ProtoField(tag = 1, type = io.edap.protobuf.wire.Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = io.edap.protobuf.wire.Field.Type.INT32)
        public int value;
    }

    public static class DemoResponse {
        @ProtoField(tag = 1, type = io.edap.protobuf.wire.Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = io.edap.protobuf.wire.Field.Type.INT32)
        public int value;
    }

    public interface DemoService {
        DemoResponse update(DemoRequest req);
    }

    public static class DemoServiceImpl implements DemoService {
        @Override
        public DemoResponse update(DemoRequest req) {
            DemoResponse resp = new DemoResponse();
            resp.name = req == null ? null : req.name;
            resp.value = req == null ? -1 : req.value * 2;
            return resp;
        }
    }

    /**
     * 生成类的专用 ClassLoader —— defineClass 不走双亲委派。
     */
    private static final class DefiningLoader extends ClassLoader {
        DefiningLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] code) {
            return defineClass(name, code, 0, code.length);
        }
    }

    /**
     * 用 ASM 生成 handler（与容器里走的是同一条路）。
     */
    private static ErpcHandler generateHandler(Object bean) throws Exception {
        Method method = DemoService.class.getMethod("update", DemoRequest.class);
        byte[] code = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                ErpcHandler.class, DemoService.class, method, new java.util.ArrayList<>(),
                ErpcContainerEndToEndTest.class.getClassLoader());

        String binaryName = HandlerAsmGenerator.handlerName(ErpcHandler.class, DemoService.class, method);
        DefiningLoader dl = new DefiningLoader(ErpcContainerEndToEndTest.class.getClassLoader());
        Class<?> cls = dl.define(binaryName, code);
        ErpcHandler handler = (ErpcHandler) cls.getConstructor(AppContext.class).newInstance((AppContext) null);
        java.lang.reflect.Field beanField = cls.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(null, bean);
        return handler;
    }

    @Test
    public void containerGeneratedHandlerIsCallableOverTheWire() throws Exception {
        int port = freePort();
        ErpcServer server = new ErpcServer();

        // methodId 由方法键算出（客户端与容器各算一遍，必须一样）
        String key = ErpcMethodIds.key(SERVICE_NAME, "update");
        int methodId = ErpcMethodIds.hashKey(key, ErpcMethodIds.DEFAULT_SEED);

        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(methodId, new ErpcMethod(methodId, SERVICE_NAME, "update",
                generateHandler(new DemoServiceImpl()), DemoRequest.class, DemoResponse.class));
        server.setMethods(methods);
        server.listen(port);

        Edap edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);

        ErpcClient client = null;
        try {
            awaitListening(port);
            ErpcCodec ser = ErpcCodecRegistry.instance().defaultCodec();
            client = new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", port)),
                    ser, ErpcClientOptions.defaults());

            DemoRequest req = new DemoRequest();
            req.name = "edap";
            req.value = 21;
            DemoResponse resp = client.invoke(methodId, req, DemoResponse.class);
            assertNotNull(resp);
            assertEquals("edap", resp.name);
            assertEquals(42, resp.value);
        } finally {
            if (client != null) {
                client.close();
            }
            edap.stop();
            server.stop();
        }
    }

    /**
     * ★ 同一个 handler 走 json codec 也能通 —— codec 是<b>按帧</b>协商的，
     * 容器生成的 handler 用的是 {@code req.getSerializer()}，不是写死 protobuf。
     */
    @Test
    public void containerGeneratedHandlerWorksWithJsonCodecToo() throws Exception {
        int port = freePort();
        ErpcServer server = new ErpcServer();

        String key = ErpcMethodIds.key(SERVICE_NAME, "update");
        int methodId = ErpcMethodIds.hashKey(key, ErpcMethodIds.DEFAULT_SEED);

        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(methodId, new ErpcMethod(methodId, SERVICE_NAME, "update",
                generateHandler(new DemoServiceImpl()), DemoRequest.class, DemoResponse.class));
        server.setMethods(methods);
        server.listen(port);

        Edap edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);

        ErpcClient client = null;
        try {
            ErpcCodec json = ErpcCodecRegistry.instance().byName("json");
            assertNotNull(json);
            client = new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", port)),
                    json, ErpcClientOptions.defaults());

            DemoRequest req = new DemoRequest();
            req.name = "js";
            req.value = 3;
            DemoResponse resp = client.invoke(methodId, req, DemoResponse.class);
            assertEquals("js", resp.name);
            assertEquals(6, resp.value);
        } finally {
            if (client != null) {
                client.close();
            }
            edap.stop();
            server.stop();
        }
    }

    /**
     * 业务抛异常 ⇒ 客户端收到带类名与 message 的错误（不是静默挂死）。
     */
    @Test
    public void businessExceptionBecomesErrorOnTheWire() throws Exception {
        int port = freePort();
        ErpcServer server = new ErpcServer();

        String key = ErpcMethodIds.key(SERVICE_NAME, "update");
        int methodId = ErpcMethodIds.hashKey(key, ErpcMethodIds.DEFAULT_SEED);

        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(methodId, new ErpcMethod(methodId, SERVICE_NAME, "update",
                generateHandler(new DemoServiceImpl() {
                    @Override
                    public DemoResponse update(DemoRequest req) {
                        throw new IllegalStateException("boom");
                    }
                }), DemoRequest.class, DemoResponse.class));
        server.setMethods(methods);
        server.listen(port);

        Edap edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);

        ErpcClient client = null;
        try {
            ErpcCodec ser = ErpcCodecRegistry.instance().defaultCodec();
            client = new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", port)),
                    ser, ErpcClientOptions.defaults());

            DemoRequest req = new DemoRequest();
            req.name = "x";
            req.value = 1;
            final ErpcClient c = client;
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> c.invoke(methodId, req, DemoResponse.class));
            assertTrue(e.getMessage().contains("IllegalStateException"),
                    "错误信息里应带异常类名: " + e.getMessage());
            assertTrue(e.getMessage().contains("boom"),
                    "错误信息里应带异常描述: " + e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
            edap.stop();
            server.stop();
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitListening(int port) throws IOException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                try {
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
        throw new IllegalStateException("端口 " + port + " 未在 5s 内开始监听");
    }
}
