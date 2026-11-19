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

package io.edap.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 拦截器的 SPI 装载器 —— 扫 {@code META-INF/services/<接口全名>}。
 *
 * <p>用法：在 jar 里放一个文本文件，文件名是拦截器接口的全限定名，
 * 内容是实现类的全限定名（每行一个）：</p>
 * <pre>{@code
 *   # META-INF/services/io.edap.grpc.GrpcServerInterceptor
 *   com.example.AuthInterceptor
 *   com.example.AuditInterceptor
 * }</pre>
 *
 * <p>★ <b>ClassLoader 用接口自己的</b>（{@code type.getClassLoader()}），不用 TCCL ——
 * TCCL 会随线程变（容器里可能是某个 app 的 CL），装载结果不确定。
 * 接口的 CL 一定能看到「和接口放在一起」的实现。</p>
 *
 * <p>★ <b>失败即 fail-fast</b>：SPI 文件是使用者显式声明的意图，装不上（类不存在、
 * 没有无参构造器、构造抛异常）是配置错误 ⇒ 直接失败，不静默跳过。
 * 先例：{@code io.edap.erpc.codec.ErpcCodecRegistry} 的静态块同样 fail-fast。</p>
 */
public final class InterceptorLoader {

    private InterceptorLoader() {
    }

    /**
     * 装载某类拦截器。
     *
     * @param type 拦截器接口
     * @param <T>  拦截器类型
     * @return 装出来的实例（按 SPI 文件里的顺序）；没有 SPI 文件 ⇒ 空列表
     * @throws IllegalStateException 装载失败（类不存在 / 构造失败）
     */
    public static <T> List<T> load(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("拦截器类型不能为 null");
        }
        List<T> out = new ArrayList<>();
        try {
            for (T interceptor : ServiceLoader.load(type, type.getClassLoader())) {
                out.add(interceptor);
            }
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("装载拦截器失败: " + type.getName(), e);
        }
        return out;
    }
}
