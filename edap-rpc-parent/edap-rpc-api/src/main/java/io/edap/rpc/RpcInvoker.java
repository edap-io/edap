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

package io.edap.rpc;

/**
 * RPC框架的执行器，将原有调用转换为RpcRequest并发起调用后获取RpcResponse的对象
 */
public interface RpcInvoker {

    /**
     * 远程执行的操作
     * @param request
     * @return
     */
    RpcResponse invoke(RpcRequest request);
}
