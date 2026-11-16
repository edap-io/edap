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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 注册表对「扩展装载失败」的容忍度。
 *
 * <p>★ 测试 classpath 上有一份<b>故意坏的</b> ServiceLoader 登记
 * （{@code src/test/resources/META-INF/services/io.edap.erpc.codec.ErpcCodec}
 * 指向一个不存在的类）⇒ {@code ServiceLoader} 迭代到这里会抛
 * {@link java.util.ServiceConfigurationError}。</p>
 *
 * <p>★ 这个测试的价值在于<b>反向锁定</b>：如果有人把
 * {@code ErpcCodecRegistry} 里那个 catch 去掉，注册表的静态初始化就会抛
 * {@code ExceptionInInitializerError} ⇒ 连内置的 protobuf / json 都用不了 ⇒ 本测试红。</p>
 */
public class ErpcCodecRegistryTest {

    /**
     * 扩展装载失败，内置实现必须照常可用。
     */
    @Test
    public void brokenExtensionDoesNotBreakBuiltins() {
        ErpcCodecRegistry registry = ErpcCodecRegistry.instance();
        assertNotNull(registry.byName("protobuf"), "内置 protobuf 必须可用");
        assertNotNull(registry.byName("json"), "内置 json 必须可用");
        assertNotNull(registry.defaultCodec(), "默认编解码器必须可用");
        assertNotNull(registry.byId(ErpcCodecIds.PROTOBUF));
        assertNotNull(registry.byId(ErpcCodecIds.JSON));
    }

    /**
     * 坏掉的扩展<b>没有</b>被注册进来（不能出现半截实现）。
     */
    @Test
    public void brokenExtensionIsNotRegistered() {
        ErpcCodecRegistry registry = ErpcCodecRegistry.instance();
        assertNull(registry.byName("missing"), "装载失败的扩展不能出现在注册表里");
        assertNull(registry.byId(999));
    }
}
