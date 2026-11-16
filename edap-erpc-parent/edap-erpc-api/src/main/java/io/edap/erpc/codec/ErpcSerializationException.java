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

package io.edap.erpc.codec;

/**
 * 载荷序列化/反序列化失败。
 *
 * <p>把不同序列化框架各自的异常（protobuf 的 {@code EncodeException}/{@code ProtoException}、
 * json 的 {@code JsonParseException} …）统一成本异常，服务端据此回 ERROR 帧、
 * 客户端据此让调用方拿到一个明确的失败原因。</p>
 */
public class ErpcSerializationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ErpcSerializationException(String message) {
        super(message);
    }

    public ErpcSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
