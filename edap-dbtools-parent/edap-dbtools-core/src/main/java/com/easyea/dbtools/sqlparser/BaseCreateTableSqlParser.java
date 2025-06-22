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

package com.easyea.dbtools.sqlparser;

import com.easyea.dbtools.enums.DataType;

import java.util.HashSet;
import java.util.Set;

public class BaseCreateTableSqlParser extends CreateTableSqlParser {

    final Set<DataType> dataTypes   = new HashSet<>();
    final Set<String>   contraits   = new HashSet<>();
    final Set<String>   tableTypes  = new HashSet<>();
    final Set<Byte>     escapeBytes = new HashSet<>();

    public BaseCreateTableSqlParser(String sql) {
        super(sql);
        DataType[] allType = DataType.values();
        for (DataType dataType : allType) {
            dataTypes.add(dataType);
        }
        contraits.add("PRIMARY");
        contraits.add("UNIQUE");
        contraits.add("CHECK");
        contraits.add("FOREIGN");
        contraits.add("CONSTRAINT");

        tableTypes.add("TEMP");
        tableTypes.add("TEMPORARY");
        tableTypes.add("VIRTUAL");

        escapeBytes.add((byte)'"');
        escapeBytes.add((byte)'`');
    }

    @Override
    public Set<String> enableTableType() {
        return tableTypes;
    }

    @Override
    public Set<String> enableTableConstraints() {
        return contraits;
    }

    @Override
    public Set<DataType> enableDataTypes() {
        return dataTypes;
    }

    @Override
    public Set<Byte> escapeByte() {
        return escapeBytes;
    }
}
