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

package io.edap.erpc.client;

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 客户端拦截器的 SPI 装载测试。
 *
 * <p>{@code META-INF/services/io.edap.erpc.client.ErpcClientInterceptor} 里的实现
 * 必须在 {@code ErpcClient} 构造时就被装上 —— 不需要使用者写任何代码。</p>
 */
public class ErpcClientInterceptorSpiTest {

    /** 只有本用例会用到的方法号 —— 避免与同模块其它用例串味。 */
    private static final int METHOD_SPI = 1901;

    /**
     * 构造 {@code ErpcClient} 时就装上了（不用等第一次调用）。
     */
    @Test
    public void spiInterceptorIsRegisteredAtConstruction() {
        ErpcCodec codec = ErpcCodecRegistry.instance().byName("protobuf");
        assertNotNull(codec, "序列化器 protobuf 没注册");

        try (ErpcClient client = new ErpcClient(
            Arrays.asList(new ErpcNode("127.0.0.1", 1)), codec, ErpcClientOptions.defaults())) {

            boolean found = false;
            for (ErpcClientInterceptor interceptor : client.interceptors()) {
                if (interceptor instanceof SpiErpcClientInterceptor) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "SPI 拦截器没有在构造时装上: " + client.interceptors());
        }
    }

    @Test
    public void spiInterceptorIsLoadedAndRuns() throws IOException {
        ErpcCodec codec = ErpcCodecRegistry.instance().byName("protobuf");
        assertNotNull(codec, "序列化器 protobuf 没注册");

        try (MiniErpcServer server = MiniErpcServer.start(codec, MiniErpcServer.Mode.ECHO);
             ErpcClient client = new ErpcClient(
                 Arrays.asList(new ErpcNode("127.0.0.1", server.port())),
                 codec, ErpcClientOptions.defaults())) {

            client.invoke(METHOD_SPI, new ClientDemoDto("spi", 1), ClientDemoDto.class);
        }

        assertTrue(SpiErpcClientInterceptor.SEEN_METHOD_IDS.contains(METHOD_SPI),
            "SPI 装出来的拦截器没有跑: " + SpiErpcClientInterceptor.SEEN_METHOD_IDS);
    }
}
