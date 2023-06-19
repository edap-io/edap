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

package io.edap.data;

import io.edap.data.model.JdbcInfo;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import static io.edap.data.util.DaoUtil.getFieldSetFuncName;
import static io.edap.data.util.DaoUtil.getJdbcInfos;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.visitMethod;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class JdbcFieldSetFuncGenerator {

    private static String FUNC_IFACT_NAME = toInternalName(JdbcFieldSetFunc.class.getName());

    private Class<?> entity;
    private List<String> columns;
    private String funcName;
    private String entityName;
    private ClassWriter cw;

    public JdbcFieldSetFuncGenerator(Class<?> entity, List<String> columns) {
        this.entity = entity;
        this.entityName = toInternalName(entity.getName());
        this.columns = columns;
        this.funcName = toInternalName(getFieldSetFuncName(entity, columns));
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = funcName;

        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, funcName,
                "Ljava/lang/Object;L" + FUNC_IFACT_NAME + "<L" + entityName + ";>;",
                "java/lang/Object", new String[] { FUNC_IFACT_NAME });
        
        visitInitMethod();
        visitSetBridgeMethod();
        visitSetMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitSetMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "set", "(L" + entityName + ";Ljava/sql/ResultSet;)V", null,
                new String[] { "java/sql/SQLException" });
        mv.visitCode();

        List<JdbcInfo> jdbcInfos = getJdbcInfos(entity);
        if (!CollectionUtils.isEmpty(jdbcInfos)) {
            for (JdbcInfo jdbcInfo : jdbcInfos) {
                if (!columns.contains(jdbcInfo.getColumnName().toLowerCase(Locale.ENGLISH))) {
                    continue;
                }
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitLdcInsn(jdbcInfo.getColumnName());
                String jdbcMethod = jdbcInfo.getJdbcMethod();
                if (jdbcMethod.startsWith("set")) {
                    jdbcMethod = "get" + jdbcMethod.substring(3);
                }
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", jdbcMethod,
                        "(Ljava/lang/String;)" + jdbcInfo.getJdbcType(), true);
                String valueMethod = jdbcInfo.getValueMethod().getName();
                if (valueMethod.startsWith("is")) {
                    valueMethod = "set" + valueMethod.substring(2);
                } else if (valueMethod.startsWith("get")) {
                    valueMethod = "set" + valueMethod.substring(3);
                }
                if (jdbcInfo.isNeedUnbox()) {
                    visitBoxOpcode(mv, jdbcInfo.getField());
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, entityName, valueMethod, "(" + jdbcInfo.getFieldType() + ")V", false);
            }
        }


        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private void visitBoxOpcode(MethodVisitor mv, Field field) {
        String name = field.getType().getName();
        switch (name) {
            case "java.lang.Integer":
                visitMethod(mv, INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                break;
            case "java.lang.Long":
                visitMethod(mv, INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                break;
            case "java.lang.Boolean":
                visitMethod(mv, INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                break;
            case "java.lang.Float":
                visitMethod(mv, INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                break;
            case "java.lang.Double":
                visitMethod(mv, INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                break;
            case "java.lang.Short":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Short", "valueOf",
                        "(S)Ljava/lang/Short;", false);
                break;
            case "java.lang.Byte":
                visitMethod(mv, INVOKESTATIC, "java/lang/Byte", "valueOf",
                        "(B)Ljava/lang/Byte", false);
        }
    }

    private void visitSetBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "set", "(Ljava/lang/Object;Ljava/sql/ResultSet;)V",
                null, new String[] { "java/sql/SQLException" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, funcName, "set", "(L" + entityName + ";Ljava/sql/ResultSet;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 3);
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
