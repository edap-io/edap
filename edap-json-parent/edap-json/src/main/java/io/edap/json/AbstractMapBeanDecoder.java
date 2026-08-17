/*
 * Copyright 2026 The edap Project
 *
 * The edap Project licenses this file to you under the Apache License,
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

/**
 * {@link MapBeanDecoder} 的抽象基类，供 ASM 生成的解码器继承。
 *
 * <p>平行于 {@link AbstractDecoder}（{@link JsonDecoder} 一侧的基类）。当前为空——{@code MapBeanDecoderGenerator}
 *     让生成类 {@code extends AbstractMapBeanDecoder} 是为了后续可在基类挂共享 helper
 *     （如 {@code readList(Map, Class)}、{@code castToXxx(Object)}）而不破坏已生成
 *     字节码的稳定性（生成字节码中 {@code super()} 调用指向 {@code AbstractMapBeanDecoder}）。</p>
 *
 * <p><b>为什么基类带泛型 {@code <Object>}</b>：与生成类的 {@code implements MapBeanDecoder<T>}
 *     签名解耦 —— 基类不需要知道具体 POJO 类型。生成类通过 {@code implements MapBeanDecoder<T>}
 *     单独擦除后提供的 {@code Object decode(Map)} 桥接方法（{@code ACC_BRIDGE | ACC_SYNTHETIC}）
 *     满足 {@code MapBeanDecoder<Object>.decode(Map)} 的契约。</p>
 */
public abstract class AbstractMapBeanDecoder implements MapBeanDecoder<Object> {
}
