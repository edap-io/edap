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

package com.easyea.dbtools.test.sqlparser;

import com.easyea.dbtools.enums.DataType;
import com.easyea.dbtools.sqlparser.CreateTableSqlParser;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class NoTypesCreateTableSqlParser extends CreateTableSqlParser {

    final Set<Byte>     escapeBytes = new HashSet<>();

    public NoTypesCreateTableSqlParser(String sql) {
        super(sql);

        escapeBytes.add((byte)'"');
        escapeBytes.add((byte)'`');
    }

    @Override
    public Set<String> enableTableType() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> enableTableConstraints() {
        return Collections.emptySet();
    }

    @Override
    public Set<DataType> enableDataTypes() {
        return Collections.emptySet();
    }

    @Override
    public Set<Byte> escapeByte() {
        return escapeBytes;
    }
}
