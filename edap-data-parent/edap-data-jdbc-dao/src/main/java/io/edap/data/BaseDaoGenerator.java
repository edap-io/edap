package io.edap.data;

import io.edap.data.model.JdbcInfo;
import io.edap.data.model.QueryInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.util.Locale;

import static io.edap.data.util.DaoUtil.getBoxedName;
import static io.edap.data.util.DaoUtil.getQueryByIdInfo;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.visitMethod;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ARETURN;

public class BaseDaoGenerator {

    protected static String STMT_SESSION_NAME = toInternalName(StatementSession.class.getName());

    protected ClassWriter cw;

    protected Class<?> entity;

    protected String entityName;

    protected String daoName;

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
