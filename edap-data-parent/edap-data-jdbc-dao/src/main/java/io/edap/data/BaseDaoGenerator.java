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
import io.edap.data.model.QueryInfo;
import io.edap.data.util.Convertor;
import io.edap.util.CollectionUtils;
import io.edap.util.Constants;
import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.util.Locale;

import static io.edap.data.util.Convertor.getConvertMethodName;
import static io.edap.data.util.DaoUtil.getBoxedName;
import static io.edap.data.util.DaoUtil.getQueryByIdInfo;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.visitMethod;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ARETURN;

public class BaseDaoGenerator {

    protected static String STMT_SESSION_NAME = toInternalName(StatementSession.class.getName());
    protected static String FIELD_SET_FUNC_NAME = toInternalName(JdbcFieldSetFunc.class.getName());
    protected static String CONSTANTS_NAME = toInternalName(Constants.class.getName());

    protected static String REGISTER_NAME = toInternalName(JdbcDaoRegister.class.getName());

    protected static String COLLECTION_UTIL_NAME = toInternalName(CollectionUtils.class.getName());

    protected static String QUERY_PARAM_NAME = toInternalName(QueryParam.class.getName());

    protected static String CONVERTOR_NAME = toInternalName(Convertor.class.getName());

    protected String PARENT_NAME;

    protected ClassWriter cw;

    protected Class<?> entity;

    protected String entityName;

    protected String daoName;

    protected String databaseType;

    protected DaoOption daoOption;

    protected void visitClinitMethod() {
        FieldVisitor fv;
        fv = cw.visitField(ACC_STATIC, "FIELD_SET_FUNCS", "Ljava/util/Map;",
                "Ljava/util/Map<Ljava/lang/String;L" + FIELD_SET_FUNC_NAME + "<L" + entityName + ";>;>;", null);
        fv.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/util/concurrent/ConcurrentHashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V", false);
        mv.visitFieldInsn(PUTSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    protected void visitInitMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    protected void visitFindOneObjectArrayBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "findOne",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "findOne", "(Ljava/lang/String;[Ljava/lang/Object;)L" + entityName + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    protected void visitFindOneObjectArrayMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "findOne", "(Ljava/lang/String;[Ljava/lang/Object;)L" + entityName + ";", null,
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "query",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/List;", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, COLLECTION_UTIL_NAME, "isEmpty", "(Ljava/util/Collection;)Z", false);
        Label l0 = new Label();
        mv.visitJumpInsn(IFEQ, l0);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 4);
        mv.visitEnd();
    }

    protected void visitFindOneTwoParamMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "findOne", "(Ljava/lang/String;[L"
                        + QUERY_PARAM_NAME + ";)L" + entityName + ";", null,
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "query",
                "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)Ljava/util/List;", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, COLLECTION_UTIL_NAME, "isEmpty", "(Ljava/util/Collection;)Z", false);
        Label l0 = new Label();
        mv.visitJumpInsn(IFEQ, l0);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(5, 4);
        mv.visitEnd();
    }

    protected void visitFindOneOneParamMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "findOne", "(Ljava/lang/String;)L" + entityName + ";", null,
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "query",
                "(Ljava/lang/String;)Ljava/util/List;", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, COLLECTION_UTIL_NAME, "isEmpty", "(Ljava/util/Collection;)Z", false);
        Label l0 = new Label();
        mv.visitJumpInsn(IFEQ, l0);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(5, 4);
        mv.visitEnd();
    }

    protected void visitQueryObjectArrayMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "query",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/List;", "" +
                        "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/List<L" + entityName + ";>;",
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, null);
        Label label3 = new Label();
        Label label4 = new Label();
        mv.visitTryCatchBlock(label3, label4, label2, null);
        Label label5 = new Label();
        mv.visitTryCatchBlock(label2, label5, label2, null);
        mv.visitLabel(label0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "execute",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/sql/ResultSet;", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitJumpInsn(IFNONNULL, label3);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_LIST", "Ljava/util/List;");
        mv.visitVarInsn(ASTORE, 4);
        mv.visitLabel(label1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitInsn(ARETURN);
        mv.visitLabel(label3);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/sql/ResultSet"}, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "io/edap/data/JdbcFieldSetFunc");
        mv.visitVarInsn(ASTORE, 5);
        mv.visitVarInsn(ALOAD, 5);
        Label label6 = new Label();
        mv.visitJumpInsn(IFNONNULL, label6);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitLabel(label6);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {"java/lang/String", FIELD_SET_FUNC_NAME},
                0, null);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "()V", false);
        mv.visitVarInsn(ASTORE, 6);
        Label label7 = new Label();
        mv.visitLabel(label7);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label label8 = new Label();
        mv.visitJumpInsn(IFEQ, label8);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 7);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set",
                "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, label7);
        mv.visitLabel(label8);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ASTORE, 7);
        mv.visitLabel(label4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitInsn(ARETURN);
        mv.visitLabel(label2);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[] {daoName, "java/lang/String", "" +
                "[Ljava/lang/Object;"}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, 8);
        mv.visitLabel(label5);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(4, 9);
        mv.visitEnd();
    }

    protected void visitQueryTwoParamMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "query", "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)Ljava/util/List;",
                "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)" +
                        "Ljava/util/List<L" + entityName + ";>;", new String[] { "java/lang/Exception" });
        mv.visitCode();

        Label l0 = new Label();
        Label l1 = new Label();
        Label l2 = new Label();
        mv.visitTryCatchBlock(l0, l1, l2, null);
        Label l3 = new Label();
        Label l4 = new Label();
        mv.visitTryCatchBlock(l3, l4, l2, null);
        Label l5 = new Label();
        mv.visitTryCatchBlock(l2, l5, l2, null);
        mv.visitLabel(l0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "execute", "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)Ljava/sql/ResultSet;", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitJumpInsn(IFNONNULL, l3);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_LIST", "Ljava/util/List;");
        mv.visitVarInsn(ASTORE, 4);
        mv.visitLabel(l1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l3);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/sql/ResultSet"}, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitVarInsn(ALOAD, 5);
        Label l6 = new Label();
        mv.visitJumpInsn(IFNONNULL, l6);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        mv.visitLabel(l6);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {"java/lang/String", FIELD_SET_FUNC_NAME},
                0, null);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 6);
        Label l7 = new Label();
        mv.visitLabel(l7);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label l8 = new Label();
        mv.visitJumpInsn(IFEQ, l8);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 7);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set", "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, l7);
        mv.visitLabel(l8);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ASTORE, 7);
        mv.visitLabel(l4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_FULL, 5, new Object[] {daoName, "java/lang/String", "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, 8);
        mv.visitLabel(l5);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(5, 11);
        mv.visitEnd();
    }

    protected void visitGetSqlFieldSetFuncMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE, "getSqlFieldSetFunc", "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME + ";",
                "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME +"<L" + entityName + ";>;",
                new String[] { "java/sql/SQLException" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getMetaData", "()Ljava/sql/ResultSetMetaData;", true);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSetMetaData", "getColumnCount", "()I", true);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, 5);
        Label l0 = new Label();
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_FULL, 6, new Object[] {daoName, "java/sql/ResultSet", "java/sql/ResultSetMetaData",
                Opcodes.INTEGER, "java/util/List", Opcodes.INTEGER}, 0, new Object[] {});
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 3);
        Label l1 = new Label();
        mv.visitJumpInsn(IF_ICMPGT, l1);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSetMetaData", "getColumnName", "(I)Ljava/lang/String;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitIincInsn(5, 1);
        mv.visitJumpInsn(GOTO, l0);
        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance", "()L" + REGISTER_NAME + ";", false);
        mv.visitLdcInsn(Type.getType("L" + entityName + ";"));
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getFieldSetFunc",
                "(Ljava/lang/Class;Ljava/util/List;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 6);
        mv.visitEnd();
    }

    protected void visitQueryOneParamMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "query", "(Ljava/lang/String;)Ljava/util/List;",
                "(Ljava/lang/String;)Ljava/util/List<L" + entityName + ";>;", new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "execute", "(Ljava/lang/String;)Ljava/sql/ResultSet;", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        Label l0 = new Label();
        mv.visitJumpInsn(IFNONNULL, l0);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_LIST", "Ljava/util/List;");
        mv.visitInsn(ARETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/sql/ResultSet"}, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getFieldsSql", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 4);
        Label l1 = new Label();
        mv.visitJumpInsn(IFNONNULL, l1);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {"java/lang/String", FIELD_SET_FUNC_NAME}, 0, null);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 5);
        Label l2 = new Label();
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label l3 = new Label();
        mv.visitJumpInsn(IFEQ, l3);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 6);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set", "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitJumpInsn(GOTO, l2);
        mv.visitLabel(l3);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(5, 7);
        mv.visitEnd();
    }

    protected void visitFindOneOneParamBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "findOne",
                "(Ljava/lang/String;)Ljava/lang/Object;", null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "findOne", "(Ljava/lang/String;)L" + entityName + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    protected void visitFindOneTwoParamBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "findOne",
                "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)Ljava/lang/Object;", null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "findOne", "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)L" + entityName + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    protected void visitFindByIdBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "findById",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "findById", "(Ljava/lang/Object;)L" + entityName + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    protected void visitFindByIdMethod() {
        MethodVisitor mv;

        QueryInfo queryInfo = getQueryByIdInfo(entity);

        mv = cw.visitMethod(ACC_PUBLIC, "findById", "(Ljava/lang/Object;)L" + entityName + ";",
                null, new String[] { "java/lang/Exception" });
        mv.visitCode();

        Label l0 = new Label();
        Label l1 = new Label();
        Label l2 = new Label();
        mv.visitTryCatchBlock(l0, l1, l2, null);
        Label l3 = new Label();
        mv.visitTryCatchBlock(l2, l3, l2, null);
        mv.visitVarInsn(ALOAD, 1);
        Label l4 = new Label();
        mv.visitJumpInsn(IFNONNULL, l4);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l4);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitLabel(l0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitLdcInsn(queryInfo.getQuerySql());
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement", "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ALOAD, 1);
        if (queryInfo.getIdInfo() != null) {
            JdbcInfo idInfo = queryInfo.getIdInfo();
            if (idInfo.isBaseType()) {
                if (idInfo.isNeedUnbox()) {
                    mv.visitTypeInsn(CHECKCAST, toInternalName(getBoxedName(idInfo.getField().getType())));
                    visitUnboxOpcode(mv, idInfo.getField());
                } else {
                    visitBoxedAndGetValue(mv, idInfo.getField());
                }
            } else {
                mv.visitTypeInsn(CHECKCAST, getDescriptor(queryInfo.getIdInfo().getField().getType()));
            }
            String setMethod = queryInfo.getIdInfo().getJdbcMethod();
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", setMethod,
                    "(I" + queryInfo.getIdInfo().getJdbcType() + ")V", true);
        }
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery", "()Ljava/sql/ResultSet;", true);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label l5 = new Label();
        mv.visitJumpInsn(IFEQ, l5);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 5);

        for (JdbcInfo jdbcInfo : queryInfo.getAllColumns()) {
            mv.visitVarInsn(ALOAD, 5);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitLdcInsn(jdbcInfo.getColumnName());
            String getMethod = "get" + jdbcInfo.getJdbcMethod().substring(3);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", getMethod,
                    "(Ljava/lang/String;)" + jdbcInfo.getJdbcType(), true);
            String fieldName = jdbcInfo.getField().getName();
            String setMethod = "set" + fieldName.substring(0,1).toUpperCase(Locale.ENGLISH)
                    + fieldName.substring(1);
            if (jdbcInfo.isNeedUnbox()) {
                visitBoxedOpcode(mv, jdbcInfo.getField());
            }
            if (!jdbcInfo.isBaseType() && !jdbcInfo.getJdbcType().equals(jdbcInfo.getFieldType())) {
                mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_NAME, getConvertMethodName(jdbcInfo.getFieldType()),
                        "(" + jdbcInfo.getJdbcType() + ")" + jdbcInfo.getFieldType(), false);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, setMethod,
                    "(" + getDescriptor(jdbcInfo.getField().getType()) + ")V", false);
        }

//        mv.visitVarInsn(ALOAD, 5);
//        mv.visitVarInsn(ASTORE, 6);
        mv.visitLabel(l1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l5);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {STMT_SESSION_NAME}, 0, null);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        Label l6 = new Label();
        mv.visitJumpInsn(GOTO, l6);
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, 6);
        mv.visitLabel(l3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitInsn(ATHROW);
        mv.visitLabel(l6);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 8);
        mv.visitEnd();

    }

    /**
     * 添加基础数据类型如int,long,double等数据的装箱的opcode
     * @param mv
     * @param field
     */
    protected void visitUnboxOpcode(MethodVisitor mv, Field field) {
        String name = field.getType().getName();
        switch (name) {
            case "java.lang.Integer":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Integer", "intValue",
                        "()I", false);
                break;
            case "java.lang.Long":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Long", "longValue",
                        "()J", false);
                break;
            case "java.lang.Boolean":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue",
                        "()Z", false);
                break;
            case "java.lang.Float":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Float", "floatValue",
                        "()F", false);
                break;
            case "java.lang.Double":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Double", "doubleValue",
                        "()D", false);
                break;
            case "java.lang.Short":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Short", "shortValue",
                        "()S", false);
                break;
            case "java.lang.Byte":
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Byte", "byteValue",
                        "()B", false);
        }
    }

    protected void visitBoxedOpcode(MethodVisitor mv, Field field) {
        String name = field.getType().getName();
        // mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
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
        }
    }

    private void visitBoxedAndGetValue(MethodVisitor mv, Field field) {
        switch (field.getType().getName()) {
            case "int":
                mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Integer", "intValue",
                        "()I", false);
                break;
            case "long":
                mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Long", "longValue",
                        "()J", false);
                break;
            case "double":
                mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Double", "doubleValue",
                        "()D", false);
                break;

        }
    }
}
