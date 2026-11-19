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

package io.edap.erpc.server;

import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcServerInterceptor;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 服务端拦截器的 SPI 装载测试。
 *
 * <p>{@code META-INF/services/io.edap.erpc.ErpcServerInterceptor} 里的实现
 * 必须在 {@code ErpcServer} 构造时就被装上 —— 不需要使用者写任何代码。</p>
 */
public class ErpcServerInterceptorSpiTest {

    /** 只有本用例会用到的方法号 —— 避免与同模块其它用例串味。 */
    private static final int METHOD_SPI = 2901;

    @Test
    public void spiInterceptorIsRegisteredAtConstruction() {
        ErpcServer server = new ErpcServer();

        boolean found = false;
        for (ErpcServerInterceptor interceptor : server.interceptors()) {
            if (interceptor instanceof SpiErpcServerInterceptor) {
                found = true;
                break;
            }
        }
        assertTrue(found, "SPI 拦截器没有在构造时装上: " + server.interceptors());
    }

    @Test
    public void spiInterceptorIsLoadedAndRuns() throws IOException {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_SPI, new ErpcMethod(METHOD_SPI, "test.SpiService", "spi",
            (req, resp) -> { }, ErpcTestDto.class, ErpcTestDto.class));

        ErpcCodec pb = ErpcCodecRegistry.instance().byName("protobuf");
        ErpcWriter writer = pb.encode(new ErpcTestDto("x", 1));
        byte[] payload = writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());

        try (ErpcTestServer server = ErpcTestServer.start(methods)) {
            try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
                raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                    METHOD_SPI, 0, 1, 0L, payload);
                raw.readFrame();
            }
        }

        assertTrue(SpiErpcServerInterceptor.SEEN_METHOD_IDS.contains(METHOD_SPI),
            "SPI 装出来的拦截器没有跑: " + SpiErpcServerInterceptor.SEEN_METHOD_IDS);
    }
}
