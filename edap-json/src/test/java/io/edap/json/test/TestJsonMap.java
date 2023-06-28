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

package io.edap.json.test;

import io.edap.json.JsonMap;
import io.edap.json.JsonObjectImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TestJsonMap {

    @Test
    public void testGetPathItem() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = JsonMap.class.getDeclaredMethod("getPathItem", String.class, int.class);
        method.setAccessible(true);
        JsonMap jsonMap = new JsonObjectImpl();
        String path = "a.b.c";
        JsonMap.ParsePathInfo info = (JsonMap.ParsePathInfo)method.invoke(jsonMap, path, 0);
        assertEquals(info.item, "a");
        assertEquals(info.endPos, 2);

        info = (JsonMap.ParsePathInfo)method.invoke(jsonMap, path, 2);
        assertEquals(info.item, "b");
        assertEquals(info.endPos, 4);

        info = (JsonMap.ParsePathInfo)method.invoke(jsonMap, path, 4);
        assertEquals(info.item, "c");
        assertEquals(info.endPos, 5);

        // 测试以"."结尾的字符串抛异常的功能
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    JsonMap.ParsePathInfo invoke
                            = (JsonMap.ParsePathInfo) method.invoke(jsonMap, "a.", 0);
                });
        assertTrue(thrown.getCause().getMessage().contains("path cann't end with '.'!"));

        path = "\"中文  \" \t.\"a.b.c\"";
        info = (JsonMap.ParsePathInfo)method.invoke(jsonMap, path, 0);
        assertEquals(info.item, "中文  ");
        assertEquals(info.endPos, 9);

        info = (JsonMap.ParsePathInfo)method.invoke(jsonMap, path, info.endPos);
        assertEquals(info.item, "a.b.c");
        assertEquals(info.endPos, path.length());

        path = "\"中文 \\\"  \"";
        info = (JsonMap.ParsePathInfo)method.invoke(jsonMap, path, 0);
        assertEquals(info.item, "中文 \\\"  ");
        assertEquals(info.endPos, path.length());

        // 测试以引号开头单没有引号结束的错误
        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    JsonMap.ParsePathInfo invoke
                            = (JsonMap.ParsePathInfo) method.invoke(jsonMap, "\"test", 0);
                });
        assertTrue(thrown.getCause().getMessage().contains("path did not end normally!"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    JsonMap.ParsePathInfo invoke
                            = (JsonMap.ParsePathInfo) method.invoke(jsonMap, "abc.\"test", 4);
                });
        assertTrue(thrown.getCause().getMessage().contains("path did not end normally!"));
    }

    @Test
    public void testGetByPathList() {

        // 测试一个层级有值情况
        Map<String, Object> map = new HashMap<>();
        map.put("a", "123");
        JsonObjectImpl jsonMap = new JsonObjectImpl();
        jsonMap.putAll(map);
        Object v = jsonMap.getByPath(Arrays.asList("a"));
        assertEquals(v.getClass().getName(), "java.lang.String");
        assertEquals(v, "123");

        // 测试path为空的情况
        List<String> path = null;
        v = jsonMap.getByPath(path);
        assertNull(v);
        path = new ArrayList<>();
        v = jsonMap.getByPath(path);
        assertNull(v);

        // 测试path多层级，但是key在map中不存在或者值不是map类型
        v = jsonMap.getByPath(Arrays.asList("a", "b"));
        assertNull(v);
        v = jsonMap.getByPath(Arrays.asList("b", "a"));
        assertNull(v);

        jsonMap.clear();
        map.clear();
        Map<String, Object> e = new HashMap<>();
        e.put("e", "123");
        Map<String, Object> d = new HashMap<>();
        d.put("d", e);
        Map<String, Object> c = new HashMap<>();
        c.put("c", d);
        Map<String, Object> b = new HashMap<>();
        b.put("b", c);
        map.put("a", b);
        jsonMap.putAll(map);
        v = jsonMap.getByPath(Arrays.asList("a", "b", "c", "d", "e"));
        assertEquals(v.getClass().getName(), "java.lang.String");
        assertEquals(v, "123");

        v = jsonMap.getByPath(Arrays.asList("a", "b", "c", "d", "e","f"));
        assertNull(v);

    }

    @Test
    public void testGetByPathDotKey() {

        // 测试一个层级有值情况
        Map<String, Object> map = new HashMap<>();
        map.put("a", "123");
        JsonObjectImpl jsonMap = new JsonObjectImpl();
        jsonMap.putAll(map);
        Object v = jsonMap.getByPath("a");
        assertEquals(v.getClass().getName(), "java.lang.String");
        assertEquals(v, "123");

        // 测试path为空的情况
        String path = null;
        v = jsonMap.getByPath(path);
        assertNull(v);
        path = "";
        v = jsonMap.getByPath(path);
        assertNull(v);

        // 测试path多层级，但是key在map中不存在或者值不是map类型
        v = jsonMap.getByPath("a.b");
        assertNull(v);
        v = jsonMap.getByPath(Arrays.asList("b.a"));
        assertNull(v);

        jsonMap.clear();
        map.clear();
        Map<String, Object> e = new HashMap<>();
        e.put("e", "123");
        Map<String, Object> d = new HashMap<>();
        d.put("d", e);
        Map<String, Object> c = new HashMap<>();
        c.put("c", d);
        Map<String, Object> b = new HashMap<>();
        b.put("b", c);
        map.put("a", b);
        jsonMap.putAll(map);
        v = jsonMap.getByPath("a.b.c.d.e");
        assertEquals(v.getClass().getName(), "java.lang.String");
        assertEquals(v, "123");

        v = jsonMap.getByPath("a.b.c.d.e.f");
        assertNull(v);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    jsonMap.getByPath("a.b.c.d.e.");;
                });
        assertTrue(thrown.getMessage().contains("path cann't end with '.'!"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    jsonMap.getByPath("\"test");
                });
        assertTrue(thrown.getMessage().contains("path did not end normally!"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    jsonMap.getByPath("abc.\"test");
                });
        assertTrue(thrown.getMessage().contains("path did not end normally!"));
    }
}
