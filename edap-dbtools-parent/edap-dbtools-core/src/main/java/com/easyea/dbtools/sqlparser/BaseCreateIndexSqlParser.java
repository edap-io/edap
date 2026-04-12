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

import java.util.HashSet;
import java.util.Set;

public class BaseCreateIndexSqlParser extends CreateIndexSqlParser {

    final Set<Byte>     escapeBytes = new HashSet<>();

    public BaseCreateIndexSqlParser(String sql) {
        super(sql);
        escapeBytes.add((byte)'"');
        escapeBytes.add((byte)'`');
    }

    @Override
    public Set<Byte> escapeByte() {
        return escapeBytes;
    }
}
