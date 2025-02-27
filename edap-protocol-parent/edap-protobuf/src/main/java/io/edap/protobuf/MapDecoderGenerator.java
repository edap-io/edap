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

package io.edap.protobuf;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Type;

import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.protobuf.util.ProtoUtil.buildProtoReadMethod;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class MapDecoderGenerator {

    static String IFACE_NAME       = toInternalName(MapDecoder.class.getName());
    static String READER_NAME       = toInternalName(ProtoBufReader.class.getName());

    private Type           keyType;
    private Type           valueType;
    private ProtoBufOption option;

    private String         keyTypeSignature;
    private String         valTypeSingature;

    private String decoderName;

    private ClassWriter cw;

    public MapDecoderGenerator(Type mapType, ProtoBufOption option) {
        ProtoUtil.MapEntryTypeInfo info = getMapEntryTypeInfo(mapType);
        this.option           = option;
        this.keyType          = info.getKeyType();
        this.valueType        = info.getValueType();
        this.decoderName      = toInternalName(buildMapDecoderName(mapType, option));
        this.keyTypeSignature = getDescriptor(keyType);
        this.valTypeSingature = getDescriptor(valueType);
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String encoderSignature = "Ljava/lang/Object;L" + IFACE_NAME + "<" + keyTypeSignature + valTypeSingature +  ">;";
        String[] ifaceList = new String[]{IFACE_NAME};
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, decoderName, encoderSignature,
                "java/lang/Object", ifaceList);

        visitInitMethod();
        visitDecodeMethod();

        gci.clazzName = decoderName;
        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitDecodeMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "decode",
                "(L" + READER_NAME + ";)Ljava/util/Map;",
                "(L" + READER_NAME + ";)Ljava/util/Map<" + keyTypeSignature + valTypeSingature + ">;",
                new String[] { "io/edap/protobuf/ProtoException" });
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/util/HashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        int varMap = 2;
        mv.visitVarInsn(ASTORE, varMap);
        mv.visitInsn(ICONST_0);
        int varFinishFlag = varMap + 1;
        mv.visitVarInsn(ISTORE, varFinishFlag);
        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitVarInsn(ILOAD, varFinishFlag);
        Label lbWhileEnd = new Label();
        mv.visitJumpInsn(IFNE, lbWhileEnd);
        int varKey = varFinishFlag + 1;
        int varVal = varKey + 1;

        int varTag = varVal + 1;
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        mv.visitVarInsn(ISTORE, varTag);
        mv.visitVarInsn(ILOAD, varTag);
        // 如果Map长度结束则退出while循环
        Label lbReadMap = new Label();
        mv.visitJumpInsn(IFNE, lbReadMap);
        mv.visitJumpInsn(GOTO, lbWhileEnd);
        // 解码key，value
        mv.visitLabel(lbReadMap);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, getReadMethod(keyType, 1), "()" + keyTypeSignature, true);
        mv.visitVarInsn(ASTORE, varKey);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        mv.visitInsn(POP);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, getReadMethod(valueType, 2), "()" + valTypeSingature, true);
        mv.visitVarInsn(ASTORE, varVal);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, varKey);
        mv.visitVarInsn(ALOAD, varVal);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        mv.visitJumpInsn(GOTO, lbWhile);
        mv.visitLabel(lbWhileEnd);

        mv.visitVarInsn(ALOAD, varMap);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 7);
        mv.visitEnd();
    }

    private String getReadMethod(Type type, int tag) {
        ProtoField protoField = buildProtoFieldAnnotation(tag, type);
        ReadMethodInfo rmi = buildProtoReadMethod(type, protoField);
        return rmi.getMethod();
    }

    private void visitInitMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }
}
