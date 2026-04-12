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

import com.easyea.dbtools.model.CreateIndex;
import com.easyea.dbtools.model.IndexedColumn;
import com.easyea.dbtools.sqlparser.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestCreateIndexSqlParser {

    @Test
    public void testParse() {
        String sql = "CREATE unique INDEX \"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC, id)";
        BaseCreateIndexSqlParser parser = new BaseCreateIndexSqlParser(sql);
        CreateIndex createIndex = parser.parse();
        assertNotNull(createIndex);

        sql = "CREATE INDEX \"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" ( id, \"CONTROLID\" ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        createIndex = parser.parse();
        assertNotNull(createIndex);

        sql = "CREATE unique INDEX \"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        createIndex = parser.parse();
        assertNotNull(createIndex);

        sql = "CREATE INDEX \"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        createIndex = parser.parse();
        assertNotNull(createIndex);

        sql = "CREATE INDEX test.\"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        createIndex = parser.parse();
        assertNotNull(createIndex);

        sql = "CREATE INDEX \"test.IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        createIndex = parser.parse();
        assertNotNull(createIndex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    String sql2 = "CREATE INDEX2 \"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC)";
                    BaseCreateIndexSqlParser parser2 = new BaseCreateIndexSqlParser(sql2);
                    parser2.parse();
                });
        assertTrue(thrown.getMessage().contains("row 1:7 isn't create index sql"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String sql2 = "CREATE INDEX \"IdxIoContentControlId\" ON1 \"XEP_IOCONTENTS\" (\"CONTROLID\" ASC)";
                    BaseCreateIndexSqlParser parser2 = new BaseCreateIndexSqlParser(sql2);
                    parser2.parse();
                });
        assertTrue(thrown.getMessage().contains("row 1:9 create index sql not \"on\" keyword"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String sql2 = "CREATE INDEX \"IdxIoContentControlId\" ON \"XEP_IOCONTENTS\" s\"CONTROLID\" ASC)";
                    BaseCreateIndexSqlParser parser2 = new BaseCreateIndexSqlParser(sql2);
                    parser2.parse();
                });
        assertTrue(thrown.getMessage().contains("row 1:11 create index sql not set columns"));
    }

    @Test
    public void testParseIndexedColumn() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateIndexSqlParser.class.getDeclaredMethod("parseIndexedColumn");
        method.setAccessible(true);

        String sql = "\"CONTROLID\")";
        BaseCreateIndexSqlParser parser = new BaseCreateIndexSqlParser(sql);
        SqlParser.ParseResult<IndexedColumn> result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");

        sql = "\"CONTROLID\" COLLATE ascci)";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getCollate(), "ascci");

        sql = "\"CONTROLID\" ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "ASC");

        sql = "\"CONTROLID\" COLLATE ascci ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getCollate(), "ascci");

        sql = "\"CONTROLID\" COLLATE ascci ASC)";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "ASC");

        sql = "\"CONTROLID\" COLLATE ascci DESC)";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "DESC");

        sql = "CONTROLID COLLATE ascci DESC)";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)')');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "DESC");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = " )";
                    BaseCreateIndexSqlParser parser2 = new BaseCreateIndexSqlParser(sql2);
                    method.invoke(parser2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("token can't is empty"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "\"CONTROLID\" COLLATE ascci DESC1)";
                    BaseCreateIndexSqlParser parser2 = new BaseCreateIndexSqlParser(sql2);
                    method.invoke(parser2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 1:4 create index format error"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "\"CONTROLID\" COLLATE ascci DESC fdasfd)";
                    BaseCreateIndexSqlParser parser2 = new BaseCreateIndexSqlParser(sql2);
                    method.invoke(parser2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 1:5 create index not finish"));


        sql = "\"CONTROLID\" ,";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)',');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");

        sql = "\"CONTROLID\" COLLATE ascci ,";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)',');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getCollate(), "ascci");

        sql = "\"CONTROLID\" ASC,";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)',');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "ASC");

        sql = "\"CONTROLID\" COLLATE ascci ASC ,";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)',');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getCollate(), "ascci");

        sql = "\"CONTROLID\" COLLATE ascci ASC,";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)',');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "ASC");

        sql = "\"CONTROLID\" COLLATE ascci DESC,";
        parser = new BaseCreateIndexSqlParser(sql);
        result = (SqlParser.ParseResult<IndexedColumn>)method.invoke(parser);
        assertNotNull(result);
        assertEquals(result.getEndByte(), (byte)',');
        assertEquals(result.getValue().getColumnName(), "CONTROLID");
        assertEquals(result.getValue().getSort(), "DESC");
    }
}
