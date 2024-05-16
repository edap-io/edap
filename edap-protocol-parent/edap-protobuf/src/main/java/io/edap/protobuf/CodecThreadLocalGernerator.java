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

import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Type;

import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.ClazzUtil.getDescriptor;
import static io.edap.util.ClazzUtil.getTypeName;
import static org.objectweb.asm.Opcodes.*;

public class CodecThreadLocalGernerator {

    private final Type localType;
    private final String name;
    private ClassWriter cw;

    public CodecThreadLocalGernerator(Type localType, String name) {
        this.localType = localType;
        this.name = name;
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = name;
        String typeDesc = getDescriptor(localType);

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String outerName;
        int index = name.indexOf("$");
        if (index == -1) {
            outerName = "";
        } else {
            outerName = name.substring(0, index);
        }
        cw.visit(V1_8, ACC_FINAL + ACC_SUPER, name,
                "Ljava/lang/ThreadLocal<" + typeDesc + ">;",
                "java/lang/ThreadLocal", null);
        cw.visitInnerClass(name, null, null, ACC_STATIC);

        if (outerName.length() > 0) {
            cw.visitOuterClass(outerName, null, null);
        }

        visitInit();
        initialValue();
        visitBridge();

        cw.visitEnd();
        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private void visitMethod(MethodVisitor mv, int type, String clsName,
                             String methodName, String desc, boolean isType) {
        mv.visitMethodInsn(type, clsName, methodName, desc, isType);
    }

    private void initialValue() {
        MethodVisitor mv;
        String typeName = toInternalName(getTypeName(localType));
        String typeDesc = getDescriptor(localType);
        mv = cw.visitMethod(ACC_PROTECTED, "initialValue", "()L" + typeName + ";",
                "()" + typeDesc, null);
        mv.visitCode();
        if ("java/util/List".equals(typeName)) {
            String typeImplName = "java/util/ArrayList";
            mv.visitTypeInsn(NEW, typeImplName);
            mv.visitInsn(DUP);
            mv.visitIntInsn(BIPUSH, 16);
            visitMethod(mv, INVOKESPECIAL, typeImplName, "<init>",
                    "(I)V", false);
        } else if ("java/util/Deque".equals(typeName)) {
            String typeImplName = "java/util/ArrayDeque";
            mv.visitTypeInsn(NEW, typeImplName);
            mv.visitInsn(DUP);
            mv.visitIntInsn(BIPUSH, 32);
            visitMethod(mv, INVOKESPECIAL, typeImplName, "<init>",
                    "(I)V", false);
        }
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
    }

    private void visitInit() {
        MethodVisitor mv;
        mv = cw.visitMethod(0, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKESPECIAL, "java/lang/ThreadLocal", "<init>",
                "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private void visitBridge() {
        MethodVisitor mv;
        String typeName = toInternalName(getTypeName(localType));
        mv = cw.visitMethod(ACC_PROTECTED + ACC_BRIDGE + ACC_SYNTHETIC,
                "initialValue", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKEVIRTUAL, name, "initialValue", "()L" + typeName
                + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }
}
