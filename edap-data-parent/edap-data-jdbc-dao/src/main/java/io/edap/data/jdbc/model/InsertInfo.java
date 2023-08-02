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

import io.edap.data.jdbc.annotation.GenerationType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class InsertInfo {
    private String insertSql;
    private String noIdInsertSql;
    private GenerationType generationType;
    private Field idField;
    private Method idSetMethod;

    private Method idGetMethod;

    public String getInsertSql() {
        return insertSql;
    }

    public void setInsertSql(String insertSql) {
        this.insertSql = insertSql;
    }

    public GenerationType getGenerationType() {
        return generationType;
    }

    public void setGenerationType(GenerationType generationType) {
        this.generationType = generationType;
    }

    public Field getIdField() {
        return idField;
    }

    public void setIdField(Field idField) {
        this.idField = idField;
    }

    public Method getIdSetMethod() {
        return idSetMethod;
    }

    public void setIdSetMethod(Method idSetMethod) {
        this.idSetMethod = idSetMethod;
    }

    public String getNoIdInsertSql() {
        return noIdInsertSql;
    }

    public void setNoIdInsertSql(String noIdInsertSql) {
        this.noIdInsertSql = noIdInsertSql;
    }

    public Method getIdGetMethod() {
        return idGetMethod;
    }

    public void setIdGetMethod(Method idGetMethod) {
        this.idGetMethod = idGetMethod;
    }
}