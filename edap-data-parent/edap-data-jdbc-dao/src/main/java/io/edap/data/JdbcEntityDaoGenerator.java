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

import io.edap.data.annotation.GenerationType;
import io.edap.data.model.InsertInfo;
import io.edap.data.model.JdbcInfo;
import io.edap.data.model.QueryInfo;
import io.edap.data.model.UpdateInfo;
import io.edap.util.CollectionUtils;
import io.edap.util.Constants;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;

import static io.edap.data.util.DaoUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

/**
 * Dao实现类的生成器
 */
public class JdbcEntityDaoGenerator {

    private static String PARENT_NAME = toInternalName(JdbcBaseDao.class.getName());
    private static String ENTITY_IFACT_NAME = toInternalName(JdbcEntityDao.class.getName());
    private static String FIELD_SET_FUNC_NAME = toInternalName(JdbcFieldSetFunc.class.getName());
    private static String REGISTER_NAME = toInternalName(JdbcDaoRegister.class.getName());
    private static String QUERY_PARAM_NAME = toInternalName(QueryParam.class.getName());
    private static String STMT_SESSION_NAME = toInternalName(StatementSession.class.getName());
    private static String COLLECTION_UTIL_NAME = toInternalName(CollectionUtils.class.getName());
    private static String CONSTANTS_NAME = toInternalName(Constants.class.getName());

    private Class<?> entity;
    private String entityName;
    private String databaseType;
    private ClassWriter cw;
    private String daoName;

    public JdbcEntityDaoGenerator(Class entity, String databaseType) {
        this.entity = entity;
        this.entityName = toInternalName(entity.getName());
        this.databaseType = databaseType;
        this.daoName = toInternalName(getEntityDaoName(entity));
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = daoName;

        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        String daoDescriptor = "L" + PARENT_NAME + ";L" + ENTITY_IFACT_NAME + "<L"
                + toInternalName(entity.getName()) + ";>;";
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, daoName,
                daoDescriptor, PARENT_NAME, new String[]{ENTITY_IFACT_NAME});

        visitInitMethod();
        visitClinitMethod();

        visitInsertListMethod();
        visitInsertMethod();
        visitInsertBridgeMethod();

        visitQueryOneParamMethod();
        visitGetSqlFieldSetFuncMethod();
        visitQueryTwoParamMethod();
        visitQueryObjectArrayMethod();

        visitFindOneOneParamMethod();
        visitFindOneTwoParamMethod();
        visitFindOneObjectArrayMethod();
        visitFindOneObjectArrayBridgeMethod();

        visitFindOneOneParamBridgeMethod();
        visitFindOneTwoParamBridgeMethod();

        visitUpdateByIdBridgeMethod();
        visitUpdateByIdMethod();

        visitFindByIdBridgeMethod();
        visitFindByIdMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitQueryObjectArrayMethod() {
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

    private void visitFindByIdMethod() {
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
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
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

    private void visitFindByIdBridgeMethod() {
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

    private void visitUpdateByIdMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "updateById", "(L" + entityName + ";)I", null,
                new String[] { "java/lang/Exception" });
        mv.visitCode();

        UpdateInfo updateInfo = getUpdateByIdSql(entity);

        Label lbInnerTry     = new Label();
        Label lbInnerFinally = new Label();
        Label lbInnerThrow   = new Label();
        mv.visitTryCatchBlock(lbInnerTry, lbInnerFinally, lbInnerThrow, null);

        int varSession    = 2;
        int varSql        = varSession + 1;
        int varPstmt      = varSql + 1;
        int varRows       = varPstmt + 1;
        int varRowsFinal  = varPstmt + 1;

        Label lbOuterFinally = new Label();
        mv.visitTryCatchBlock(lbInnerThrow, lbOuterFinally, lbInnerThrow, null);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varSession);
        mv.visitLabel(lbInnerTry);
        mv.visitLdcInsn(updateInfo.getUpdateSql());
        mv.visitVarInsn(ASTORE, varSql);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitVarInsn(ALOAD, varSql);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement", "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);

        List<JdbcInfo> upFields = updateInfo.getUpdateColumns();
        int count = 0;
        if (!CollectionUtils.isEmpty(upFields)) {
            count += upFields.size();
            for (int i=0;i<upFields.size();i++) {
                JdbcInfo jdbcInfo = upFields.get(i);
                mv.visitVarInsn(ALOAD, varPstmt);
                visitIntInsn(i+1, mv);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, entityName, jdbcInfo.getValueMethod().getName(),
                        "()" + jdbcInfo.getFieldType(), false);
                if (jdbcInfo.isNeedUnbox()) {
                    visitUnboxOpcode(mv, jdbcInfo.getField());
                }
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", jdbcInfo.getJdbcMethod(),
                        "(I" + jdbcInfo.getJdbcType() + ")V", true);
            }
        }

        List<JdbcInfo> idFields = updateInfo.getWhereColumns();
        if (!CollectionUtils.isEmpty(idFields)) {
            for (int i=0;i<idFields.size();i++) {
                JdbcInfo jdbcInfo = idFields.get(i);
                mv.visitVarInsn(ALOAD, varPstmt);
                visitIntInsn(count+i+1, mv);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, entityName, jdbcInfo.getValueMethod().getName(),
                        "()" + jdbcInfo.getFieldType(), false);
                if (jdbcInfo.isNeedUnbox()) {
                    visitUnboxOpcode(mv, jdbcInfo.getField());
                }
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", jdbcInfo.getJdbcMethod(),
                        "(I" + jdbcInfo.getJdbcType() + ")V", true);
            }
        }

        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeUpdate", "()I", true);
        mv.visitVarInsn(ISTORE, varRows);
        mv.visitLabel(lbInnerFinally);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ILOAD, varRows);
        mv.visitInsn(IRETURN);
        mv.visitLabel(lbInnerThrow);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[] {daoName, entityName, STMT_SESSION_NAME}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, varRowsFinal);
        mv.visitLabel(lbOuterFinally);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ALOAD, varRowsFinal);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(4, 7);
        mv.visitEnd();


    }

    private void visitFindOneTwoParamMethod() {
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

    private void visitFindOneObjectArrayMethod() {
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

    private void visitFindOneOneParamMethod() {
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

    private void visitQueryTwoParamMethod() {
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

    private void visitGetSqlFieldSetFuncMethod() {

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

    private void visitQueryOneParamMethod() {

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

    private void visitUpdateByIdBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "updateById",
                "(Ljava/lang/Object;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "updateById", "(L" + entityName + ";)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private void visitFindOneObjectArrayBridgeMethod() {
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

    private void visitFindOneTwoParamBridgeMethod() {
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

    private void visitFindOneOneParamBridgeMethod() {
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

    private void visitClinitMethod() {
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

    private void visitInsertListMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "insert", "(Ljava/util/List;)[I",
                "(Ljava/util/List<L" + entityName + ";>;)[I", new String[] { "java/lang/Exception" });

        InsertInfo insertInfo = getInsertSql(entity);
        Field idField = insertInfo.getIdField();
        Method idSetMethod = insertInfo.getIdSetMethod();

        mv.visitCode();
        Label lbInnerTry     = new Label();
        Label lbInnerFinally = new Label();
        Label lbInnerThrow   = new Label();
        mv.visitTryCatchBlock(lbInnerTry, lbInnerFinally, lbInnerThrow, null);

        int varSession    = 2;
        int varPstmt      = varSession + 1;
        int varAutoCommit = varPstmt + 1;
        int varListSize   = varAutoCommit + 1;
        int varForIndex   = varListSize + 1;
        int varEntity     = varForIndex + 1;
        int varEntitySeq  = varEntity  + 1;
        int varRows       = varEntitySeq + 1;

        Label lbOuterFinally = new Label();
        mv.visitTryCatchBlock(lbInnerThrow, lbOuterFinally, lbInnerThrow, null);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, COLLECTION_UTIL_NAME, "isEmpty", "(Ljava/util/Collection;)Z", false);
        Label l4 = new Label();
        mv.visitJumpInsn(IFEQ, l4);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l4);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varSession);
        mv.visitLabel(lbInnerTry);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitLdcInsn(insertInfo.getInsertSql());
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement", "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "getAutoCommit", "()Z", true);
        mv.visitVarInsn(ISTORE, varAutoCommit);
        mv.visitVarInsn(ILOAD, varAutoCommit);
        Label l5 = new Label();
        mv.visitJumpInsn(IFEQ, l5);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "setAutoCommit", "(Z)V", true);
        mv.visitLabel(l5);
        mv.visitFrame(Opcodes.F_APPEND,3, new Object[] {STMT_SESSION_NAME, "java/sql/PreparedStatement", Opcodes.INTEGER}, 0, null);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "clearBatch", "()V", true);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        mv.visitVarInsn(ISTORE, varListSize);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varForIndex);
        Label l6 = new Label();
        mv.visitLabel(l6);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {Opcodes.INTEGER, Opcodes.INTEGER}, 0, null);
        mv.visitVarInsn(ILOAD, varForIndex);
        mv.visitVarInsn(ILOAD, varListSize);
        Label l7 = new Label();
        mv.visitJumpInsn(IF_ICMPGE, l7);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitVarInsn(ASTORE, varEntity);

        int pos = 1;
        List<JdbcInfo> jdbcInfos = getJdbcInfos(entity);
        for (JdbcInfo jdbcInfo : jdbcInfos) {
            if (insertInfo.getGenerationType() == GenerationType.IDENTITY &&
                    idField.getName().equals(jdbcInfo.getField().getName())) {
                continue;
            }
            mv.visitVarInsn(ALOAD, varPstmt);
            visitIntInsn(pos, mv);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, jdbcInfo.getValueMethod().getName(),
                    "()" + jdbcInfo.getFieldType(), false);
            if (jdbcInfo.isNeedUnbox()) {
                visitUnboxOpcode(mv, jdbcInfo.getField());
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement",
                    jdbcInfo.getJdbcMethod(), "(I" + jdbcInfo.getJdbcType() + ")V", true);
            pos++;
        }

        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "addBatch", "()V", true);
        mv.visitIincInsn(varForIndex, 1);
        mv.visitJumpInsn(GOTO, l6);
        mv.visitLabel(l7);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeBatch", "()[I", true);
        mv.visitVarInsn(ASTORE, varForIndex);

        Label lbAutoCommit = new Label();

        if (insertInfo.getGenerationType() == GenerationType.IDENTITY) {
            mv.visitVarInsn(ALOAD, varPstmt);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "getGeneratedKeys", "()Ljava/sql/ResultSet;", true);
            mv.visitVarInsn(ASTORE, varEntity);
            mv.visitVarInsn(ALOAD, varEntity);

            mv.visitJumpInsn(IFNULL, lbAutoCommit);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, varEntitySeq);
            Label l9 = new Label();
            mv.visitLabel(l9);
            mv.visitFrame(Opcodes.F_APPEND, 3, new Object[]{"[I", "java/sql/ResultSet", Opcodes.INTEGER}, 0, null);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);
            Label l10 = new Label();
            mv.visitJumpInsn(IFEQ, l10);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, varEntitySeq);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, entityName);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitInsn(ICONST_1);

            if ("int".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
            } else if ("long".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getLong", "(I)J", true);
            } else if ("java.lang.Long".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getLong", "(I)J", true);
                visitBoxedOpcode(mv, idField);
            } else if ("java.lang.Integer".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
                visitBoxedOpcode(mv, idField);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, idSetMethod.getName(),
                    "(" + getDescriptor(idField.getType()) + ")V", false);

            mv.visitIincInsn(varEntitySeq, 1);
            mv.visitJumpInsn(GOTO, l9);
            mv.visitLabel(l10);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "close", "()V", true);
            mv.visitLabel(lbAutoCommit);
            mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        }

        mv.visitVarInsn(ILOAD, varAutoCommit);
        Label l11 = new Label();
        mv.visitJumpInsn(IFEQ, l11);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "commit", "()V", true);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "setAutoCommit", "(Z)V", true);
        mv.visitLabel(l11);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, varForIndex);
        mv.visitVarInsn(ASTORE, varEntitySeq);
        mv.visitLabel(lbInnerFinally);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ALOAD, varEntitySeq);
        mv.visitInsn(ARETURN);
        mv.visitLabel(lbInnerThrow);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[] {daoName, "java/util/List", STMT_SESSION_NAME}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, varRows);
        mv.visitLabel(lbOuterFinally);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ALOAD, varRows);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(4, 10);
        mv.visitEnd();

    }

    private void visitInsertMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "insert", "(L" + entityName + ";)I", null,
                new String[] {"java/lang/Exception" });

        InsertInfo insertInfo = getInsertSql(entity);
        Field idField = insertInfo.getIdField();
        Method idSetMethod = insertInfo.getIdSetMethod();
        Method idGetMethod = insertInfo.getIdGetMethod();

        mv.visitCode();
        Label lbInnerTry     = new Label();
        Label lbInnerFinally = new Label();
        Label lbInnerThrow   = new Label();
        mv.visitTryCatchBlock(lbInnerTry, lbInnerFinally, lbInnerThrow, null);

        Label lbOuterFinally = new Label();
        mv.visitTryCatchBlock(lbInnerThrow, lbOuterFinally, lbInnerThrow, null);

        int varEntity     = 1;
        int varSession    = varEntity + 1;
        int varPstmt      = varSession + 1;
        int varRows       = varPstmt + 1;
        int varHasIdValue = varRows + 1;
        int varAutoKeysRs = varHasIdValue + 1;
        int varRowsReturn = varAutoKeysRs + 1;
        int varRowsFinal  = varRowsReturn + 1;

        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varSession);

        mv.visitLabel(lbInnerTry);

        // 如果主键是int，lang类型没有设置值的话忽略jdbc的主键的赋值使用自增主键的方式填充主键的值
        Label varLbHasIdValue;
        Label varLbJdbcSet;
        if (isAutoIncrementType(idField.getType())) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, idGetMethod.getName(),
                    "()" + getDescriptor(idField.getGenericType()), false);
            mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "hasIdValue",
                    "(" + getDescriptor(idField.getGenericType()) + ")Z", false);
            mv.visitVarInsn(ISTORE, varHasIdValue);
            mv.visitVarInsn(ILOAD, varHasIdValue);
            varLbHasIdValue = new Label();
            mv.visitJumpInsn(IFEQ, varLbHasIdValue);
            mv.visitVarInsn(ALOAD, varSession);
            mv.visitLdcInsn(insertInfo.getInsertSql());
            mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                    "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
            mv.visitVarInsn(ASTORE, varPstmt);
            varLbJdbcSet = new Label();
            mv.visitJumpInsn(GOTO, varLbJdbcSet);

            mv.visitLabel(varLbHasIdValue);
            mv.visitVarInsn(ALOAD, varSession);
            mv.visitLdcInsn(insertInfo.getNoIdInsertSql());
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                    "(Ljava/lang/String;I)Ljava/sql/PreparedStatement;", true);
            mv.visitVarInsn(ASTORE, varPstmt);

            mv.visitLabel(varLbJdbcSet);
        } else {
            mv.visitVarInsn(ALOAD, varSession);
            mv.visitLdcInsn(insertInfo.getInsertSql());
            mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                    "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
            mv.visitVarInsn(ASTORE, varPstmt);
        }

        // 循环持久化bean的字段分别进行设置
        int pos = 1;
        int pstmtVar = 3;
        List<JdbcInfo> jdbcInfos = getJdbcInfos(entity);
        JdbcInfo idJdbcInfo = null;
        for (JdbcInfo jdbcInfo : jdbcInfos) {
            if (idField != null && jdbcInfo.getField().getName().equals(idField.getName())) {
                idJdbcInfo = jdbcInfo;
                continue;
            }
            mv.visitVarInsn(ALOAD, pstmtVar);
            visitIntInsn(pos, mv);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, jdbcInfo.getValueMethod().getName(),
                    "()" + jdbcInfo.getFieldType(), false);
            if (jdbcInfo.isNeedUnbox()) {
                visitUnboxOpcode(mv, jdbcInfo.getField());
            }

            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement",
                    jdbcInfo.getJdbcMethod(), "(I" + jdbcInfo.getJdbcType() + ")V", true);
            pos++;
        }
        // 如果主键不为空则为jdbc设置主键的值
        if (isAutoIncrementType(idField.getType()) && idJdbcInfo != null) {
            mv.visitVarInsn(ILOAD, varHasIdValue);
            Label varLbSetIdJdbc = new Label();
            mv.visitJumpInsn(IFEQ, varLbSetIdJdbc);
            mv.visitVarInsn(ALOAD, varPstmt);
            visitIntInsn(pos, mv);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, idGetMethod.getName(),
                    "()" + getDescriptor(idField.getGenericType()), false);
            if (idJdbcInfo.isNeedUnbox()) {
                visitUnboxOpcode(mv, idJdbcInfo.getField());
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement",
                    idJdbcInfo.getJdbcMethod(), "(I" + idJdbcInfo.getJdbcType() + ")V", true);
            mv.visitLabel(varLbSetIdJdbc);
        }
        mv.visitVarInsn(ALOAD, pstmtVar);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeUpdate", "()I", true);
        mv.visitVarInsn(ISTORE, varRows);

        if (isAutoIncrementType(idField.getType())) {
            mv.visitVarInsn(ILOAD, varHasIdValue);
            Label varLbNoId = new Label();
            mv.visitJumpInsn(IFNE, varLbNoId);
            Method idsetMethod = insertInfo.getIdSetMethod();
            mv.visitVarInsn(ALOAD, pstmtVar);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "getGeneratedKeys", "()Ljava/sql/ResultSet;", true);

            mv.visitVarInsn(ASTORE, varAutoKeysRs);
            mv.visitVarInsn(ALOAD, varAutoKeysRs);

            // getGeneratedKeys resultSet 是否为空
            Label labelReturnRow = new Label();
            mv.visitJumpInsn(IFNULL, labelReturnRow);

            mv.visitVarInsn(ALOAD, varAutoKeysRs);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next", "()Z", true);

            Label lbKeysRsClose = new Label();
            mv.visitJumpInsn(IFEQ, lbKeysRsClose);
            mv.visitVarInsn(ALOAD, varEntity);
            mv.visitVarInsn(ALOAD, varAutoKeysRs);
            mv.visitInsn(ICONST_1);
            if ("int".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
            } else if ("long".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getLong", "(I)J", true);
            } else if ("java.lang.Long".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getLong", "(I)J", true);
                visitBoxedOpcode(mv, idField);
            } else if ("java.lang.Integer".equals(idField.getType().getName())) {
                mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
                visitBoxedOpcode(mv, idField);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, idsetMethod.getName(),
                    "(" + getDescriptor(idField.getType()) + ")V", false);

            // 关闭ResultSet对象
            mv.visitLabel(lbKeysRsClose);
            mv.visitFrame(Opcodes.F_FULL, 6, new Object[]{daoName, entityName, STMT_SESSION_NAME,
                    "java/sql/PreparedStatement", Opcodes.INTEGER, "java/sql/ResultSet"}, 0, new Object[]{});
            mv.visitVarInsn(ALOAD, varAutoKeysRs);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "close", "()V", true);


            mv.visitLabel(labelReturnRow);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

            mv.visitLabel(varLbNoId);
        }
        mv.visitVarInsn(ILOAD, varRows);
        mv.visitVarInsn(ISTORE, varRowsReturn);

        mv.visitLabel(lbInnerFinally);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ILOAD, varRowsReturn);
        mv.visitInsn(IRETURN);
        mv.visitLabel(lbInnerThrow);
        mv.visitFrame(Opcodes.F_FULL, 3, new Object[] {daoName, entityName, STMT_SESSION_NAME}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, varRowsFinal);
        mv.visitLabel(lbOuterFinally);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "close", "()V", true);
        mv.visitVarInsn(ALOAD, varRowsFinal);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(4, 8);
        mv.visitEnd();
    }

    private void visitInsertBridgeMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "insert", "(Ljava/lang/Object;)I",
                null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "insert", "(L" + entityName + ";)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private void visitInitMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /**
     * 添加基础数据类型如int,long,double等数据的装箱的opcode
     * @param mv
     * @param field
     */
    private void visitUnboxOpcode(MethodVisitor mv, Field field) {
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

    private void visitBoxedOpcode(MethodVisitor mv, Field field) {
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
                visitMethod(mv, INVOKEVIRTUAL, "java/lang/Double", "valueOf",
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
}
