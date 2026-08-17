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

import io.edap.json.MapBeanDecoderGenerator;
import io.edap.util.AsmUtil;
import io.edap.util.internal.GeneratorClassInfo;
import io.edap.json.test.model.DemoPojo;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直接测试 {@link MapBeanDecoderGenerator} 的字节码生成。
 */
public class MapBeanDecoderGeneratorMain {

    public static void main(String[] args) throws Exception {
        MapBeanDecoderGenerator gen = new MapBeanDecoderGenerator(DemoPojo.class);
        GeneratorClassInfo info = gen.getClassInfo();

        System.out.println("class name: " + info.clazzName);
        System.out.println("byte size:  " + info.clazzBytes.length);

        // 写到磁盘以便 javap 分析
        AsmUtil.saveClassFile("./" + info.clazzName + ".class", info.clazzBytes);

        // 用反射 defineClass 加载（绕开 JsonCodecLoader 的 pre-existing 缓存 bug）
        ClassLoader cl = DemoPojo.class.getClassLoader();
        Method define = ClassLoader.class.getDeclaredMethod("defineClass",
                String.class, byte[].class, int.class, int.class);
        define.setAccessible(true);
        Class<?> generated = (Class<?>) define.invoke(cl,
                info.clazzName.replace('/', '.'),
                info.clazzBytes, 0, info.clazzBytes.length);

        System.out.println("loaded: " + generated.getName());
        System.out.println("super:  " + generated.getSuperclass().getName());

        // 检查 decode(Map) 和 bridge decode(Map)
        try {
            for (Method m : generated.getDeclaredMethods()) {
                System.out.println("method: " + m.getName()
                        + " return=" + m.getReturnType().getName()
                        + " bridge=" + m.isBridge() + " synthetic=" + m.isSynthetic());
            }
        } catch (NoClassDefFoundError e) {
            System.out.println("(getDeclaredMethods skipped: " + e.getMessage() + ")");
        }

        System.out.println("interfaces:");
        for (Class<?> iface : generated.getInterfaces()) {
            System.out.println("  " + iface.getName());
        }

        // 实例化并实际调用一次 —— 用 unsafe allocateInstance 绕开构造器解析
        sun.misc.Unsafe unsafe;
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);
        Object decoder = unsafe.allocateInstance(generated);
        System.out.println("instantiated via unsafe: " + decoder.getClass().getName());

        // 手工构造一个简单 Map 测试 decode
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("name", "test-name");
        map.put("age", 42);
        map.put("old", true);
        map.put("balance", 3.14);
        map.put("integral", 999L);
        map.put("children", java.util.Arrays.asList()); // 空 list

        java.lang.reflect.Method decode = generated.getMethod("decode", Map.class);
        Object result = decode.invoke(decoder, map);
        System.out.println("decoded: " + result.getClass().getName());
        DemoPojo bean = (DemoPojo) result;
        if (!"test-name".equals(bean.getName())) throw new AssertionError("name mismatch: " + bean.getName());
        if (bean.getAge() != 42) throw new AssertionError("age mismatch: " + bean.getAge());
        if (!bean.isOld()) throw new AssertionError("old mismatch");
        if (Math.abs(bean.getBalance() - 3.14) > 0.0001) throw new AssertionError("balance mismatch");
        if (bean.getIntegral() != 999L) throw new AssertionError("integral mismatch");
        if (bean.getChildren() != null && !bean.getChildren().isEmpty()) throw new AssertionError("children should be empty");

        System.out.println("OK - decode works");
    }
}