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

package io.edap.data.jdbc;

import io.edap.data.jdbc.model.JdbcInfo;
import io.edap.data.jdbc.util.DaoUtil;
import io.edap.data.jdbc.util.Convertor;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Field;
import java.util.List;

import static io.edap.data.jdbc.util.Convertor.getConvertMethodName;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class JdbcFieldSetFuncGenerator {

    private static String FUNC_IFACT_NAME = toInternalName(JdbcFieldSetFunc.class.getName());

    protected static String CONVERTOR_NAME = toInternalName(Convertor.class.getName());

    private Class<?> entity;
    private List<String> columns;
    private String funcName;
    private String entityName;
    private String columnStr;
    private ClassWriter cw;

    public JdbcFieldSetFuncGenerator(Class<?> entity, List<String> columns, String columnStr) {
        this.entity = entity;
        this.entityName = toInternalName(entity.getName());
        this.columns = columns;
        this.columnStr = columnStr;
        this.funcName = toInternalName(DaoUtil.getFieldSetFuncName(entity, columns, columnStr));
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = funcName;

        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, funcName,
                "Ljava/lang/Object;L" + FUNC_IFACT_NAME + "<L" + entityName + ";>;",
                "java/lang/Object", new String[] { FUNC_IFACT_NAME });
        
        visitInitMethod();
        visitCinitMethod();
        visitSetBridgeMethod();
        visitSetMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitCinitMethod() {
        FieldVisitor fv;
        fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "COLUMN_NAMES", "Ljava/lang/String;", null, null);
        fv.visitEnd();

        fv = cw.visitField(ACC_PRIVATE | ACC_STATIC, "COLUMN_STR", "Ljava/lang/String;", null, null);
        fv.visitEnd();

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        StringBuilder sb = new StringBuilder();
        if (!CollectionUtils.isEmpty(columns)) {
            for (String name : columns) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(name);
            }
        }

        mv.visitLdcInsn(sb.toString());
        mv.visitFieldInsn(PUTSTATIC, funcName, "COLUMN_NAMES", "Ljava/lang/String;");

        mv.visitLdcInsn(columnStr);
        mv.visitFieldInsn(PUTSTATIC, funcName, "COLUMN_STR", "Ljava/lang/String;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }

    private void visitSetMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "set", "(L" + entityName + ";Ljava/sql/ResultSet;)V", null,
                new String[] { "java/sql/SQLException" });
        mv.visitCode();

        List<JdbcInfo> jdbcInfos = DaoUtil.getJdbcInfos(entity);
        if (!CollectionUtils.isEmpty(jdbcInfos)) {
            for (JdbcInfo jdbcInfo : jdbcInfos) {
                if (!CollectionUtils.isEmpty(columns) && !columns.contains(jdbcInfo.getColumnName())) {
                    continue;
                }
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitLdcInsn(jdbcInfo.getColumnName());
                //visitMethodVisitIntVaue(mv, columns.indexOf(jdbcInfo.getColumnName()) + 1);
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
                if (!jdbcInfo.isBaseType() && !jdbcInfo.getJdbcType().equals(jdbcInfo.getFieldType())) {
                    mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_NAME, getConvertMethodName(jdbcInfo.getFieldType()),
                            "(" + jdbcInfo.getJdbcType() + ")" + jdbcInfo.getFieldType(), false);
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, entityName, valueMethod,
                        "(" + getDescriptor(jdbcInfo.getField().getType()) + ")V", false);
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
                visitMethod(mv, INVOKESTATIC, "java/lang/Short", "valueOf",
                        "(S)Ljava/lang/Short;", false);
                break;
            case "java.lang.Byte":
                visitMethod(mv, INVOKESTATIC, "java/lang/Byte", "valueOf",
                        "(B)Ljava/lang/Byte;", false);
                break;
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
