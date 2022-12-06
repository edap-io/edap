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

import static org.junit.jupiter.api.Assertions.*;

public class MethodDescParserTest {

    @Test
    public void testErrorFormatDesc() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser("B)V");
                    mdp.parse();
                });
        assertTrue(thrown.getMessage().contains("not method desc start char is not '('"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser("(T)V");
                    mdp.parse();
                });
        assertTrue(thrown.getMessage().contains("desc is error format desc:"));

        ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser("(Ljava/util/List<Ljava/lang/Integer;");
        mdp.parse();

        mdp = new ClassVisitorUtil.MethodDescParser("(Ljava/util/Map<Ljava/lang/Integer;Ljava/lang/String;");
        mdp.parse();
    }

    @Test
    public void testBaseTypeOneParamRespVoid() {
        ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser("(B)V");
        testBaseOneRespVoidEquals("(B)V", "B", "byte");
        testBaseOneRespVoidEquals("(Z)V", "Z", "boolean");
        testBaseOneRespVoidEquals("(S)V", "S", "short");
        testBaseOneRespVoidEquals("(F)V", "F", "float");
        testBaseOneRespVoidEquals("(D)V", "D", "double");
        testBaseOneRespVoidEquals("(I)V", "I", "int");
        testBaseOneRespVoidEquals("(J)V", "J", "long");
        testBaseOneRespVoidEquals("(C)V", "C", "char");

    }

    private void testBaseOneRespVoidEquals(String desc, String binaryName, String canonicalName) {
        ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser(desc);
        ClassVisitorUtil.MethodDesc md = mdp.parse();
        assertEquals(md.getTypes().size(), 1);
        IdlJavaType type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        IdlJavaType respType = md.getReturnsType();
        assertEquals(respType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlRespClass = (IdlJavaClass)respType;
        assertEquals(idlRespClass.binaryName(), "V");
        assertEquals(idlRespClass.canonicalName(), "void");
    }

    @Test
    public void testBaseTypeTwoParamRespVoid() {
        String desc = "(IJ)V";
        ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser(desc);
        ClassVisitorUtil.MethodDesc md = mdp.parse();
        assertEquals(md.getTypes().size(), 2);
        String binaryName = "I";
        String canonicalName = "int";
        IdlJavaType type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        binaryName = "J";
        canonicalName = "long";
        type = md.getTypes().get(1);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        IdlJavaType respType = md.getReturnsType();
        assertEquals(respType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlRespClass = (IdlJavaClass)respType;
        assertEquals(idlRespClass.binaryName(), "V");
        assertEquals(idlRespClass.canonicalName(), "void");
    }

    @Test
    public void testBaseTypeThreeParamRespVoid() {
        String desc = "(IJZ)V";
        ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser(desc);
        ClassVisitorUtil.MethodDesc md = mdp.parse();
        assertEquals(md.getTypes().size(), 3);
        String binaryName = "I";
        String canonicalName = "int";
        IdlJavaType type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        binaryName = "J";
        canonicalName = "long";
        type = md.getTypes().get(1);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        binaryName = "Z";
        canonicalName = "boolean";
        type = md.getTypes().get(2);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        IdlJavaType respType = md.getReturnsType();
        assertEquals(respType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlRespClass = (IdlJavaClass)respType;
        assertEquals(idlRespClass.binaryName(), "V");
        assertEquals(idlRespClass.canonicalName(), "void");
    }

    @Test
    public void testObjectOneParamVoidResp() {
        String desc = "(Ljava/lang/Integer;)V";
        ClassVisitorUtil.MethodDescParser mdp = new ClassVisitorUtil.MethodDescParser(desc);
        ClassVisitorUtil.MethodDesc md = mdp.parse();
        assertEquals(md.getTypes().size(), 1);
        String binaryName = "Ljava/lang/Integer;";
        String canonicalName = "java.lang.Integer";
        IdlJavaType type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        IdlJavaClass idlJavaClass = (IdlJavaClass)type;
        assertEquals(idlJavaClass.binaryName(), binaryName);
        assertEquals(idlJavaClass.canonicalName(), canonicalName);

        desc = "(Ljava/util/List<Ljava/lang/String;>;)V";
        mdp = new ClassVisitorUtil.MethodDescParser(desc);
        md = mdp.parse();
        assertEquals(md.getTypes().size(), 1);
        binaryName = "Ljava/util/List;";
        canonicalName = "java.util.List";
        type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl");
        IdlParameterizedType parameterizedType = (IdlParameterizedType)type;
        IdlJavaClass rawType = (IdlJavaClass) parameterizedType.rawType();
        assertEquals(rawType.binaryName(), binaryName);
        assertEquals(rawType.canonicalName(), canonicalName);

        IdlJavaType[] argTypes = parameterizedType.ActualTypeArgs();
        assertEquals(argTypes.length, 1);
        IdlJavaType argType = argTypes[0];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/String;";
        canonicalName = "java.lang.String";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);

        desc = "(Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;)V";
        mdp = new ClassVisitorUtil.MethodDescParser(desc);
        md = mdp.parse();
        assertEquals(md.getTypes().size(), 1);
        binaryName = "Ljava/util/Map;";
        canonicalName = "java.util.Map";
        type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl");
        parameterizedType = (IdlParameterizedType)type;
        rawType = (IdlJavaClass) parameterizedType.rawType();
        assertEquals(rawType.binaryName(), binaryName);
        assertEquals(rawType.canonicalName(), canonicalName);
        argTypes = parameterizedType.ActualTypeArgs();
        assertEquals(argTypes.length, 2);
        argType = argTypes[0];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/String;";
        canonicalName = "java.lang.String";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);
        argType = argTypes[1];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/Integer;";
        canonicalName = "java.lang.Integer";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);

        desc = "(Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljava/lang/Integer;>;>;)V";
        mdp = new ClassVisitorUtil.MethodDescParser(desc);
        md = mdp.parse();
        assertEquals(md.getTypes().size(), 1);
        binaryName = "Ljava/util/Map;";
        canonicalName = "java.util.Map";
        type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl");
        parameterizedType = (IdlParameterizedType)type;
        rawType = (IdlJavaClass) parameterizedType.rawType();
        assertEquals(rawType.binaryName(), binaryName);
        assertEquals(rawType.canonicalName(), canonicalName);
        argTypes = parameterizedType.ActualTypeArgs();
        assertEquals(argTypes.length, 2);
        argType = argTypes[0];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/String;";
        canonicalName = "java.lang.String";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);
        argType = argTypes[1];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl");
        binaryName = "Ljava/util/List;";
        canonicalName = "java.util.List";
        parameterizedType = (IdlParameterizedType)argType;
        rawType = (IdlJavaClass) parameterizedType.rawType();
        assertEquals(rawType.binaryName(), binaryName);
        assertEquals(rawType.canonicalName(), canonicalName);
        argTypes = parameterizedType.ActualTypeArgs();
        assertEquals(argTypes.length, 1);
        argType = argTypes[0];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/Integer;";
        canonicalName = "java.lang.Integer";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);


        desc = "(Ljava/util/Map<Ljava/lang/String;Ljava/util/Map<Ljava/lang/Integer;Ljava/lang/String;>;>;)V";
        mdp = new ClassVisitorUtil.MethodDescParser(desc);
        md = mdp.parse();
        assertEquals(md.getTypes().size(), 1);
        binaryName = "Ljava/util/Map;";
        canonicalName = "java.util.Map";
        type = md.getTypes().get(0);
        assertEquals(type.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl");
        parameterizedType = (IdlParameterizedType)type;
        rawType = (IdlJavaClass) parameterizedType.rawType();
        assertEquals(rawType.binaryName(), binaryName);
        assertEquals(rawType.canonicalName(), canonicalName);
        argTypes = parameterizedType.ActualTypeArgs();
        assertEquals(argTypes.length, 2);
        argType = argTypes[0];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/String;";
        canonicalName = "java.lang.String";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);
        argType = argTypes[1];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl");
        binaryName = "Ljava/util/Map;";
        canonicalName = "java.util.Map";
        parameterizedType = (IdlParameterizedType)argType;
        rawType = (IdlJavaClass) parameterizedType.rawType();
        assertEquals(rawType.binaryName(), binaryName);
        assertEquals(rawType.canonicalName(), canonicalName);
        argTypes = parameterizedType.ActualTypeArgs();
        assertEquals(argTypes.length, 2);
        argType = argTypes[0];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/Integer;";
        canonicalName = "java.lang.Integer";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);
        argType = argTypes[1];
        assertEquals(argType.getClass().getName(), "io.edap.protobuf.idl.model.impl.IdlJavaClass");
        binaryName = "Ljava/lang/String;";
        canonicalName = "java.lang.String";
        assertEquals(argType.binaryName(), binaryName);
        assertEquals(argType.canonicalName(), canonicalName);
    }

}
