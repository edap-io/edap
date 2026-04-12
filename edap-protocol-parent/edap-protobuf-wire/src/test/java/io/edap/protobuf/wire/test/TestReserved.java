/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf.wire.test;

import io.edap.protobuf.wire.*;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试解析Reserved结构的逻辑
 */
public class TestReserved {

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "//reserved comment\n" +
                    "  reserved 2, 15, 9 to 11, 50000 to max;\n" +
                    "  reserved \"foo\", \"bar\";\n" +
                    "}"
    })
    void testWellReserved(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            Message msg = proto.getMessages().get(0);

            List<Reserved> reserveds = msg.getReserveds();
            assertEquals(2, reserveds.size());

            Reserved reserved1 = reserveds.get(0);
            assertTrue(reserved1 instanceof TagReserved);
            TagReserved tagReserved = (TagReserved)reserved1;
            assertEquals(2, tagReserved.getTags().size());
            assertEquals(2, tagReserved.getTags().get(0));
            assertEquals(15, tagReserved.getTags().get(1));
            assertEquals(2, tagReserved.getStartEnds().size());
            assertEquals(9, tagReserved.getStartEnds().get(0).getStartTag());
            assertEquals(11, tagReserved.getStartEnds().get(0).getEndTag());
            assertEquals(50000, tagReserved.getStartEnds().get(1).getStartTag());
            assertEquals(WireFormat.MAX_TAG_VALUE, tagReserved.getStartEnds().get(1).getEndTag());
            assertEquals("2, 15, 9 to 11, 50000 to max", tagReserved.getExpression());
            assertEquals("reserved comment", tagReserved.getComment().getLines().get(0));

            NameReserved nameReserved = (NameReserved)reserveds.get(1);
            assertEquals(2, nameReserved.getFieldNames().size(), 2);
            assertEquals("foo", nameReserved.getFieldNames().get(0));
            assertEquals("bar", nameReserved.getFieldNames().get(1));
            assertEquals("\"foo\", \"bar\"", nameReserved.getExpression());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "//reserved comment\n" +
                    "  reserved 2, 15, 9 to 11, 50000 to max;\n" +
                    "  reserved \"foo\", \"\";\n" +
                    "}"
    })
    void testWellReserved2(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            Message msg = proto.getMessages().get(0);
            assertEquals(1, msg.getComment().getLines().size());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试syntax版本错误的判断逻辑
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "  reserved   ;\n" +
                    "  reserved \"foo\", \"bar\";\n" +
                    "}"
    })
    void testParseWrongReserved(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("reserved expression is empty"));
    }

    /**
     * 测试syntax版本错误的判断逻辑
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "  reserved  2,15;\n" +
                    "  reserved \"foo\", bar;\n" +
                    "}"
    })
    void testParseWrongReserved2(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        System.out.println("thrown=" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("field name not start with ' or \""));
    }

    /**
     * 测试syntax版本错误的判断逻辑
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "  reserved  2,15;\n" +
                    "  reserved \"foo\", \"bar\",\"bar2\"f; \n" +
                    "}"
    })
    void testParseWrongReserved3(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        System.out.println("thrown=" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("reserved not end \";\""));
    }

    /**
     * 测试syntax版本错误的判断逻辑
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "  tag=2,000;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "  tag=2_000;\n" +
                    "}"
    })
    void testParseWellTag(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            Message msg = proto.getMessages().get(0);
            assertEquals(2000, msg.getFields().get(0).getTag());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试Reserved没有正确结束的用例
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message Foo {\n" +
                    "  reserved  2,15",
    })
    void testParseReservedNotEnd(String protoStr) {

        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                    Message msg = proto.getMessages().get(0);
                });
        assertTrue(thrown.getMessage().contains("expression not end with char ';'"));
    }
}