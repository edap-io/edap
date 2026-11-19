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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc;

/**
 * eRPC 的<b>连接级</b>鉴权器 —— 校验 AUTH 帧带来的凭据。
 *
 * <p>★ <b>连接级</b>：客户端建连后发<b>一个</b> AUTH 帧（载荷是 UTF-8 凭据字符串），
 * 服务端校验通过后把「已鉴权」标记 + 认证主体记进<b>那条连接</b>的
 * {@code RpcChannelContext} ⇒ <b>同一连接的后续请求免校验</b>
 * （连接不断就不用重新校验）。</p>
 *
 * <p>★ <b>与请求级元数据是两回事</b>：本接口只管「这条连接是谁」，
 * 请求级的东西（txId 等）走 {@code RpcContext.metadata()}。</p>
 *
 * <p><b>调用位置</b>：跑在服务端的<b>业务 worker 线程池</b>里（不是 IO 线程）——
 * 校验可能要查库 / 验签，不能挡住 IO 线程。</p>
 *
 * <p><b>线程安全</b>：同一个实例会被多条连接的 worker 线程并发调用 ⇒ 实现必须线程安全。</p>
 */
public interface ErpcAuthenticator {

    /**
     * 校验连接凭据。
     *
     * @param credential AUTH 帧的载荷（UTF-8 字符串）；可能为空串
     * @return 校验结果：成功带认证主体，失败带原因
     */
    ErpcAuthResult authenticate(String credential);
}
