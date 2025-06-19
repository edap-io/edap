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

import com.easyea.dbtools.columncontraits.*;
import com.easyea.dbtools.enums.DataType;
import com.easyea.dbtools.model.ColumnDefine;
import com.easyea.dbtools.model.CreateTable;
import com.easyea.dbtools.sqlparser.BaseCreateTableSqlParser;
import com.easyea.dbtools.sqlparser.ByteIsSpace;
import com.easyea.dbtools.sqlparser.CreateTableSqlParser;
import com.easyea.dbtools.sqlparser.SqlParser;
import com.easyea.dbtools.tablecontraits.TblPrimaryKeyConstraint;
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

        assertEquals(createTable.isIfNotExist(), true);

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

    @Test
    public void testParseTableContrait() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateTableSqlParser.class.getDeclaredMethod("parseTableContrait",
                String.class, String.class, CreateTable.class);
        method.setAccessible(true);

        String sql = "   \r\t\n KEY(id)";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        CreateTable createTable = new CreateTable();
        method.invoke(parser, "Primary", "", createTable);
        assertNotNull(createTable.getConstraints());
        assertEquals(createTable.getConstraints().size(), 1);
        assertEquals(((TblPrimaryKeyConstraint)createTable.getConstraints().get(0)).getColumns().get(0), "id");

        sql = "   \r\t\n KEY ( id )";
        parser = new BaseCreateTableSqlParser(sql);
        createTable = new CreateTable();
        method.invoke(parser, "Primary", "", createTable);
        assertNotNull(createTable.getConstraints());
        assertEquals(createTable.getConstraints().size(), 1);
        assertEquals(((TblPrimaryKeyConstraint)createTable.getConstraints().get(0)).getColumns().get(0), "id");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   \r\t\n ( id )";
                    CreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    CreateTable createTable2 = new CreateTable();
                    method.invoke(parser2, "Primary", "", createTable2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:2 PRIMARY KEY not set"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   \r\t\n KEY  id )";
                    CreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    CreateTable createTable2 = new CreateTable();
                    method.invoke(parser2, "Primary", "", createTable2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:4 PRIMARY KEY not set"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   \r\t\n KEY ()";
                    CreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    CreateTable createTable2 = new CreateTable();
                    method.invoke(parser2, "Primary", "", createTable2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:3 PRIMARY KEY column not set"));
    }

    @Test
    public void testParseColumnType() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateTableSqlParser.class.getDeclaredMethod("parseColumnType");
        method.setAccessible(true);

        String sql = "   \r\n bigint,";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);

        CreateTableSqlParser.ParseResult<DataType> parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.BIGINT);

        sql = "   \r\n bigint)";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.BIGINT);
        assertEquals(parseResult.isColumnFinished(), true);

        sql = "   \r\n bigint )";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.BIGINT);
        assertEquals(parseResult.isColumnFinished(), true);

        sql = "   \r\n bigint ,";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.BIGINT);
        assertEquals(parseResult.isColumnFinished(), true);


        sql = "   \r\n varchar(200),";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.VARCHAR);
        assertEquals(parseResult.getValue().getPrecision(), 200);


        sql = "   \r\n DECIMAL ( 10 , 5 ) ,";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.DECIMAL);
        assertEquals(parseResult.getValue().getPrecision(), 10);
        assertEquals(parseResult.getValue().getScale(), 5);

        sql = "   \r\n DECIMAL ( 10 , 5 )    ";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), true);
        assertEquals(parseResult.getValue(), DataType.DECIMAL);
        assertEquals(parseResult.getValue().getPrecision(), 10);
        assertEquals(parseResult.getValue().getScale(), 5);


        sql = "   \r\n COLLATE RTRIM ,";
        parser = new BaseCreateTableSqlParser(sql);

        parseResult = (CreateTableSqlParser.ParseResult)method.invoke(parser);
        assertNotNull(parseResult);
        assertEquals(parseResult.isSuccess(), false);
        assertEquals(parseResult.getToken(), "COLLATE");


        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   \r\t\n ";
                    CreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    method.invoke(parser2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:2 column type is error"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   \r\t\n INT ( ) ";
                    CreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    method.invoke(parser2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:4 column type is error"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   \r\t\n INT ";
                    CreateTableSqlParser parser2 = new NoTypesCreateTableSqlParser(sql2);
                    method.invoke(parser2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("enable DataTypes can't empty!"));
    }

    @Test
    public void testParseColumnDefine() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateTableSqlParser.class.getDeclaredMethod("parseColumnDefine",
                String.class, CreateTable.class);
        method.setAccessible(true);

        String sql = "   \r\n bigint,";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        CreateTable createTable = new CreateTable();
        method.invoke(parser, "id", createTable);
        assertEquals(createTable.getColumns().size(), 1);
        assertEquals(createTable.getColumns().get(0).getName(), "id");
        assertEquals(createTable.getColumns().get(0).getDataType(), DataType.BIGINT);

        sql = "   \r\n bigint primary key,";
        parser = new BaseCreateTableSqlParser(sql);
        createTable = new CreateTable();
        method.invoke(parser, "id", createTable);
        assertEquals(createTable.getColumns().size(), 1);
        assertEquals(createTable.getColumns().get(0).getName(), "id");
        assertEquals(createTable.getColumns().get(0).getDataType(), DataType.BIGINT);
        assertTrue(createTable.getColumns().get(0).getColumnConstraints().get(0) instanceof ColPrimaryKeyConstraint);


        sql = "   COLLATE NOCASE unique,";
        parser = new BaseCreateTableSqlParser(sql);
        createTable = new CreateTable();
        method.invoke(parser, "value", createTable);
        assertEquals(createTable.getColumns().size(), 1);
        assertEquals(createTable.getColumns().get(0).getName(), "value");
        assertTrue(createTable.getColumns().get(0).getColumnConstraints().get(0) instanceof ColCollateConstraint);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = " int unique ";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    CreateTable createTable2 = new CreateTable();
                    method.invoke(parser2, "value", createTable2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("column define have't finish"));
    }

    @Test
    public void testParseColumnContraint() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = CreateTableSqlParser.class.getDeclaredMethod("parseColumnContraint",
                String.class, boolean.class, ColumnDefine.class);
        method.setAccessible(true);

        String sql = "key ,";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        ColumnDefine columnDefine = new ColumnDefine();
        boolean columnFinished = (Boolean)method.invoke(parser, "primary", false, columnDefine);
        assertNotNull(columnFinished);

        sql = "key,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "primary", false, columnDefine);
        assertNotNull(columnFinished);

        sql = "key ASC,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "primary", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertEquals(((ColPrimaryKeyConstraint)columnDefine.getColumnConstraints().get(0)).getSort(), "ASC");

        sql = "key autoincrement,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "primary", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertEquals(((ColPrimaryKeyConstraint)columnDefine.getColumnConstraints().get(0)).getAutoIncrement(), "AUTOINCREMENT");

        sql = "key desc autoincrement,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "primary", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertEquals(((ColPrimaryKeyConstraint)columnDefine.getColumnConstraints().get(0)).getSort(), "DESC");
        assertEquals(((ColPrimaryKeyConstraint)columnDefine.getColumnConstraints().get(0)).getAutoIncrement(), "AUTOINCREMENT");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   bigint,";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    ColumnDefine columnDefine2 = new ColumnDefine();
                    method.invoke(parser2, "primary", false, columnDefine2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("PRIMARY KEY not well"));


        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "   bigint,";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    ColumnDefine columnDefine2 = new ColumnDefine();
                    method.invoke(parser2, "default", true, columnDefine2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("DEFAULT value can't empty"));

        sql = "'abc edf',";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "default", false, columnDefine);
        assertNotNull(columnFinished);

        sql = "'abc edf' ,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "default", false, columnDefine);
        assertNotNull(columnFinished);

        sql = "'abc edf' COLLATE RTRIM,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "default", false, columnDefine);
        assertNotNull(columnFinished);

        sql = "'',";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "default", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(((ColDefaultConstraint)columnDefine.getColumnConstraints().get(0)).getValue(), "");


        sql = " null,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "NOT", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColNotNullConstraint);

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "  ,";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    ColumnDefine columnDefine2 = new ColumnDefine();
                    method.invoke(parser2, "Not", true, columnDefine2);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("NOT NULL not well"));

        sql = " null   \r\n,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "NOT", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColNotNullConstraint);

        sql = " null  Primary key \r\n,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "NOT", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 2);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColNotNullConstraint);
        assertTrue(columnDefine.getColumnConstraints().get(1) instanceof ColPrimaryKeyConstraint);


        sql = "";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "UNIQUE", true, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColUniqueConstraint);

        sql = " ,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "UNIQUE", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColUniqueConstraint);

        sql = " COLLATE abc,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "UNIQUE", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 2);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColUniqueConstraint);
        assertTrue(columnDefine.getColumnConstraints().get(1) instanceof ColCollateConstraint);


        sql = " abc,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "COLLATE", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 1);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColCollateConstraint);
        assertEquals(((ColCollateConstraint)columnDefine.getColumnConstraints().get(0)).getCollationName(), "abc");

        sql = " abc unique,";
        parser = new BaseCreateTableSqlParser(sql);
        columnDefine = new ColumnDefine();
        columnFinished = (Boolean)method.invoke(parser, "COLLATE", false, columnDefine);
        assertNotNull(columnFinished);
        assertEquals(columnDefine.getColumnConstraints().size(), 2);
        assertTrue(columnDefine.getColumnConstraints().get(0) instanceof ColCollateConstraint);
        assertEquals(((ColCollateConstraint)columnDefine.getColumnConstraints().get(0)).getCollationName(), "abc");
        assertTrue(columnDefine.getColumnConstraints().get(1) instanceof ColUniqueConstraint);
    }

    @Test
    public void testCheckCreateStart() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = SqlParser.class.getDeclaredMethod("checkCreateStart");
        method.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n bigint,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:2 not start CREATE"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n cigint,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:3 not start CREATE"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n crgint,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:4 not start CREATE"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n creint,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:5 not start CREATE"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n creant,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:6 not start CREATE"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n creatt,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:7 not start CREATE"));

        thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql = "   \r\n create,";
                    BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
                    method.invoke(parser);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("row 2:8 not start CREATE"));

        String sql = "   \r\n create ";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        method.invoke(parser);
    }

    @Test
    public void testParseWithSpaceToken() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = SqlParser.class.getDeclaredMethod("parseWithSpaceToken",
                ByteIsSpace.class, byte.class, byte[].class);
        method.setAccessible(true);

        ByteIsSpace byteIsSpace = b -> b == ' ' || b == '\r' || b == '\n' || b == '\t';
        byte[] endBytes = new byte[]{(byte)',', (byte)')'};
        String sql = "'abc defg, w\t\b\n '";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        CreateTableSqlParser.ParseResult<String> result = (CreateTableSqlParser.ParseResult<String>)
                method.invoke(parser, byteIsSpace, (byte)'\'', endBytes);
        assertNotNull(result);

        sql = "'abc defg, \'w\t\b\n '";
        parser = new BaseCreateTableSqlParser(sql);
        result = (CreateTableSqlParser.ParseResult<String>)
                method.invoke(parser, byteIsSpace, (byte)'\'', endBytes);
        assertNotNull(result);

        sql = "'abc defg, \'w\t\b\n ' ,";
        parser = new BaseCreateTableSqlParser(sql);
        result = (CreateTableSqlParser.ParseResult<String>)
                method.invoke(parser, byteIsSpace, (byte)'\'', endBytes);
        assertNotNull(result);

        sql = "'abc defg, \'w\t\b\n ',";
        parser = new BaseCreateTableSqlParser(sql);
        result = (CreateTableSqlParser.ParseResult<String>)
                method.invoke(parser, byteIsSpace, (byte)'\'', endBytes);
        assertNotNull(result);

        sql = "'abc defg, \'w\t\b\n '\r\n\n)";
        parser = new BaseCreateTableSqlParser(sql);
        result = (CreateTableSqlParser.ParseResult<String>)
                method.invoke(parser, byteIsSpace, (byte)'\'', endBytes);
        assertNotNull(result);

        sql = "abc ";
        parser = new BaseCreateTableSqlParser(sql);
        result = (CreateTableSqlParser.ParseResult<String>)
                method.invoke(parser, byteIsSpace, (byte)'\'', endBytes);
        assertNotNull(result);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> {
                    String sql2 = "'abc defg, ";
                    BaseCreateTableSqlParser parser2 = new BaseCreateTableSqlParser(sql2);
                    method.invoke(parser2, byteIsSpace, (byte)'\'', endBytes);
                });
        assertTrue(thrown.getTargetException().getMessage().contains("String not finish"));
    }

    @Test
    public void testParse() {
        String sql = "CREATE TABLE \"VINRANGES\" (\n" +
                "\t`VINBANDFROM`\tVARCHAR NOT NULL,\n" +
                "\t`VINBANDTO`\tVARCHAR NOT NULL,\n" +
                "\t`TYPSCHLUESSEL`\tVARCHAR NOT NULL,\n" +
                "\t`PRODUCTIONDATEYEAR`\tVARCHAR NOT NULL,\n" +
                "\t`PRODUCTIONDATEMONTH`\tVARCHAR NOT NULL,\n" +
                "\t`RELEASESTATE`\tINTEGER NOT NULL,\n" +
                "\t`CHANGEDATE`\tDATETIME NOT NULL,\n" +
                "\t`GEARBOX_TYPE`\tVARCHAR,\n" +
                "\t`VIN17_4_7`\tVARCHAR,\n" +
                "\tPRIMARY KEY(`VINBANDFROM`,`VINBANDTO`, `VIN17_4_7`)\n" +
                ")";
        BaseCreateTableSqlParser parser = new BaseCreateTableSqlParser(sql);
        CreateTable createTable = parser.parse();
        assertNotNull(createTable);
        
        sql = "CREATE TABLE \"RG_ECUFAULT_DOCIDS\" (\"ECUFAULT_ID\" INTEGER NOT NULL, \"INFOOBJECTID\" INTEGER, " +
                "\"CONTENT_DEDE\" INTEGER,\"CONTENT_ENGB\" INTEGER,\"CONTENT_ENUS\" INTEGER,\"CONTENT_FR\" INTEGER," +
                "\"CONTENT_TH\" INTEGER,\"CONTENT_SV\" INTEGER,\"CONTENT_IT\" INTEGER,\"CONTENT_ES\" INTEGER," +
                "\"CONTENT_ID\" INTEGER,\"CONTENT_KO\" INTEGER,\"CONTENT_EL\" INTEGER,\"CONTENT_TR\" INTEGER," +
                "\"CONTENT_ZHCN\" INTEGER,\"CONTENT_RU\" INTEGER,\"CONTENT_NL\" INTEGER,\"CONTENT_PT\" INTEGER," +
                "\"CONTENT_ZHTW\" INTEGER,\"CONTENT_JA\"  INTEGER, \"CONTENT_CSCZ\"  INTEGER, \"CONTENT_PLPL\"  INTEGER)";
        parser = new BaseCreateTableSqlParser(sql);
        createTable = parser.parse();
        assertNotNull(createTable);
    }
}
