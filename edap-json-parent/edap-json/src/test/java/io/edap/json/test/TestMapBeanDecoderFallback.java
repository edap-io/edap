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

package io.edap.json.test;

import io.edap.json.JsonCodecRegister;
import io.edap.json.MapBeanDecoder;
import io.edap.json.decoders.MapReflectDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证 {@link JsonCodecRegister#getMapBeanDecoder} 在 ASM 生成失败时正确 fallback。
 *
 * <p><b>触发条件</b>：传入的 {@code Class} 由 bootstrap classloader 加载（如 {@link Date}）。
 *     codec loader 的 parent 为 bootstrap → 生成字节码引用 {@code io.edap.json.MapBeanDecoder}
 *     时找不到接口（bootstrap 看不到 app classloader 加载的类）→ {@code NoClassDefFoundError}
 *     抛出。</p>
 *
 * <p><b>预期行为（修复后）</b>：
 * <ul>
 *   <li>原 generator 异常被打印到 stderr（业务侧诊断用）</li>
 *   <li>{@code getMapBeanDecoder} 返回的实例是 {@link MapReflectDecoder}</li>
 *   <li>fallback decoder 实际可用（{@code decode} 返回正常 POJO 实例）</li>
 * </ul>
 *
 * <p><b>Bug 行为（修复前）</b>：catch 块吞掉原异常，丢出
 *     {@code RuntimeException("generateMapBeanDecoderClass ... error", cause=ClassNotFoundException)}
 *     —— 误导业务侧，且没有 fallback 到 {@code MapReflectDecoder}。</p>
 */
public class TestMapBeanDecoderFallback {

    /**
     * 清理可能残留的生成 class 文件。
     *
     * <p>{@code JsonCodecRegister.generateMapBeanDecoderClass} 第一步是
     *     {@code Class.forName(decoderName)} —— 当 CWD 是模块根目录且
     *     {@code ./ejmb/<className>.class} 存在时（之前测试写入的），
     *     {@code Class.forName} 会从 CWD 找到这个类，跳过 ASM 生成，
     *     就触发不到 fallback 路径。每个测试前清掉，保证确定性。</p>
     */
    @BeforeEach
    public void cleanCachedDecoders() {
        File cached = new File("./ejmb/java/util/DateMapBeanDecoder.class");
        if (cached.exists()) {
            cached.delete();
        }
    }

    @Test
    public void testFallbackToMapReflectDecoder() {
        MapBeanDecoder<Date> decoder = JsonCodecRegister.instance().getMapBeanDecoder(Date.class);
        assertNotNull(decoder);
        // 关键断言：fallback 路径生效，返回的是 MapReflectDecoder
        assertEquals(MapReflectDecoder.class, decoder.getClass(),
                "Generator 失败时应当 fallback 到 MapReflectDecoder，而非抛 RuntimeException");

        // 进一步验证：fallback decoder 实际可用 —— empty map → new Date()
        Map<String, Object> empty = new LinkedHashMap<>();
        Date result = decoder.decode(empty);
        assertNotNull(result);
    }
}

