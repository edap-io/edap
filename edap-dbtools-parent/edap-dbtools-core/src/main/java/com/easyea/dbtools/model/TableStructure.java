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

import com.easyea.dbtools.columncontraits.ColPrimaryKeyConstraint;
import com.easyea.dbtools.columncontraits.ColUniqueConstraint;
import com.easyea.dbtools.tablecontraits.TblPrimaryKeyConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TableStructure {
    private CreateTable       createTable;
    private List<CreateIndex> createIndexs;

    public CreateTable getCreateTable() {
        return createTable;
    }

    public void setCreateTable(CreateTable createTable) {
        this.createTable = createTable;
    }

    public List<CreateIndex> getCreateIndexs() {
        return createIndexs;
    }

    public void setCreateIndexs(List<CreateIndex> createIndexs) {
        this.createIndexs = createIndexs;
    }

    public List<CreateIndex> getUniqueIndexes() {
        List<CreateIndex> uniqueIndexList = new ArrayList<>();
        List<CreateIndex> createIndexs = getCreateIndexs();
        if (createIndexs != null && !createIndexs.isEmpty()) {
            for (CreateIndex createIndex : createIndexs) {
                if (createIndex.isUnique()) {
                    uniqueIndexList.add(createIndex);
                }
            }
        }

        return uniqueIndexList;
    }

    public List<String> getUniqueColumns() {
        List<String> uniueColumns = new ArrayList<>();
        List<ColumnDefine> columnDefins = createTable.getColumns();
        if (columnDefins == null || columnDefins.isEmpty()) {
            return uniueColumns;
        }
        for (ColumnDefine columnDefine : columnDefins) {
            List<ColumnConstraint> columnConstraints = columnDefine.getColumnConstraints();
            if (columnConstraints != null && !columnConstraints.isEmpty()) {
                for (ColumnConstraint constraint : columnConstraints) {
                    if (constraint instanceof ColUniqueConstraint) {
                        uniueColumns.add(columnDefine.getName());
                    }
                }
            }
        }
        return uniueColumns;
    }

    public List<String> getPrimaryKeyColumns() {
        List<String> columns = new ArrayList<>();
        List<ColumnDefine> columnDefins = createTable.getColumns();
        if (columnDefins == null || columnDefins.isEmpty()) {
            return columns;
        }
        for (ColumnDefine columnDefine : columnDefins) {
            if (isPrimaryColumn(columnDefine)) {
                columns.add(columnDefine.getName().toUpperCase(Locale.ENGLISH));
                return columns;
            }
        }
        List<TableConstraint> tableConstraints = createTable.getConstraints();
        if (tableConstraints == null || tableConstraints.isEmpty()) {
            return columns;
        }
        for (TableConstraint tableConstraint : tableConstraints) {
            if (tableConstraint instanceof TblPrimaryKeyConstraint) {
                TblPrimaryKeyConstraint primaryKeyConstraint = (TblPrimaryKeyConstraint)tableConstraint;
                for (String colName : primaryKeyConstraint.getColumns()) {
                    columns.add(colName.toUpperCase(Locale.ENGLISH));
                }
            }
        }

        return columns;
    }

    private boolean isPrimaryColumn(ColumnDefine columnDefine) {
        if (columnDefine.getColumnConstraints() == null || columnDefine.getColumnConstraints().isEmpty()) {
            return false;
        }
        for (ColumnConstraint constraint : columnDefine.getColumnConstraints()) {
            if (constraint instanceof ColPrimaryKeyConstraint) {
                return true;
            }
        }
        return false;
    }
}
