/*
 * Copyright 2023 The edap Project
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

package io.edap.log.helps;

import io.edap.log.converter.TextConverter;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.visitIntInsn;
import static io.edap.util.CryptUtil.md5;
import static org.objectweb.asm.Opcodes.*;

public class TextEncoderGenerator {

    private final String text;

    static final String IFACE_NAME = toInternalName(TextConverter.class.getName());
    static final String BUILDER_NAME = toInternalName(ByteArrayBuilder.class.getName());

    private ClassWriter cw;

    private String parentName;
    private String converterName;

    private int bytesLen;

    public TextEncoderGenerator(String text) {
        this.text = text;
        this.bytesLen = text.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String getConverterName(String text) {
        return "io.edap.log.converters.TextEncoder_" + md5(text);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        parentName = toInternalName(Object.class.getName());
        String[] ifaceName = new String[]{IFACE_NAME};
        converterName = toInternalName(getConverterName(text));

        cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, converterName,
                null, parentName, ifaceName);

        visitInitMethod();
        visitCinitMethod();
        visitConvertToBridgeMethod();
        visitConvertToMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        gci.clazzName = converterName;
        return gci;
    }

    private void visitConvertToMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "convertTo",
                "(L" + BUILDER_NAME + ";Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        StringBuilder args = new StringBuilder();
        for (int i=0;i<bytesLen;i++) {
            args.append('B');
            mv.visitFieldInsn(GETSTATIC, converterName, "b" + (i+1), "B");
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER_NAME, "append",
                "(" + args + ")L" + BUILDER_NAME + ";", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(bytesLen+1, 3);
        mv.visitEnd();
    }

    private void visitConvertToBridgeMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, "convertTo",
                "(L" + BUILDER_NAME + ";Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(CHECKCAST, "java/lang/String");
        mv.visitMethodInsn(INVOKEVIRTUAL, converterName, "convertTo",
                "(L" + BUILDER_NAME + ";Ljava/lang/String;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private void visitCinitMethod() {

        for (int i=0;i<bytesLen;i++) {
            FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC,
                    "b" + (i+1), "B", null, null);
            fv.visitEnd();
        }

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(text);
        mv.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "getBytes", "(Ljava/nio/charset/Charset;)[B", false);
        mv.visitVarInsn(ASTORE, 0);

        for (int i=0;i<bytesLen;i++) {
            mv.visitVarInsn(ALOAD, 0);
            //mv.visitInsn(ICONST_0);
            visitIntInsn(i, mv);
            mv.visitInsn(BALOAD);
            mv.visitFieldInsn(PUTSTATIC, converterName, "b" + (i+1), "B");
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 1);
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
