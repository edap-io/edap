/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.json;

import io.edap.util.AsmUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.reflect.Type;

import static io.edap.json.util.JsonUtil.buildMapDecoderName;
import static io.edap.json.util.JsonUtil.getWriteMethod;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class MapEncoderGenerator {

    static String IFACE_NAME       = toInternalName(JsonEncoder.class.getName());
    static String MAP_ENCODER_NAME = toInternalName(MapEncoder.class.getName());
    static String REGISTER_NAME    = toInternalName(JsonCodecRegister.class.getName());
    static String WRITER_NAME      = toInternalName(JsonWriter.class.getName());

    private ClassWriter cw;
    private String encoderName;

    private final Type keyType;
    private final Type valueType;
    private String keyTypeSignature;
    private String valTypeSingature;

    public MapEncoderGenerator(java.lang.reflect.Type mapType) {
        AsmUtil.MapEntryTypeInfo info = getMapEntryTypeInfo(mapType);
        this.keyType          = info.getKeyType();
        this.valueType        = info.getValueType();
        this.encoderName      = toInternalName(buildMapDecoderName(mapType));
        this.keyTypeSignature = getDescriptor(keyType);
        this.valTypeSingature = getDescriptor(valueType);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String encoderSignature = "Ljava/lang/Object;L" + MAP_ENCODER_NAME + "<" + keyTypeSignature + valTypeSingature +  ">;";
        String[] ifaceList = new String[]{MAP_ENCODER_NAME};
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, encoderName, encoderSignature,
                "java/lang/Object", ifaceList);

        visitInitMethod();
        visitCinitMethod();
        visitEncodeMethod();


        cw.visitEnd();

        gci.clazzName  = encoderName;
        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private void visitEncodeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "encode", "(L" + WRITER_NAME + ";Ljava/util/Map;)V",
                "(L" + WRITER_NAME + ";Ljava/util/Map<" + keyTypeSignature + valTypeSingature + ">;)V", null);
        mv.visitCode();

        // map为空直接返回
        mv.visitVarInsn(ALOAD, 2);
        Label lbNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNotNull);
        mv.visitInsn(RETURN);

        // 如果map为空,输出"{}"
        mv.visitLabel(lbNotNull);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true);
        Label lbNotEmpty = new Label();
        mv.visitJumpInsn(IFEQ, lbNotEmpty);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 123);
        mv.visitIntInsn(BIPUSH, 125);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(BB)V", true);
        mv.visitInsn(RETURN);


        // Map不为空
        mv.visitLabel(lbNotEmpty);
        // 如果有POJO的元素则获取POJO的编码器
        int varValEncoder = 2;
        if (isPojo(valueType)) {
            varValEncoder++;
            mv.visitFieldInsn(GETSTATIC, encoderName, "valueEncoder", "L" + IFACE_NAME + ";");
            mv.visitVarInsn(ASTORE, varValEncoder);
        }
        // 输出"{"
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 123);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);

        // 输出第一个Map.Entry
        int varMapItr = varValEncoder + 1;
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, varMapItr);
        mv.visitVarInsn(ALOAD, varMapItr);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        int varEntry = varMapItr + 1;
        mv.visitTypeInsn(CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(ASTORE, varEntry);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, varEntry);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(Ljava/lang/String;)V", true);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 58);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);
        if (isPojo(valueType)) {
            mv.visitVarInsn(ALOAD, varValEncoder);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, varEntry);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, valTypeSingature.substring(1, valTypeSingature.length()-1));
            mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "encode", "(L" + WRITER_NAME + ";Ljava/lang/Object;)V", true);
        } else {
            String typeString = valTypeSingature.substring(1, valTypeSingature.length()-1);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, varEntry);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, typeString);
            String writeMethod = getWriteMethod((Class) valueType);
            if (writeMethod.equals("writeObject")) {
                typeString = "Ljava/lang/Object;";
            }
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod, "(" + typeString + ")V", true);
        }

        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitVarInsn(ALOAD, varMapItr);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        Label lbWhileExit = new Label();
        mv.visitJumpInsn(IFEQ,lbWhileExit);
        mv.visitVarInsn(ALOAD, varMapItr);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(ASTORE, varEntry);
        // 输出","
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 44);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);
        // 输出key
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, varEntry);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(Ljava/lang/String;)V", true);
        // 输出":"
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 58);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);
        // 输出value
        if (isPojo(valueType)) {
            mv.visitVarInsn(ALOAD, varValEncoder);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, varEntry);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, valTypeSingature.substring(1, valTypeSingature.length()-1));
            mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "encode", "(L" + WRITER_NAME + ";Ljava/lang/Object;)V", true);
        } else {
            String typeString = valTypeSingature.substring(1, valTypeSingature.length()-1);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, varEntry);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, typeString);
            String writeMethod = getWriteMethod((Class) valueType);
            if (writeMethod.equals("writeObject")) {
                typeString = "Ljava/lang/Object;";
            }
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod, "(" + typeString + ")V", true);
        }
        mv.visitJumpInsn(GOTO, lbWhile);

        mv.visitLabel(lbWhileExit);

        // 输出"}"
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 125);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);

        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 7);
        mv.visitEnd();
    }

    private void visitCinitMethod() {

    }

    private void visitInitMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (isPojo(valueType)) {
            FieldVisitor fvValeEncoder = cw.visitField(ACC_PRIVATE | ACC_STATIC, "valueEncoder",
                    "L" + IFACE_NAME + ";", "L" + IFACE_NAME + "<" + valTypeSingature + ">;", null);
            fvValeEncoder.visitEnd();

            //mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance", "()L" + REGISTER_NAME + ";", false);
            mv.visitLdcInsn(org.objectweb.asm.Type.getType(valTypeSingature));
            mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getEncoder",
                    "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
            mv.visitFieldInsn(PUTSTATIC, encoderName, "valueEncoder", "L" + IFACE_NAME + ";");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();

    }
}
