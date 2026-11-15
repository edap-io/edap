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

package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 标记 proto 服务方法可经 eRPC 协议调用，并携带 eRPC 运行时所需的元信息。
 *
 * <p><b>与 {@link ProtoGrpc} 的关键差别</b>：{@code @ProtoGrpc} <b>必须</b>写
 * {@code req()/resp()}（流式方法的 DTO 类型在 JVM descriptor 里被泛型擦除掉了，
 * 注解是唯一载体）；而 eRPC 的 DTO 类型<b>不需要</b>写 —— v1 只支持一元调用，
 * 请求/响应的类型直接取自方法签名（1 个参数 + 非 void 返回值）。
 * 所以 {@code @ProtoErpc} 只带一个「显式 methodId」的可选项。</p>
 *
 * <p><b>不写这个注解也能用</b>：eRPC 的绑定不依赖方法级协议注解 ——
 * {@code @ProtoService} 标注的接口里，所有「一元」方法自动经 eRPC 暴露。
 * 这个注解只在需要<b>覆盖</b>自动算出来的 methodId 时才需要。</p>
 *
 * @see io.edap.erpc.ErpcMethodIds
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoErpc {

    /**
     * 显式指定 methodId —— 覆盖容器按策略自动算出来的号。
     *
     * <p>用于「兼容老系统 / 固定协议号」的场景（对应 proto 里的
     * {@code option (edap.rpc).method = 10001;}）。</p>
     *
     * <p>取值必须为正数；{@code -1}（默认）表示交给容器按
     * {@code edap.binding.erpc.methodIdStrategy} 自动分配。</p>
     */
    int methodId() default -1;
}
