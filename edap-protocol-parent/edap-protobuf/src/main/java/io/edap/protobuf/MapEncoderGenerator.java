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

import static io.edap.protobuf.util.ProtoUtil.buildMapEncodeName;
import static io.edap.util.AsmUtil.isMap;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class MapEncoderGenerator {

    static String IFACE_NAME       = toInternalName(MapEncoder.class.getName());
    static String PROTO_FIELD_NAME = toInternalName(Field.class.getName());
    static String PROTO_UTIL_NAME  = toInternalName(ProtoUtil.class.getName());
    static String WRITER_NAME      = toInternalName(ProtoBufWriter.class.getName());


    private final Type keyType;
    private final Type valueType;
    private String keyTypeSignature;
    private String valTypeSingature;

    private ClassWriter cw;
    private String encoderName;

    public MapEncoderGenerator(Type mapType) {
        if (mapType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)mapType;
            if (ptype.getActualTypeArguments() != null
                    && ptype.getActualTypeArguments().length == 2) {
                keyType = ptype.getActualTypeArguments()[0];
                valueType = ptype.getActualTypeArguments()[1];
            } else {
                throw new RuntimeException("MapType define error");
            }
        } else if (mapType instanceof Class) {
            Class clazz = (Class)mapType;
            if (isMap(clazz)) {
                keyType   = Object.class;
                valueType = Object.class;
            } else {
                throw new RuntimeException("MapType [" + mapType + "] not Map");
            }
        } else {
            if (isMap(mapType)) {
                keyType   = Object.class;
                valueType = Object.class;
            } else {
                throw new RuntimeException("MapType define error");
            }
        }
        this.encoderName = toInternalName(buildMapEncodeName(mapType, null));
        this.keyTypeSignature = getDescriptor(keyType);
        this.valTypeSingature = getDescriptor(valueType);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String encoderSignature = "";
        String[] ifaceList = new String[]{IFACE_NAME};
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, encoderName, encoderSignature,
                "java/lang/Object", null);

        visitInitMethod();
        visitCinitMethod();
        visitEncodeMethod();


        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private void visitEncodeMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "encode", "(L" + WRITER_NAME + ";Ljava/util/Map;)V",
                "(L" + WRITER_NAME + ";Ljava/util/Map<" + keyTypeSignature + valTypeSingature + ">;)V",
                new String[] { "io/edap/protobuf/EncodeException" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/CollectionUtils", "isEmpty",
                "(Ljava/util/Map;)Z", false);
        Label labelNotEmpty = new Label();
        // 如果map为空
        mv.visitJumpInsn(IFEQ, labelNotEmpty);
        mv.visitInsn(RETURN);
        mv.visitLabel(labelNotEmpty);

        int varEntryItr = 3;
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "entrySet", "()Ljava/util/Set;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Set", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, varEntryItr);

        Label lbItr = new Label();
        mv.visitLabel(lbItr);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/Iterator"}, 0, null);
        mv.visitVarInsn(ALOAD, varEntryItr);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);

        int varEntry = varEntryItr + 1;
        Label lbHasNotNext = new Label();
        mv.visitJumpInsn(IFEQ, lbHasNotNext);
        mv.visitVarInsn(ALOAD, varEntryItr);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(ASTORE, varEntry);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, varEntry);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/lang/String");
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeString", "(Ljava/lang/String;)V", true);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, varEntry);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map$Entry", "getValue", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeObject", "(Ljava/lang/Object;)V", true);

        mv.visitJumpInsn(GOTO, lbItr);
        mv.visitLabel(lbHasNotNext);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 5);
        mv.visitEnd();
    }

    private void visitCinitMethod() {

        FieldVisitor fvTag1 = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "keyTag", "[B", null, null);
        fvTag1.visitEnd();
        FieldVisitor fvTag2 = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "valueTag", "[B", null, null);
        fvTag2.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_1);
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Type", "STRING",
                "L" + PROTO_FIELD_NAME + "$Type;");
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Cardinality", "OPTIONAL",
                "L" + PROTO_FIELD_NAME + "$Cardinality;");
        mv.visitMethodInsn(INVOKESTATIC, PROTO_UTIL_NAME, "buildFieldData",
                "(IL" + PROTO_FIELD_NAME + "$Type;L" + PROTO_FIELD_NAME + "$Cardinality;)[B", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, "keyTag", "[B");
        mv.visitInsn(ICONST_2);
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Type", "OBJECT",
                "L" + PROTO_FIELD_NAME + "$Type;");
        mv.visitFieldInsn(GETSTATIC, PROTO_FIELD_NAME + "$Cardinality", "OPTIONAL",
                "L" + PROTO_FIELD_NAME + "$Cardinality;");
        mv.visitMethodInsn(INVOKESTATIC, PROTO_UTIL_NAME, "buildFieldData",
                "(IL" + PROTO_FIELD_NAME + "$Type;L" + PROTO_FIELD_NAME + "$Cardinality;)[B", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, "valueTag", "[B");
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
