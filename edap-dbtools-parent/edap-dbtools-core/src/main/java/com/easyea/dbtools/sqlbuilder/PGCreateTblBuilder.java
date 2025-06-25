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

package com.easyea.dbtools.sqlbuilder;

import com.easyea.dbtools.CreateIndexIfNotExists;
import com.easyea.dbtools.DataTypeConvertor;
import com.easyea.dbtools.ColKeywordConvertor;
import com.easyea.dbtools.PrimaryKeyNotNull;
import com.easyea.dbtools.enums.DataType;
import com.easyea.dbtools.enums.DbType;

import static com.easyea.dbtools.enums.DataType.TEXT;

public class PGCreateTblBuilder extends CreateTblBuilder implements DataTypeConvertor, ColKeywordConvertor,
        CreateIndexIfNotExists, PrimaryKeyNotNull {

    @Override
    public boolean enableIfNotExists() {
        return true;
    }

    @Override
    public DataType convert(DbType dbType, DataType dataType) {
        if (dataType == null) {
            return TEXT;
        }
        switch (dataType) {
            case BLOB:
                return DataType.BYTEA;
            case DATETIME:
                return DataType.TIMESTAMP_WITH_ZONE;
            case INTEGER:
                if (dbType == DbType.SQLITE) {
                    return DataType.BIGINT;
                }
            default:
                return dataType;
        }
    }

    @Override
    public String colNameConvert(String colName) {
        if ("OFFSET".equalsIgnoreCase(colName)) {
            return "COl_" + colName;
        }
        return colName;
    }

    @Override
    public  boolean enablePrimaryKeyNotNull() {
        return false;
    }

    @Override
    public String escapeString(String str) {
        if (str == null || str.trim().length() == 0) {
            return str;
        }
        int start = 0;
        int index = str.indexOf('\'', start);
        if (index == -1) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        while (index != -1) {
            sb.append(str.substring(start, index));
            sb.append("''");
            start = index + 1;
            index = str.indexOf('\'', start);
        }
        if (start < str.length()) {
            sb.append(str.substring(start));
        }
        return sb.toString();
    }
}
