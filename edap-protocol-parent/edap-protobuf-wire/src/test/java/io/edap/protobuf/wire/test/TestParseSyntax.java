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

import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.Syntax;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试解析protobuf版本的逻辑
 */
public class TestParseSyntax {

    /**
     * 测试解析Syntax2版本正确的几种写法,除去空行和注释行的第一行标注proto的版本为proto2
     * 或者没有标准版本默认为proto2
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";",
            "",
            "//comment\n//fdsfds \n\n\t",
            "\n\n\n\n\n"
    })
    void testParseWellSyntax2(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_2, proto.getSyntax());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";",
            "",
            "//comment\n//fdsfds \n\n\t",
            "// 结束行为commment的情况",
            "syntax = \"proto2\";",
            "",
            "//comment\n//fdsfds \n\n\t",
            "//结束行为commment的情况"
    })
    void testParseCommentEnd(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_2, proto.getSyntax());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";/**\n * fdsafd ",
    })
    void testParseCommentEnd2(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_2, proto.getSyntax());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * 测试解析Syntax3版本的proto正确格式，除去空行和注释行的第一行标注proto的版本为proto3
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";",
            "// comment \n// jieshao\nsyntax = \"proto3\";",
            "\n\n\nsyntax = \"proto3\";",
            "\r\n\r\n\r\nsyntax = \"proto3\";\r\n\r\n"
    })
    void testParseWellSyntax3(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            assertEquals("proto3", Syntax.PROTO_3.getValue());
        } catch (ProtoParseException e) {
            System.err.println(e.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import \"google/protobuf/any.proto\";\nsyntax = \"proto2\";",
            "package foo.bar;\nsyntax = \"proto2\";",
            "option java_package = \"com.example.foo\";syntax = \"proto2\";",
            "service SearchService {\n" +
                    "  rpc Search (string) returns (string);\n" +
                    "}\nsyntax = \"proto2\";",
            "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}\nsyntax = \"proto2\";",
            "enum EnumNotAllowingAlias {\n" +
                    "  UNKNOWN = 0;\n" +
                    "  STARTED = 1;\n" +
                    "  // RUNNING = 1;  // Uncommenting this line will cause a compile error inside Google and a warning message outside.\n" +
                    "};\nsyntax = \"proto2\";"
    })
    void testParseWrongSyntax2(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertEquals("syntax must be start line", thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import \"google/protobuf/any.proto\";\nsyntax = \"proto3\";",
            "package foo.bar;\nsyntax = \"proto3\";",
            "option java_package = \"com.example.foo\";syntax = \"proto3\";",
            "service SearchService {\n" +
                    "  rpc Search (string) returns (string);\n" +
                    "}\nsyntax = \"proto3\";",
            "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "}\nsyntax = \"proto3\";",
            "enum EnumNotAllowingAlias {\n" +
                    "  UNKNOWN = 0;\n" +
                    "  STARTED = 1;\n" +
                    "  // RUNNING = 1;  // Uncommenting this line will cause a compile error inside Google and a warning message outside.\n" +
                    "};\nsyntax = \"proto3\";"
    })
    void testParseWrongSyntax3(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertEquals("syntax must be start line", thrown.getMessage());
    }

    /**
     * 测试syntax版本错误的判断逻辑
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto4\";",
            "syntax = \"5\";"
    })
    void testParseWrongSyntax(String protoStr) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().contains("no enum value Syntax"));
    }
}
