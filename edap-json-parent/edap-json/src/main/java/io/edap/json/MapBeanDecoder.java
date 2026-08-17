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

import java.util.Map;

/**
 * {@code Map<String, Object> → JavaBean} 解码器接口（edap-json 运行时路径）。
 *
 * <p>与 {@link JsonDecoder} 平级但 <b>不</b>共用父接口 —— {@code JsonDecoder.decode}
 *     抛 {@code InvocationTargetException / InstantiationException / IllegalAccessException}
 *     （JSON 反射遗留），Map 路径无此需要。</p>
 *
 * <p><b>典型用法</b>（{@link Eson#toBean} 实现）：
 * <pre>{@code
 *   MapBeanDecoder<User> decoder = JsonCodecRegister.instance().getMapBeanDecoder(User.class);
 *   User user = decoder.decode(jsonObject);   // JsonObject extends Map<String, Object>
 * }</pre></p>
 *
 * <p><b>实现来源</b>：每个 POJO class 对应一个由 {@code MapBeanDecoderGenerator} 生成的
 *     实现类（命名规则见 {@link io.edap.json.util.JsonUtil#buildMapBeanDecoderName}）；
 *     当 ASM 生成失败时 fallback 到 {@code MapReflectDecoder}（反射版）。</p>
 *
 * <p><b>单例语义</b>：实例由 {@code JsonCodecRegister.getMapBeanDecoder} 缓存，
 *     无状态；可安全共享。生成类内部如有嵌套 POJO sub-decoder，会通过 lazy init
 *     静态字段在 {@code <init>} 阶段填充（双检锁），不依赖外部 cache 同步。</p>
 *
 * <p><b>throws 语义</b>：当前接口不抛 checked exception。value 形态非法
 *     （如 Map key 缺失、类型不匹配）→ 由实现类决定：要么 cast 运行时抛
 *     {@code ClassCastException}，要么 {@code try/catch} 后抛 {@code RuntimeException}
 *     包装。</p>
 */
public interface MapBeanDecoder<T> {

    /**
     * 将 {@code map} 转换为 {@code T} 类型实例。
     *
     * <p>不修改入参 map；map 中缺失的字段对应 bean 字段保持默认值（构造器默认）。
     *     值类型与字段不一致时按实现策略处理（{@code JsonUtil.getXxxValue}/{@code CHECKCAST}/
     *     递归 {@code decode}）。</p>
     *
     * @param map 字段名 → 值的 Map（通常为 {@code JsonObject}，已 typed Java 对象）
     * @return 新构造的 {@code T} 实例
     */
    T decode(Map<String, Object> map);
}
