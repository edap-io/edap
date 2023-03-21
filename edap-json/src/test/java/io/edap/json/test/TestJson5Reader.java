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
import io.edap.json.enums.CommentItemType;
import io.edap.json.model.CommentItem;
import io.edap.json.test.model.DemoPojo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJson5Reader {


    @Test
    public void testRead() {
        String json = "{\n" +
                "  // comments\n" +
                "  /**\n" +
                "   *\n" +
                "   */\n" +
                "  unquoted: 'and you can quote me on that',\n" +
                "  singleQuotes: 'I can use \"double quotes\" here',\n" +
                "  lineBreaks: \"Look, Mom! \\\n" +
                "No \\\\n's!\",\n" +
                "  hexadecimal: 0xdecaf,\n" +
                "  leadingDecimalPoint: .8675309, andTrailing: 8675309.,\n" +
                "  positiveSign: +1,\n" +
                "  trailingComma: 'in objects', andIn: ['arrays',],\n" +
                "  \"backwardsCompatible\": \"with JSON\",\n" +
                "}";
        StringJson5Reader parser = new StringJson5Reader(json);
        Object object = parser.readObject();
        assertNotNull(object);

        ByteArrayJson5Reader br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        object = br.readObject();
        assertNotNull(object);
    }

    @Test
    public void testReadString() {

    }

    @Test
    public void testReadNumber() {
        String json = "{value:NaN}";
        StringJson5Reader reader = new StringJson5Reader(json);
        Object obj = reader.readObject();
        assertNotNull(obj);
        assertEquals(obj instanceof JsonObject, true);
        JsonObject jobj = (JsonObject) obj;
        assertEquals(Double.isNaN((Double) jobj.get("value")), true);

        ByteArrayJson5Reader br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        obj = br.readObject();
        assertNotNull(obj);
        assertEquals(obj instanceof JsonObject, true);
        jobj = (JsonObject) obj;
        assertEquals(Double.isNaN((Double) jobj.get("value")), true);

        json = "{value:Infinity}";
        reader = new StringJson5Reader(json);
        obj = reader.readObject();
        assertNotNull(obj);
        assertEquals(obj instanceof JsonObject, true);
        jobj = (JsonObject) obj;
        assertEquals(Double.isInfinite((Double) jobj.get("value")), true);

        br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        obj = br.readObject();
        assertNotNull(obj);
        assertEquals(obj instanceof JsonObject, true);
        jobj = (JsonObject) obj;
        assertEquals(Double.isInfinite((Double) jobj.get("value")), true);
    }

    @Test
    public void testReadPojo() throws InvocationTargetException, InstantiationException, IllegalAccessException {
        String json = "{\n// my is comment \n\"name\":\"john\",\"age\":0,\"integral\":0,\"balance\":0}";
        DemoPojo pojo = new StringJson5Reader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 0);
        assertEquals(pojo.getBalance(), 0);
        assertEquals(pojo.getIntegral(), 0);

        pojo = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john");
        assertEquals(pojo.getAge(), 0);
        assertEquals(pojo.getBalance(), 0);
        assertEquals(pojo.getIntegral(), 0);

        json = "{\"name\":\"john325\",age:549,\"integral\":1234567898776,\"balance\":12345.67898776}";
        pojo = new StringJson5Reader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        pojo = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        json = "{\"name\":\"john325\",\"age\":549,\"integral\":1234567898776,\"balance\":12345.67898776}";
        pojo = new StringJson5Reader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        pojo = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getBalance(), 12345.67898776D);

        json = "{\"name\":\"john325\",\"age\":549,\"integral\":1234567898776,\"balance\":12345}";
        pojo = new StringJson5Reader(json).readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getIntegral(), 1234567898776L);
        assertEquals(pojo.getBalance(), 12345D);

        pojo = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8))
                .readObject(DemoPojo.class);
        assertNotNull(pojo);
        assertEquals(pojo.getName(), "john325");
        assertEquals(pojo.getAge(), 549);
        assertEquals(pojo.getBalance(), 12345D);


        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":中}";
                    new StringJson5Reader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":中}";
                    System.out.println("json2.leng=" + json2.length());
                    System.out.println("json2.bs.leng=" + json2.getBytes(StandardCharsets.UTF_8).length);
                    new ByteArrayJson5Reader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":01}";
                    DemoPojo pojo2 = new StringJson5Reader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":01}";
                    new ByteArrayJson5Reader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不能有前导0的字符"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":a}";
                    new StringJson5Reader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"age\":a}";
                    new ByteArrayJson5Reader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"balance\":123.}";
                    new StringJson5Reader(json2).readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("double类型\".\"没有其他数字"));

        thrown = assertThrows(JsonParseException.class,
                () -> {
                    String json2 = "{\"name\":\"john\",\"balance\":123.}";
                    new ByteArrayJson5Reader(json2.getBytes(StandardCharsets.UTF_8))
                            .readObject(DemoPojo.class);
                });
        assertTrue(thrown.getMessage().contains("double类型\".\"没有其他数字"));
    }

    @Test
    public void testReadObject() {
        String json = "// An empty object\n" +
                "{}";
        StringJson5Reader parser = new StringJson5Reader(json);
        Object object = parser.readObject();
        assertNotNull(object);
        assertTrue(object instanceof Map);
        Map<String, Object> map = (Map<String, Object>)object;
        assertTrue(map.isEmpty());

        ByteArrayJson5Reader br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        object = br.readObject();
        assertNotNull(object);
        assertTrue(object instanceof Map);
        map = (Map<String, Object>)object;
        assertTrue(map.isEmpty());

        json = "// An object with two properties\n" +
                "// and a trailing comma\n" +
                "{\n" +
                "    width: 1920\n" +
                "}";
        parser = new StringJson5Reader(json);
        object = parser.readObject();
        assertNotNull(object);
        assertTrue(object instanceof Map);
        map = (Map<String, Object>)object;
        assertEquals(map.size(), 1);

        br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        object = br.readObject();
        assertNotNull(object);
        assertTrue(object instanceof Map);
        map = (Map<String, Object>)object;
        assertEquals(map.size(), 1);
    }

    @Test
    public void testReadComment() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = StringJson5Reader.class.getDeclaredMethod("readComment", new Class[0]);
        method.setAccessible(true);
        String json = "// This file is written in JSON5 syntax, naturally, but npm needs a regular\n" +
                "// JSON file, so compile via `npm run build`. Be sure to keep both in sync!\n" +
                "\n" +
                "{}";
        StringJson5Reader parser = new StringJson5Reader(json);
        List<CommentItem> commentItems = (List<CommentItem>)method.invoke(parser, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 3);
        assertEquals(commentItems.get(0).getComments().size(), 1);
        assertEquals(commentItems.get(0).getType(), CommentItemType.SINGLE_COMMENT);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");

        assertEquals(commentItems.get(1).getComments().size(), 1);
        assertEquals(commentItems.get(1).getType(), CommentItemType.SINGLE_COMMENT);
        assertEquals(commentItems.get(1).getComments().get(0),
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync!");

        assertEquals(commentItems.get(2).getType(), CommentItemType.EMPTY_ROW);

        Method methodByte = ByteArrayJson5Reader.class.getDeclaredMethod("readComment", new Class[0]);
        methodByte.setAccessible(true);
        ByteArrayJson5Reader br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        commentItems = (List<CommentItem>)methodByte.invoke(br, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 3);
        assertEquals(commentItems.get(0).getComments().size(), 1);
        assertEquals(commentItems.get(0).getType(), CommentItemType.SINGLE_COMMENT);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");

        assertEquals(commentItems.get(1).getComments().size(), 1);
        assertEquals(commentItems.get(1).getType(), CommentItemType.SINGLE_COMMENT);
        assertEquals(commentItems.get(1).getComments().get(0),
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync!");

        assertEquals(commentItems.get(2).getType(), CommentItemType.EMPTY_ROW);


        json = "//This file is written in JSON5 syntax, naturally, but npm needs a regular\n" +
                "{}";
        parser = new StringJson5Reader(json);
        commentItems = (List<CommentItem>)method.invoke(parser, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 1);
        assertEquals(commentItems.get(0).getComments().size(), 1);
        assertEquals(commentItems.get(0).getType(), CommentItemType.SINGLE_COMMENT);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");

        br = new ByteArrayJson5Reader(json.getBytes(StandardCharsets.UTF_8));
        commentItems = (List<CommentItem>)methodByte.invoke(br, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 1);
        assertEquals(commentItems.get(0).getComments().size(), 1);
        assertEquals(commentItems.get(0).getType(), CommentItemType.SINGLE_COMMENT);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");
    }

    @Test
    public void testReadCommentNotWell() throws NoSuchMethodException {
        Method method = StringJson5Reader.class.getDeclaredMethod("readComment", new Class[0]);
        method.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String json = "/";
                    StringJson5Reader parser = new StringJson5Reader(json);
                    method.invoke(parser, new Object[0]);
                });
        assertTrue(thrown.getCause().getMessage().contains("注释非正常结束"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String json = "/a";
                    StringJson5Reader parser = new StringJson5Reader(json);
                    method.invoke(parser, new Object[0]);
                });
        assertTrue(thrown.getCause().getMessage().contains("注释需要以\"//\"后者\"/*\"开始"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String json = "/* \n";
                    StringJson5Reader parser = new StringJson5Reader(json);
                    method.invoke(parser, new Object[0]);
                });
        assertTrue(thrown.getCause().getMessage().contains("多行注释没正常结束"));
    }

    @Test
    public void testReadMultiComment() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = StringJson5Reader.class.getDeclaredMethod("readComment", new Class[0]);
        method.setAccessible(true);
        String json = "/* This file is written in JSON5 syntax, naturally, but npm needs a regular\n" +
                " * JSON file, so compile via `npm run build`. Be sure to keep both in sync!\n" +
                " */" +
                "{}";
        StringJson5Reader parser = new StringJson5Reader(json);
        List<CommentItem> commentItems = (List<CommentItem>) method.invoke(parser, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 1);
        assertEquals(commentItems.get(0).getComments().size(), 2);
        assertEquals(commentItems.get(0).getType(), CommentItemType.MULTILINE);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");
        assertEquals(commentItems.get(0).getComments().get(1),
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync!");

        json = "/* This file is written in JSON5 syntax, naturally, but npm needs a regular\n" +
                " *JSON file, so compile via `npm run build`. Be sure to keep both in sync!\n" +
                " */" +
                "{}";
        parser = new StringJson5Reader(json);
        commentItems = (List<CommentItem>) method.invoke(parser, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 1);
        assertEquals(commentItems.get(0).getComments().size(), 2);
        assertEquals(commentItems.get(0).getType(), CommentItemType.MULTILINE);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");
        assertEquals(commentItems.get(0).getComments().get(1),
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync!");

        json = "/* This file is written in JSON5 syntax, naturally, but npm needs a regular\n" +
                " *JSON file, so compile via `npm run build`. Be sure to keep both in sync!\r\n" +
                " */" +
                "{}";
        parser = new StringJson5Reader(json);
        commentItems = (List<CommentItem>) method.invoke(parser, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 1);
        assertEquals(commentItems.get(0).getComments().size(), 2);
        assertEquals(commentItems.get(0).getType(), CommentItemType.MULTILINE);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");
        assertEquals(commentItems.get(0).getComments().get(1),
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync!");

        json = "/* This file is written in JSON5 syntax, naturally, but npm needs a regular\n" +
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync! */" +
                "{}";
        parser = new StringJson5Reader(json);
        commentItems = (List<CommentItem>) method.invoke(parser, new Object[0]);
        assertNotNull(commentItems);
        assertEquals(commentItems.size(), 1);
        assertEquals(commentItems.get(0).getComments().size(), 2);
        assertEquals(commentItems.get(0).getType(), CommentItemType.MULTILINE);
        assertEquals(commentItems.get(0).getComments().get(0),
                "This file is written in JSON5 syntax, naturally, but npm needs a regular");
        assertEquals(commentItems.get(0).getComments().get(1),
                "JSON file, so compile via `npm run build`. Be sure to keep both in sync!");
    }
}
