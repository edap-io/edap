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

package io.edap.protobuf;

/**
 * 定义Protobuf协议的解码器接口
 * @param <T> 解码器的对象类型
 */
public interface ProtoBufDecoder<T> {

    /**
     * 从ProtoBufReader中反序列化java的POJO对象
     * @param reader ProtoBufReader对象
     * @return 返回反序列化后的POJO对象
     * @throws ProtoBufException 如果给定的数据不是正确的ProtoBuf编码的数据则抛错
     */
    T decode(ProtoBufReader reader) throws ProtoBufException;

    default T decode(ProtoBufReader reader, int endGroupTag) throws ProtoBufException {
        throw new RuntimeException("Not support method readMessage(ProtoBufDecoder<T> decoder, int endTag)");
    }
}