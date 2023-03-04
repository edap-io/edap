/*
 * Copyright 2023 The edap Project
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

package io.edap.json;

import java.lang.reflect.InvocationTargetException;

/**
 * 定义JSON反序列化解码器
 * @param <T> 对象的类型
 */
public interface Decoder<T> {

    /**
     * 使用JsonParser解析器解码一个类实例
     * @param jsonReader
     */
    T decode(JsonReader jsonReader) throws InvocationTargetException, InstantiationException, IllegalAccessException;
}
