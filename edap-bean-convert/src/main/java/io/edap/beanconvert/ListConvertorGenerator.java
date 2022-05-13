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

package io.edap.beanconvert;

import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.util.List;

import static io.edap.beanconvert.util.ConvertUtil.CONVERT_LIST_METHOD;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.RETURN;

/**
 * List类型Bean转换器的生成器
 */
public class ListConvertorGenerator {

    static String REGISTER_NAME = toInternalName(ConvertorRegister.class.getName());
    static String PARENT_ANME = AbstractConvertor.class.getName();
    static String LIST_CONVERTOR_NAME = "LIST_CONVERTOR";
    static String IFACE_NAME = toInternalName(Convertor.class.getName());

    private Class orignalCls;
    private Class destCls;
    private String convertorName;

    private ClassWriter cw;

    public ListConvertorGenerator(Class orignalCls, Class destCls, List<MapperConfig> configs) {
        this.orignalCls = orignalCls;
        this.destCls = destCls;
        this.convertorName = toInternalName(ConvertorRegister.instance().getListConvertorName(orignalCls, destCls));
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = convertorName;

        System.out.println("convertorName=" + convertorName);
        //定义编码器名称，继承的虚拟编码器以及实现的接口
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, convertorName, null, "java/lang/Object", null);



        // 生成初始化函数
        visitInitMethod();

        visitClinitMethod();

        visitConvertMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitConvertMethod() {
        MethodVisitor mv;

        mv = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, CONVERT_LIST_METHOD, "(Ljava/util/List;)Ljava/util/List;",
                "(Ljava/util/List<L" + toInternalName(orignalCls.getName()) + ";>;)Ljava/util/List<L" + toInternalName(destCls.getName()) + ";>;", null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        Label l0 = new Label();
        mv.visitJumpInsn(IFNONNULL, l0);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, 2);
        Label l1 = new Label();
        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {"java/util/List", "java/util/Iterator"}, 0, null);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        Label l2 = new Label();
        mv.visitJumpInsn(IFEQ, l2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, toInternalName(orignalCls.getName()));
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, toInternalName(convertorName), LIST_CONVERTOR_NAME, "L" + IFACE_NAME + ";");
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "convert", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, l1);
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 4);
        mv.visitEnd();
    }

    private void visitClinitMethod() {
        MethodVisitor mv;
        FieldVisitor fv;

        String ifaceDesc = "L" + IFACE_NAME + "<L" + toInternalName(orignalCls.getName()) + ";L"
                + toInternalName(destCls.getName()) + ";>;";
        fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, LIST_CONVERTOR_NAME, "L" + IFACE_NAME + ";", ifaceDesc, null);
        fv.visitEnd();

        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance", "()L" + REGISTER_NAME + ";", false);
        mv.visitLdcInsn(Type.getType("L" + toInternalName(orignalCls.getName()) + ";"));
        mv.visitLdcInsn(Type.getType("L" + toInternalName(destCls.getName()) + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getConvertor", "(Ljava/lang/Class;Ljava/lang/Class;)L" + toInternalName(PARENT_ANME) + ";", false);
        mv.visitFieldInsn(PUTSTATIC, convertorName, LIST_CONVERTOR_NAME, "L" + IFACE_NAME + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
    }

    private void visitInitMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

    }
}
