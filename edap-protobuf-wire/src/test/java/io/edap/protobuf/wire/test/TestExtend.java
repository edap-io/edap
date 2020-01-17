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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试extend的解析
 */
public class TestExtend {

    /**
     * 测试正确的Extend消息体的解析是否正确。tag是 startTag to endTag
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 199;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend Foo {\n" +
                    "  optional int32 bar = 126;\n" +
                    "}"
    })
    void testParseExtend(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());
            Message msg = msgs.get(0);
            List<Extensions> extensionses = msg.getExtensionses();
            assertEquals(1, extensionses.size());
            Extensions extensions = extensionses.get(0);
            assertEquals(100, extensions.getStartTag());
            assertEquals(199, extensions.getEndTag());

            List<Extend> exts = proto.getProtoExtends();
            assertEquals(1, exts.size());
            Extend ext = exts.get(0);
            assertEquals("Foo", ext.getName());
            assertEquals("extend的注释", ext.getComment().getLines().get(0));

            List<Field> fields = ext.getFields();
            assertEquals(1, fields.size());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试正确的Extend消息体的解析是否正确。tag是 startTag to max
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to max;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend Foo {\n" +
                    "//extenField的说明" +
                    "  optional int32 bar = 126;\n" +
                    "  int64 zoo = 128;\n" +
                    "}"
    })
    void testParseExtensions(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());
            Message msg = msgs.get(0);
            List<Extensions> extensionses = msg.getExtensionses();
            assertEquals(1, extensionses.size());
            Extensions extensions = extensionses.get(0);
            assertEquals(100, extensions.getStartTag());
            assertEquals(WireFormat.MAX_TAG_VALUE, extensions.getEndTag());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试Extend消息体Field操作的函数逻辑
     */
    @Test
    void testExtend() {
        Extend ext = new Extend();
        assertEquals(0, ext.getFields().size());
        ext.addField(null);
        assertEquals(0, ext.getFields().size());

        Field field = new Field();
        ext.addField(field);
        assertEquals(1, ext.getFields().size());

        Extend ext2 = new Extend();
        List<Field> fs = Arrays.asList(field);
        assertFalse(fs instanceof ArrayList);
        ext2.setFields(fs);
        assertTrue(ext2.getFields() instanceof ArrayList);

        Extend ext3 = new Extend();
        fs = new ArrayList<>();
        assertTrue(fs instanceof ArrayList);
        ext3.setFields(fs);
        assertTrue(ext3.getFields() instanceof ArrayList);
    }

    /**
     * 测试extensions表达式没有设置 starttag to endtag
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions ;\n" +
                    "}"
    })
    void testParseWrongExtensionsExpression(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extensions expression is empty"));
    }

    /**
     * 测试extensions 开始和结束tag值小于等于0的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 0 to 100;\n" +
                    "}",
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions -1 to 100;\n" +
                    "}"
    })
    void testParseWrongExtensionsTag(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extensions expression start cann't be"));
    }

    /**
     * 测试extension表达式没有"to"关键字的的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 1 100;\n" +
                    "}"
    })
    void testParseWrongExtensionsNotTo(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extensions expression must be start to end"));
    }

    /**
     * 测试extension 结束标签没有设置的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 1 to;\n" +
                    "}"
    })
    void testParseWrongExtensionsEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extensions expression end empty"));
    }

    /**
     * 测试extension 结束标签小于开始标签的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 99;\n" +
                    "}"
    })
    void testParseWrongExtensionsStartEndTag(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extensions start tag <= end tag"));
    }

    /**
     * 测试extension 开始tag没有设置的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions  to 99;\n" +
                    "}"
    })
    void testParseWrongExtensionsNotStart(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extensions expression start empty"));
    }

    /**
     * 测试测试extension 没有设置表达式的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions;\n" +
                    "}"
    })
    void testParseWrongExtensionsNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("expression not end with char"));
    }

    /**
     * 测试extend没有设置name的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 199;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend  {\n" +
                    "  optional int32 bar = 126;\n" +
                    "}"
    })
    void testParseWrongExtendNoName(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().contains("extend name not set"));
    }

    /**
     * 测试extend没有以"}"结束的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 199;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend Inner {\n" +
                    "  optional int32 bar = 126;\n" +
                    ""
    })
    void testParseWrongExtendEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("extend not end"));
    }

    /**
     * 测试map没有以"<"开始的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 199;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend Inner {\n" +
                    "  map{} bar = 126;\n" +
                    "}"
    })
    void testParseWrongMapStartChar(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("GenericType not started with '<'"));
    }

    /**
     * 测试map没有以">"结束的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 199;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend Inner {\n" +
                    "  map<int32, string} bar = 126;\n" +
                    "}"
    })
    void testParseWrongMapEndChar(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("GenericType not end with '>'"));
    }

    /**
     * 测试map没有以">"结束的错误
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\nmessage Foo {\n" +
                    "  // ...\n" +
                    "  string name=1;\n" +
                    "  extensions 100 to 199;\n" +
                    "}\n\n" +
                    "//extend的注释\n" +
                    "extend Inner {\n" +
                    "  map<int32, string" +
                    "}"
    })
    void testParseWrongMapEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("GenericType not end with '>'"));
    }
}