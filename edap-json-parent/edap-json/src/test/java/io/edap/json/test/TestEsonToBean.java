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

import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.json.test.model.DemoPojo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Eson#toBean} 与 {@link Eson#parseObject} 路径一致性测试。
 *
 * <p>策略：构造 bean → {@code parseObject(jsonString, T.class)} 与
 *     {@code toBean(parseJsonObject(jsonString), T.class)} 两条路径结果字段
 *     对比，证明 ASM 生成的 {@code MapBeanDecoder} 与 {@code JsonDecoder}
 *     字段映射语义一致。</p>
 */
public class TestEsonToBean {

    private DemoPojo source;

    @BeforeEach
    public void setUp() {
        source = new DemoPojo();
        source.setName("alice");
        source.setAge(30);
        source.setOld(false);
        source.setBalance(12345.67);
        source.setIntegral(9_000_000_000L);

        DemoPojo child1 = new DemoPojo();
        child1.setName("bob");
        child1.setAge(8);
        child1.setOld(false);
        child1.setBalance(0.0);
        child1.setIntegral(0L);

        DemoPojo child2 = new DemoPojo();
        child2.setName("carol");
        child2.setAge(10);
        child2.setOld(true);
        child2.setBalance(99.99);
        child2.setIntegral(100L);

        source.setChildren(Arrays.asList(child1, child2));
    }

    @Test
    public void testToBeanEqualsParseObject() {
        String json = Eson.toJsonString(source);
        DemoPojo a = Eson.parseObject(json, DemoPojo.class);
        JsonObject jo = Eson.parseJsonObject(json);
        DemoPojo b = Eson.toBean(jo, DemoPojo.class);
        assertBeanEquals(a, b);
    }

    @Test
    public void testToBeanMapOverload() {
        String json = Eson.toJsonString(source);
        Map<String, Object> map = Eson.parseJsonObject(json);
        DemoPojo b = Eson.toBean(map, DemoPojo.class);
        assertBeanEquals(source, b);
    }

    @Test
    public void testMissingFieldsAreDefaults() {
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("name", "dave");
        // 其他字段缺失
        DemoPojo b = Eson.toBean(partial, DemoPojo.class);
        assertNotNull(b);
        assertEquals("dave", b.getName());
        assertEquals(0, b.getAge());
        assertNull(b.getChildren());
    }

    @Test
    public void testNumberCoercion() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "x");
        m.put("age", 30L);              // Long → int
        m.put("balance", "123.45");     // String → double
        m.put("integral", BigDecimal.valueOf(123)); // BigDecimal → long
        m.put("old", 1);                // Integer → boolean
        DemoPojo b = Eson.toBean(m, DemoPojo.class);
        assertEquals(30, b.getAge());
        assertEquals(123.45, b.getBalance(), 0.0001);
        assertEquals(123L, b.getIntegral());
        assertTrue(b.isOld());
    }

    @Test
    public void testNullChildren() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "x");
        m.put("age", 1);
        DemoPojo b = Eson.toBean(m, DemoPojo.class);
        assertNull(b.getChildren());
    }

    @Test
    public void testNestedPojoField() {
        // 直接构造嵌套 map（不通过 toJsonString 路径）
        Map<String, Object> childMap = new LinkedHashMap<>();
        childMap.put("name", "child");
        childMap.put("age", 5);
        childMap.put("old", false);
        childMap.put("balance", 0.0);
        childMap.put("integral", 0L);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "parent");
        m.put("age", 30);
        m.put("children", new ArrayList<>(Arrays.asList(childMap)));

        DemoPojo b = Eson.toBean(m, DemoPojo.class);
        assertEquals("parent", b.getName());
        assertEquals(1, b.getChildren().size());
        assertEquals("child", b.getChildren().get(0).getName());
        assertEquals(5, b.getChildren().get(0).getAge());
    }

    private void assertBeanEquals(DemoPojo a, DemoPojo b) {
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.getName(), b.getName());
        assertEquals(a.getAge(), b.getAge());
        assertEquals(a.isOld(), b.isOld());
        assertEquals(a.getBalance(), b.getBalance(), 0.0001);
        assertEquals(a.getIntegral(), b.getIntegral());
        if (a.getChildren() == null) {
            assertNull(b.getChildren());
        } else {
            assertEquals(a.getChildren().size(), b.getChildren().size());
            for (int i = 0; i < a.getChildren().size(); i++) {
                assertBeanEquals(a.getChildren().get(i), b.getChildren().get(i));
            }
        }
    }
}