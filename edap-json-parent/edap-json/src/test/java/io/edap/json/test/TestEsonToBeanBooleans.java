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
import io.edap.json.test.model.BooleansPojo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * boolean {@code isXxx} 字段命名约定下，{@link Eson#toBean} 路径正确识别 setter。
 *
 * <p>回归保护：之前 {@code JsonUtil.getSetMethod} 只尝试 {@code set + upperCaseFirst(name)}
 *     和 {@code name}，遗漏 {@code setXxx}（去掉 {@code is} 前缀）的常见约定，
 *     导致生成器走 {@code PUTFIELD} 跨 classloader 时触发 {@code IllegalAccessError}。</p>
 */
public class TestEsonToBeanBooleans {

    @Test
    public void testIsXxxPrimitiveBoolean() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isTop", true);
        m.put("isActive", false);
        m.put("isVisible", true);

        BooleansPojo b = Eson.toBean(m, BooleansPojo.class);
        assertNotNull(b);
        assertEquals(true, b.isTop());
        assertEquals(false, b.isActive());
        assertEquals(true, b.getIsVisible());
    }

    @Test
    public void testIsXxxDefaultsWhenMissing() {
        Map<String, Object> m = new LinkedHashMap<>();
        BooleansPojo b = Eson.toBean(m, BooleansPojo.class);
        assertNotNull(b);
        assertEquals(false, b.isTop());
        assertEquals(false, b.isActive());
        assertNull(b.getIsVisible());
    }

    @Test
    public void testParseObjectRoundTripMatchesToBean() {
        BooleansPojo source = new BooleansPojo();
        source.setTop(true);
        source.setActive(false);
        source.setIsVisible(null);

        String json = Eson.toJsonString(source);
        BooleansPojo viaParseObject = Eson.parseObject(json, BooleansPojo.class);
        BooleansPojo viaToBean = Eson.toBean(Eson.parseJsonObject(json), BooleansPojo.class);

        assertEquals(viaParseObject.isTop(), viaToBean.isTop());
        assertEquals(viaParseObject.isActive(), viaToBean.isActive());
        assertEquals(viaParseObject.getIsVisible(), viaToBean.getIsVisible());
    }
}
