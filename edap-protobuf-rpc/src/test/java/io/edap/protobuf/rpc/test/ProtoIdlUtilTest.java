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

package io.edap.protobuf.rpc.test;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.rpc.UnsupportDataType;
import io.edap.protobuf.rpc.test.dto.AllType;
import io.edap.protobuf.rpc.test.dto.EmptyMsg;
import io.edap.protobuf.rpc.test.dto.OptionDemo;
import io.edap.protobuf.rpc.test.dto.Order;
import io.edap.protobuf.rpc.test.service.DemoService;
import io.edap.protobuf.rpc.util.ProtoIdlUtil;
import io.edap.protobuf.wire.Message;
import io.edap.protobuf.wire.Option;
import io.edap.protobuf.wire.Proto;
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

import static io.edap.protobuf.rpc.util.ProtoIdlUtil.buildClassMessage;
import static io.edap.protobuf.rpc.util.ProtoIdlUtil.buildServiceProto;
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
    public void testBuildClassMessage() throws IOException {
        Proto proto = new Proto();
        proto.setProtoPackage("io.edap.protobuf.rpc.test.service");


        Class msgCls = EmptyMsg.class;
        Map<String, Proto> dtoProtos = new HashMap<>();
        buildClassMessage(msgCls, proto, dtoProtos);
        assertNotNull(dtoProtos);
        assertEquals(dtoProtos.size(), 1);

        msgCls = Order.class;
        dtoProtos = new HashMap<>();
        buildClassMessage(msgCls, proto, dtoProtos);
        assertNotNull(dtoProtos);
        assertEquals(dtoProtos.size(), 1);
        List<Message> messages = dtoProtos.get("io.edap.protobuf.rpc.test.dto.dto.proto").getMessages();
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
    public void testBuildServiceProto() throws IOException {
        Map<String, Proto> protos = buildServiceProto(DemoService.class);
        System.out.println(protos);
    }
}
