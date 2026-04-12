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

package io.edap.test.util;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import io.edap.nio.util.ConfigUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class TestConfigUtils {

    @Test
    public void testGetConfigValue() {

    }

    @Test
    public void testParseKey() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = ConfigUtils.class.getDeclaredMethod("parseKey", String.class);
        method.setAccessible(true);

        String key = "";
        List<String> keys = (List)method.invoke(null, key);
        Assertions.assertNotNull(keys);
        Assertions.assertEquals(keys.size(), 1);
        Assertions.assertEquals(keys.get(0), "");

        key = "a.b.c";
        keys = (List)method.invoke(null, key);
        Assertions.assertNotNull(keys);
        Assertions.assertEquals(keys.size(), 3);
        Assertions.assertEquals(keys.get(0), "a");
        Assertions.assertEquals(keys.get(1), "b");
        Assertions.assertEquals(keys.get(2), "c");

        key = "a.b..c";
        keys = (List)method.invoke(null, key);
        Assertions.assertNotNull(keys);
        Assertions.assertEquals(keys.size(), 3);
        Assertions.assertEquals(keys.get(0), "a");
        Assertions.assertEquals(keys.get(1), "b");
        Assertions.assertEquals(keys.get(2), "c");

        key = "a.b.c....";
        keys = (List)method.invoke(null, key);
        Assertions.assertNotNull(keys);
        Assertions.assertEquals(keys.size(), 3);
        Assertions.assertEquals(keys.get(0), "a");
        Assertions.assertEquals(keys.get(1), "b");
        Assertions.assertEquals(keys.get(2), "c");

        key = "....a.b.c....";
        keys = (List)method.invoke(null, key);
        Assertions.assertNotNull(keys);
        Assertions.assertEquals(keys.size(), 3);
        Assertions.assertEquals(keys.get(0), "a");
        Assertions.assertEquals(keys.get(1), "b");
        Assertions.assertEquals(keys.get(2), "c");
    }

    @Test
    public void testParseEnvValue() throws Exception {
        Method method = ConfigUtils.class.getDeclaredMethod("parseEnvValue", List.class);
        method.setAccessible(true);

        List<String> keys = Arrays.asList("a", "b", "c");
        Object value = SystemLambda.withEnvironmentVariable("a.b.c", "hello world")
                .execute(() -> method.invoke(null, keys));
        Assertions.assertNotNull(value);
        Assertions.assertEquals(value, "hello world");

        System.setProperty("a.b.c", "hello world2");
        value = method.invoke(null, keys);
        Assertions.assertNotNull(value);
        Assertions.assertEquals(value, "hello world2");


        List<String> keys2 = Arrays.asList("a", "b", "io/edap/test");
        value = method.invoke(null, keys2);
        Assertions.assertNull(value);
    }
}
