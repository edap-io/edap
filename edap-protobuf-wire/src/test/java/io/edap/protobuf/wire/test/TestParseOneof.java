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

import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Message;
import io.edap.protobuf.wire.Oneof;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试解析proto文件Oneof消息体的逻辑
 */
public class TestParseOneof {

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  repeated int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  // oneof的注释信息\n" +
                    "  oneof test_oneof {\n" +
                    "            \n  \n   \n" +
                    "            //名称\n" +
                    "            repeated string name = 5;\n" +
                    "            \n  \n   \n" +
                    "            SubMessage sub_message = 6;\n" +
                    "            \n  \n   \n" +
                    "        }" +
                    "}"
    })
    void testWellOneof(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            Message msg = proto.getMessages().get(0);
            List<Oneof> oneofs = msg.getOneofs();
            assertEquals(1, oneofs.size());
            Oneof oneof = oneofs.get(0);
            assertEquals("test_oneof", oneof.getName());
            assertEquals("oneof的注释信息", oneof.getComment().getLines().get(oneof.getComment().getLines().size()-1));
            List<Field> fields = oneof.getFields();
            assertEquals(2, fields.size());

            Field f1 = fields.get(0);
            assertEquals("name", f1.getName());
            assertEquals(5, f1.getTag());

            Field f2 = fields.get(1);
            assertEquals("sub_message", f2.getName());
            assertEquals(6, f2.getTag());
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
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  //oneof的注释信息\n" +
                    "  oneof {\n" +
                    "            \n  \n   \n" +
                    "            string name = 5;\n" +
                    "            \n  \n   \n" +
                    "            SubMessage sub_message = 6;\n" +
                    "            \n  \n   \n" +
                    "        }" +
                    "}"
    })
    void testWrongOneof(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("oneof name not set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  //oneof的注释信息\n" +
                    "  oneof one_of {\n" +
                    "            \n  \n   \n" +
                    "            string name = 5;\n" +
                    "            \n  \n   \n" +
                    "            SubMessage sub_message = 6;\n" +
                    "            \n  \n   \n" +
                    "        "
    })
    void testWrongOneofNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("oneof not end"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n//消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  //oneof的注释信息\n" +
                    "  oneof one_of {\n" +
                    "            \n  \n   \n" +
                    "            string name = 5;\n" +
                    "            \n  \n   \n" +
                    "            SubMessage sub_message = 6;\n" +
                    "            \n  \n   \n" +
                    "        "
    })
    void testWrongTagValue(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("oneof not end"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto3\";\n/消息的注释内容\n" +
                    "message SearchRequest {\n" +
                    "  string query = 1;\n" +
                    "  int32 page_number = 2;\n" +
                    "  int32 result_per_page = 3;\n" +
                    "  map<int32, string> extendInfo = 4;\n" +
                    "  //oneof的注释信息\n" +
                    "  oneof one_of {\n" +
                    "            \n  \n   \n" +
                    "            string name = 5;\n" +
                    "            \n  \n   \n" +
                    "            SubMessage sub_message = 6;\n" +
                    "            \n  \n   \n" +
                    "       }\n} "
    })
    void testWrongCommentStart(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().contains("token not enable start with \"/\""));
    }
}