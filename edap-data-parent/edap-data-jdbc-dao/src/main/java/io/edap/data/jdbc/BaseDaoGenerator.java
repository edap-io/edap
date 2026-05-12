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

import io.edap.data.PageResult;
import io.edap.data.QueryParam;
import io.edap.data.jdbc.model.ColumnsInfo;
import io.edap.data.jdbc.model.JdbcInfo;
import io.edap.data.jdbc.model.QueryInfo;
import io.edap.data.jdbc.util.Convertor;
import io.edap.data.jdbc.util.DaoUtil;
import io.edap.data.jdbc.util.DialectFactory;
import io.edap.util.CollectionUtils;
import io.edap.util.Constants;
import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.util.Locale;

import static io.edap.util.AsmUtil.*;
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

    protected static String LIMIT_DIALECT_NAME   = toInternalName(LimitDialect.class.getName());
    protected static String LIMIT_QUERYINFO_NAME   = toInternalName(LimitQueryInfo.class.getName());
    protected static String PAGE_RESULT_NAME = toInternalName(PageResult.class.getName());
    protected static String DAO_OPTION_NAME = toInternalName(DaoOption.class.getName());
    protected static String DIALECT_FACTORY_NAME = toInternalName(DialectFactory.class.getName());

    protected String PARENT_NAME;

    protected ClassWriter cw;

    protected Class<?> entity;

    protected String entityName;

    protected String daoName;

    protected DaoOption daoOption;

    protected void visitFillSqlFieldMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "fillSqlField",
                "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);

        Label lbSqlNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbSqlNotNull);
        mv.visitLdcInsn("");
        mv.visitVarInsn(ASTORE, 0);

        Label lbIsSelect = new Label();
        mv.visitJumpInsn(GOTO, lbIsSelect);

        mv.visitLabel(lbSqlNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 0);

        String dialectFactoryName = toInternalName(DialectFactory.class.getName());
        mv.visitLabel(lbIsSelect);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, dialectFactoryName, "isSelectStart", "(Ljava/lang/String;)Z", false);

        Label lbIfSelect = new Label();
        mv.visitJumpInsn(IFNE, lbIfSelect);
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "()V", false);
        StringBuilder allFields = new StringBuilder();
        ColumnsInfo columnsInfo = DaoUtil.getColumns(entity, daoOption);
        for (String col : columnsInfo.getColumns()) {
            if (allFields.length() > 0) {
                allFields.append(',');
            }
            allFields.append(col);
        }
        mv.visitLdcInsn("SELECT " + allFields + " FROM " + DaoUtil.getTableName(entity) + " ");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 0);

        mv.visitLabel(lbIfSelect);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

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
        FieldVisitor fv = cw.visitField(ACC_PRIVATE, "daoOption", "L" + DAO_OPTION_NAME+ ";", null, null);
        fv.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "<init>", "(L" + DAO_OPTION_NAME + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        MethodVisitor methodVisitor = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + DAO_OPTION_NAME + ";)V", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "()V", false);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitFieldInsn(PUTFIELD, daoName, "daoOption", "L" + DAO_OPTION_NAME + ";");
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESTATIC, DIALECT_FACTORY_NAME, "createLimitDialect",
                "(L" + DAO_OPTION_NAME + ";)L" + LIMIT_DIALECT_NAME +";", false);
        methodVisitor.visitFieldInsn(PUTFIELD, daoName, "limitDialect", "L" + LIMIT_DIALECT_NAME+ ";");
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(2, 2);
        methodVisitor.visitEnd();
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
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "fillSqlField",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "execute",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/sql/ResultSet;", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitJumpInsn(IFNONNULL, label3);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_LIST", "Ljava/util/List;");
        mv.visitVarInsn(ASTORE, 5);
        mv.visitLabel(label1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ARETURN);
        mv.visitLabel(label3);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/sql/ResultSet"}, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, 6);
        mv.visitVarInsn(ALOAD, 6);
        Label label6 = new Label();
        mv.visitJumpInsn(IFNONNULL, label6);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, 6);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 6);
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
        mv.visitVarInsn(ASTORE, 7);
        Label label7 = new Label();
        mv.visitLabel(label7);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label label8 = new Label();
        mv.visitJumpInsn(IFEQ, label8);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set",
                "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, label7);
        mv.visitLabel(label8);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitLabel(label4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitInsn(ARETURN);
        mv.visitLabel(label2);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[] {daoName, "java/lang/String", "" +
                "[Ljava/lang/Object;"}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, 9);
        mv.visitLabel(label5);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 9);
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
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "fillSqlField",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 3);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "execute", "(Ljava/lang/String;[L" + QUERY_PARAM_NAME + ";)Ljava/sql/ResultSet;", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitJumpInsn(IFNONNULL, l3);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_LIST", "Ljava/util/List;");
        mv.visitVarInsn(ASTORE, 5);
        mv.visitLabel(l1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l3);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/sql/ResultSet"}, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, 6);
        mv.visitVarInsn(ALOAD, 6);
        Label l6 = new Label();
        mv.visitJumpInsn(IFNONNULL, l6);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKESPECIAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, 6);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        mv.visitLabel(l6);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {"java/lang/String", FIELD_SET_FUNC_NAME},
                0, null);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 7);
        Label l7 = new Label();
        mv.visitLabel(l7);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/List"}, 0, null);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label l8 = new Label();
        mv.visitJumpInsn(IFEQ, l8);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set", "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, l7);
        mv.visitLabel(l8);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitLabel(l4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_FULL, 5, new Object[] {daoName, "java/lang/String", "java/util/List", Opcodes.INTEGER, Opcodes.INTEGER}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, 9);
        mv.visitLabel(l5);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "()V", false);
        mv.visitVarInsn(ALOAD, 9);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(5, 11);
        mv.visitEnd();
    }

    protected void visitGetSqlFieldSetFuncMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE, "getSqlFieldSetFunc", "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME +"<L" + entityName + ";>;",
                new String[] { "java/sql/SQLException" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getMetaData", "()Ljava/sql/ResultSetMetaData;", true);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSetMetaData", "getColumnCount", "()I", true);
        mv.visitVarInsn(ISTORE, 4);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, 6);
        Label l0 = new Label();
        mv.visitLabel(l0);

        mv.visitVarInsn(ILOAD, 6);
        mv.visitVarInsn(ILOAD, 4);
        Label l1 = new Label();
        mv.visitJumpInsn(IF_ICMPGT, l1);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSetMetaData", "getColumnName", "(I)Ljava/lang/String;", true);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitIincInsn(6, 1);
        mv.visitJumpInsn(GOTO, l0);
        mv.visitLabel(l1);

        mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance", "()L" + REGISTER_NAME + ";", false);
        mv.visitLdcInsn(Type.getType("L" + entityName + ";"));
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getFieldSetFunc",
                "(Ljava/lang/Class;Ljava/util/List;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
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
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_ARRAY", "[Ljava/lang/Object;");
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "query", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/util/List;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(6, 2);
        mv.visitEnd();
    }

    protected void visitQueryThreeParamMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "query", "(Ljava/lang/String;II)Ljava/util/List;",
                "(Ljava/lang/String;II)Ljava/util/List<L" + entityName + ";>;", new String[] { "java/lang/Exception" });
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_ARRAY", "[Ljava/lang/Object;");
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "query",
                "(Ljava/lang/String;II[Ljava/lang/Object;)Ljava/util/List;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(5, 4);
        mv.visitEnd();
    }

    protected void visitQueryFourParamMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "query",
                "(Ljava/lang/String;II[Ljava/lang/Object;)Ljava/util/List;",
                "(Ljava/lang/String;II[Ljava/lang/Object;)Ljava/util/List<L" + entityName + ";>;",
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        int varSql       = 1;
        int varStart     = varSql + 1;
        int varCount     = varStart + 1;
        int varParam     = varCount + 1;
        int varSession   = varParam + 1;
        int varLimitInfo = varSession + 1;
        int varPstmt     = varLimitInfo + 1;
        int varResultSet = varPstmt + 1;
        int varFieldKey  = varResultSet + 1;
        int varFunc      = varFieldKey + 1;
        int varResList   = varFunc + 1;
        int varEntity    = varResList + 1;
        int varEx        = varEntity + 1;

        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, null);
        Label label3 = new Label();
        mv.visitTryCatchBlock(label2, label3, label2, null);
        mv.visitVarInsn(ILOAD, varStart);

        // start小于0
        Label lbStartGe = new Label();
        mv.visitJumpInsn(IFGE, lbStartGe);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varStart);

        mv.visitLabel(lbStartGe);
        // 判断count是否小于1
        mv.visitVarInsn(ILOAD, varCount);
        mv.visitInsn(ICONST_1);
        Label lbCountGe = new Label();
        mv.visitJumpInsn(IF_ICMPGE, lbCountGe);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitLabel(lbCountGe);

        // 完善sql语句，如果没有提供select 部分则使用select所有字段补全
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "fillSqlField", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varSql);

        // 获取StatementSession
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varSession);
        // try内执行limit查询
        mv.visitLabel(label0);
        // 根据分页sql以及变量返回分页信息
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, daoName, "limitDialect", "L" + LIMIT_DIALECT_NAME + ";");
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitVarInsn(ILOAD, varStart);
        mv.visitVarInsn(ILOAD, varCount);
        mv.visitMethodInsn(INVOKEINTERFACE, LIMIT_DIALECT_NAME, "process",
                "(Ljava/lang/String;II)L" + LIMIT_QUERYINFO_NAME + ";", true);
        mv.visitVarInsn(ASTORE, varLimitInfo);

        // preparedStatement
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getSql", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);

        // 为PreparedStatement设置参数
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParam);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;[Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParam);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getParams", "()[Ljava/lang/Object;", false);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;I[Ljava/lang/Object;)V", false);

        // 执行executeQuery
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery", "()Ljava/sql/ResultSet;");
        mv.visitVarInsn(ASTORE, varResultSet);

        // 获取为entity赋值的函数实例
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varFieldKey);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldKey);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/String;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, varFunc);

        // 如果func为空则使创建func
        mv.visitVarInsn(ALOAD, varFunc);
        Label lbFuncNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbFuncNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitVarInsn(ALOAD, varFieldKey);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varFunc);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldKey);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        mv.visitLabel(lbFuncNotNull);
        // 初始化List
        mv.visitLabel(lbFuncNotNull);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varResList);

        // while (rs.next) 循环
        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label lbReturn = new Label();
        mv.visitJumpInsn(IFEQ, lbReturn);

        // 新建entity实例并且赋值
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varEntity);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set", "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);

        // 添加到List中
        mv.visitVarInsn(ALOAD, varResList);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, lbWhile);

        mv.visitLabel(lbReturn);

        mv.visitLabel(label1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);

        mv.visitVarInsn(ALOAD, varResList);
        mv.visitInsn(ARETURN);

        mv.visitLabel(label2);
        mv.visitFrame(Opcodes.F_FULL, 6, new Object[] {daoName, "java/lang/String", Opcodes.INTEGER,
                        Opcodes.INTEGER, "[Ljava/lang/Object;", STMT_SESSION_NAME}, 1,
                new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, varEx);

        mv.visitLabel(label3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varEx);
        mv.visitInsn(ATHROW);

        mv.visitMaxs(4, 14);
        mv.visitEnd();
    }

    protected void visitQueryPageMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "queryPage", "(Ljava/lang/String;Ljava/lang/String;II)Lio/edap/data/PageResult;",
                "(Ljava/lang/String;Ljava/lang/String;II)Lio/edap/data/PageResult<L" + entityName + ";>;",
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitFieldInsn(GETSTATIC, CONSTANTS_NAME, "EMPTY_ARRAY", "[Ljava/lang/Object;");
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "queryPage",
                "(Ljava/lang/String;Ljava/lang/String;II[Ljava/lang/Object;)Lio/edap/data/PageResult;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(5, 4);
        mv.visitEnd();
    }

    protected void visitQueryPageObjectParamMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "queryPage",
                "(Ljava/lang/String;Ljava/lang/String;II[Ljava/lang/Object;)Lio/edap/data/PageResult;",
                "(Ljava/lang/String;Ljava/lang/String;II[Ljava/lang/Object;)Lio/edap/data/PageResult<L" + entityName + ";>;",
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, null);
        Label label3 = new Label();
        mv.visitTryCatchBlock(label2, label3, label2, null);

        int varSql         = 1;
        int varOrderBy     = varSql + 1;
        int varPageNum     = varOrderBy + 1;
        int varPageSize    = varPageNum + 1;
        int varParams      = varPageSize + 1;
        int varStart       = varParams + 1;
        int varPageRes     = varStart + 1;
        int varStmtSession = varPageRes + 1;
        int varTotalSql    = varStmtSession + 1;
        int varPstmt       = varTotalSql + 1;
        int varResultSet   = varPstmt + 1;
        int varLimitInfo   = varResultSet + 1;
        int varFieldsKey   = varLimitInfo + 1;
        int varFunc        = varFieldsKey + 1;
        int varResList     = varFunc + 1;
        int varEntity      = varResList + 1;
        int varEx          = varEntity + 1;

        // 如果pageSize 小于1 则赋值为1
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitInsn(ICONST_1);
        Label lbPageSizeGe = new Label();
        mv.visitJumpInsn(IF_ICMPGE, lbPageSizeGe);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, varPageSize);
        mv.visitLabel(lbPageSizeGe);

        mv.visitVarInsn(ILOAD, varPageNum);
        mv.visitInsn(ICONST_1);
        Label lbPageNumGe = new Label();
        mv.visitJumpInsn(IF_ICMPGE, lbPageNumGe);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varStart);
        Label lbResDef = new Label();
        mv.visitJumpInsn(GOTO, lbResDef);

        // 计算开始位置
        mv.visitLabel(lbPageNumGe);
        mv.visitVarInsn(ILOAD, varPageNum);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitInsn(IMUL);
        mv.visitVarInsn(ISTORE, varStart);

        // 声明返回实例
        mv.visitLabel(lbResDef);
        mv.visitTypeInsn(NEW, PAGE_RESULT_NAME);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PAGE_RESULT_NAME, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varPageRes);

        // 为result赋值pageSize
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitMethodInsn(INVOKEVIRTUAL, PAGE_RESULT_NAME, "setPageSize", "(I)V", false);

        // 完善sql语句
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "fillSqlField",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varSql);

        // 获取StatementSession
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varStmtSession);

        // try开始
        mv.visitLabel(label0);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitLdcInsn(DaoUtil.getTableName(entity));
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, daoName, "daoOption", "L" + DAO_OPTION_NAME + ";");
        mv.visitMethodInsn(INVOKESTATIC, DIALECT_FACTORY_NAME, "buildTotalSql",
                "(Ljava/lang/String;Ljava/lang/String;L" + DAO_OPTION_NAME + ";)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varTotalSql);

        // preparedStatement操作
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitVarInsn(ALOAD, varTotalSql);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);

        // 为preparedStatement绑定参数,并执行查询
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParams);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;[Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery",
                "()Ljava/sql/ResultSet;", true);
        mv.visitVarInsn(ASTORE, varResultSet);

        // 填充PageResult的total值
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label lbNotTotal = new Label();
        mv.visitJumpInsn(IFEQ, lbNotTotal);
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
        mv.visitMethodInsn(INVOKEVIRTUAL, PAGE_RESULT_NAME, "setTotal", "(I)V", false);
        mv.visitLabel(lbNotTotal);

        // 获取分页的数据实例
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, daoName, "limitDialect", "L" + LIMIT_DIALECT_NAME + ";");
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitVarInsn(ILOAD, varStart);
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitVarInsn(ALOAD, varOrderBy);
        mv.visitMethodInsn(INVOKEINTERFACE, LIMIT_DIALECT_NAME, "process",
                "(Ljava/lang/String;IILjava/lang/String;)L" + LIMIT_QUERYINFO_NAME + ";", true);
        mv.visitVarInsn(ASTORE, varLimitInfo);

        // 生成PreparedStatement并执行查询
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getSql", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParams);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;[Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParams);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getParams", "()[Ljava/lang/Object;", false);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;I[Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery",
                "()Ljava/sql/ResultSet;", true);
        mv.visitVarInsn(ASTORE, varResultSet);

        // 获取设置entity的函数
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varFieldsKey);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldsKey);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, varFunc);
        mv.visitVarInsn(ALOAD, varFunc);
        Label lbFuncNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbFuncNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitVarInsn(ALOAD, varFieldsKey);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varFunc);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldsKey);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitLabel(lbFuncNotNull);

        // 创建entity的列表
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varResList);

        // while循环
        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label lbWhileFinish = new Label();
        mv.visitJumpInsn(IFEQ, lbWhileFinish);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varEntity);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set",
                "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitVarInsn(ALOAD, varResList);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, lbWhile);

        mv.visitLabel(lbWhileFinish);
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitVarInsn(ALOAD, varResList);
        mv.visitMethodInsn(INVOKEVIRTUAL, PAGE_RESULT_NAME, "setDataList", "(Ljava/util/List;)V", false);

        mv.visitLabel(label1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);

        Label lbReturn = new Label();
        mv.visitJumpInsn(GOTO, lbReturn);
        mv.visitLabel(label2);
        mv.visitVarInsn(ASTORE, varEx);
        mv.visitLabel(label3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varEx);
        mv.visitInsn(ATHROW);

        mv.visitLabel(lbReturn);
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 17);
        mv.visitEnd();

    }

    protected void visitQueryPageQueryParamParamMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "queryPage",
                "(Ljava/lang/String;Ljava/lang/String;II[L" + QUERY_PARAM_NAME + ";)Lio/edap/data/PageResult;",
                "(Ljava/lang/String;Ljava/lang/String;II[L" + QUERY_PARAM_NAME + ";)Lio/edap/data/PageResult<L" + entityName + ";>;",
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, null);
        Label label3 = new Label();
        mv.visitTryCatchBlock(label2, label3, label2, null);

        int varSql         = 1;
        int varOrderBy     = varSql + 1;
        int varPageNum     = varOrderBy + 1;
        int varPageSize    = varPageNum + 1;
        int varParams      = varPageSize + 1;
        int varStart       = varParams + 1;
        int varPageRes     = varStart + 1;
        int varStmtSession = varPageRes + 1;
        int varTotalSql    = varStmtSession + 1;
        int varPstmt       = varTotalSql + 1;
        int varResultSet   = varPstmt + 1;
        int varLimitInfo   = varResultSet + 1;
        int varFieldsKey   = varLimitInfo + 1;
        int varFunc        = varFieldsKey + 1;
        int varResList     = varFunc + 1;
        int varEntity      = varResList + 1;
        int varEx          = varEntity + 1;

        // 如果pageSize 小于1 则赋值为1
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitInsn(ICONST_1);
        Label lbPageSizeGe = new Label();
        mv.visitJumpInsn(IF_ICMPGE, lbPageSizeGe);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, varPageSize);
        mv.visitLabel(lbPageSizeGe);

        mv.visitVarInsn(ILOAD, varPageNum);
        mv.visitInsn(ICONST_1);
        Label lbPageNumGe = new Label();
        mv.visitJumpInsn(IF_ICMPGE, lbPageNumGe);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varStart);
        Label lbResDef = new Label();
        mv.visitJumpInsn(GOTO, lbResDef);

        // 计算开始位置
        mv.visitLabel(lbPageNumGe);
        mv.visitVarInsn(ILOAD, varPageNum);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitInsn(IMUL);
        mv.visitVarInsn(ISTORE, varStart);

        // 声明返回实例
        mv.visitLabel(lbResDef);
        mv.visitTypeInsn(NEW, PAGE_RESULT_NAME);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PAGE_RESULT_NAME, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varPageRes);

        // 为result赋值pageSize
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitMethodInsn(INVOKEVIRTUAL, PAGE_RESULT_NAME, "setPageSize", "(I)V", false);

        // 完善sql语句
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "fillSqlField",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varSql);

        // 获取StatementSession
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varStmtSession);

        // try开始
        mv.visitLabel(label0);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitLdcInsn(DaoUtil.getTableName(entity));
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, daoName, "daoOption", "L" + DAO_OPTION_NAME + ";");
        mv.visitMethodInsn(INVOKESTATIC, DIALECT_FACTORY_NAME, "buildTotalSql",
                "(Ljava/lang/String;Ljava/lang/String;L" + DAO_OPTION_NAME + ";)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varTotalSql);

        // preparedStatement操作
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitVarInsn(ALOAD, varTotalSql);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);

        // 为preparedStatement绑定参数,并执行查询
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParams);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;[L" + QUERY_PARAM_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery",
                "()Ljava/sql/ResultSet;", true);
        mv.visitVarInsn(ASTORE, varResultSet);

        // 填充PageResult的total值
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label lbNotTotal = new Label();
        mv.visitJumpInsn(IFEQ, lbNotTotal);
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
        mv.visitMethodInsn(INVOKEVIRTUAL, PAGE_RESULT_NAME, "setTotal", "(I)V", false);
        mv.visitLabel(lbNotTotal);

        // 获取分页的数据实例
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, daoName, "limitDialect", "L" + LIMIT_DIALECT_NAME + ";");
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitVarInsn(ILOAD, varStart);
        mv.visitVarInsn(ILOAD, varPageSize);
        mv.visitVarInsn(ALOAD, varOrderBy);
        mv.visitMethodInsn(INVOKEINTERFACE, LIMIT_DIALECT_NAME, "process",
                "(Ljava/lang/String;IILjava/lang/String;)L" + LIMIT_QUERYINFO_NAME + ";", true);
        mv.visitVarInsn(ASTORE, varLimitInfo);

        // 生成PreparedStatement并执行查询
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getSql", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParams);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;[L" + QUERY_PARAM_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParams);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getParams", "()[Ljava/lang/Object;", false);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;I[L" + QUERY_PARAM_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery",
                "()Ljava/sql/ResultSet;", true);
        mv.visitVarInsn(ASTORE, varResultSet);

        // 获取设置entity的函数
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varFieldsKey);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldsKey);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, varFunc);
        mv.visitVarInsn(ALOAD, varFunc);
        Label lbFuncNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbFuncNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitVarInsn(ALOAD, varFieldsKey);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varFunc);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldsKey);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);
        mv.visitLabel(lbFuncNotNull);

        // 创建entity的列表
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varResList);

        // while循环
        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label lbWhileFinish = new Label();
        mv.visitJumpInsn(IFEQ, lbWhileFinish);
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varEntity);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set",
                "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);
        mv.visitVarInsn(ALOAD, varResList);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, lbWhile);

        mv.visitLabel(lbWhileFinish);
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitVarInsn(ALOAD, varResList);
        mv.visitMethodInsn(INVOKEVIRTUAL, PAGE_RESULT_NAME, "setDataList", "(Ljava/util/List;)V", false);

        mv.visitLabel(label1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);

        Label lbReturn = new Label();
        mv.visitJumpInsn(GOTO, lbReturn);
        mv.visitLabel(label2);
        mv.visitVarInsn(ASTORE, varEx);
        mv.visitLabel(label3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varStmtSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varEx);
        mv.visitInsn(ATHROW);

        mv.visitLabel(lbReturn);
        mv.visitVarInsn(ALOAD, varPageRes);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 17);
        mv.visitEnd();

    }

    protected void visitQueryFourObjectArrayMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC | ACC_VARARGS, "query",
                "(Ljava/lang/String;II[L" + QUERY_PARAM_NAME + ";)Ljava/util/List;",
                "(Ljava/lang/String;II[L" + QUERY_PARAM_NAME + ";)Ljava/util/List<L" + entityName + ";>;",
                new String[] { "java/lang/Exception" });
        mv.visitCode();
        int varSql       = 1;
        int varStart     = 2;
        int varCount     = 3;
        int varParam     = 4;
        int varSession   = 5;
        int varLimitInfo = 6;
        int varPstmt     = 7;
        int varResultSet = 8;
        int varFieldKey  = 9;
        int varFunc      = 10;
        int varResList   = 11;
        int varEntity    = 12;
        int varEx        = 13;

        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, null);
        Label label3 = new Label();
        mv.visitTryCatchBlock(label2, label3, label2, null);
        mv.visitVarInsn(ILOAD, varStart);

        // start小于0
        Label lbStartGe = new Label();
        mv.visitJumpInsn(IFGE, lbStartGe);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varStart);

        mv.visitLabel(lbStartGe);
        // 判断count是否小于1
        mv.visitVarInsn(ILOAD, varCount);
        mv.visitInsn(ICONST_1);
        Label lbCountGe = new Label();
        mv.visitJumpInsn(IF_ICMPGE, lbCountGe);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitLabel(lbCountGe);

        // 完善sql语句，如果没有提供select 部分则使用select所有字段补全
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "fillSqlField", "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varSql);

        // 获取StatementSession
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varSession);
        // try内执行limit查询
        mv.visitLabel(label0);
        // 根据分页sql以及变量返回分页信息
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, daoName, "limitDialect", "L" + LIMIT_DIALECT_NAME + ";");
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitVarInsn(ILOAD, varStart);
        mv.visitVarInsn(ILOAD, varCount);
        mv.visitMethodInsn(INVOKEINTERFACE, LIMIT_DIALECT_NAME, "process",
                "(Ljava/lang/String;II)L" + LIMIT_QUERYINFO_NAME + ";", true);
        mv.visitVarInsn(ASTORE, varLimitInfo);

        // preparedStatement
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getSql", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);

        // 为PreparedStatement设置参数
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParam);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;[L" + QUERY_PARAM_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitVarInsn(ALOAD, varParam);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, varLimitInfo);
        mv.visitMethodInsn(INVOKEVIRTUAL, LIMIT_QUERYINFO_NAME, "getParams", "()[Ljava/lang/Object;", false);
        mv.visitMethodInsn(INVOKESTATIC, daoName, "setPreparedParams",
                "(Ljava/sql/PreparedStatement;I[L" + QUERY_PARAM_NAME + ";)V", false);

        // 执行executeQuery
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeQuery", "()Ljava/sql/ResultSet;");
        mv.visitVarInsn(ASTORE, varResultSet);

        // 获取为entity赋值的函数实例
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getFieldsSql",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ASTORE, varFieldKey);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldKey);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/String;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, FIELD_SET_FUNC_NAME);
        mv.visitVarInsn(ASTORE, varFunc);

        // 如果func为空则使创建func
        mv.visitVarInsn(ALOAD, varFunc);
        Label lbFuncNotNull = new Label();
        mv.visitJumpInsn(IFNONNULL, lbFuncNotNull);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitVarInsn(ALOAD, varFieldKey);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getSqlFieldSetFunc",
                "(Ljava/sql/ResultSet;Ljava/lang/String;)L" + FIELD_SET_FUNC_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varFunc);
        mv.visitFieldInsn(GETSTATIC, daoName, "FIELD_SET_FUNCS", "Ljava/util/Map;");
        mv.visitVarInsn(ALOAD, varFieldKey);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(POP);

        mv.visitLabel(lbFuncNotNull);
        // 初始化List
        mv.visitLabel(lbFuncNotNull);
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varResList);

        // while (rs.next) 循环
        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
        Label lbReturn = new Label();
        mv.visitJumpInsn(IFEQ, lbReturn);

        // 新建entity实例并且赋值
        mv.visitTypeInsn(NEW, entityName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, entityName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varEntity);
        mv.visitVarInsn(ALOAD, varFunc);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitVarInsn(ALOAD, varResultSet);
        mv.visitMethodInsn(INVOKEINTERFACE, FIELD_SET_FUNC_NAME, "set", "(Ljava/lang/Object;Ljava/sql/ResultSet;)V", true);

        // 添加到List中
        mv.visitVarInsn(ALOAD, varResList);
        mv.visitVarInsn(ALOAD, varEntity);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, lbWhile);

        mv.visitLabel(lbReturn);

        mv.visitLabel(label1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);

        mv.visitVarInsn(ALOAD, varResList);
        mv.visitInsn(ARETURN);

        mv.visitLabel(label2);
        mv.visitFrame(Opcodes.F_FULL, 6, new Object[] {daoName, "java/lang/String", Opcodes.INTEGER,
                        Opcodes.INTEGER, "[Ljava/lang/Object;", STMT_SESSION_NAME}, 1,
                new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, varEx);

        mv.visitLabel(label3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "closeStatmentSession", "(L" + STMT_SESSION_NAME + ";)V", false);
        mv.visitVarInsn(ALOAD, varEx);
        mv.visitInsn(ATHROW);

        mv.visitMaxs(4, 14);
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

        QueryInfo queryInfo = DaoUtil.getQueryByIdInfo(entity);

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
                    mv.visitTypeInsn(CHECKCAST, toInternalName(DaoUtil.getBoxedName(idInfo.getField().getType())));
                    visitUnboxOpcode(mv, idInfo.getField());
                } else {
                    visitBoxedAndGetValue(mv, idInfo.getField());
                }
            } else {
                String desc = getDescriptor(queryInfo.getIdInfo().getField().getType());
                if (desc.startsWith("L")) {
                    desc = desc.substring(1, desc.length()-1);
                }
                mv.visitTypeInsn(CHECKCAST, desc);
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

        for (int i=0;i<queryInfo.getAllColumns().size();i++) {
            JdbcInfo jdbcInfo = queryInfo.getAllColumns().get(i);
            mv.visitVarInsn(ALOAD, 5);
            mv.visitVarInsn(ALOAD, 4);
            visitMethodVisitIntValue(mv, i+1);
            String getMethod = "get" + jdbcInfo.getJdbcMethod().substring(3);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", getMethod,
                    "(I)" + jdbcInfo.getJdbcType(), true);
            String fieldName = jdbcInfo.getField().getName();
            String setMethod = "set" + fieldName.substring(0,1).toUpperCase(Locale.ENGLISH)
                    + fieldName.substring(1);
            if (jdbcInfo.isNeedUnbox()) {
                visitBoxedOpcode(mv, jdbcInfo.getField());
            }
            if (!jdbcInfo.isBaseType() && !jdbcInfo.getJdbcType().equals(jdbcInfo.getFieldType())) {
                mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_NAME, Convertor.getConvertMethodName(jdbcInfo.getFieldType()),
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

        }
    }
}
