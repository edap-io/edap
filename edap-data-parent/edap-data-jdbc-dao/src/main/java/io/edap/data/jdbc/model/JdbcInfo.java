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

package io.edap.data.jdbc.model;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * jdbc操作相关的元数据信息
 */
public class JdbcInfo {
    /**
     * java对象获取属性的方法名
     */
    private Method valueMethod;
    /**
     * jdbc需要传入的数据类型
     */
    private String jdbcType;
    /**
     * java对象属性的类型
     */
    private String fieldType;
    /**
     * jdbc的方法名比如setString
     */
    private String jdbcMethod;
    /**
     * 是否需要拆包
     */
    private boolean needUnbox;
    private boolean baseType;
    private String columnName;
    /**
     * 关联的java对象的属性
     */
    private Field field;

    public void setValueMethod(Method valueMethod) {
        this.valueMethod = valueMethod;
    }

    public Method getValueMethod() {
        return valueMethod;
    }

    public void setJdbcType(String jdbcType) {
        this.jdbcType = jdbcType;
    }

    public String getJdbcType() {
        return jdbcType;
    }

    public String getFieldType() {
        return fieldType;
    }

    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    public String getJdbcMethod() {
        return jdbcMethod;
    }

    public void setJdbcMethod(String jdbcMethod) {
        this.jdbcMethod = jdbcMethod;
    }

    public boolean isNeedUnbox() {
        return needUnbox;
    }

    public void setNeedUnbox(boolean needUnbox) {
        this.needUnbox = needUnbox;
    }

    public Field getField() {
        return field;
    }

    public void setField(Field field) {
        this.field = field;
    }

    public String getColumnName() {
        return this.columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public boolean isBaseType() {
        return baseType;
    }

    public void setBaseType(boolean baseType) {
        this.baseType = baseType;
    }
}
