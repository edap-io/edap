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

package io.edap.rpc;

/**
 * RPC 协议。
 *
 * <p>业务代码不应该靠「判断协议」做分支，但诊断、埋点、日志里经常需要知道
 * 「这次调用走的是哪条链路」—— 这个枚举就是给这些场合用的。</p>
 */
public enum RpcProtocol {

    /** gRPC（HTTP/2 + protobuf）。 */
    GRPC,

    /** eRPC（edap 自有的 socket + 32 字节定长帧头）。 */
    ERPC
}
