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

package io.edap.protobuf;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.util.ClazzUtil;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static io.edap.protobuf.util.ProtoUtil.javaToProtoType;
import static io.edap.protobuf.wire.Field.Type.OBJECT;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;


/**
 * 生成Map对象的Class文件的生成器
 */
public class MapEntryGenerator {

    private final String entryName;

    private Type keyType;
    private Type valType;

    private static final String ANNOTATION_NAME = getDescriptor(ProtoField.class);
    private static final String PROTO_TYPE = getDescriptor(io.edap.protobuf.wire.Field.Type.class);

    private ClassWriter cw;

    public MapEntryGenerator(String entryName, Type mapType) {
        this.entryName = entryName;
        if (mapType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)mapType;
            if (ptype.getActualTypeArguments() != null
                    && ptype.getActualTypeArguments().length == 2) {
                keyType = ptype.getActualTypeArguments()[0];
                valType = ptype.getActualTypeArguments()[1];
            } else {
                throw new RuntimeException("MapType define error");
            }
        }  else if (mapType instanceof Class) {
            Class clazz = (Class)mapType;
            if (isMap(clazz)) {
                keyType = Object.class;
                valType = Object.class;
            } else {
                throw new RuntimeException("MapType [" + mapType + "] not Map");
            }
        } else {
            if (isMap(mapType)) {
                keyType = Object.class;
                valType = Object.class;
            } else {
                throw new RuntimeException("MapType define error");
            }
        }
    }

    public byte [] getEntryBytes() {
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, entryName, null,
                "java/lang/Object", null);

        FieldVisitor fv;
        AnnotationVisitor av;

        String keyProtoType;
        String keyTypeStr;
        String keyTypeDesc;
        if (keyType instanceof ParameterizedType) {
            ParameterizedType itemParamType = (ParameterizedType)keyType;
            if (isList(itemParamType.getRawType())
                    && itemParamType.getActualTypeArguments()[0] instanceof ParameterizedType) {
                keyProtoType = OBJECT.name();
            } else if (isIterable(itemParamType.getRawType())
                    && itemParamType.getActualTypeArguments()[0] instanceof ParameterizedType) {
                keyProtoType = OBJECT.name();
            } else {
                keyProtoType = javaToProtoType(keyType).getProtoType().name();
            }
            keyTypeStr = ClazzUtil.getDescriptor(itemParamType.getRawType());
            keyTypeDesc = ClazzUtil.getDescriptor(keyType);
        } else {
            keyProtoType = javaToProtoType(keyType).getProtoType().name();
            keyTypeStr = ClazzUtil.getDescriptor((Class)keyType);
            keyTypeDesc = null;
        }
        String valProtoType = javaToProtoType(valType).getProtoType().name();
        String valTypeStr;
        String valTypeDesc;
        if (valType instanceof ParameterizedType) {
            ParameterizedType itemParamType = (ParameterizedType)valType;
            if (isList(itemParamType.getRawType())
                    && itemParamType.getActualTypeArguments()[0] instanceof ParameterizedType) {
                valProtoType = OBJECT.name();
            } else if (isIterable(itemParamType.getRawType())
                    && itemParamType.getActualTypeArguments()[0] instanceof ParameterizedType) {
                valProtoType = OBJECT.name();
            } else {
                valProtoType = javaToProtoType(valType).getProtoType().name();
            }
            valTypeStr = ClazzUtil.getDescriptor(itemParamType.getRawType());
            valTypeDesc = ClazzUtil.getDescriptor(valType);
        } else {
            valProtoType = javaToProtoType(valType).getProtoType().name();
            valTypeStr = ClazzUtil.getDescriptor((Class)valType);
            valTypeDesc = null;
        }
        //System.out.println("keyProtoType=" + keyProtoType + ",valProtoType=" + valProtoType);
        fv = cw.visitField(ACC_PUBLIC, "key", keyTypeStr,
                keyTypeDesc, null);
        av = fv.visitAnnotation(ANNOTATION_NAME, true);
        av.visit("tag", 1);
        av.visitEnum("type", PROTO_TYPE, keyProtoType);
        av.visitEnd();
        fv.visitEnd();


        fv = cw.visitField(ACC_PUBLIC, "value", valTypeStr,
                valTypeDesc, null);
        av = fv.visitAnnotation(ANNOTATION_NAME, true);
        av.visit("tag", 2);
        av.visitEnum("type", PROTO_TYPE, valProtoType);
        av.visitEnd();
        fv.visitEnd();

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();

        return cw.toByteArray();

    }
}