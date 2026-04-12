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

import io.edap.protobuf.wire.Option;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.ProtoEnum;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试即系Enum消息体定义的逻辑
 */
public class TestParseEnum {

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias {\n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    "}"
    })
    void testParseWellEnum(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(1, proto.getEnums().size());
            List<ProtoEnum> protoenums = proto.getEnums();
            ProtoEnum pe = protoenums.get(0);
            assertEquals("EnumAllowingAlias", pe.getName());
            List<ProtoEnum.EnumEntry> entries = pe.getEntries();
            assertEquals(3, entries.size());

            List<Option> options = pe.getOptions();
            assertEquals(1, options.size());
            Option opt = options.get(0);
            assertEquals("allow_alias", opt.getName());
            assertEquals("true", opt.getValue());


            ProtoEnum.EnumEntry e1 = entries.get(0);
            assertEquals("UNKNOWN", e1.getLabel());
            assertEquals(0, e1.getValue());
            assertEquals(1, e1.getComment().getLines().size());
            String comment = e1.getComment().getLines().get(0);
            assertEquals("默认值", comment);

            ProtoEnum.EnumEntry e2 = entries.get(1);
            assertEquals("STARTED", e2.getLabel());
            assertEquals(1, e2.getValue());
            assertEquals(1, e2.getComment().getLines().size());
            String comment2 = e2.getComment().getLines().get(0);
            assertEquals("开始状态", comment2);

            ProtoEnum.EnumEntry e3 = entries.get(2);
            assertEquals("RUNNING", e3.getLabel());
            assertEquals(2, e3.getValue());
            List<Option> eoptions = e3.getOptions();
            assertEquals(1, eoptions.size());
            Option eoption = eoptions.get(0);
            assertEquals("(custom_option)", eoption.getName());
            assertEquals("hello world", eoption.getValue());

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias test {\n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    "}"
    })
    void testParseWrongNameEnum(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().endsWith("enum not start char '{'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum {\n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    "}"
    })
    void testParseNoNameEnum(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().endsWith("Enum name not set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias \n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    "}"
    })
    void testParseNotStartBraceEnum(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().endsWith("enum not start char '{'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias {\n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    ""
    })
    void testParseEnumNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        System.out.println("thrown=" + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Enum has't end with '}'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias { \n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = 2[(custom_option) = \"hello world\"];\n" +
                    "\n"
    })
    void testParseNotEndBraceEnum(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().endsWith("Enum has't end with '}'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias { \n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING;\n" +
                    "\n"
    })
    void testParseWrongEntryEnum(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertEquals("EnumEntry has't char '='", thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias { \n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = yyy;\n" +
                    "\n"
    })
    void testParseWrongValueEnumNotNumber(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().endsWith("not NumberFormat"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias { \n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0; // 默认值\n" +
                    "//开始状态\n" +
                    "  STARTED = 1;\n" +
                    "  RUNNING = -3;\n" +
                    "\n"
    })
    void testParseWrongValueEnumNegative(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().contains("Enum value can't is"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\nenum EnumAllowingAlias { \n" +
                    "  option allow_alias = true;\n" +
                    "  UNKNOWN = 0;" +
                    "\n"
    })
    void testParseWrongNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("Enum has't end with '}'"));
    }
}
