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

import io.edap.protobuf.idl.model.IdlJavaType;
import io.edap.protobuf.idl.model.IdlParameterizedType;
import io.edap.protobuf.idl.model.impl.IdlJavaClass;
import io.edap.protobuf.idl.util.ClassVisitorUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SignatureParserTest {

    @Test
    public void testParse() {
        String signature = "<T:Ljava/lang/Object;D:Ljava/lang/Object;>" +
                "Ljava/util/LinkedHashMap<Ljava/lang/String;Ljava/lang/Object;>;" +
                "Ljava/io/Serializable;Lio/edap/x/protobuf/ProtoBufEncoder<TD;>;";
        ClassVisitorUtil.SignatureParser parser = new ClassVisitorUtil.SignatureParser(signature);

        ClassVisitorUtil.ClazzSignature cs = parser.parse();
        assertNotNull(cs);
        assertNotNull(cs.getFormalTypes());
        assertEquals(cs.getFormalTypes().size(), 2);
        assertEquals(cs.getFormalTypes().get(0).getFormal(), "T");
        assertEquals(cs.getFormalTypes().get(1).getType().getClass().getName(),
                "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        assertEquals(cs.getFormalTypes().get(1).getType().canonicalName(),
                "java.lang.Object");
        assertEquals(cs.getFormalTypes().get(1).getType().binaryName(),
                "Ljava/lang/Object;");

        assertNotNull(cs.getParentType());
        assertEquals(cs.getParentType() instanceof IdlJavaType,
                true);
        IdlParameterizedType ipt = (IdlParameterizedType)cs.getParentType();
        assertEquals(ipt.rawType().binaryName(),
                "Ljava/util/LinkedHashMap;");
        assertEquals(ipt.ActualTypeArgs()[0].canonicalName(),
                "java.lang.String");
        assertEquals(ipt.ActualTypeArgs()[0].binaryName(),
                "Ljava/lang/String;");
        assertEquals(ipt.ActualTypeArgs()[1].canonicalName(),
                "java.lang.Object");
        assertEquals(ipt.ActualTypeArgs()[1].binaryName(),
                "Ljava/lang/Object;");

        List<IdlJavaType> ifaces = cs.getIfaces();
        assertNotNull(ifaces);
        assertEquals(cs.getIfaces().size(), 2);
        assertEquals(cs.getIfaces().get(0) instanceof IdlJavaClass, true);
        assertEquals(cs.getIfaces().get(0).canonicalName(), "java.io.Serializable");
        assertEquals(cs.getIfaces().get(0).binaryName(), "Ljava/io/Serializable;");
        assertEquals(cs.getIfaces().get(1) instanceof IdlParameterizedType, true);

        ipt = (IdlParameterizedType)cs.getIfaces().get(1);
        assertEquals(ipt.rawType().binaryName(), "Lio/edap/x/protobuf/ProtoBufEncoder;");
        assertEquals(ipt.rawType().canonicalName(), "io.edap.x.protobuf.ProtoBufEncoder");
    }
}
