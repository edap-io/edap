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

package io.edap.erpc;

/**
 * 一个可以通过 eRPC 调用的方法。
 *
 * <p>由容器根据 proto 生成的服务接口构造，注册到 {@code ErpcServer} 的路由表上
 * （{@code methodId → ErpcMethod}）。</p>
 */
public final class ErpcMethod {

    /** 方法号 —— 线上帧头里的 methodId。 */
    private final int methodId;

    /** proto 服务名（proto package + "." + service 名）。 */
    private final String serviceName;

    /** java 方法名。 */
    private final String methodName;

    /** 方法的实现（字节码生成）。 */
    private final ErpcHandler handler;

    /** 请求消息的 DTO 类型。 */
    private final Class<?> reqType;

    /** 响应消息的 DTO 类型。 */
    private final Class<?> respType;

    public ErpcMethod(int methodId, String serviceName, String methodName,
                      ErpcHandler handler, Class<?> reqType, Class<?> respType) {
        if (methodId <= 0) {
            throw new IllegalArgumentException("eRPC 方法号必须为正数: " + methodId);
        }
        if (handler == null) {
            throw new IllegalArgumentException("eRPC 方法的 handler 不能为空: "
                + serviceName + "." + methodName);
        }
        this.methodId = methodId;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.handler = handler;
        this.reqType = reqType;
        this.respType = respType;
    }

    public int getMethodId() {
        return methodId;
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
     * 方法唯一键：{@code serviceName + "." + methodName}。
     */
    public String key() {
        return ErpcMethodIds.key(serviceName, methodName);
    }

    @Override
    public String toString() {
        return "ErpcMethod{" + key() + ", id=" + methodId + "}";
    }
}
