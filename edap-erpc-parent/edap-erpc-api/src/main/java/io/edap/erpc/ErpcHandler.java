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
 * eRPC 服务的处理器。
 *
 * <p>由容器根据 proto 生成的服务接口，用字节码生成为本接口的实现：解码请求载荷、
 * 调业务方法、把结果编码后写回响应帧。</p>
 *
 * <p>★ <b>v1 只支持一元调用</b>：没有流式形态，handler 一次处理完一个请求。</p>
 */
public interface ErpcHandler {

    /**
     * 处理一次 eRPC 调用。
     *
     * @param req  请求帧上下文（载荷未解码）
     * @param resp 响应帧上下文（handler 往里填载荷或异常）
     * @throws Exception 处理失败，框架会把它转换成 ERROR 帧
     */
    void handle(ErpcRequest req, ErpcResponse resp) throws Exception;
}
