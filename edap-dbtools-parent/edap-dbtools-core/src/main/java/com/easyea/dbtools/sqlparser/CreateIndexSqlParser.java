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

import com.easyea.dbtools.model.CreateIndex;
import com.easyea.dbtools.model.IndexedColumn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public abstract class CreateIndexSqlParser extends SqlParser {

    public CreateIndexSqlParser(String sql) {
        this.data = sql.getBytes(StandardCharsets.UTF_8);
    }

    public CreateIndex parse() {
        CreateIndex createIndex = new CreateIndex();
        checkCreateStart();
        String token = nextToken();
        if ("UNIQUE".equalsIgnoreCase(token)) {
            createIndex.setUnique(true);
            trim();
            token = nextToken();
        }
        if (!"INDEX".equalsIgnoreCase(token)) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " isn't create index sql");
        }
        trim();
        ParseResult<String> tokenResult = parseWithSpaceToken(b -> isSpace(b), escapeByte(), (byte)'.');
        if (tokenResult.getEndByte() == '.') {
            createIndex.setSchemaName(tokenResult.getToken());
            pos++;
            tokenResult = parseWithSpaceToken(b -> isSpace(b), escapeByte());
            createIndex.setName(tokenResult.getToken());
        } else {
            token = tokenResult.getToken();
            int dotIndex = token.indexOf(".");
            if (dotIndex != -1) {
                createIndex.setSchemaName(token.substring(0, dotIndex));
                createIndex.setName(token.substring(dotIndex+1));
            } else {
                createIndex.setName(token);
            }
        }

        trim();
        token = nextToken();
        if (!"ON".equalsIgnoreCase(token)) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " create index sql not \"on\" keyword");
        }
        trim();
        tokenResult = parseWithSpaceToken(b -> isSpace(b), escapeByte(), (byte)'(');
        token = tokenResult.getToken();
        createIndex.setTableName(token);

        trim();
        byte sep = data[pos];
        if (sep != '(') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " create index sql not set columns");
        }
        pos++;
        trim();
        ParseResult<IndexedColumn> result = parseIndexedColumn();
        List<IndexedColumn> columns = new ArrayList<>();
        while (result.getEndByte() != ')') {
            columns.add(result.getValue());
            pos++;
            trim();
            result = parseIndexedColumn();
        }
        columns.add(result.getValue());
        createIndex.setIndexColumns(columns);

        return createIndex;
    }

    private ParseResult<IndexedColumn> parseIndexedColumn() {
        ParseResult<IndexedColumn> columnResult = new ParseResult<>();
        ParseResult<String> result = parseWithSpaceToken(b -> isSpace(b), escapeByte(), (byte)',', (byte)')');
        IndexedColumn indexedColumn = new IndexedColumn();
        indexedColumn.setColumnName(result.getToken());
        columnResult.setValue(indexedColumn);
        if (result.getEndByte() == ',' || result.getEndByte() == ')') {
            indexedColumn.setColumnName(result.getToken());
            columnResult.setEndByte(result.getEndByte());
            return columnResult;
        }
        trim();
        result = parseWithSpaceToken(b -> isSpace(b), escapeByte(), (byte)',', (byte)')');
        if ("COLLATE".equalsIgnoreCase(result.getToken())) {
            trim();
            result = parseWithSpaceToken(b -> isSpace(b), escapeByte(), (byte)',', (byte)')');
            indexedColumn.setCollate(result.getToken());
            if (result.getEndByte() == ',' || result.getEndByte() == ')') {
                columnResult.setEndByte(result.getEndByte());
                return columnResult;
            }
            trim();
            result = parseWithSpaceToken(b -> isSpace(b), escapeByte(), (byte)',', (byte)')');
        }
        if ("ASC".equalsIgnoreCase(result.getToken())) {
            indexedColumn.setSort("ASC");
        } else if ("DESC".equalsIgnoreCase(result.getToken())) {
            indexedColumn.setSort("DESC");
        } else {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " create index format error");
        }
        if (result.getEndByte() == ' ') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " create index not finish");
        }
        columnResult.setEndByte(result.getEndByte());

        return columnResult;
    }

}
