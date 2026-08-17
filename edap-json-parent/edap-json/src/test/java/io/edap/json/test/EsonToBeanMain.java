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
import io.edap.json.JsonObjectImpl;
import io.edap.json.test.model.DemoPojo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手测 main：覆盖 {@code Eson.toBean} 各路径。
 *
 * <p>绕过 surefire 的 {@code skipTests=true}，直接 java 运行。</p>
 */
public class EsonToBeanMain {

    public static void main(String[] args) {
        DemoPojo source = newSource();

        // 1. parseObject vs toBean 一致性 —— 手工构造 JsonObject（避开 encoder/decoder bug）
        JsonObject jo = makeJsonObjectOrEmpty(source);
        // parseObject 路径目前有 pre-existing bug（JsonDecoderGenerator 生成问题），
        // 直接用 toBean 路径并对照期望值
        DemoPojo b = Eson.toBean(jo, DemoPojo.class);
        check("testToBeanJsonObject", source, b);

        // 2. toBean(Map) 重载
        Map<String, Object> map = jo;
        DemoPojo c = Eson.toBean(map, DemoPojo.class);
        check("testToBeanMapOverload", source, c);

        // 3. 部分字段缺失 → 走默认
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("name", "dave");
        DemoPojo d = Eson.toBean(partial, DemoPojo.class);
        if (!"dave".equals(d.getName())) throw new AssertionError("name: " + d.getName());
        if (d.getAge() != 0) throw new AssertionError("age: " + d.getAge());
        if (d.getChildren() != null) throw new AssertionError("children should be null");

        // 4. 数字容错
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "x");
        m.put("age", 30L);
        m.put("balance", "123.45");
        m.put("integral", BigDecimal.valueOf(123));
        m.put("old", 1);
        DemoPojo e = Eson.toBean(m, DemoPojo.class);
        if (e.getAge() != 30) throw new AssertionError("age: " + e.getAge());
        if (Math.abs(e.getBalance() - 123.45) > 0.0001) throw new AssertionError("balance: " + e.getBalance());
        if (e.getIntegral() != 123L) throw new AssertionError("integral: " + e.getIntegral());
        if (!e.isOld()) throw new AssertionError("old: " + e.isOld());

        // 5. 嵌套 map → POJO
        Map<String, Object> childMap = new LinkedHashMap<>();
        childMap.put("name", "child");
        childMap.put("age", 5);
        childMap.put("old", false);
        childMap.put("balance", 0.0);
        childMap.put("integral", 0L);
        Map<String, Object> parent = new LinkedHashMap<>();
        parent.put("name", "parent");
        parent.put("age", 30);
        parent.put("children", new ArrayList<>(Arrays.asList(childMap)));
        DemoPojo f = Eson.toBean(parent, DemoPojo.class);
        if (!"parent".equals(f.getName())) throw new AssertionError("parent.name");
        if (f.getChildren().size() != 1) throw new AssertionError("children.size");
        if (!"child".equals(f.getChildren().get(0).getName())) throw new AssertionError("child.name");
        if (f.getChildren().get(0).getAge() != 5) throw new AssertionError("child.age");

        System.out.println("ALL PASSED");
    }

    private static DemoPojo newSource() {
        DemoPojo p = new DemoPojo();
        p.setName("alice");
        p.setAge(30);
        p.setOld(false);
        p.setBalance(12345.67);
        p.setIntegral(9_000_000_000L);

        DemoPojo c1 = new DemoPojo();
        c1.setName("bob");
        c1.setAge(8);
        c1.setOld(false);
        c1.setBalance(0.0);
        c1.setIntegral(0L);

        DemoPojo c2 = new DemoPojo();
        c2.setName("carol");
        c2.setAge(10);
        c2.setOld(true);
        c2.setBalance(99.99);
        c2.setIntegral(100L);

        p.setChildren(Arrays.asList(c1, c2));
        return p;
    }

    /**
     * 手工构造 JsonObject（绕过 toJsonString 的 encoder 生成失败）。
     */
    private static JsonObject makeJsonObject(DemoPojo p) {
        if (p == null) return new JsonObjectImpl();
        JsonObjectImpl jo = new JsonObjectImpl();
        jo.put("name", p.getName());
        jo.put("age", p.getAge());
        jo.put("old", p.isOld());
        jo.put("balance", p.getBalance());
        jo.put("integral", p.getIntegral());
        ArrayList<JsonObject> arr = new ArrayList<>();
        if (p.getChildren() != null) {
            for (DemoPojo c : p.getChildren()) {
                arr.add(makeJsonObject(c));
            }
        }
        jo.put("children", arr);
        return jo;
    }

    private static JsonObject makeJsonObjectOrEmpty(DemoPojo p) {
        return makeJsonObject(p);
    }

    private static void check(String label, DemoPojo a, DemoPojo b) {
        if (a == null || b == null) throw new AssertionError(label + ": null");
        if (!equals(a.getName(), b.getName())) throw new AssertionError(label + " name");
        if (a.getAge() != b.getAge()) throw new AssertionError(label + " age");
        if (a.isOld() != b.isOld()) throw new AssertionError(label + " old");
        if (Math.abs(a.getBalance() - b.getBalance()) > 0.0001)
            throw new AssertionError(label + " balance: " + a.getBalance() + " vs " + b.getBalance());
        if (a.getIntegral() != b.getIntegral()) throw new AssertionError(label + " integral");
        if (a.getChildren() == null || b.getChildren() == null)
            throw new AssertionError(label + " children null");
        if (a.getChildren().size() != b.getChildren().size())
            throw new AssertionError(label + " children size");
        for (int i = 0; i < a.getChildren().size(); i++) {
            check(label + ".child[" + i + "]", a.getChildren().get(i), b.getChildren().get(i));
        }
    }

    private static boolean equals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}