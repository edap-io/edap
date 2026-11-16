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

package io.edap.erpc.server;

/**
 * eRPC 协议错误 —— 收到不是 eRPC 协议的字节（magic 不符）或不支持的协议版本。
 *
 * <p>这类错误**不是**业务错误：业务错误回 ERROR 帧、连接继续用；
 * 协议错误说明对端根本不是 eRPC 客户端 ⇒ 直接关连接。</p>
 */
public class ErpcProtocolException extends RuntimeException {

    public ErpcProtocolException(String message) {
        super(message);
    }
}
