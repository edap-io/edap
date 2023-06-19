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

package io.edap.data.util;

import io.edap.data.annotation.*;
import io.edap.data.model.*;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import static io.edap.util.ClazzUtil.*;
import static io.edap.util.Constants.EMPTY_LIST;
import static io.edap.util.Constants.EMPTY_STRING;
import static io.edap.util.CryptUtil.md5;
import static io.edap.util.StringUtil.isEmpty;

public class DaoUtil {

    private DaoUtil() {}

    public static QueryInfo getQueryByIdInfo(Class entityClazz) {
        QueryInfo qInfo = new QueryInfo();
        StringBuilder sb = new StringBuilder("select * from ");
        sb.append(getTableName(entityClazz));
        List<JdbcInfo> allColumns = getJdbcInfos(entityClazz);
        qInfo.setAllColumns(allColumns);
        JdbcInfo idField = null;
        if (!CollectionUtils.isEmpty(allColumns)) {
            for (JdbcInfo jdbcInfo : allColumns) {
                if (isIdField(jdbcInfo.getField(), jdbcInfo.getValueMethod())) {
                    idField = jdbcInfo;
                }
            }
        }
        if (idField != null) {
            sb.append(" where ").append(idField.getColumnName()).append("=?");
        }
        qInfo.setQuerySql(sb.toString());
        qInfo.setIdInfo(idField);
        return qInfo;
    }

    public static UpdateInfo getUpdateByIdSql(Class entityClazz) {
        UpdateInfo updateInfo = new UpdateInfo();
        if (entityClazz == null) {
            updateInfo.setUpdateSql(EMPTY_STRING);
            return updateInfo;
        }
        StringBuilder sb = new StringBuilder();

        sb.append("UPDATE ").append(getTableName(entityClazz)).append(" SET ");

        List<JdbcInfo> allColumns = getJdbcInfos(entityClazz);
        List<JdbcInfo> updateColumns = new ArrayList<>();
        List<JdbcInfo> wherColumns = new ArrayList<>();
        JdbcInfo idField = null;
        if (!CollectionUtils.isEmpty(allColumns)) {
            boolean hasSetColumn = false;
            for (JdbcInfo jdbcInfo : allColumns) {
                if (isIdField(jdbcInfo.getField(), jdbcInfo.getValueMethod())) {
                    idField = jdbcInfo;
                    continue;
                }
                if (hasSetColumn) {
                    sb.append(",");
                } else {
                    hasSetColumn = true;
                }
                String column = jdbcInfo.getColumnName();
                sb.append(column).append("=?");
                updateColumns.add(jdbcInfo);
            }
            sb.append(" where ").append(idField.getColumnName()).append("=?");
        }
        updateInfo.setUpdateSql(sb.toString());
        updateInfo.setUpdateColumns(updateColumns);
        wherColumns.add(idField);
        updateInfo.setWhereColumns(wherColumns);
        return updateInfo;
    }

    /**
     * 根据持久化Bean的Class获取该持久化Bean的insert语句
     * @param entityClazz
     * @return
     */
    public static InsertInfo getInsertSql(Class entityClazz) {
        InsertInfo insertInfo = new InsertInfo();
        if (entityClazz == null) {
            insertInfo.setInsertSql(EMPTY_STRING);
            insertInfo.setGenerationType(null);
            return insertInfo;
        }
        StringBuilder sb = new StringBuilder();

        sb.append("INSERT INTO ").append(getTableName(entityClazz)).append(" (");
        ColumnsInfo columnsInfo = getColumns(entityClazz);
        List<String> columns = columnsInfo.getColumns();
        int size = columns.size();
        for (int i=0;i<size;i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(columns.get(i));
        }
        sb.append(") VALUES (");
        for (int i=0;i<size;i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        sb.append(")");

        insertInfo.setIdField(columnsInfo.getIdField());
        insertInfo.setIdSetMethod(columnsInfo.getIdSetMethod());
        insertInfo.setGenerationType(columnsInfo.getGenerationType());
        insertInfo.setInsertSql(sb.toString());
        return insertInfo;
    }

    public static List<JdbcInfo> getJdbcInfos(Class clazz) {
        List<Field> fields = getClassFields(clazz);
        if (CollectionUtils.isEmpty(fields)) {
            return EMPTY_LIST;
        }
        List<Method> methods = getClassMethods(clazz);
        Map<String, Method> fieldMethods = new HashMap<>();
        if (!CollectionUtils.isEmpty(methods)) {
            for (Method m : methods) {
                String typeName = m.getReturnType().getName();
                String fieldName = "";
                String name = m.getName();
                if (m.getName().startsWith("is") && m.getName().length() > 2 && "boolean".equals(typeName)) {
                    fieldName = name.substring(2, 3).toLowerCase(Locale.ENGLISH) + name.substring(3);
                } else if (m.getName().startsWith("get") && m.getName().length() > 3) {
                    fieldName = name.substring(3, 4).toLowerCase(Locale.ENGLISH) + name.substring(4);
                }
                if (!isEmpty(fieldName)) {
                    fieldMethods.put(fieldName, m);
                }
            }
        }
        List<JdbcInfo> jdbcInfos = new ArrayList<>();
        for (Field f : fields) {
            if (Modifier.isFinal(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) {
                continue;
            }

            JdbcInfo jdbcInfo = new JdbcInfo();
            jdbcInfo.setValueMethod(fieldMethods.get(f.getName()));
            JdbcTypeInfo typeInfo = getJdbcType(f, f.getAnnotations());
            jdbcInfo.setJdbcType(typeInfo.type);
            jdbcInfo.setJdbcMethod(typeInfo.setMethod);
            jdbcInfo.setFieldType(getDescriptor(f.getType()));
            jdbcInfo.setNeedUnbox(typeInfo.needUnbox);
            jdbcInfo.setField(f);
            jdbcInfo.setBaseType(typeInfo.isBaseType);
            String columnName;
            Column column = getFieldColumn(f, fieldMethods);
            if (column != null && !StringUtil.isEmpty(column.name())) {
                columnName = column.name();
            } else {
                columnName = toUnderScore(f.getName());
            }
            jdbcInfo.setColumnName(columnName);
            jdbcInfos.add(jdbcInfo);
        }

        return jdbcInfos;
    }

    static class JdbcTypeInfo {
        private String setMethod;
        private String type;
        private boolean needUnbox;
        private boolean isBaseType;
    }

    public static String getBoxedName(Class type) {
        switch (type.getName()) {
            case "byte":
                return "java/lang/Byte";
            case "boolean":
                return "java/lang/Boolean";
            case "short":
                return "java/lang/Short";
            case "int":
                return "java/lang/Integer";
            case "float":
                return "java/lang/Float";
            case "long":
                return "java/lang/Long";
            default:
                return type.getName();
        }
    }

    private static JdbcTypeInfo getJdbcType(Field f, Annotation[] anns) {
        JdbcTypeInfo typeInfo = new JdbcTypeInfo();
        Class<?> type = f.getType();
        switch (type.getName()) {
            case "boolean":
                typeInfo.setMethod = "setBoolean";
                typeInfo.type = "Z";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Boolean":
                typeInfo.setMethod = "setBoolean";
                typeInfo.type = "Z";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "byte":
                typeInfo.setMethod = "setByte";
                typeInfo.type = "B";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Byte":
                typeInfo.setMethod = "setByte";
                typeInfo.type = "B";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "[B":
                typeInfo.setMethod = "setBytes";
                typeInfo.type = "[B";
                return typeInfo;
            case "short":
                typeInfo.setMethod = "setShort";
                typeInfo.type = "S";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Short":
                typeInfo.setMethod = "setShort";
                typeInfo.type = "S";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "int":
                typeInfo.setMethod = "setInt";
                typeInfo.type = "I";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Integer":
                typeInfo.setMethod = "setInt";
                typeInfo.type = "I";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "float":
                typeInfo.setMethod = "setFloat";
                typeInfo.type = "F";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Float":
                typeInfo.setMethod = "setFloat";
                typeInfo.type = "F";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "long":
                typeInfo.setMethod = "setLong";
                typeInfo.type = "J";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Long":
                typeInfo.setMethod = "setLong";
                typeInfo.type = "J";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "double":
                typeInfo.setMethod = "setDouble";
                typeInfo.type = "double";
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.lang.Double":
                typeInfo.setMethod = "setDouble";
                typeInfo.type = "double";
                typeInfo.needUnbox = true;
                typeInfo.isBaseType = true;
                return typeInfo;
            case "java.math.BigDecimal":
                typeInfo.setMethod = "setBigDecimal";
                typeInfo.type = "Ljava/math/BigDecimal;";
                return typeInfo;
            case "java.lang.String":
                typeInfo.setMethod = "setString";
                typeInfo.type = "Ljava/lang/String;";
                return typeInfo;

            default:
                typeInfo.setMethod = "setObject";
                typeInfo.type = "Ljava/lang/Object;";
                return typeInfo;
        }
    }

    public static String getEntityDaoName(Class entity) {
        return "edao." + entity.getName() + "JdbcEntityDao";
    }

    public static String getFieldSetFuncName(Class entity, List<String> columns) {
        StringBuilder sb = new StringBuilder("::");
        if (!CollectionUtils.isEmpty(columns)) {
            for (int i=0;i<columns.size();i++) {
                if (i > 0) {
                    sb.append(":");
                }
                sb.append(columns.get(i));
            }
        }
        return "edao.setfunc.Func_" + md5(entity.getName() + sb);
    }

    /**
     * 获取持久化Bean所有的字段列表
     * @param clazz
     * @return
     */
    public static ColumnsInfo getColumns(Class clazz) {
        ColumnsInfo columnsInfo = new ColumnsInfo();
        List<String> columns = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();
        List<Field> fields = getClassFields(clazz);
        if (CollectionUtils.isEmpty(fields)) {
            columnsInfo.setColumns(EMPTY_LIST);
            columnsInfo.setGenerationType(null);
            return columnsInfo;
        }
        List<Method> methods = getClassMethods(clazz);
        Map<String, Method> fieldGetMethods = new HashMap<>();
        Map<String, Method> fieldSetMethods = new HashMap<>();
        if (!CollectionUtils.isEmpty(methods)) {
            for (Method m : methods) {
                String typeName = m.getReturnType().getName();
                String fieldName;
                String name = m.getName();
                if (m.getName().startsWith("is") &&
                        ("boolean".equals(typeName) || "java.lang.Boolean".equals(typeName))) {
                    fieldName = name.substring(2, 3).toLowerCase(Locale.ENGLISH) + name.substring(3);
                    fieldGetMethods.put(fieldName, m);
                } else if (m.getName().startsWith("get")) {
                    fieldName = name.substring(3, 4).toLowerCase(Locale.ENGLISH) + name.substring(4);
                    fieldGetMethods.put(fieldName, m);
                } else if (m.getName().startsWith("set")) {
                    fieldName = name.substring(3, 4).toLowerCase(Locale.ENGLISH) + name.substring(4);
                    fieldSetMethods.put(fieldName, m);
                }

            }
        }
        GenerationType generationType = null;
        for (Field f : fields) {
            if (Modifier.isFinal(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Id id = getFieldId(f, fieldGetMethods);
            GeneratedValue generatedValue = getFieldGeneratedValue(f, fieldGetMethods);
            GenerationType curType = null;
            if (id != null) {
                columnsInfo.setIdField(f);
                columnsInfo.setIdSetMethod(fieldSetMethods.get(f.getName()));
            }
            if (id != null && generatedValue != null) {
                curType = generationType = generatedValue.strategy();
            }
            if (curType == GenerationType.IDENTITY) {
                continue;
            }
            Column column = getFieldColumn(f, fieldGetMethods);
            String columName;
            if (column != null && !isEmpty(column.name())) {
                columName = column.name();
            } else {
                columName = toUnderScore(f.getName());
            }
            if (id != null) {
                columnsInfo.setIdColumnName(columName);
            }
            columns.add(columName);
            fieldNames.add(f.getName());
        }

        columnsInfo.setColumns(columns);
        columnsInfo.setFieldNames(fieldNames);
        columnsInfo.setGenerationType(generationType);
        return columnsInfo;
    }

    /**
     * 根据持久化Bean的Field以及和Field相关联的get开头的method来获取该属性对应的Column的注解，如果没有注解则返回null
     * @param f 属性的对象
     * @param fieldMethods 所有属性对应的get方法
     * @return
     */
    private static GeneratedValue getFieldGeneratedValue(Field f, Map<String, Method> fieldMethods) {
        GeneratedValue generatedValue = null;
        Annotation[] anns = f.getAnnotations();
        if (anns != null) {
            for (Annotation ann : anns) {
                if (ann instanceof GeneratedValue) {
                    generatedValue = (GeneratedValue) ann;
                }
            }
        }
        if (generatedValue != null) {
            return generatedValue;
        }
        Method m = fieldMethods.get(f.getName());
        if (m != null && m.getAnnotations() != null) {
            for (Annotation ann : m.getAnnotations()) {
                if (ann instanceof GeneratedValue) {
                    generatedValue = (GeneratedValue) ann;
                }
            }
        }
        return generatedValue;
    }

    public static boolean isIdField(Field f, Method fieldGetMethod) {
        boolean isIdField = false;
        if (getFieldIdAnnotation(f) != null) {
            return true;
        }
        if (fieldGetMethod != null && getMethodIdAnnotation(fieldGetMethod) != null) {
            return true;
        }
        if ("id".equals(f.getName())) {
            return true;
        }
        return false;
    }

    public static Id getFieldIdAnnotation(Field field) {
        Annotation[] anns = field.getAnnotations();
        if (anns != null) {
            for (Annotation ann : anns) {
                if (ann instanceof Id) {
                    return (Id)ann;
                }
            }
        }
        return null;
    }

    public static Id getMethodIdAnnotation(Method method) {
        Annotation[] anns = method.getAnnotations();
        if (anns != null) {
            for (Annotation ann : anns) {
                if (ann instanceof Id) {
                    return (Id)ann;
                }
            }
        }
        return null;
    }

    /**
     * 根据持久化Bean的Field以及和Field相关联的get开头的method来获取该属性对应的Column的注解，如果没有注解则返回null
     * @param f 属性的对象
     * @param fieldMethods 所有属性对应的get方法
     * @return
     */
    private static Id getFieldId(Field f, Map<String, Method> fieldMethods) {
        Id id = null;
        Annotation[] anns = f.getAnnotations();
        if (anns != null) {
            for (Annotation ann : anns) {
                if (ann instanceof Id) {
                    id = (Id) ann;
                }
            }
        }
        if (id != null) {
            return id;
        }
        Method m = fieldMethods.get(f.getName());
        if (m != null && m.getAnnotations() != null) {
            for (Annotation ann : m.getAnnotations()) {
                if (ann instanceof Id) {
                    id = (Id) ann;
                }
            }
        }
        return id;
    }

    /**
     * 根据持久化Bean的Field以及和Field相关联的get开头的method来获取该属性对应的Column的注解，如果没有注解则返回null
     * @param f 属性的对象
     * @param fieldMethods 所有属性对应的get方法
     * @return
     */
    private static Column getFieldColumn(Field f, Map<String, Method> fieldMethods) {
        Column column = null;
        Annotation[] anns = f.getAnnotations();
        if (anns != null) {
            for (Annotation ann : anns) {
                if (ann instanceof Column) {
                    column = (Column) ann;
                }
            }
        }
        if (column != null) {
            return column;
        }
        Method m = fieldMethods.get(f.getName());
        if (m != null && m.getAnnotations() != null) {
            for (Annotation ann : m.getAnnotations()) {
                if (ann instanceof Column) {
                    column = (Column) ann;
                }
            }
        }
        return column;
    }

    /**
     * 根据持久化Bean的class对象获取对应的数据表的名称
     * @param entityClazz
     * @return
     */
    public static String getTableName(Class entityClazz) {
        String tableName = EMPTY_STRING;
        Annotation[] anns = entityClazz.getAnnotations();
        for (Annotation ann : anns) {
            if (ann instanceof Table) {
                Table table = (Table) ann;
                if (!isEmpty(table.name())) {
                    tableName = table.name();
                }
            }
        }
        if (isEmpty(tableName)) {
            tableName = toUnderScore(entityClazz.getSimpleName());
        }
        return tableName;
    }

    /**
     * 将驼峰命名的风格字符串转为下划线风格的命名字符串。连续多个大些字母时，如果以连续的大写字母
     * 结束则在第一个大写字母前增加下划线，如果不是以大写字母结束则在第一个大写字母前增加一个下划线
     * 然后再最后一个大写字母前增加一个下划线
     * @param camel
     * @return
     */
    public static String toUnderScore(String camel) {
        if (camel == null || camel.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int len = camel.length();
        for (int i=0;i<len;i++) {
            char c = camel.charAt(i);
            if (isUpperCase(c) && i>0) { //如果为大写字母则判断后面是否是大写字母
                int upCount = getUpperCaseCount(i, camel, len);
                if (upCount + i == len) {
                    if (i == len - 1) {
                        sb.append(toLowerCase(camel.charAt(i)));
                    } else {
                        sb.append("_").append(toLowerCase(c));
                        for (int j = 0; j < upCount - 1; j++) {
                            i++;
                            sb.append(toLowerCase(camel.charAt(i)));
                        }
                    }
                } else {
                    if (i > 0) {
                        sb.append("_");
                    }
                    sb.append(toLowerCase(c));
                    if (upCount > 1) {
                        for (int j=0;j<upCount-2;j++) {
                            i++;
                            sb.append(toLowerCase(camel.charAt(i)));
                        }
                    }
                }
            } else {
                if (isUpperCase(c)) {
                    sb.append(toLowerCase(c));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String name = "ID";
        System.out.println(toUnderScore(name));
    }

    /**
     * 查询连续大写字母的个数
     * @param start 开始位置
     * @param camel 原字符串
     * @param len 字符传长度
     * @return
     */
    private static int getUpperCaseCount(int start, String camel, int len) {
        if (start == len -1) {
            return 1;
        }
        for (int i=start + 1;i<len;i++) {
            char c = camel.charAt(i);
            if (!isUpperCase(c)) {
                return i - start;
            }
        }
        return len - start;
    }

    public static char toLowerCase(char c) {
        return (char)(c + 32);
    }

    /**
     * 判断一个字符是否是大写字母
     * @param c
     * @return
     */
    public static boolean isUpperCase(char c) {
        return c >= 'A' && c <= 'Z';
    }
}
