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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试解析Message的逻辑
 */
public class TestParseMessage {

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}"
    })
    void testParseWellBaseMessage(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            assertEquals("SearchRequest", msg.getName());
            Comment comment = msg.getComment();
            assertEquals(1, comment.getLines().size());
            String c = comment.getLines().get(0);
            assertEquals("消息的注释内容", c);

            List<Field> fields = msg.getFields();
            assertEquals(3, fields.size());
            Field f1 = fields.get(0);
            assertEquals("query", f1.getName());
            assertEquals("string", f1.getType());
            assertEquals(Field.Cardinality.OPTIONAL, f1.getCardinality());
            assertEquals(1, f1.getTag());

            Field f2 = fields.get(1);
            assertEquals("page_number", f2.getName());
            assertEquals("int32", f2.getType());
            assertEquals(Field.Cardinality.OPTIONAL, f2.getCardinality());
            assertEquals(2, f2.getTag());

            Field f3 = fields.get(2);
            assertEquals("result_per_page", f3.getName());
            assertEquals("int32", f3.getType());
            assertEquals(Field.Cardinality.OPTIONAL, f3.getCardinality());
            assertEquals(3, f3.getTag());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}"
    })
    void testMessageAddNullItem(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            assertEquals("SearchRequest", msg.getName());

            assertEquals(0, msg.getEnums().size());
            msg.addEnum(null);
            assertEquals(0, msg.getEnums().size());

            assertEquals(0, msg.getProtoExtends().size());
            msg.addProtoExtends(null);
            assertEquals(0, msg.getProtoExtends().size());

            assertEquals(0, msg.getExtensionses().size());
            msg.addExtensions(null);
            assertEquals(0, msg.getExtensionses().size());

            assertEquals(3, msg.getFields().size());
            msg.addField(null);
            assertEquals(3, msg.getFields().size());

            assertEquals(0, msg.getMessages().size());
            msg.addMessage(null);
            assertEquals(0, msg.getMessages().size());

            assertEquals(0, msg.getOneofs().size());
            msg.addOneof(null);
            assertEquals(0, msg.getOneofs().size());

            assertEquals(0, msg.getReserveds().size());
            msg.addReserved(null);
            assertEquals(0, msg.getReserveds().size());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}"
    })
    void testMessageAddItem(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            assertEquals("SearchRequest", msg.getName());

            assertEquals(0, msg.getEnums().size());
            ProtoEnum eitem = new ProtoEnum();
            msg.addEnum(eitem);
            assertEquals(1, msg.getEnums().size());

            assertEquals(0, msg.getProtoExtends().size());
            Extend exd = new Extend();
            msg.addProtoExtends(exd);
            assertEquals(1, msg.getProtoExtends().size());

            assertEquals(0, msg.getExtensionses().size());
            Extensions exts = new Extensions();
            msg.addExtensions(exts);
            assertEquals(1, msg.getExtensionses().size());

            assertEquals(3, msg.getFields().size());
            Field f = new Field();
            msg.addField(f);
            assertEquals(4, msg.getFields().size());

            assertEquals(0, msg.getMessages().size());
            Message m = new Message();
            msg.addMessage(m);
            assertEquals(1, msg.getMessages().size());

            assertEquals(0, msg.getOneofs().size());
            Oneof oneof = new Oneof();
            msg.addOneof(oneof);
            assertEquals(1, msg.getOneofs().size());

            assertEquals(0, msg.getReserveds().size());
            Reserved reserved = new TagReserved();
            msg.addReserved(reserved);
            assertEquals(1, msg.getReserveds().size());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "}"
    })
    void testMessageSetItems(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            assertEquals("SearchRequest", msg.getName());

            assertEquals(0, msg.getEnums().size());
            ProtoEnum eitem = new ProtoEnum();
            msg.setEnums(Arrays.asList(eitem));
            assertEquals(1, msg.getEnums().size());
            assertTrue(msg.getEnums() instanceof ArrayList);
            List<ProtoEnum> enums = new ArrayList<>();
            enums.add(eitem);
            msg.setEnums(enums);
            assertEquals(1, msg.getEnums().size());
            assertTrue(msg.getEnums() instanceof ArrayList);

            assertEquals(0, msg.getProtoExtends().size());
            Extend exd = new Extend();
            msg.setProtoExtends(Arrays.asList(exd));
            assertEquals(1, msg.getProtoExtends().size());
            assertTrue(msg.getExtensionses() instanceof ArrayList);
            List<Extend> exds = new ArrayList<>();
            exds.add(exd);
            msg.setProtoExtends(exds);
            assertEquals(1, msg.getProtoExtends().size());
            assertTrue(msg.getProtoExtends() instanceof ArrayList);

            assertEquals(0, msg.getExtensionses().size());
            Extensions exts = new Extensions();
            msg.setExtensionses(Arrays.asList(exts));
            assertEquals(1, msg.getExtensionses().size());
            assertTrue(msg.getExtensionses() instanceof ArrayList);
            List<Extensions> extses = new ArrayList<>();
            extses.add(exts);
            msg.setExtensionses(extses);
            assertEquals(1, msg.getExtensionses().size());
            assertTrue(msg.getExtensionses() instanceof ArrayList);

            assertEquals(0, msg.getFields().size());
            Field f = new Field();
            msg.setFields(Arrays.asList(f));
            assertEquals(1, msg.getFields().size());
            assertTrue(msg.getFields() instanceof ArrayList);
            List<Field> fields = new ArrayList<>();
            fields.add(f);
            msg.setFields(fields);
            assertEquals(1, msg.getFields().size());
            assertTrue(msg.getFields() instanceof ArrayList);

            assertEquals(0, msg.getMessages().size());
            Message m = new Message();
            msg.setMessages(Arrays.asList(m));
            assertEquals(1, msg.getMessages().size());
            assertTrue(msg.getMessages() instanceof ArrayList);
            List<Message> nestMsgs = new ArrayList<>();
            nestMsgs.add(m);
            msg.setMessages(nestMsgs);
            assertEquals(1, msg.getMessages().size());
            assertTrue(msg.getMessages() instanceof ArrayList);

            assertEquals(0, msg.getOneofs().size());
            Oneof oneof = new Oneof();
            msg.setOneofs(Arrays.asList(oneof));
            assertEquals(1, msg.getOneofs().size());
            assertTrue(msg.getOneofs() instanceof ArrayList);
            List<Oneof> oneofs = new ArrayList<>();
            oneofs.add(oneof);
            msg.setOneofs(oneofs);
            assertEquals(1, msg.getOneofs().size());
            assertTrue(msg.getOneofs() instanceof ArrayList);

            assertEquals(0, msg.getReserveds().size());
            Reserved reserved = new TagReserved();
            msg.setReserveds(Arrays.asList(reserved));
            assertEquals(1, msg.getReserveds().size());
            assertTrue(msg.getReserveds() instanceof ArrayList);
            List<Reserved> reses = new ArrayList<>();
            reses.add(reserved);
            msg.setReserveds(reses);
            assertEquals(1, msg.getReserveds().size());
            assertTrue(msg.getReserveds() instanceof ArrayList);

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2; //每页条数\r\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "}"
    })
    void testParseWellMapMessage(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            MapField mapField = (MapField) msg.getFields().get(3);
            assertEquals("extendInfo", mapField.getName());
            assertEquals(Field.Type.INT32, mapField.getKey());
            assertEquals("string", mapField.getValue());
            assertEquals("map<int32, string>", mapField.getTypeString());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2; //每页条数\r\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "message InnerRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2; //每页条数\r\n" +
                    "}" +
                    "}"
    })
    void testParseWellInnerMessage(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            MapField mapField = (MapField) msg.getFields().get(3);
            assertEquals("extendInfo", mapField.getName());
            assertEquals(Field.Type.INT32, mapField.getKey());
            assertEquals("string", mapField.getValue());
            assertEquals("map<int32, string>", mapField.getTypeString());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2; //每页条数\r\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "enum EnumAllowingAlias {\n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    "}" +
                    "}"
    })
    void testParseWellEnumMessage(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            MapField mapField = (MapField) msg.getFields().get(3);
            assertEquals("extendInfo", mapField.getName());
            assertEquals(Field.Type.INT32, mapField.getKey());
            assertEquals("string", mapField.getValue());
            assertEquals("map<int32, string>", mapField.getTypeString());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n/*\n *消息的注释内容\n" +
                    " *第二行的内容\n" +
                    " *\n" +
                    " */\n" +
                    "message SearchRequest {\n" +
                    "  //查询请求\n" +
                    "  string query = 1 ;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int64 timestamp = 3 [java_type=\"LocalDateTime\"];\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  InnerMessage innerMsg = 5[java_type=\"LocalDateTime\",deprecated=\"false\"];\n" +
                    "}"
    })
    void testParseWellMultiLineComment(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Comment comment = msgs.get(0).getComment();
            assertEquals(Comment.CommentType.MULTILINE, comment.getType());

            List<String> lines = comment.getLines();
            assertEquals("消息的注释内容", lines.get(0));
            assertEquals("第二行的内容", lines.get(1));
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n" +
                    "\"//注释内容\n"
                    + " /*消息的注释内容\n" +
                    " *第二行的内容\n" +
                    " *\n" +
                    " */\n" +

                    "message SearchRequest {\n" +
                    "  //查询请求\n" +
                    "  string query = 1 ;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int64 timestamp = 3 [java_type=\"LocalDateTime\"];\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  InnerMessage innerMsg = 5[java_type=\"LocalDateTime\",deprecated=\"false\"];\n" +
                    "}"
    })
    void testMultiLineCommentFirstLineNotEmpty(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Comment comment = msgs.get(0).getComment();
            assertEquals(Comment.CommentType.MULTILINE, comment.getType());

            List<String> lines = comment.getLines();
            assertEquals("消息的注释内容", lines.get(0));
            assertEquals("第二行的内容", lines.get(1));
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n/**\n *消息的注释内容\n" +
                    " *第二行的内容\n" +
                    " * 第二行的内容\r\n" +
                    " */\n" +
                    "message SearchRequest {\n" +
                    "  //查询请求\n" +
                    "  string query = 1 ;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int64 timestamp = 3 [java_type=\"LocalDateTime\"];\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  InnerMessage innerMsg = 5[java_type=\"LocalDateTime\",deprecated=\"false\"];\n" +
                    "}"
    })
    void testParseWellDocumentComment(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Comment comment = msgs.get(0).getComment();
            assertEquals(Comment.CommentType.DOCUMENT, comment.getType());

            List<String> lines = comment.getLines();
            assertEquals("消息的注释内容", lines.get(0));
            assertEquals("第二行的内容", lines.get(1));
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  //查询请求\n" +
                    "  string query = 1 ;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int64 timestamp = 3 [java_type=\"LocalDateTime\"];\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  InnerMessage innerMsg = 5[java_type=\"LocalDateTime\",deprecated=\"false\"];\n" +
                    "}"

    })
    void testParseWellFieldOptions(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            Message msg = msgs.get(0);
            Field field1 = msg.getFields().get(0);
            assertEquals("", field1.getDefaultValue());
            Comment comment = field1.getComment();
            assertEquals(1, comment.getLines().size());
            assertEquals("查询请求", comment.getLines().get(0));
            assertEquals("string", field1.getTypeString());

            MapField mapField = (MapField) msg.getFields().get(3);
            assertEquals("extendInfo", mapField.getName());
            assertEquals(Field.Type.INT32, mapField.getKey());
            assertEquals("string", mapField.getValue());
            assertEquals("map<int32, string>", mapField.getTypeString());
            assertNull(mapField.getDefaultValue());
            assertFalse(mapField.getDeprecated());

            Field field2 = msg.getFields().get(1);
            Option option = new Option();
            option.setName("deprecated");
            option.setValue("true");
            List<Option> options = Arrays.asList(option);
            assertFalse(options instanceof ArrayList);
            field2.setOptions(options);
            assertTrue(field2.getOptions() instanceof ArrayList);
            assertTrue(field2.getDeprecated());
            field2.setDeprecated(false);
            assertFalse(field2.getDeprecated());

            Field field3 = msg.getFields().get(2);
            assertEquals(1, field3.getOptions().size());
            option = field3.getOptions().get(0);
            assertEquals("java_type", option.getName());
            assertEquals("LocalDateTime", option.getValue());

            Field field5 = msg.getFields().get(4);
            options = field5.getOptions();
            assertEquals(2, options.size());
            Option option1 = options.get(0);
            assertEquals("java_type", option1.getName());
            assertEquals("LocalDateTime", option1.getValue());
            Option option2 = options.get(1);
            assertEquals("deprecated", option2.getName());
            assertEquals("false", option2.getValue());
            assertNull(field5.getDefaultValue());
            assertFalse(field5.getDeprecated());
            field5.setDeprecated(true);
            assertTrue(field5.getDeprecated());
            field5.setDefaultValue("test");
            assertEquals("test", field5.getDefaultValue());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1 \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}"
    })
    void testParseWrongMessageNotSemicolon(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("value not end with \";\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message {\n" +
                    "  string query = 1 \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
    })
    void testParseWrongMessageNotName(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("message name not set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 0; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = -1; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 19000; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 19999; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}"
    })
    void testParseWrongTag(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("] not enabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = ab; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 09; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 0x0g; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",
    })
    void testParseWrongTagNotNumber(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("] not NumberFormat"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = ab[/name=true]; \n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}",

    })
    void testParseWrongOption(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("Option name can not start with"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = ab[\n" +
                    "}",

    })
    void testParseOptionNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        System.out.println("thrown=" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Option name not end"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=\"\n" +
                    "}",

    })
    void testParseOptionNotWithBrackets(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        System.out.println("thrown=" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("value not end with"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=\"abc\\\"\"];\n" +
                    "}",

    })
    void testParseOptionWithQuotation(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            Field field = proto.getMessages().get(0).getFields().get(0);
            assertEquals("java_name", field.getOptions().get(0).getName());
            assertEquals("abc\"", field.getOptions().get(0).getValue());
        } catch (Exception e) {
            e.printStackTrace();
        }
        //System.out.println("thrown=" + thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=\"\"" +
                    "}",

    })
    void testParseOptionNotBrackets(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().contains("value not end with"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=\"",

    })
    void testParseOptionValueNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("value not end with"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=\"DateTime\"]t",

    })
    void testParsOptionErrorEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("value not end with"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=DateTime];" +
                    "}",

    })
    void testParsOption(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            assertEquals(1, proto.getMessages().get(0).getFields().get(0).getOptions().size());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=DateTime,packed=true ];\n" +
                    "}",

    })
    void testParseOptionMultiValue(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            assertEquals(2, proto.getMessages().get(0).getFields().get(0).getOptions().size());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=DateTime\n",

    })
    void testParseOptionNotBrackets2(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("value not end with"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=DateTime,];\n}",

    })
    void testParseOptionSuperfluousComma(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            assertEquals(1, proto.getMessages().get(0).getFields().get(0).getOptions().size());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1[java_name=DateTime",

    })
    void testParseOptionError(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("value not end"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  string query = 1",

    })
    void testParseFieldError(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("value not end"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message test {\n" +
                    "  // 第一行的注释\n" +
                    "  string query = 1;\n" +
                    "  // 第二行的注释\n" +
                    "  string query2 = 2;\n" +
                    "  // 第三行的注释\n" +
                    "  string query3 = 1;\n" +
                    "}",

    })
    void testParseFieldComment(String protoStr) {
        try {
            ProtoParser parser = new ProtoParser(protoStr);
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(msgs.size(), 1);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
