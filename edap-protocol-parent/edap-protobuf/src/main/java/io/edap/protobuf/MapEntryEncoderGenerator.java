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

import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class MapEntryEncoderGenerator {

    static String IFACE_NAME       = toInternalName(MapEntryEncoder.class.getName());
    static String PROTO_FIELD_NAME = toInternalName(Field.class.getName());
    static String PROTO_UTIL_NAME  = toInternalName(ProtoUtil.class.getName());
    static String WRITER_NAME      = toInternalName(ProtoBufWriter.class.getName());
    static String PB_ENCODER_NAME  = toInternalName(ProtoBufEncoder.class.getName());
    static String PB_REGISTER_NAME = toInternalName(ProtoBufCodecRegister.class.getName());


    private final Type keyType;
    private final Type valueType;
    private String keyTypeSignature;
    private String valTypeSingature;

    private ClassWriter cw;
    private String encoderName;

    public MapEntryEncoderGenerator(java.lang.reflect.Type mapType) {
        MapEntryTypeInfo info = getMapEntryTypeInfo(mapType);
        this.keyType = info.getKeyType();
        this.valueType = info.getValueType();
        this.encoderName = toInternalName(buildMapEntryEncodeName(mapType, null));
        this.keyTypeSignature = getDescriptor(keyType);
        this.valTypeSingature = getDescriptor(valueType);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String encoderSignature = "Ljava/lang/Object;L" + IFACE_NAME + "<" + keyTypeSignature + valTypeSingature +  ">;";
        String[] ifaceList = new String[]{IFACE_NAME};
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
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "encode", "(L" + WRITER_NAME + ";Ljava/util/Map$Entry;)V",
                "(L" + WRITER_NAME + ";Ljava/util/Map$Entry<" + keyTypeSignature + valTypeSingature + ">;)V",
                new String[] { "io/edap/protobuf/EncodeException" });
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, encoderName, "keyTag", "[B");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        visitWriteCode(mv, keyType);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, encoderName, "valueTag", "[B");
        if (isPojo(valueType)) {
            mv.visitInsn(ICONST_2);
        }
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
        visitWriteCode(mv, valueType);

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 5);
        mv.visitEnd();
    }

    private void visitWriteCode(MethodVisitor mv, Type valueType) {
        String writeMethod = getWriteMethod(javaToProtoType(valueType).getProtoType());
        String rType = getDescriptor(valueType);
        if (rType.startsWith("L")) {
            rType = rType.substring(1, rType.length()-1);
        }
        if (!rType.equals("java/lang/Object")) {
            mv.visitTypeInsn(CHECKCAST, rType);
        }

        if (isPojo(valueType)) {
            mv.visitFieldInsn(GETSTATIC, encoderName, "valueEncoder", "L" + PB_ENCODER_NAME + ";");
            mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeMessage",
                    "([BILjava/lang/Object;L" + PB_ENCODER_NAME + ";)V", true);
        } else {
            if (valueType instanceof Class) {
                String typeName = ((Class)valueType).getName();
                if (typeName.equals("byte") ||
                        typeName.equals("short") ||
                        typeName.equals("char")) {
                    rType = "I";
                } else if (typeName.equals("java.lang.Byte")) {
                    rType = "I";
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                } else if (typeName.equals("java.lang.Short")) {
                    rType = "I";
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                } else if (typeName.equals("java.lang.Character")) {
                    rType = "I";
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                }
            }
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod,
                    "([BL" + rType + ";)V", true);
        }
    }

    private void visitCinitMethod() {

        FieldVisitor fvTag1 = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "keyTag", "[B", null, null);
        fvTag1.visitEnd();
        FieldVisitor fvTag2 = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "valueTag", "[B", null, null);
        fvTag2.visitEnd();
        if (isPojo(valueType)) {
            FieldVisitor fvVEncoder = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC,
                    "valueEncoder", "L" + PB_ENCODER_NAME + ";",
                    "L" + PB_ENCODER_NAME + "<" + valTypeSingature + ">;", null);
            fvVEncoder.visitEnd();
        }


        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_1);
        String keyPbType = javaToProtoType(keyType).getProtoType().name();
        String valPbType = javaToProtoType(valueType).getProtoType().name();
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Type", keyPbType,
                "L" + PROTO_FIELD_NAME + "$Type;");
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Cardinality", "OPTIONAL",
                "L" + PROTO_FIELD_NAME + "$Cardinality;");
        mv.visitMethodInsn(INVOKESTATIC, PROTO_UTIL_NAME, "buildFieldData",
                "(IL" + PROTO_FIELD_NAME + "$Type;L" + PROTO_FIELD_NAME + "$Cardinality;)[B", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, "keyTag", "[B");
        mv.visitInsn(ICONST_2);
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Type", valPbType,
                "L" + PROTO_FIELD_NAME + "$Type;");
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Cardinality", "OPTIONAL",
                "L" + PROTO_FIELD_NAME + "$Cardinality;");
        mv.visitMethodInsn(INVOKESTATIC, PROTO_UTIL_NAME, "buildFieldData",
                "(IL" + PROTO_FIELD_NAME + "$Type;L" + PROTO_FIELD_NAME + "$Cardinality;)[B", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, "valueTag", "[B");

        if (isPojo(valueType)) {
            mv.visitFieldInsn(GETSTATIC, PB_REGISTER_NAME, "INSTANCE", "L" + PB_REGISTER_NAME + ";");
            mv.visitLdcInsn(org.objectweb.asm.Type.getType(valTypeSingature));
            mv.visitMethodInsn(INVOKEVIRTUAL, PB_REGISTER_NAME, "getEncoder",
                    "(Ljava/lang/Class;)L" + PB_ENCODER_NAME + ";", false);
            mv.visitFieldInsn(PUTSTATIC, encoderName, "valueEncoder", "L" + PB_ENCODER_NAME + ";");
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 0);
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
