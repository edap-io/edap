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

package io.edap.erpc;

/**
 * eRPC 协议错误 —— 收到不是 eRPC 协议的字节（magic 不符）、不支持的协议版本，
 * 或者帧内容区结构不对（RPC 上下文段长度越界……）。
 *
 * <p>这类错误<b>不是</b>业务错误：业务错误回 ERROR 帧、连接继续用；
 * 协议错误说明对端发的字节我们解不了 ⇒ 直接关连接。</p>
 *
 * <p>★ <b>放在 {@code edap-erpc-api}</b>（而不是服务端模块）：协议错误是
 * <b>协议层</b>的概念，客户端与服务端都要能表达它。</p>
 */
public class ErpcProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ErpcProtocolException(String message) {
        super(message);
    }

    public ErpcProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
