/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc;

/**
 * gRPC 服务的处理器。
 *
 * <p>由容器根据 proto 生成的服务接口，用字节码生成为本接口的实现：调用对应的
 * 业务方法，把结果通过 {@link GrpcCall} 回写给客户端。
 */
public interface GrpcHandler {

    /**
     * 处理一次 gRPC 调用。
     *
     * @param call 调用上下文
     * @throws Exception 处理失败，框架会把它转换成 gRPC 的错误状态
     */
    void handle(GrpcCall call) throws Exception;
}
