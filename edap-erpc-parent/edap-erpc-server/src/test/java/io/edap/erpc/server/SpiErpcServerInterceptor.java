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

import io.edap.erpc.ErpcServerInterceptor;
import io.edap.erpc.ErpcRequest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPI 装载测试用的服务端拦截器（测试夹具）。
 *
 * <p>注册在 {@code src/test/resources/META-INF/services/io.edap.erpc.ErpcServerInterceptor}。
 * 它只记 methodId、不改请求 ⇒ 不会影响同模块的其它测试。</p>
 */
public class SpiErpcServerInterceptor implements ErpcServerInterceptor {

    /**
     * 走过的 methodId 集合。
     *
     * <p>★ <b>为什么记 methodId 而不是计数</b>：本模块的其它测试也会起
     * {@code ErpcServer} ⇒ 同一个 JVM 里计数会互相污染；methodId 是每个用例独有的。</p>
     */
    public static final Set<Integer> SEEN_METHOD_IDS = ConcurrentHashMap.newKeySet();

    @Override
    public void beforeCall(ErpcRequest request) {
        SEEN_METHOD_IDS.add(request.getMethodId());
    }
}
