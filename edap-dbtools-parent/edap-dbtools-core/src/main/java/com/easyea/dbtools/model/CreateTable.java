/*
 * Copyright 2023 The edap Project
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

package com.easyea.dbtools.model;

import com.easyea.dbtools.enums.DbType;

import java.util.List;

public class CreateTable {
    private DbType                dbType;
    private String                type;
    private String                schema;
    private String                tableName;
    private boolean               ifNotExist;
    private String                selectStmt;
    private UsingStmt             usingStmt;
    private List<ColumnDefine>    columns;
    private List<TableConstraint> constraints;
    private List<TableOption>     options;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSelectStmt() {
        return selectStmt;
    }

    public void setSelectStmt(String selectStmt) {
        this.selectStmt = selectStmt;
    }

    public List<ColumnDefine> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnDefine> columns) {
        this.columns = columns;
    }

    public List<TableConstraint> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<TableConstraint> constraints) {
        this.constraints = constraints;
    }

    public List<TableOption> getOptions() {
        return options;
    }

    public void setOptions(List<TableOption> options) {
        this.options = options;
    }

    public DbType getDbType() {
        return dbType;
    }

    public void setDbType(DbType dbType) {
        this.dbType = dbType;
    }

    public boolean isIfNotExist() {
        return ifNotExist;
    }

    public void setIfNotExist(boolean ifNotExist) {
        this.ifNotExist = ifNotExist;
    }

    public UsingStmt getUsingStmt() {
        return usingStmt;
    }

    public void setUsingStmt(UsingStmt usingStmt) {
        this.usingStmt = usingStmt;
    }
}
