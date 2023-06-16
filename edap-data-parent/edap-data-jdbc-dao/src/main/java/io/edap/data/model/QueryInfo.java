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

package io.edap.data.model;

import java.util.List;

public class QueryInfo {
    private String querySql;
    private JdbcInfo idInfo;
    private List<JdbcInfo> allColumns;

    public String getQuerySql() {
        return querySql;
    }

    public void setQuerySql(String querySql) {
        this.querySql = querySql;
    }

    public JdbcInfo getIdInfo() {
        return idInfo;
    }

    public void setIdInfo(JdbcInfo idInfo) {
        this.idInfo = idInfo;
    }

    public List<JdbcInfo> getAllColumns() {
        return allColumns;
    }

    public void setAllColumns(List<JdbcInfo> allColumns) {
        this.allColumns = allColumns;
    }
}
