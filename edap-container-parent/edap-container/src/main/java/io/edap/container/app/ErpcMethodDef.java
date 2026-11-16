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

package io.edap.container.app;

import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcMethodIds;

/**
 * <b>单个 app 内</b>的一个 eRPC 方法定义 —— {@link AppContext} 持有它，
 * {@link io.edap.container.Container} 在合并期把它变成带 methodId 的
 * {@link io.edap.erpc.ErpcMethod}。
 *
 * <p><b>为什么要与 {@link io.edap.erpc.ErpcMethod} 分开</b>：</p>
 * <ul>
 *   <li>{@code io.edap.erpc.ErpcMethod} 是<b>线上</b>的方法 —— 必须有确定的 methodId
 *       （服务端靠它路由，客户端靠它寻址）。</li>
 *   <li>methodId 的分配规则里 {@code increment} 策略需要<b>全局</b>方法键集合才能排序，
 *       单个 app 看不到全局 ⇒ per-app 阶段<b>不能</b>定下 methodId。</li>
 * </ul>
 *
 * <p>所以 per-app 阶段只产出「方法是什么」（服务名 / 方法名 / handler / DTO 类型），
 * methodId 留到 {@link io.edap.container.Container} 合并所有 CURRENT 槽时统一分配 ——
 * 与 gRPC 侧的「per-app 表存 {@code :path}，Container 合并后整张发布」是同一套形状。</p>
 *
 * <p>与方法键的关系见 {@link ErpcMethodIds#key(String, String)}。</p>
 */
public final class ErpcMethodDef {

    /** proto 服务名（{@code @ProtoService.serviceName()}）；为空时退化为 proto 接口 FQCN。 */
    private final String   serviceName;

    /** java 方法名。 */
    private final String   methodName;

    /** 字节码生成的处理器。 */
    private final ErpcHandler handler;

    /** 请求消息的 DTO 类型。 */
    private final Class<?> reqType;

    /** 响应消息的 DTO 类型。 */
    private final Class<?> respType;

    /**
     * {@code @ProtoErpc(methodId = ...)} 显式指定的 methodId；{@code -1} = 交给容器自动分配。
     */
    private final int      explicitMethodId;

    public ErpcMethodDef(String serviceName, String methodName, ErpcHandler handler,
                         Class<?> reqType, Class<?> respType, int explicitMethodId) {
        this.serviceName      = serviceName;
        this.methodName       = methodName;
        this.handler          = handler;
        this.reqType          = reqType;
        this.respType         = respType;
        this.explicitMethodId = explicitMethodId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public ErpcHandler getHandler() {
        return handler;
    }

    public Class<?> getReqType() {
        return reqType;
    }

    public Class<?> getRespType() {
        return respType;
    }

    /**
     * 显式指定的 methodId；{@code -1} 表示没有显式指定（走容器自动分配）。
     */
    public int getExplicitMethodId() {
        return explicitMethodId;
    }

    /**
     * 方法唯一键：{@code serviceName + "." + methodName}。
     *
     * <p>★ <b>这是 methodId 的计算输入</b> —— 客户端与服务端各自拿同一个键算号，
     * 必须算出一样的结果（见 {@link ErpcMethodIds}）。</p>
     */
    public String key() {
        return ErpcMethodIds.key(serviceName, methodName);
    }

    @Override
    public String toString() {
        return "ErpcMethodDef{" + key() + ", explicitId=" + explicitMethodId + "}";
    }
}
