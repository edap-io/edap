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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestParseService {

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search (SearchRequest) returns (SearchResponse) {}\n" +
                    "}",
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search (SearchRequest) returns (SearchResponse) {\n\n}\n" +
                    "}",
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  Search (SearchRequest) returns (SearchResponse) {\n\n}\n" +
                    "}",
    })
    void testParseWellServiceEndBrace(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            Assertions.assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());

            Service service = services.get(0);
            assertEquals("SearchService", service.getName());
            List<String> comments = service.getComments().getLines();
            assertEquals(1, comments.size());

            List<ServiceMethod> methods = service.getMethods();
            assertEquals(1, methods.size());
            ServiceMethod method = methods.get(0);
            assertEquals("Search", method.getName());
            Assertions.assertEquals(Service.ServiceType.UNARY, method.getType());
            Assertions.assertEquals("unary", method.getType().getValue());
            assertEquals("SearchRequest", method.getRequest());
            assertEquals("SearchResponse", method.getResponse());

            Comment comment = method.getComment();
            assertNotNull(comment);
            assertEquals(comment.getLines().size(), 1);
            String mcomment = comment.getLines().get(0);
            assertEquals("服务方法说明", mcomment);

            //测试setMethods的方法
            Service service2 = new Service();
            service2.setMethods(methods);
            assertEquals(1, service2.getMethods().size());

            //检查非ArrayList的method的列表被设置时转化为ArrayList
            Service service3 = new Service();
            service3.setMethods(Arrays.asList(method));
            assertEquals(1, service3.getMethods().size());
            assertTrue(service3.getMethods() instanceof ArrayList);

        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search (stream Request) returns (SearchResponse) {}\n" +
                    "}",

    })
    void testParseWellServiceClientStream(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());

            Service service = services.get(0);
            ServiceMethod serviceMethod = service.getMethods().get(0);
            assertEquals(Service.ServiceType.CLIENT_STREAM, serviceMethod.getType());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search (asyn Request) returns (SearchResponse) {}\n" +
                    "}",

    })
    void testParseWellServiceNotClientStream(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());

            Service service = services.get(0);
            ServiceMethod serviceMethod = service.getMethods().get(0);
            assertEquals(Service.ServiceType.UNARY, serviceMethod.getType());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search (Request) returns (stream SearchResponse) {}\n" +
                    "}",

    })
    void testParseWellServiceServerStream(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());

            Service service = services.get(0);
            ServiceMethod serviceMethod = service.getMethods().get(0);
            assertEquals(Service.ServiceType.SERVER_STREAM, serviceMethod.getType());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search (stream Request) returns (stream SearchResponse) {}\n" +
                    "}",

    })
    void testParseWellServiceBidirectional(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());

            Service service = services.get(0);
            ServiceMethod serviceMethod = service.getMethods().get(0);
            assertEquals(Service.ServiceType.BIDIRECTIONAL, serviceMethod.getType());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "//\n" +
                    "  rpc Search (SearchRequest, param2, taram3) returns (SearchResponse) {}\n" +
                    "}",

    })
    void testParseWellServiceMultiParam(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "// 服务方法说明\n" +
                    "  rpc Search () returns (SearchResponse) {}\n" +
                    "}",

    })
    void testParseWellServiceNoParam(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());
        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc Search (SearchRequest) returns (SearchResponse); //服务单行服务说明\n" +
                    "}"
    })
    void testParseWellService(String protoStr) {
        ProtoParser parser = new ProtoParser(protoStr);
        try {
            Proto proto = parser.parse();
            assertEquals(Syntax.PROTO_3, proto.getSyntax());

            List<Service> services = proto.getServices();
            assertEquals(1, services.size());

            Service service = services.get(0);
            assertEquals("SearchService", service.getName());
            List<String> comments = service.getComments().getLines();
            assertEquals(1, comments.size());

            List<ServiceMethod> methods = service.getMethods();
            assertEquals(1, methods.size());
            ServiceMethod method = methods.get(0);
            assertEquals("Search", method.getName());
            Assertions.assertEquals(Service.ServiceType.UNARY, method.getType());
            Assertions.assertEquals("unary", method.getType().getValue());
            assertEquals("SearchRequest", method.getRequest());
            assertEquals("SearchResponse", method.getResponse());

            Comment mcomment = method.getComment();
            assertEquals("服务单行服务说明", mcomment.getLines().get(0));


        } catch (ProtoParseException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc Search SearchRequest) returns (SearchResponse); //服务单行服务说明\n" +
                    "}"
    })
    void testParseMethodNotBrackets(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });

        assertTrue(thrown.getMessage().contains("method not start with '('"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc (Search, SearchRequest) returns (SearchResponse); //服务单行服务说明\n" +
                    "}"
    })
    void testParseMethodNotName(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service method not define"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc Search (SearchRequest) return (SearchResponse); //服务单行服务说明\n" +
                    "}"
    })
    void testParseMethodNotReturns(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service haven't returns"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc Search (SearchRequest) returns SearchResponse); //服务单行服务说明\n" +
                    "}"
    })
    void testParseMethodReturnNotBrackets(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("method not start with '('"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc Search (SearchRequest) returns (SearchResponse) //服务单行服务说明\n" +
                    "}"
    })
    void testParseMethodNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service method not finished"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "  rpc Search (SearchRequest) returns (SearchResponse) ;//服务单行服务说明\n" +
                    ""
    })
    void testParseServiceNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service not end"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "Search SearchRequest) returns (SearchResponse) ;//服务单行服务说明\n" +
                    ""
    })
    void testParseServiceNotType(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service method define error"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "Search (SearchRequest) returns (SearchResponse)" +
                    "",
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "Search (SearchRequest) returns (SearchResponse) {" +
                    "t"
    })
    void testParseServiceNotFinish(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service method not finished"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "Search (SearchRequest" +
                    "",
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice SearchService {\n" +
                    "\n" +
                    "Search (SearchRequest) returns (SearchResponse"
    })
    void testParseMethodReturnNotEnd(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("method define error"));
    }

    /**
     *
     * @param protoStr
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "syntax=\"proto3\";\n" +
                    "//服务的说明\nservice {\n" +
                    "\n" +
                    "Search (SearchRequest" +
                    ""
    })
    void testParseServiceNotName(String protoStr) {
        ProtoParseException thrown = assertThrows(ProtoParseException.class,
                () -> {
                    ProtoParser parser = new ProtoParser(protoStr);
                    Proto proto = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("service name not set"));
    }
}