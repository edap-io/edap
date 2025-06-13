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

import com.easyea.dbtools.model.CreateTable;
import com.easyea.dbtools.sqlparser.BaseCreateTableSqlParser;
import com.easyea.dbtools.sqlparser.CreateTableSqlParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestCreateTableSqlParser {

    @Test
    public void testParseColumns() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateTableSqlParser.class.getDeclaredMethod("parseColumns", String.class);
        method.setAccessible(true);

        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser("");
        String text = "\"column1\" , column2, column3";
        List<String> columns = (List<String>)method.invoke(parser, text);
        assertNotNull(columns);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String text2 = "\"column1\" , column2, \"column3";
                    method.invoke(parser, text2);
                });

        assertTrue(thrown.getTargetException().getMessage().contains("\"column1\" , column2, \"column3 not end with [\"]"));
    }

    @Test
    public void testCheckIfNotExists() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateTableSqlParser.class.getDeclaredMethod("checkIfNotExists", CreateTable.class);
        method.setAccessible(true);
        String sql = "   \r\t\n NOT EXISTS";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        CreateTable createTable = new CreateTable();
        method.invoke(parser, createTable);

        assertEquals(createTable.getCreateOption(), "IF NOT EXISTS");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = " NO EX";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    CreateTable createTable2 = new CreateTable();
                    method.invoke(parser2, createTable2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 1:2 not start \"NOT EXISTS\""));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = " NOT EX";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    CreateTable createTable2 = new CreateTable();
                    method.invoke(parser2, createTable2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 1:3 not start \"NOT EXISTS\""));
    }
}
