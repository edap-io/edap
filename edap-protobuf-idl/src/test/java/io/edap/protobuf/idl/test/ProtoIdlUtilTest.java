/*
 * Copyright 2022 The edap Project
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

package io.edap.protobuf.idl.test;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.builder.ProtoBuilder;
import io.edap.protobuf.builder.ProtoV3Builder;
import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.test.dto.AllType;
import io.edap.protobuf.idl.test.dto.EmptyMsg;
import io.edap.protobuf.idl.test.dto.OptionDemo;
import io.edap.protobuf.idl.test.dto.Order;
import io.edap.protobuf.idl.test.service.DemoBaseTypeService;
import io.edap.protobuf.idl.test.service.DemoNoRespService;
import io.edap.protobuf.idl.util.ProtoIdlUtil;
import io.edap.protobuf.wire.*;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.protobuf.idl.util.ProtoIdlUtil.buildClassMessage;
import static io.edap.protobuf.idl.util.ProtoIdlUtil.buildServiceProto;
import static org.junit.jupiter.api.Assertions.*;

public class ProtoIdlUtilTest {

    private static final Charset UTF_8 = Charset.forName("utf-8");

    @Test
    public void testGetProtoJavaPackageName()
            throws ProtoParseException, IOException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method getMethod = ProtoIdlUtil.class.getDeclaredMethod("getProtoJavaPackageName", new Class[]{Proto.class});
        getMethod.setAccessible(true);
        String withOptionData = getResourceToString("/proto/test_javapackname_withjavaoption.proto");
        Proto withOptiion = new ProtoParser(withOptionData).parse();
        assertEquals("io.edap.protobuf.test.message.v3", getMethod.invoke(null, withOptiion));

        String withoutOptionData = getResourceToString("/proto/test_javapackname_withoutjavaoption.proto");
        Proto withoutOption = new ProtoParser(withoutOptionData).parse();
        assertEquals("test.message", getMethod.invoke(null, withoutOption));
    }

    @Test
    public void testParseOptions() throws NoSuchMethodException, NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Field localTimeField = OptionDemo.class.getDeclaredField("createTime");
        localTimeField.setAccessible(true);
        ProtoField protoFieldAnn = localTimeField.getAnnotation(ProtoField.class);
        Method parseOptionsMethod = ProtoIdlUtil.class.getDeclaredMethod("parseOptions", String[].class);
        parseOptionsMethod.setAccessible(true);
        Object options = parseOptionsMethod.invoke(null, (Object) protoFieldAnn.options());
        assertNotNull(options);
        List<Option> listOptions = (List<Option>)options;
        assertEquals(listOptions.size(), 1);
        assertEquals(listOptions.get(0).getName(), "javatype");
        assertEquals(listOptions.get(0).getValue(), "java.time.LocalDateTime");

        options = parseOptionsMethod.invoke(null, (Object) null);
        assertNotNull(options);
        listOptions = (List<Option>)options;
        assertEquals(listOptions.size(), 0);
    }

    private String getResourceToString(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ProtoIdlUtilTest.class.getResourceAsStream(path), UTF_8))) {
            String line = reader.readLine();
            StringBuilder sb = new StringBuilder();
            while (line != null) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(line);
                line = reader.readLine();
            }
            return sb.toString();
        }

    }

    @Test
    public void testParseDiworkService() throws IOException {
//        Class iface = IDiWorkPermissionService.class;
//        buildServiceProto(iface);
    }

    @Test
    public void testBuildClassMessage() throws IOException {
        Proto proto = new Proto();
        proto.setProtoPackage("io.edap.protobuf.rpc.test.service");


        Class msgCls = EmptyMsg.class;
        Map<String, Proto> dtoProtos = new HashMap<>();
        Map<String, Boolean> existsMessages = new HashMap<>();
        buildClassMessage(msgCls, proto, dtoProtos);
        assertNotNull(dtoProtos);
        assertEquals(dtoProtos.size(), 1);

        msgCls = Order.class;
        dtoProtos = new HashMap<>();
        buildClassMessage(msgCls, proto, dtoProtos);
        assertNotNull(dtoProtos);
        assertEquals(dtoProtos.size(), 1);
        List<Message> messages = dtoProtos.get("io.edap.protobuf.idl.test.dto.dto").getMessages();
        assertNotNull(messages);
        assertEquals(messages.size(), 2);

        msgCls = AllType.class;
        dtoProtos = new HashMap<>();
        buildClassMessage(msgCls, proto, dtoProtos);
        assertNotNull(dtoProtos);
    }

    @Test
    public void testGetProtoMessageClass() throws NoSuchFieldException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = ProtoIdlUtil.class.getDeclaredMethod("getProtoMessageClass", new Class[]{Type.class});
        method.setAccessible(true);
        Type type = AllType.class.getDeclaredField("fieldArrayMessage").getGenericType();
        Class cls = (Class)method.invoke(null, type);
        assertEquals(cls.getName(), Order.class.getName());

        Type unsupportType = AllType.class.getDeclaredField("fieldGernericList").getGenericType();
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                     Class unsupportCls = (Class)method.invoke(null, unsupportType);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("unsupport data type java.util.List"));

        type = AllType.class.getDeclaredField("fieldMessage").getGenericType();
        cls = (Class)method.invoke(null, type);
        assertEquals(cls.getName(), Order.class.getName());
    }

    @Test
    public void testBaseTypeServiceProto() throws IOException {
        Map<String, Proto> dtoProtos = new HashMap<>();
        Proto proto = buildServiceProto(DemoBaseTypeService.class, dtoProtos);
        //Proto serviceProto = protos.get("io.edap.protobuf.rpc.test.service.DemoBaseTypeService");
        printProto(proto);
        assertEquals(dtoProtos.size(), 0);
    }

    public void printProto(Proto proto) {
        ProtoBuilder protoBuilder = new ProtoV3Builder(proto);
        System.out.println(proto.getName());
        System.out.println(protoBuilder.toProtoString());
    }

    public void printProtos(Map<String, Proto> protos) {
        ProtoBuilder protoBuilder;
        for (Map.Entry<String, Proto> entry : protos.entrySet()) {
            protoBuilder = new ProtoV3Builder(entry.getValue());
            System.out.println(protoBuilder.toProtoString());
        }
    }

    @Test
    public void testBuildNoRespServiceProto() throws IOException {
        Map<String, Proto> dtoProtos = new HashMap<>();

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> {
                    Proto proto = buildServiceProto(null, dtoProtos);
                });
        assertTrue(thrown.getMessage().contains("interface can't null"));
        thrown = assertThrows(NullPointerException.class,
                () -> {
                    Proto proto = buildServiceProto(DemoNoRespService.class, null);
                });
        assertTrue(thrown.getMessage().contains("dtoProtos can't null"));

        assertTrue(dtoProtos.isEmpty());
        Proto proto = buildServiceProto(DemoNoRespService.class, dtoProtos);
        assertEquals(dtoProtos.size(), 1);
        Proto serviceProto = proto;
        assertNotNull(serviceProto);
        assertEquals(serviceProto.getName(), "io.edap.protobuf.idl.test.service.DemoNoRespService");
        assertEquals(serviceProto.getProtoPackage(), "io.edap.protobuf.idl.test.service");
        assertEquals(serviceProto.getOptions().size(), 1);
        assertEquals(serviceProto.getOptions().get(0).getName(), "java_package");
        assertEquals(serviceProto.getOptions().get(0).getValue(), "io.edap.protobuf.idl.test.service");
        assertEquals(serviceProto.getSyntax(), Syntax.PROTO_3);
        assertEquals(serviceProto.getImports().size(), 1);
        assertEquals(serviceProto.getImports().get(0), "edap-idl/Empty.proto");
        assertEquals(serviceProto.getServices().size(), 1);
        assertEquals(serviceProto.getServices().get(0).getName(), "DemoNoRespService");
        Service service = serviceProto.getServices().get(0);
        assertEquals(service.getMethods().size(), 2);
        ServiceMethod methodNoParam = service.getMethods().get(0);
        assertEquals(methodNoParam.getName(), "noResponseAndArg");
        assertEquals(methodNoParam.getRequest(), "");
        assertEquals(methodNoParam.getResponse(), "Empty");
        assertEquals(methodNoParam.getType(), Service.ServiceType.UNARY);
        ServiceMethod oneParamMethod = service.getMethods().get(1);
        assertEquals(oneParamMethod.getName(), "noResponse");
        assertEquals(oneParamMethod.getRequest(), "Order");
        assertEquals(oneParamMethod.getResponse(), "Empty");
        assertEquals(oneParamMethod.getType(), Service.ServiceType.UNARY);


        BuildOption buildOption = new BuildOption();
        dtoProtos.clear();
        buildOption.setGrpcCompatible(true);

        serviceProto = buildServiceProto(DemoNoRespService.class, dtoProtos, buildOption);;
        service = serviceProto.getServices().get(0);
        methodNoParam = service.getMethods().get(0);
        assertEquals(methodNoParam.getName(), "noResponseAndArg");
        assertEquals(methodNoParam.getRequest(), "Empty");
        assertEquals(methodNoParam.getResponse(), "Empty");
        assertEquals(methodNoParam.getType(), Service.ServiceType.UNARY);
    }
}
