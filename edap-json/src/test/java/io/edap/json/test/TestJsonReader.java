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

import io.edap.json.*;
import io.edap.json.model.DataRange;
import io.edap.json.model.StringDataRange;
import io.edap.json.test.model.DemoPojo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestJsonReader {

    /**
     * 测试不以"{"和""开头数据抛异常的逻辑
     */
    @Test
    public void testReadInvalidJsonFormat() {
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser = new StringJsonReader("key:123");
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("Not json string!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser = new ByteArrayJsonReader("key:123".getBytes(StandardCharsets.UTF_8));
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("Not json string!"));
    }

    @Test
    public void testSetJsonData() {
        StringJsonReader reader = new StringJsonReader("{}");
        JsonObjectImpl jsonObject = (JsonObjectImpl) reader.readObject();
        assertNotNull(jsonObject);
        assertEquals(jsonObject.size(), 0);
        reader.reset();
        reader.setJsonData("{\"name\":\"louis\"}");
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertNotNull(jsonObject);
        assertEquals(jsonObject.size(), 1);
        assertEquals(jsonObject.get("name"), "louis");

        ByteArrayJsonReader br = new ByteArrayJson5Reader("{}".getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) br.readObject();
        assertNotNull(jsonObject);
        assertEquals(jsonObject.size(), 0);
        br.reset();
        br.setJsonData("{\"name\":\"louis\"}".getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) br.readObject();
        assertNotNull(jsonObject);
        assertEquals(jsonObject.size(), 1);
        assertEquals(jsonObject.get("name"), "louis");
    }

    @Test
    public void testParseEmptyDocument() {
        StringJsonReader parser = new StringJsonReader("    \n\t   \r\n");
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertNull(jsonObject);

        ByteArrayJsonReader byteArrayJsonReader = new ByteArrayJsonReader("    \n\t   \r\n".getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertNull(jsonObject);
    }

    @Test
    public void testReadStringValue() {
        String json = "\n{\t\"name\":\"jhon\"}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        assertEquals(jsonObject.get("name"), "jhon");

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 1);
        assertEquals(jsonObject.get("name"), "jhon");

        json = "\n{\t\"name\":\"jhon\",\"desc\":\"带有\\\"转义的符号\"}\n\n";
        System.out.println(json);
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 2);
        assertEquals(jsonObject.get("name"), "jhon");
        assertEquals(jsonObject.get("desc"), "带有\"转义的符号");

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 2);
        assertEquals(jsonObject.get("name"), "jhon");
        assertEquals(jsonObject.get("desc"), "带有\"转义的符号");
    }

    @Test
    public void testReadJsonValue() {
        String json = "\n{\t\"a\":{\"b\":\"c\"}}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        assertTrue(jsonObject.get("a") instanceof JsonObjectImpl);
        JsonObjectImpl child = (JsonObjectImpl) jsonObject.get("a");
        assertEquals(child.size(), 1);
        assertTrue(child instanceof JsonObjectImpl);
        assertEquals(child.get("b"), "c");

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 1);
        assertTrue(jsonObject.get("a") instanceof JsonObjectImpl);
        child = (JsonObjectImpl) jsonObject.get("a");
        assertEquals(child.size(), 1);
        assertTrue(child instanceof JsonObjectImpl);
        assertEquals(child.get("b"), "c");
    }

    /**
     * 检测key的后面不是":" 冒号的异常
     */
    @Test
    public void testReadKeyNotColon() {
        String json = "{\"key\" 123";
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser = new StringJsonReader(json);
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));
    }

    /**
     * 检测key不是以'"'引号开头的异常
     */
    @Test
    public void testReadKeyNotQuotationMarks() {
        String json = "{key:123";
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser = new StringJsonReader(json);
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));
    }

    /**
     * 测试空的JSON文档只有"{}"没有任何内容的情况
     */
    @Test
    public void testReadEmptyJson() {
        String json = "\n{\t}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);
    }

    /**
     * 检测key和value后不是","和"}"的异常
     */
    @Test
    public void testKeyValueNotWellFormat() {
        String json = "{\"key\":\"123\" t";
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser = new StringJsonReader(json);
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("key and value 后为不符合json字符[t]"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
                    parser.readObject();
                });
        assertTrue(thrown.getMessage().contains("key and value 后为不符合json字符[t]"));
    }

    @Test
    public void testReadNumberArray() {
        String json = "[123,34,567,0.12,3.1415]";
        StringJsonReader parser = new StringJsonReader(json);
        List<Object> array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals((long)array.get(0), 123);
        assertEquals((long)array.get(1), 34);
        assertEquals((long)array.get(2), 567);
        assertEquals(array.get(3), 0.12d);
        assertEquals(array.get(4), 3.1415d);

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals((long)array.get(0), 123);
        assertEquals((long)array.get(1), 34);
        assertEquals((long)array.get(2), 567);
        assertEquals(array.get(3), 0.12d);
        assertEquals(array.get(4), 3.1415d);
    }

    @Test
    public void testParseBooleanArray() {
        String json = "[true,false,true,true,false]";
        StringJsonReader parser = new StringJsonReader(json);
        List<Object> array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals((boolean)array.get(0), true);
        assertEquals((boolean)array.get(1), false);
        assertEquals((boolean)array.get(2), true);
        assertEquals((boolean)array.get(3), true);
        assertEquals((boolean)array.get(4), false);

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals((boolean)array.get(0), true);
        assertEquals((boolean)array.get(1), false);
        assertEquals((boolean)array.get(2), true);
        assertEquals((boolean)array.get(3), true);
        assertEquals((boolean)array.get(4), false);

        json = "[ true, false ,true ,  true,false  ]";
        parser = new StringJsonReader(json);
        array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals((boolean)array.get(0), true);
        assertEquals((boolean)array.get(1), false);
        assertEquals((boolean)array.get(2), true);
        assertEquals((boolean)array.get(3), true);
        assertEquals((boolean)array.get(4), false);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals((boolean)array.get(0), true);
        assertEquals((boolean)array.get(1), false);
        assertEquals((boolean)array.get(2), true);
        assertEquals((boolean)array.get(3), true);
        assertEquals((boolean)array.get(4), false);
    }

    @Test
    public void testReadStringArray() {
        String json = "[\"abc\",\"bc\",\"cedf\"]";
        StringJsonReader parser = new StringJsonReader(json);
        List<Object> array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 3);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 3);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");

        json = "[\"abc\",\"bc\",\"cedf\",]";
        parser = new StringJsonReader(json);
        array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 3);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 3);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");

        json = "[\"abc\",\"bc\",\"cedf\",,,]";
        parser = new StringJsonReader(json);
        array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 3);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 3);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");

        json = "[\"abc\",\"bc\",\"cedf\",,,\"defgh\"]";
        parser = new StringJsonReader(json);
        array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 4);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");
        assertEquals(array.get(3), "defgh");

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 4);
        assertEquals(array.get(0), "abc");
        assertEquals(array.get(1), "bc");
        assertEquals(array.get(2), "cedf");
        assertEquals(array.get(3), "defgh");

        json = "[]";
        parser = new StringJsonReader(json);
        array = (List<Object>)parser.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 0);

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser2 = new StringJsonReader("[\"123\"t");
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("数组格式不正确"));

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        array = (List<Object>)reader.readObject();
        assertTrue(array != null);
        assertEquals(array.size(), 0);

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser2 = new ByteArrayJsonReader("[\"123\"t".getBytes(StandardCharsets.UTF_8));
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("数组格式不正确"));
    }

    @Test
    public void testReadNullValue() {
        String json = "\n{\t\"name\":null}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        assertNull(jsonObject.get("name"));

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser2 = new StringJsonReader("\n{\t\"name\":nult}\n\n");
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("null 格式错误"));

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 1);
        assertNull(jsonObject.get("name"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser2 = new ByteArrayJsonReader("\n{\t\"name\":nult}\n\n".getBytes(StandardCharsets.UTF_8));
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("null 格式错误"));

    }

    @Test
    public void testParseBooleanValue() {
        String json = "\n{\t\"open\":true}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        assertTrue((boolean)jsonObject.get("open"));

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 1);
        assertTrue((boolean)jsonObject.get("open"));

        json = "\n{\t\"open\":false}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        assertFalse((boolean)jsonObject.get("open"));

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 1);
        assertFalse((boolean)jsonObject.get("open"));

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser2 = new StringJsonReader("\n{\t\"open\":truu}\n\n");
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser2 = new StringJsonReader("\n{\t\"open\":falsa}\n\n");
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser2 = new ByteArrayJsonReader("\n{\t\"open\":truu}\n\n".getBytes(StandardCharsets.UTF_8));
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader parser2 = new ByteArrayJsonReader("\n{\t\"open\":falsa}\n\n".getBytes(StandardCharsets.UTF_8));
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));
    }

    @Test
    public void testReadArrayValue() {
        String json = "\n{\t\"list\":[\"a\",\"bc\"]}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        List<String> list = (List<String>)jsonObject.get("list");
        assertEquals(list.size(), 2);
        assertEquals(list.get(0), "a");
        assertEquals(list.get(1), "bc");

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 1);
        list = (List<String>)jsonObject.get("list");
        assertEquals(list.size(), 2);
        assertEquals(list.get(0), "a");
        assertEquals(list.get(1), "bc");
    }

    @Test
    public void testFirstNotSpaceChar() {
        String json = "\n {}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n  {}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n   {}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n   \t{}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n   \t {}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n   \t  {}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n   \t   {}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "\n   \t    {}\n\n";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 0);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertEquals(jsonObject.size(), 0);

        json = "";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertNull(jsonObject);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertNull(jsonObject);

        json = "    ";
        parser = new StringJsonReader(json);
        jsonObject = (JsonObjectImpl) parser.readObject();
        assertNull(jsonObject);

        reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) reader.readObject();
        assertNull(jsonObject);

    }

    @Test
    public void testReadNumberValue() {
        String json = "\n{\t\"value\":123.45}\n\n";
        StringJsonReader parser = new StringJsonReader(json);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        assertEquals(jsonObject.size(), 1);
        assertEquals(jsonObject.get("value"), 123.45d);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        jsonObject = (JsonObjectImpl) br.readObject();
        assertEquals(jsonObject.size(), 1);
        assertEquals(jsonObject.get("value"), 123.45d);


        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader parser2 = new StringJsonReader("\n{\t\"value\":123.45");
                    parser2.readObject();
                });
        assertTrue(thrown.getMessage().contains("Json没有正确结束"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJsonReader reader = new ByteArrayJsonReader(
                            "\n{\t\"value\":123.45".getBytes(StandardCharsets.UTF_8));
                    reader.readObject();
                });
        assertTrue(thrown.getMessage().contains("Json没有正确结束"));
    }

    @Test
    public void testReadDepthJson() {
        String jsonStr = "{\n" +
                "    \"a\":{\n" +
                "        \"b\":{\n" +
                "            \"c\":{\n" +
                "                \"d\":{\n" +
                "                    \"e\":\"123\"\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}";
        StringJsonReader parser = new StringJsonReader(jsonStr);
        JsonObjectImpl jsonObject = (JsonObjectImpl) parser.readObject();
        Object v = jsonObject.getByPath(Arrays.asList("a", "b", "c", "d", "e"));
        assertEquals(v.getClass().getName(), "java.lang.String");
        assertEquals(v, "123");
    }

    @Test
    public void testReadPojoBoolean() throws InvocationTargetException, InstantiationException, IllegalAccessException {
        String json = "{\"age\":549,\"old\":true}";
        DemoPojo pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.isOld(), true);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.isOld(), true);

        json = "{\"name\":\"john325\",\"old\":false}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.isOld(), false);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.isOld(), false);

        json = "{\"old\":false,\"name\":\"john325\"}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.isOld(), false);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.isOld(), false);

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"old\":ts}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"old\":ts}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"old\":fs}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"old\":fs}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"old\":r}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"old\":r}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("boolean 格式错误"));
    }

    @Test
    public void testReadPojo() throws InvocationTargetException, InstantiationException, IllegalAccessException {
        String json = "{\"name\":\"john\",\"age\":0,\"integral\":0,\"balance\":0}";
        StringJsonReader reader = new StringJsonReader(json);
        DemoPojoDecoder decoder = new DemoPojoDecoder();
        decoder.decode(reader);
        DemoPojo pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 0);
        assertEquals(pojo.getBalance(), 0);
        assertEquals(pojo.getIntegral(), 0);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 0);
        assertEquals(pojo.getBalance(), 0);
        assertEquals(pojo.getIntegral(), 0);

        json = "{\"name\":\"john325\",\"age\":549,\"integral\":1234567898776,\"balance\":12345.67898776}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        json = "{\"name\":\"john325\",\"age\":549,\"integral\":1234567898776,\"balance\":12345.67898776}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        json = "{\"name\":\"john325\",\"age\":549,\"integral\":1234567898776,\"balance\":12345}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345D);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getBalance(), 12345D);


        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":中}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":中}";
                    System.out.println("json2.leng=" + json2.length());
                    System.out.println("json2.bs.leng=" + json2.getBytes(StandardCharsets.UTF_8).length);
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":01}";
                    DemoPojo pojo2 = new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":01}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":a}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":a}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"balance\":123.}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("double类型\".\"没有其他数字"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"balance\":123.}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("double类型\".\"没有其他数字"));
    }

    @Test
    public void testReadInt0() {
        String json = "9           ";
        StringJsonReader reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 9);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 9);

        json = "89           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 89);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 89);

        json = "789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 789);

        json = "6789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 6789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 6789);

        json = "56789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 56789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 56789);

        json = "456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 456789);

        json = "3456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 3456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 3456789);

        json = "23456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 23456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 23456789);

        json = "123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 123456789);

        json = "9 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 9);

        json = "89 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 89);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 89);

        json = "789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 789);

        json = "6789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 6789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 6789);

        json = "56789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 56789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 56789);

        json = "456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 456789);

        json = "3456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 3456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 3456789);

        json = "23456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 23456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 23456789);

        json = "123456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt0(), 123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt0(), 123456789);


        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "3123456789 ";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readInt0();
                });
        assertTrue(thrown.getMessage().contains("value is too large for int"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "3123456789 ";
                    ByteArrayJsonReader reader2
                            = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readInt0();
                });
        assertTrue(thrown.getMessage().contains("value is too large for int"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "123456789";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readInt0();
                });
        assertTrue(thrown.getMessage().contains("整数没有正确结束"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "123456789";
                    ByteArrayJsonReader reader2
                            = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readInt0();
                });
        assertTrue(thrown.getMessage().contains("整数没有正确结束"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "05";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readInt0();
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "05";
                    ByteArrayJsonReader reader2
                            = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readInt0();
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));
    }

    @Test
    public void testReadLong0() {
        String json = "0 ";
        StringJsonReader reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 0L);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 0);

        json = "9           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 9L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 9);

        json = "89           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 89);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 89);

        json = "789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 789);

        json = "6789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 6789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 6789);

        json = "56789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 56789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 56789);

        json = "456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 456789);

        json = "3456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 3456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 3456789);

        json = "23456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 23456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 23456789);

        json = "123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 123456789);

        json = "2123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 2123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 2123456789L);

        json = "9 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 9);

        json = "89 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 89);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 89);

        json = "789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 789);

        json = "6789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 6789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 6789);

        json = "56789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 56789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 56789);

        json = "456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 456789);

        json = "3456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 3456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 3456789);

        json = "23456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 23456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 23456789);

        json = "123456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 123456789);

        json = "2123456789 ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong0(), 2123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong0(), 2123456789);


        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "67898765432123456789 ";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readLong0();
                });
        assertTrue(thrown.getMessage().contains("value is too large for long"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "67898765432123456789 ";
                    ByteArrayJsonReader reader2
                            = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readLong0();
                });
        assertTrue(thrown.getMessage().contains("value is too large for long"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "123456789";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readLong0();
                });
        assertTrue(thrown.getMessage().contains("整数没有正确结束"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "123456789";
                    ByteArrayJsonReader reader2
                            = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readLong0();
                });
        assertTrue(thrown.getMessage().contains("整数没有正确结束"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "07";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readLong0();
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "07";
                    ByteArrayJsonReader reader2
                            = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readLong0();
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));
    }

    @Test
    public void testReadInt() {
        String json = "-9           ";
        StringJsonReader reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -9);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -9);

        json = "-89           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -89);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -89);

        json = "-789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -789);

        json = "-6789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -6789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -6789);

        json = "-56789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -56789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -56789);

        json = "-456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -456789);

        json = "-3456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -3456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -3456789);

        json = "-23456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -23456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -23456789);

        json = "-123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readInt(), -123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readInt(), -123456789);
    }

    @Test
    public void testReadDouble() {
        String json = "-9           ";
        StringJsonReader reader = new StringJsonReader(json);
        assertEquals(reader.readDouble(), -9);
        reader.reset();
        assertEquals(reader.readDouble(), -9);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readDouble(), -9);
        br.reset();
        assertEquals(br.readDouble(), -9);
    }

    @Test
    public void testReadFloat() {
        String json = "{\"v\":123.45}";
        StringJsonReader reader = new StringJsonReader(json);
        reader.nextPos(5);
        assertEquals(reader.readFloat(), 123.45f);
        reader.reset();
        reader.nextPos(5);
        assertEquals(reader.readFloat(), 123.45f);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        br.nextPos(5);
        assertEquals(br.readFloat(), 123.45f);
        br.reset();
        br.nextPos(5);
        assertEquals(br.readFloat(), 123.45f);

        json = "{\"v\":-1234.5}";
        reader = new StringJsonReader(json);
        reader.nextPos(5);
        assertEquals(reader.readFloat(), -1234.5f);
        reader.reset();
        reader.nextPos(5);
        assertEquals(reader.readFloat(), -1234.5f);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        br.nextPos(5);
        assertEquals(br.readFloat(), -1234.5f);
        br.reset();
        br.nextPos(5);
        assertEquals(br.readFloat(), -1234.5f);

        json = "{\"v\":-1234}";
        reader = new StringJsonReader(json);
        reader.nextPos(5);
        assertEquals(reader.readFloat(), -1234f);
        reader.reset();
        reader.nextPos(5);
        assertEquals(reader.readFloat(), -1234f);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        br.nextPos(5);
        assertEquals(br.readFloat(), -1234f);
        br.reset();
        br.nextPos(5);
        assertEquals(br.readFloat(), -1234f);

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "123.";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readFloat();
                });
        assertTrue(thrown.getMessage().contains("double类型\".\"没有其他数字"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "123.";
                    ByteArrayJsonReader reader2 = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readFloat();
                });
        assertTrue(thrown.getMessage().contains("double类型\".\"没有其他数字"));
    }

    @Test
    public void testReadLong() {
        String json = "-9           ";
        StringJsonReader reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -9);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -9);

        json = "-89           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -89);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -89);

        json = "-789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -789);

        json = "-6789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -6789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -6789);

        json = "-56789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -56789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -56789);

        json = "-456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -456789);

        json = "-3456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -3456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -3456789);

        json = "-23456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -23456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -23456789);

        json = "-123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -123456789);

        json = "-2123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -2123456789);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -2123456789);

        json = "-32123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -32123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -32123456789L);

        json = "-432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -432123456789L);

        json = "-5432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -5432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -5432123456789L);

        json = "-65432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -65432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -65432123456789L);

        json = "-765432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -765432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -765432123456789L);

        json = "-8765432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -8765432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -8765432123456789L);

        json = "-98765432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -98765432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -98765432123456789L);

        json = "-898765432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -898765432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -898765432123456789L);

        json = "-7898765432123456789           ";
        reader = new StringJsonReader(json);
        assertEquals(reader.readLong(), -7898765432123456789L);

        br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.readLong(), -7898765432123456789L);


        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "-67898765432123456789           ";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readLong();
                });
        assertTrue(thrown.getMessage().contains("value is too large for long"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "-67898765432123456789           ";
                    ByteArrayJsonReader reader2 = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readLong();
                });
        assertTrue(thrown.getMessage().contains("value is too large for long"));
    }

    @Test
    public void testReadKeyRange() {
        String json = "name:\"john\",\"age\":0}";
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    StringJsonReader reader = new StringJsonReader(json);
                    reader.readKeyRange();
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    ByteArrayJson5Reader reader = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
                    reader.readKeyRange();
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));


        thrown = assertThrows(JsonParseException.class,
                () -> {
                   String json2 = "\"name\",\"john\",\"age\":0}";
                    StringJsonReader reader = new StringJsonReader(json2);
                    reader.readKeyRange();
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"name\",\"john\",\"age\":0}";
                    ByteArrayJson5Reader reader = new ByteArrayJson5Reader(json2.getBytes(StandardCharsets.UTF_8));
                    reader.readKeyRange();
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));
    }

    @Test
    public void testSkipVaue() throws InvocationTargetException, InstantiationException, IllegalAccessException {
        String json = "{\"namd\":\"john\"}";
        DemoPojo pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);

        json = "{\"name\":\"john\",\"extInfo\":{\"weight\":100,\"height\":175}}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");

        json = "{\"name\":\"john\",\"extInfo\":{\"weight\":100,\"height\":175},\"age\":11}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 11);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 11);

        json = "{\"name\":\"john\",\"extInfo\":[\"123\",\"456\"]}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");

        json = "{\"name\":\"john\",\"extInfo\":[123,456]}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");

        json = "{\"name\":\"john\",\"extInfo\":[123,456],\"age\":21}";
        pojo = new StringJsonReader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 21);

        pojo = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 21);

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":[\"123\"{},456]}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("数组格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":[\"123\"{},456]}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("数组格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":[\"123\"abc,456]}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("数组格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":[\"123\"abc,456]}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("数组格式错误"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":{\"a\":true,'b':10}}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":{\"a\":true,'b':10}}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":{\"a\"true,'b':10}}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":{\"a\"true,'b':10}}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":{\"a\":true,\"b\":\"10\"]}}";
                    new StringJsonReader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("key and value 后为不符合json字符["));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"extInfo\":{\"a\":true,\"b\":\"10\"]}}";
                    new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("key and value 后为不符合json字符["));
    }

    @Test
    public void testReadString() {
        String json = "\"InvocationTargetException\"";

        StringJsonReader reader = new StringJsonReader(json);
        String s = reader.readString();
        assertEquals(json.substring(1, json.length()-1), s);

        ByteArrayJsonReader bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals(json.substring(1, json.length()-1), s);

        json = "\"012345678901234567890123456789012345678901234567890123456789012345678901234567" +
                "8901234567890123456789012345678901234567890123456789012345678901234567890123456789\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals(json.substring(1, json.length()-1), s);

        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals(json.substring(1, json.length()-1), s);

        s = bsReader.readString();
        System.out.println("src.len=" + (json.length() - 2));
        System.out.println("desc.len=" + s.length());
        assertEquals(json.substring(1, json.length()-1), s);

        json = "\"Invo\\bfd\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\bfd", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\bfd", s);

        json = "\"Invo\\bfd123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\bfd123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\bfd123456", s);

        json = "\"Invo\\t\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\t", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\t", s);

        json = "\"Invo\\t123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\t123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\t123456", s);

        json = "\"Invo\\n\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\n", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\n", s);

        json = "\"Invo\\n123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\n123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\n123456", s);

        json = "\"Invo\\f\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\f", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\f", s);

        json = "\"Invo\\f123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\f123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\f123456", s);

        json = "\"Invo\\r\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\r", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\r", s);

        json = "\"Invo\\r123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo\r123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        assertEquals("Invo\r123456", s);

        json = "\"Invo\\u7814\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo研", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        System.out.println("str=[" + s + "]");
        assertEquals("Invo研", s);

        json = "\"Invo\\u7814123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo研123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        s = bsReader.readString();
        System.out.println("str=[" + s + "]");
        assertEquals("Invo研123456", s);

        json = "\"Invo\\u7814\\u4e2d\\u4E2D\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo研中中", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8), 7);
        s = bsReader.readString();
        System.out.println("str=[" + s + "]");
        assertEquals("Invo研中中", s);

        json = "\"Invo\\u7814123456\"";
        reader = new StringJsonReader(json);
        s = reader.readString();
        assertEquals("Invo研123456", s);

        System.out.println("json=[" + json + "]");
        bsReader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8), 7);
        s = bsReader.readString();
        System.out.println("str=[" + s + "]");
        assertEquals("Invo研123456", s);

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"123\\\"ok";
                    ByteArrayJsonReader read = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8), 4);
                    read.readString();
                });
        assertTrue(thrown.getMessage().contains("JSON string was not closed with a char["));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"InvocationTargetException";
                    ByteArrayJsonReader reader2 = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    reader2.readString();
                });
        assertTrue(thrown.getMessage().contains("JSON string was not closed with a char["));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"Invo";
                    ByteArrayJsonReader reader2 = new ByteArrayJsonReader(
                            json2.getBytes(StandardCharsets.UTF_8), 4);
                    reader2.readString();
                });
        assertTrue(thrown.getMessage().contains("JSON string was not closed with a char["));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "Invo";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readString();
                });
        assertTrue(thrown.getMessage().contains("字符串没有使用引号"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "Invo";
                    ByteArrayJsonReader reader2 = new ByteArrayJsonReader(
                            json2.getBytes(StandardCharsets.UTF_8), 4);
                    reader2.readString();
                });
        assertTrue(thrown.getMessage().contains("字符串没有使用引号"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"Invo\\uwedf\"";
                    StringJsonReader reader2 = new StringJsonReader(json2);
                    reader2.readString();
                });
        assertTrue(thrown.getMessage().contains("Could not parse unicode escape, expected a hexadecimal digit"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"Invo\\uwedf\"";
                    ByteArrayJsonReader reader2 = new ByteArrayJsonReader(
                            json2.getBytes(StandardCharsets.UTF_8), 4);
                    reader2.readString();
                });
        assertTrue(thrown.getMessage().contains("Could not parse unicode escape, expected a hexadecimal digit"));
    }

    @Test
    public void testKeyHash() {
        String json = "\"name\":";
        StringJsonReader sr = new StringJsonReader(json);
        DataRange sdr = StringDataRange.from("name");
        assertEquals(sr.keyHash(), sdr.hashCode());

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(br.keyHash(), sdr.hashCode());

        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "name\":";
                    StringJsonReader read = new StringJsonReader(json2);
                    read.keyHash();
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "name\":";
                    ByteArrayJsonReader read = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    read.keyHash();
                });
        assertTrue(thrown.getMessage().contains("Key must start with '\"'!"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"name\",";
                    StringJsonReader read = new StringJsonReader(json2);
                    read.keyHash();
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "\"name\",";
                    ByteArrayJsonReader read = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    read.keyHash();
                });
        assertTrue(thrown.getMessage().contains("Key and value must use colon split"));
    }

    @Test
    public void testNextPos() throws NoSuchFieldException, IllegalAccessException {
        String json = "12";
        StringJsonReader reader = new StringJsonReader(json);
        reader.nextPos(1);
        Field posField = StringJsonReader.class.getDeclaredField("pos");
        posField.setAccessible(true);
        assertEquals((int)posField.get(reader), 1);

        ByteArrayJsonReader br = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
        br.nextPos(1);
        posField = ByteArrayJsonReader.class.getDeclaredField("pos");
        posField.setAccessible(true);
        assertEquals((int)posField.get(br), 1);

        IndexOutOfBoundsException thrown = assertThrows(IndexOutOfBoundsException.class,
                () -> {
                    String json2 = "12";
                    StringJsonReader read = new StringJsonReader(json2);
                    read.nextPos(3);
                });
        assertTrue(thrown.getMessage().contains("pos + count > 2"));

        thrown = assertThrows(IndexOutOfBoundsException.class,
                () -> {
                    String json2 = "12";
                    ByteArrayJsonReader read = new ByteArrayJsonReader(json2.getBytes(StandardCharsets.UTF_8));
                    read.nextPos(3);
                });
        assertTrue(thrown.getMessage().contains("pos + count > 2"));
    }

    @Test
    public void testJsonReadComment() {
        String json = "{}";
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> {
                    StringJsonReader reader = new StringJsonReader(json);
                    reader.readComment();
                });
        assertTrue(thrown.getMessage().contains("Standard json not support comments"));

        thrown = assertThrows(UnsupportedOperationException.class,
                () -> {
                    ByteArrayJsonReader reader = new ByteArrayJsonReader(json.getBytes(StandardCharsets.UTF_8));
                    reader.readComment();
                });
        assertTrue(thrown.getMessage().contains("Standard json not support comments"));
    }

}
