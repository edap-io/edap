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

import io.edap.protobuf.wire.Message;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.Syntax;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试ProtoParser的操作逻辑
 */
public class TestProtoParser {

    /**
     * 测试ProtoParser的dumpRowsPos方法
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
    void dumpRowsPos(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            List<Message> msgs = proto.getMessages();
            assertEquals(1, msgs.size());

            parser.dumpRowsPos();
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax = \"proto2\";\n" +
                    "package,;\n" +
                    "}"
    })
    void errorReadValueSeparator(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_2, proto.getSyntax());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }
}