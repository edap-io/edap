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
 * 定义Protobuf协议的编码器接口
 * @param <T> java的POJO的对象类型
 */
public interface ProtoBufEncoder<T> {

    /**
     * 将Java的POJO对象做ProtoBuf编码写到BufOut中
     * @param writer 写入byte[]的BufOut的对象
     * @param t java的POJO对象
     * @throws EncodeException 编码异常
     */
    void encode(ProtoBufWriter writer, T t) throws EncodeException;
}