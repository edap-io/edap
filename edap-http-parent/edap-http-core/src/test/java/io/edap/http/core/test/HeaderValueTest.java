/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http.core.test;

import io.edap.http.HeaderValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class HeaderValueTest {

    @Test
    public void testConstruct() throws NoSuchFieldException, IllegalAccessException {
        Field valField  = HeaderValue.class.getDeclaredField("value");
        Field dataField = HeaderValue.class.getDeclaredField("data");
        Field intField  = HeaderValue.class.getDeclaredField("intValue");
        valField.setAccessible(true);
        dataField.setAccessible(true);
        intField.setAccessible(true);
        HeaderValue hv = new HeaderValue();
        assertNull(valField.get(hv));
        assertNull(dataField.get(hv));
        assertNull(intField.get(hv));
        assertNull(hv.getValue());

        hv = new HeaderValue("www.easyea.com");
        assertEquals(valField.get(hv), "www.easyea.com");
        assertArrayEquals((byte[])dataField.get(hv), "www.easyea.com".getBytes(StandardCharsets.UTF_8));
        assertNull(intField.get(hv));

        hv = new HeaderValue("www.easyea.com".getBytes(StandardCharsets.UTF_8));
        assertNull(valField.get(hv));
        assertArrayEquals(hv.getData(), "www.easyea.com".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals((byte[])dataField.get(hv), "www.easyea.com".getBytes(StandardCharsets.UTF_8));
        assertNull(intField.get(hv));
        assertEquals(hv.getValue(), "www.easyea.com");

        hv = new HeaderValue();
        hv.setValue("1234");
        assertEquals(hv.getValue(), "1234");
        assertTrue(hv.getIntValue() < 0);
    }

    @Test
    public void testGetIntValue() {
        Random random = new Random();
        HeaderValue hv;
        for (int i=0;i<10;i++) {
            int v = random.nextInt(10);
            hv = new HeaderValue(String.valueOf(v));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("10".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 10);
        assertEquals(hv.getIntValue(), 10);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(90) + 10;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("100".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 100);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(900) + 100;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("1000".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 1000);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(9000) + 1000;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("10000".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 10000);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(90000) + 10000;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("100000".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 100000);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(900000) + 100000;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("1000000".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 1000000);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(9000000) + 1000000;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("10000000".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 10000000);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(90000000) + 10000000;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue("100000000".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 100000000);

        for (int i=0;i<10;i++) {
            int v = random.nextInt(Integer.MAX_VALUE - 100000000) + 100000000;
            hv = new HeaderValue(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            assertEquals(hv.getIntValue(), v);
        }
        hv = new HeaderValue(String.valueOf(Integer.MAX_VALUE).getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), Integer.MAX_VALUE);

        hv = new HeaderValue(" 9".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 9);

        hv = new HeaderValue("  9".getBytes(StandardCharsets.UTF_8));
        assertEquals(hv.getIntValue(), 9);

        hv = new HeaderValue("123456789123".getBytes(StandardCharsets.UTF_8));
        assertTrue(hv.getIntValue() < 0);
    }


}
