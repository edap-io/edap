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

import io.edap.data.jdbc.model.InsertInfo;
import io.edap.data.jdbc.model.JdbcInfo;
import io.edap.data.jdbc.model.UpdateInfo;
import io.edap.data.jdbc.util.DaoUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static io.edap.data.jdbc.util.Convertor.getConvertMethodName;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

/**
 * Dao实现类的生成器
 */
public class JdbcEntityDaoGenerator extends BaseDaoGenerator {


    private static String ENTITY_IFACT_NAME = toInternalName(JdbcEntityDao.class.getName());

    public JdbcEntityDaoGenerator(Class entity, DaoOption daoOption) {
        super();
        this.entity = entity;
        this.entityName = toInternalName(entity.getName());
        this.daoName = toInternalName(DaoUtil.getEntityDaoName(entity));
        this.PARENT_NAME = toInternalName(JdbcBaseEntityDao.class.getName());
        this.daoOption = daoOption;
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

        visitFillSqlFieldMethod();

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

        visitiDeleteNoParamMethod();
        visitiDeleteObjectParamsMethod();
        visitiDeleteQueryParamsMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitUpdateByIdMethod() {

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "updateById", "(L" + entityName + ";)I", null,
                new String[] { "java/lang/Exception" });
        mv.visitCode();

        UpdateInfo updateInfo = DaoUtil.getUpdateByIdSql(entity, daoOption);

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
                if (!jdbcInfo.isBaseType() && !jdbcInfo.getJdbcType().equals(jdbcInfo.getFieldType())) {
                    mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_NAME, getConvertMethodName(jdbcInfo.getJdbcType()),
                            "(" + jdbcInfo.getFieldType() + ")" + jdbcInfo.getJdbcType(), false);
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

    private void visitInsertListMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "insert", "(Ljava/util/List;)[I",
                "(Ljava/util/List<L" + entityName + ";>;)[I", new String[] { "java/lang/Exception" });

        InsertInfo insertInfo = DaoUtil.getInsertSql(entity, daoOption);
        Field idField = insertInfo.getIdField();
        Method idSetMethod = insertInfo.getIdSetMethod();
        Method idGetMethod = insertInfo.getIdGetMethod();

        mv.visitCode();
        Label lbInnerTry     = new Label();
        Label lbInnerFinally = new Label();
        Label lbInnerThrow   = new Label();
        mv.visitTryCatchBlock(lbInnerTry, lbInnerFinally, lbInnerThrow, null);

        int varSession    = 2;
        int varPstmt      = varSession + 1;
        int varHasIdVal   = varPstmt + 1;
        int varAutoCommit = varHasIdVal + 1;
        int varListSize   = varAutoCommit + 1;
        int varForIndex   = varListSize + 1;
        int varEntity     = varForIndex + 1;
        int varEntitySeq  = varEntity  + 1;
        int varRows       = varEntitySeq + 1;
        int varIdRs       = varRows + 1;
        int varIdIndex    = varIdRs + 1;

        Label lbOuterFinally = new Label();
        mv.visitTryCatchBlock(lbInnerThrow, lbOuterFinally, lbInnerThrow, null);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, COLLECTION_UTIL_NAME, "isEmpty", "(Ljava/util/Collection;)Z", false);
        Label l4 = new Label();
        mv.visitJumpInsn(IFEQ, l4);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(l4);

        // 如果保存的对象列表不为空
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "getStatementSession", "()L" + STMT_SESSION_NAME + ";", false);
        mv.visitVarInsn(ASTORE, varSession);
        mv.visitLabel(lbInnerTry);

        // 判断是否设置了主键
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, entityName);
        String idType = getDescriptor(idGetMethod.getReturnType());
        mv.visitMethodInsn(INVOKEVIRTUAL, entityName, idGetMethod.getName(),
                "()" + idType, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, daoName, "hasIdValue", "(" + idType + ")Z",
                false);
        mv.visitVarInsn(ISTORE, varHasIdVal);
        mv.visitVarInsn(ILOAD, varHasIdVal);

        // 如果有设置主键则使用带有主键字段的PreparedStatement，否则使用不带主键字段的PreparedStatement
        Label varLbhasId = new Label();
        mv.visitJumpInsn(IFEQ, varLbhasId);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitLdcInsn(insertInfo.getInsertSql());
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);
        Label varlbAutoCommit = new Label();
        mv.visitJumpInsn(GOTO, varlbAutoCommit);

        // 没有设置id的PreparedStatement
        mv.visitLabel(varLbhasId);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitLdcInsn(insertInfo.getNoIdInsertSql());
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "prepareStatement",
                "(Ljava/lang/String;I)Ljava/sql/PreparedStatement;", true);
        mv.visitVarInsn(ASTORE, varPstmt);

        // 判断Connection的autoCommit
        mv.visitLabel(varlbAutoCommit);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "getAutoCommit", "()Z", true);
        mv.visitVarInsn(ISTORE, varAutoCommit);
        mv.visitVarInsn(ILOAD, varAutoCommit);
        Label l5 = new Label();
        mv.visitJumpInsn(IFEQ, l5);
        mv.visitVarInsn(ALOAD, varSession);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEINTERFACE, STMT_SESSION_NAME, "setAutoCommit", "(Z)V", true);

        // 清理批处理的上下文开始执行批处理
        mv.visitLabel(l5);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "clearBatch", "()V", true);

        // 循环对象列表，为PreparedStatement进行赋值
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        mv.visitVarInsn(ISTORE, varListSize);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varForIndex);
        Label l6 = new Label();
        mv.visitLabel(l6);
        mv.visitVarInsn(ILOAD, varForIndex);
        mv.visitVarInsn(ILOAD, varListSize);
        Label l7 = new Label();
        mv.visitJumpInsn(IF_ICMPGE, l7);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, varForIndex);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, entityName);
        mv.visitVarInsn(ASTORE, varEntity);

        int pos = 1;
        List<JdbcInfo> jdbcInfos = DaoUtil.getJdbcInfos(entity);
        JdbcInfo idJdbcInfo = null;
        for (JdbcInfo jdbcInfo : jdbcInfos) {
            if (idField != null && jdbcInfo.getField().getName().equals(idField.getName())) {
                idJdbcInfo = jdbcInfo;
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
            if (!jdbcInfo.isBaseType() && !jdbcInfo.getJdbcType().equals(jdbcInfo.getFieldType())) {
                mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_NAME, getConvertMethodName(jdbcInfo.getJdbcType()),
                        "(" + jdbcInfo.getFieldType() + ")" + jdbcInfo.getJdbcType(), false);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement",
                    jdbcInfo.getJdbcMethod(), "(I" + jdbcInfo.getJdbcType() + ")V", true);
            pos++;
        }
        // 如果主键不为空则为jdbc设置主键的值
        if (DaoUtil.isAutoIncrementType(idField.getType()) && idJdbcInfo != null) {
            mv.visitVarInsn(ILOAD, varHasIdVal);
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

        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "addBatch", "()V", true);
        mv.visitIincInsn(varForIndex, 1);
        mv.visitJumpInsn(GOTO, l6);
        mv.visitLabel(l7);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitVarInsn(ALOAD, varPstmt);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement", "executeBatch", "()[I", true);
        mv.visitVarInsn(ASTORE, varRows);

        // 如果没有设置主键则获取自增主键并将主键赋值给对应的持久化对象
        if (DaoUtil.isAutoIncrementType(idField.getType())) {
            Label lbAutoCommit = new Label();
            mv.visitVarInsn(ILOAD, varHasIdVal);
            mv.visitJumpInsn(IFNE, lbAutoCommit);
            mv.visitVarInsn(ALOAD, varPstmt);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement",
                    "getGeneratedKeys", "()Ljava/sql/ResultSet;", true);
            mv.visitVarInsn(ASTORE, varIdRs);
            mv.visitVarInsn(ALOAD, varIdRs);
            mv.visitJumpInsn(IFNULL, lbAutoCommit);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, varIdIndex);

            //  遍历为持久化对象的主键赋值
            Label varLbIdWhile = new Label();
            mv.visitLabel(varLbIdWhile);
            mv.visitVarInsn(ALOAD, varIdRs);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "next",
                    "()Z", true);
            Label varLbCloseRs = new Label();
            mv.visitJumpInsn(IFEQ, varLbCloseRs);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, varIdIndex);
            mv.visitIincInsn(varIdIndex, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get",
                    "(I)Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, entityName);
            mv.visitVarInsn(ALOAD, varIdRs);
            mv.visitInsn(ICONST_1);
            String jdbcGetMethod = idJdbcInfo.getJdbcMethod().replace("set", "get");
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", jdbcGetMethod,
                    "(I)" + idJdbcInfo.getJdbcType(), true);
            if (idJdbcInfo.isNeedUnbox()) {
                visitBoxedOpcode(mv, idJdbcInfo.getField());
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, entityName, idSetMethod.getName(),
                    "(" + getDescriptor(idField.getType()) + ")V", false);
            mv.visitJumpInsn(GOTO, varLbIdWhile);

            // 关闭获取自增主键的ResultSet对象
            mv.visitLabel(varLbCloseRs);
            mv.visitVarInsn(ALOAD, varIdRs);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/ResultSet", "close",
                    "()V", true);
            mv.visitLabel(lbAutoCommit);
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
        mv.visitVarInsn(ALOAD, varRows);
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

    private void visitiDeleteNoParamMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "delete", "(Ljava/lang/String;)I",
                null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(DaoUtil.getTableName(entity));
        mv.visitMethodInsn(INVOKESTATIC, daoName, "getFullDeleteSql",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "delete",
                "(Ljava/lang/String;)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
    }

    private void visitiDeleteObjectParamsMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "delete", "(Ljava/lang/String;[Ljava/lang/Object;)I",
                null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(DaoUtil.getTableName(entity));
        mv.visitMethodInsn(INVOKESTATIC, daoName, "getFullDeleteSql",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "delete",
                "(Ljava/lang/String;[Ljava/lang/Object;)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
    }

    private void visitiDeleteQueryParamsMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "delete", "(Ljava/lang/String;[Lio/edap/data/QueryParam;)I",
                null, new String[] { "java/lang/Exception" });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn(DaoUtil.getTableName(entity));
        mv.visitMethodInsn(INVOKESTATIC, daoName, "getFullDeleteSql",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "delete",
                "(Ljava/lang/String;[Lio/edap/data/QueryParam;)I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(3, 2);
        mv.visitEnd();
    }

    private void visitInsertMethod() {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "insert", "(L" + entityName + ";)I", null,
                new String[] {"java/lang/Exception" });

        InsertInfo insertInfo = DaoUtil.getInsertSql(entity, daoOption);
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
        if (DaoUtil.isAutoIncrementType(idField.getType())) {
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
        List<JdbcInfo> jdbcInfos = DaoUtil.getJdbcInfos(entity);
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
            if (!jdbcInfo.isBaseType() && !jdbcInfo.getJdbcType().equals(jdbcInfo.getFieldType())) {
                mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_NAME, getConvertMethodName(jdbcInfo.getJdbcType()),
                        "(" + jdbcInfo.getFieldType() + ")" + jdbcInfo.getJdbcType(), false);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/sql/PreparedStatement",
                    jdbcInfo.getJdbcMethod(), "(I" + jdbcInfo.getJdbcType() + ")V", true);
            pos++;
        }
        // 如果主键不为空则为jdbc设置主键的值
        if (DaoUtil.isAutoIncrementType(idField.getType()) && idJdbcInfo != null) {
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

        if (DaoUtil.isAutoIncrementType(idField.getType())) {
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

}