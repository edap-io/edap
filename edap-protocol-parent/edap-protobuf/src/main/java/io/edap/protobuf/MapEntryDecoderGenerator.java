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
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.WireFormat;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.reflect.Type;

import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class MapEntryDecoderGenerator {
    static String IFACE_NAME       = toInternalName(MapEntryDecoder.class.getName());
    static String READER_NAME      = toInternalName(ProtoBufReader.class.getName());
    static String PB_DECODER_NAME  = toInternalName(ProtoBufDecoder.class.getName());
    static String PB_REGISTER_NAME = toInternalName(ProtoBufCodecRegister.class.getName());
    static String PROTOBUF_OPTIION_NAME  = toInternalName(ProtoBufOption.class.getName());
    static String WIRE_FORMAT_NAME = toInternalName(WireFormat.class.getName());


    private final Type keyType;
    private final Type valueType;
    private String keyTypeSignature;
    private String valTypeSingature;

    private ClassWriter cw;
    private String decoderName;
    private ProtoBufOption option;

    public MapEntryDecoderGenerator(java.lang.reflect.Type mapType, ProtoBufOption option) {
        MapEntryTypeInfo info = getMapEntryTypeInfo(mapType);
        this.option           = option;
        this.keyType          = info.getKeyType();
        this.valueType        = info.getValueType();
        this.decoderName      = toInternalName(buildMapEntryDecoderName(mapType, option));
        this.keyTypeSignature = getDescriptor(keyType);
        this.valTypeSingature = getDescriptor(valueType);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String encoderSignature = "Ljava/lang/Object;L" + IFACE_NAME + "<" + keyTypeSignature + valTypeSingature +  ">;";
        String[] ifaceList = new String[]{IFACE_NAME};
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, decoderName, encoderSignature,
                "java/lang/Object", ifaceList);

        visitInitMethod();
        visitCinitMethod();
        visitDecodeMethod();


        cw.visitEnd();

        gci.clazzName  = decoderName;
        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private void visitDecodeMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "decode", "(L" + READER_NAME + ";Ljava/util/Map;)V",
                "(L" + READER_NAME + ";Ljava/util/Map<" + keyTypeSignature + valTypeSingature + ">;)V",
                new String[]{"io/edap/protobuf/ProtoException"});
        mv.visitCode();
        boolean isFast;
        if (option != null && option.getCodecType() == CodecType.FAST) {
            isFast = true;
        } else {
            isFast = false;
        }
        if (!isFast) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readUInt32", "()I", true);
            mv.visitInsn(POP);
        }

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        int varTagNum = 3;
        mv.visitVarInsn(ISTORE, varTagNum);
        mv.visitVarInsn(ILOAD, varTagNum);
        mv.visitMethodInsn(INVOKESTATIC, WIRE_FORMAT_NAME, "getTagFieldNumber", "(I)I", false);
        mv.visitInsn(ICONST_1);

        Label lbNoKey = new Label();
        mv.visitJumpInsn(IF_ICMPNE, lbNoKey);
        // 如果读取的tag值为1则读取key 然后读取value的tag
        ReadMethodInfo keyRmi = getReadMethod(keyType, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, keyRmi.getMethod(), "()" + keyRmi.getReturnType(), true);
        switch (keyType.getTypeName()) {
            case "java.lang.Byte":
                mv.visitInsn(I2B);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                break;
            case "java.lang.Boolean":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                break;
            case "java.lang.Character":
                mv.visitInsn(I2C);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                break;
            case "java.lang.Short":
                mv.visitInsn(I2S);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                break;
            case "java.lang.Long":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case "java.lang.Integer":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case "java.lang.Float":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                break;
            case "java.lang.Double":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
        }

        int varKey = varTagNum + 1;
        mv.visitVarInsn(ASTORE, varKey);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        mv.visitInsn(POP);
        Label lbReadValue = new Label();
        mv.visitJumpInsn(GOTO, lbReadValue);

        // 如果第一个tagNum不是1 则按默认值初始化Key
        mv.visitLabel(lbNoKey);
        switch (keyType.getTypeName()) {
            case "java.lang.Byte":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Boolean":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Character":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Short":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Long":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Integer":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Float":

                break;
            case "java.lang.Double":

                break;
        }


        if (isPojo(valueType)) {
            String getDecoderMethodName = "getValueDecoder";
            String fieldName = lowerCaseFirstChar(getDecoderMethodName.substring(3));
            visitGetPojoDecodeMethod(fieldName, getDecoderMethodName, valueType, valTypeSingature);

            FieldVisitor valFv = cw.visitField(ACC_PRIVATE, fieldName, "L" + PB_DECODER_NAME + ";",
                    "L" + PB_DECODER_NAME + "<" + valTypeSingature + ">;", null);
            valFv.visitEnd();
        }

        mv.visitLabel(lbReadValue);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, varKey);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, decoderName, "getValueDecoder",
                "()L" + PB_DECODER_NAME + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readMessage",
                "(L" + PB_DECODER_NAME + ";)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "io/edap/protobuf/test/message/v3/Project");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        if (isFast) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readUInt32", "()I", true);
            mv.visitInsn(POP);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    private void visitDecodeMethod2() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "decode", "(L" + READER_NAME + ";Ljava/util/Map;)V",
                "(L" + READER_NAME + ";Ljava/util/Map<" + keyTypeSignature + valTypeSingature + ">;)V",
                new String[]{"io/edap/protobuf/ProtoException"});
        mv.visitCode();
        boolean isFast;
        if (option != null && option.getCodecType() == CodecType.FAST) {
            isFast = true;
        } else {
            isFast = false;
        }
        if (!isFast) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readUInt32", "()I", true);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        int varTag = 3;
        mv.visitVarInsn(ISTORE, varTag);
        mv.visitVarInsn(ILOAD, varTag);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/protobuf/wire/WireFormat", "getTagFieldNumber",
                "(I)I", false);
        mv.visitInsn(ICONST_1);

        Label lbNoKey = new Label();
        mv.visitJumpInsn(IF_ICMPNE, lbNoKey);
        mv.visitVarInsn(ALOAD, 1);
        ReadMethodInfo keyRmi = getReadMethod(keyType, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, keyRmi.getMethod(), "()" + keyRmi.getReturnType(), true);
        switch (keyType.getTypeName()) {
            case "java.lang.Byte":
                mv.visitInsn(I2B);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                break;
            case "java.lang.Boolean":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                break;
            case "java.lang.Character":
                mv.visitInsn(I2C);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                break;
            case "java.lang.Short":
                mv.visitInsn(I2S);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                break;
            case "java.lang.Long":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case "java.lang.Integer":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case "java.lang.Float":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                break;
            case "java.lang.Double":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
        }
        int varKey = varTag + 1;
        mv.visitVarInsn(ASTORE, varKey);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        mv.visitInsn(POP);

        Label lbReadVal = new Label();
        mv.visitJumpInsn(GOTO, lbReadVal);

        mv.visitLabel(lbNoKey);
        switch (keyType.getTypeName()) {
            case "java.lang.Byte":
                mv.visitInsn(ICONST_0);
                mv.visitInsn(I2B);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Boolean":
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                mv.visitVarInsn(ASTORE, varKey);
                break;
            case "java.lang.Character":
                mv.visitInsn(I2C);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                break;
            case "java.lang.Short":
                mv.visitInsn(I2S);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                break;
            case "java.lang.Long":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                break;
            case "java.lang.Integer":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                break;
            case "java.lang.Float":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                break;
            case "java.lang.Double":
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                break;
        }

        mv.visitLabel(lbReadVal);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, varKey);
        if (isPojo(valueType)) {
            String getDecoderMethodName = "getValueDecoder";
            String fieldName = lowerCaseFirstChar(getDecoderMethodName.substring(3));
            visitGetPojoDecodeMethod(fieldName, getDecoderMethodName, valueType, valTypeSingature);

            FieldVisitor valFv = cw.visitField(ACC_PRIVATE, fieldName, "L" + PB_DECODER_NAME + ";",
                    "L" + PB_DECODER_NAME + "<" + valTypeSingature + ">;", null);
            valFv.visitEnd();

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, decoderName, getDecoderMethodName,
                    "()L" + PB_DECODER_NAME + ";", false);
            if (!isFast) {
                mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readMessage",
                        "(L" + PB_DECODER_NAME + ";)Ljava/lang/Object;", true);
            } else {
                mv.visitInsn(ICONST_2);
                mv.visitFieldInsn(GETSTATIC, "io/edap/protobuf/wire/WireType", "END_GROUP",
                        "Lio/edap/protobuf/wire/WireType;");
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/protobuf/wire/WireFormat", "makeTag",
                        "(ILio/edap/protobuf/wire/WireType;)I", false);
                mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readMessage",
                        "(L" + PB_DECODER_NAME + ";I)Ljava/lang/Object;", true);
            }
            mv.visitTypeInsn(CHECKCAST, valTypeSingature.substring(1, valTypeSingature.length() - 1));
        } else {
            ReadMethodInfo valRmi = getReadMethod(valueType, 2);
            mv.visitVarInsn(ALOAD, 1);
            if ("readObject".equals(valRmi.getMethod())) {
                String castType = valTypeSingature.substring(1, valTypeSingature.length() - 1);
                int index = castType.indexOf("<");
                if (index != -1) {
                    castType = castType.substring(0, index);
                }
                mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, valRmi.getMethod(), "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(CHECKCAST, castType);
            } else {
                mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, valRmi.getMethod(), "()" + valTypeSingature, true);
            }
        }
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        if (isFast) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readUInt32", "()I", true);
            mv.visitInsn(POP);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    private ReadMethodInfo getReadMethod(Type type, int tag) {
        ProtoField protoField = buildMapItemProtoFieldAnnotation(tag, type);
        return buildProtoReadMethod(type, protoField);
    }

    private void visitGetPojoDecodeMethod(String fieldName, String methodName, Type type, String typeSignature) {
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, methodName, "()L" + PB_DECODER_NAME+ ";",
                "()L" + PB_DECODER_NAME+ "<" + typeSignature + ">;", null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, decoderName, fieldName, "L" + PB_DECODER_NAME + ";");
        Label lbNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, PB_REGISTER_NAME, "INSTANCE", "L" + PB_REGISTER_NAME + ";");
        mv.visitLdcInsn(org.objectweb.asm.Type.getType(getDescriptor(type)));
        mv.visitFieldInsn(GETSTATIC, decoderName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, PB_REGISTER_NAME, "getDecoder",
                "(Ljava/lang/Class;L" + PROTOBUF_OPTIION_NAME + ";)L" + PB_DECODER_NAME + ";", false);
        mv.visitFieldInsn(PUTFIELD, decoderName, fieldName, "L" + PB_DECODER_NAME + ";");

        mv.visitLabel(lbNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, decoderName, fieldName, "L" + PB_DECODER_NAME + ";");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
    }

    private void visitCinitMethod() {
        boolean isFast = false;
        if (option != null && CodecType.FAST == option.getCodecType()) {
            isFast = true;
        }

        FieldVisitor fvOption = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "PROTO_BUF_OPTION",
                "L" + PROTOBUF_OPTIION_NAME + ";", null, null);
        fvOption.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        mv.visitTypeInsn(NEW, PROTOBUF_OPTIION_NAME);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PROTOBUF_OPTIION_NAME, "<init>", "()V", false);
        mv.visitFieldInsn(PUTSTATIC, decoderName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME + ";");
        if (isFast) {
            mv.visitFieldInsn(GETSTATIC, decoderName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME + ";");
            mv.visitFieldInsn(GETSTATIC, "io/edap/protobuf/CodecType",
                    "FAST", "Lio/edap/protobuf/CodecType;");
            mv.visitMethodInsn(INVOKEVIRTUAL, PROTOBUF_OPTIION_NAME, "setCodecType",
                    "(Lio/edap/protobuf/CodecType;)V", false);
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
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
