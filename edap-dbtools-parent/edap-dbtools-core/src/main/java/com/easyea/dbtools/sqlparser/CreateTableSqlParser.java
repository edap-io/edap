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

import com.easyea.dbtools.columncontraits.*;
import com.easyea.dbtools.enums.DataType;
import com.easyea.dbtools.model.*;
import com.easyea.dbtools.tablecontraits.TblPrimaryKeyConstraint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public abstract class CreateTableSqlParser extends SqlParser {



    public CreateTableSqlParser(String sql) {
        this.data = sql.getBytes(StandardCharsets.UTF_8);
    }

    public CreateTable parse() {
        checkCreateStart();

        String token = nextToken();
        CreateTable createTable = new CreateTable();
        // 如果数据库支持在建表时支持表类型，则解析表类型
        Set tableTypes = enableTableType();
        if (tableTypes != null || tableTypes.size() > 0) {
            String tableType = token.toUpperCase(Locale.ENGLISH);
            if (tableTypes.contains(tableType)) {
                createTable.setType(tableType);
                trim();
                token = nextToken();
            }
        }
        if (!"TABLE".equalsIgnoreCase(token)) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        }
        trim();
        ParseResult<String> parseResult = parseWithSpaceToken(b->isSpace(b), escapeByte(), (byte)' ', (byte)'(');
        token = parseResult.getToken();
        if ("IF".equalsIgnoreCase(token)) {
            checkIfNotExists(createTable);
            trim();
            token = nextToken();
        }
        int dotIndex = token.indexOf('.');
        if (dotIndex != -1) {
            createTable.setSchema(token.substring(0, dotIndex));
            createTable.setTableName(token.substring(dotIndex + 1));
        } else {
            createTable.setTableName(token);
        }
        trim();
        byte tokenByte = data[pos];
        if (tokenByte == '(') {
            token = "(";
            pos++;
        } else {
            token = nextToken();
            if ("AS".equalsIgnoreCase(token)) {
                trim();
                createTable.setSelectStmt(new String(data, pos, data.length - pos, StandardCharsets.UTF_8));

                return createTable;
            } else if ("USING".equalsIgnoreCase(token)) {
                trim();
                ParseResult<String> tokenResult = parseWithSpaceToken(b -> isSpace(b), (byte)'\'', (byte)'(');
                String moduleName = tokenResult.getToken();
                UsingStmt usingStmt = new UsingStmt();
                usingStmt.setModuleName(moduleName);
                createTable.setUsingStmt(usingStmt);
                trim();
                token = nextToken();
            }
        }
        if (token.length() == 1) {
            if (!token.equals("(")) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " not start \"(\"");
            }
            trim();
            pos++;
            ParseResult<String> tokenResult = parseWithSpaceToken(b -> isSpace(b), this.escapeByte(),
                    (byte)',', (byte)'(', (byte)')');
            token = tokenResult.getToken();
        } else {
            if (token.charAt(0) == '(') {
                token = token.substring(1);
            } else {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " not start \"(\"");
            }
        }

        Set<String> tableContraits = enableTableConstraints();
        while (true) {
            if (")".equals(token)) {  // 列定义以及约束定义结束
                break;
            } else if (",".equalsIgnoreCase(token)) {
                pos++;
                trim();
                ParseResult<String> tokenResult = parseWithSpaceToken(b -> isSpace(b), this.escapeByte(),
                        (byte)',', (byte)')');
                token = tokenResult.getToken();
            } else {
                if (tableContraits != null && tableContraits.contains(token.toUpperCase(Locale.ENGLISH))) {
                    parseTableContrait(token, null, createTable);
                    trim();
                    token = nextToken();
                } else {
                    parseColumnDefine(token, createTable);
                    trim();
                    token = nextToken();
                }
            }
        }
        return createTable;
    }

    private void parseColumnDefine(String token, CreateTable createTable) {
        ColumnDefine columnDefine = new ColumnDefine();
        columnDefine.setName(token);
        ParseResult<DataType> typeResult = parseColumnType();
        boolean columFinish = typeResult.isColumnFinished();
        String contraitToken = null;
        ParseResult<String> tokenResult;
        List<ColumnDefine> defines = createTable.getColumns();
        if (defines == null) {
            defines = new ArrayList<>();
            createTable.setColumns(defines);
        }
        if (typeResult.isSuccess()) {
            columnDefine.setDataType(typeResult.getValue());
            if (!columFinish) {
                tokenResult = parseToken(b -> isSpace(b), (byte)',', (byte)')');
                contraitToken = tokenResult.getToken();
            } else {
                defines.add(columnDefine);
                return;
            }
        } else {
            tokenResult = new ParseResult<>();
            tokenResult.setToken(typeResult.getToken());
            tokenResult.setColumnFinished(typeResult.isColumnFinished());
            contraitToken = typeResult.getToken();
        }
        while (!columFinish && contraitToken != null && contraitToken.trim().length() > 0) {
            columFinish = parseColumnContraint(tokenResult.getToken(), tokenResult.isColumnFinished(), columnDefine);
            if (!columFinish) {
                throw new RuntimeException("column define have't finish");
            }
        }

        defines.add(columnDefine);
    }

    private boolean parseColumnContraint(String token, boolean isColFinished, ColumnDefine columnDefine) {
        List<ColumnConstraint> constraints = columnDefine.getColumnConstraints();
        if (constraints == null) {
            constraints = new ArrayList<>();
            columnDefine.setColumnConstraints(constraints);
        }
        while (true) {
            if ("PRIMARY".equalsIgnoreCase(token)) {
                trim();
                ParseResult<String> tokenResult = parseToken(b -> isSpace(b), (byte) ',', (byte) ')');
                if (!"KEY".equalsIgnoreCase(tokenResult.getToken())) {
                    throw new RuntimeException("PRIMARY KEY not well");
                }
                ColPrimaryKeyConstraint primaryKeyConstraint = new ColPrimaryKeyConstraint();
                if (tokenResult.isColumnFinished()) {
                    constraints.add(primaryKeyConstraint);
                    return true;
                }
                trim();
                tokenResult = parseToken(b -> isSpace(b), (byte)'\'', (byte) ',', (byte) ')');
                if ((tokenResult.getToken() == null || tokenResult.getToken().length() == 0) && tokenResult.isColumnFinished()) {
                    constraints.add(primaryKeyConstraint);
                    return true;
                }
                if ("ASC".equalsIgnoreCase(tokenResult.getToken()) || "DESC".equalsIgnoreCase(tokenResult.getToken())) {
                    primaryKeyConstraint.setSort(tokenResult.getToken().toUpperCase(Locale.ENGLISH));
                } else if ("AUTOINCREMENT".equalsIgnoreCase(tokenResult.getToken())) {
                    primaryKeyConstraint.setAutoIncrement("AUTOINCREMENT");
                } else {
                    token = tokenResult.getToken();
                    continue;
                }
                if (tokenResult.isColumnFinished()) {
                    constraints.add(primaryKeyConstraint);
                    return true;
                }
                trim();
                tokenResult = parseToken(b -> isSpace(b), (byte) ',', (byte) ')');
                if ("AUTOINCREMENT".equalsIgnoreCase(tokenResult.getToken())) {
                    primaryKeyConstraint.setAutoIncrement("AUTOINCREMENT");
                }
                if (tokenResult.isColumnFinished()) {
                    constraints.add(primaryKeyConstraint);
                    return true;
                } else {
                    token = tokenResult.getToken();
                }
            } else if ("NOT".equalsIgnoreCase(token)) {
                trim();
                ParseResult<String> tokenResult = parseToken(b -> isSpace(b), (byte) ',', (byte) ')');
                if (!"NULL".equalsIgnoreCase(tokenResult.getToken())) {
                    throw new RuntimeException("NOT NULL not well");
                }
                ColNotNullConstraint colNotNullConstraint = new ColNotNullConstraint();
                constraints.add(colNotNullConstraint);
                if (tokenResult.isColumnFinished()) {
                    return true;
                }
                trim();
                tokenResult = parseToken(b -> isSpace(b), (byte) ',', (byte) ')');
                if (tokenResult.isColumnFinished()) {
                    return true;
                }
                token = tokenResult.getToken();
            } else if ("UNIQUE".equalsIgnoreCase(token)) {
                ColUniqueConstraint colUniqueConstraint = new ColUniqueConstraint();
                constraints.add(colUniqueConstraint);
                if (isColFinished) {
                    return true;
                }
                trim();
                if (pos >= data.length) {
                    return false;
                }
                ParseResult<String> tokenResult = parseWithSpaceToken(b -> isSpace(b),
                        escapeByte(), (byte)',', (byte)')');
                if (tokenResult.isColumnFinished()) {
                    return true;
                }
                token = tokenResult.getToken();
            } else if ("CHECK".equalsIgnoreCase(token)) {

            } else if ("DEFAULT".equalsIgnoreCase(token)) {
                if (isColFinished) {
                    throw new RuntimeException("DEFAULT value can't empty");
                }
                trim();
                ParseResult<String> tokenResult = parseWithSpaceToken(b -> isSpace(b),
                        (byte)'\'', (byte)',', (byte)')');
                ColDefaultConstraint colDefaultConstraint = new ColDefaultConstraint();
                if (tokenResult.isColumnFinished()) {
                    colDefaultConstraint.setValue(tokenResult.getToken());
                    constraints.add(colDefaultConstraint);
                    return true;
                }
                trim();
                tokenResult = parseWithSpaceToken(b -> isSpace(b),
                        (byte)'\'', (byte)',', (byte)')');
                token = tokenResult.getToken();
            } else if ("GENERATED".equalsIgnoreCase(token)) {

            } else if ("COLLATE".equalsIgnoreCase(token)) {
                ColCollateConstraint colCollateConstraint = new ColCollateConstraint();
                trim();
                ParseResult<String> tokenResult = parseToken(b -> isSpace(b), (byte) ',', (byte) ')');
                constraints.add(colCollateConstraint);
                colCollateConstraint.setCollationName(tokenResult.getToken());
                if (tokenResult.isColumnFinished()) {
                    return true;
                }
                trim();
                tokenResult = parseToken(b -> isSpace(b), (byte) ',', (byte) ')');
                if (tokenResult.isColumnFinished()) {
                    isColFinished = true;
                }
                token = tokenResult.getToken();
            }
            if (pos >= data.length) {
                break;
            }
        }
        return false;
    }

    private void parseTableContrait(String token, String name, CreateTable createTable) {
        if ("PRIMARY".equalsIgnoreCase(token)) {
            trim();
            ParseResult<String> parseResult = parseToken(b -> isSpace(b), (byte)'(', (byte)' ');
            String key = parseResult.getToken();
            if (key == null || !"KEY".equalsIgnoreCase(key)) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " PRIMARY KEY not set");
            }
            int _pos = pos;
            if (data[_pos] != '(') {
                trim();
                _pos = pos;
                if (data[_pos] != '(') {
                    throw new RuntimeException("row " + rowNum + ":" + columNum + " PRIMARY KEY not set");
                }
            }
            _pos++;
            int oldPos = _pos;
            String option = null;
            for (;_pos<data.length;_pos++) {
                byte b = data[_pos];
                if (b == ')') {
                    option = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                    pos = _pos + 1;
                    break;
                }
            }
            if (option == null || option.trim().length() == 0) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " PRIMARY KEY column not set");
            }
            TblPrimaryKeyConstraint primaryKey = new TblPrimaryKeyConstraint();
            primaryKey.setColumns(parseColumns(option));
            primaryKey.setName(name);
            List<TableConstraint> constraints = new ArrayList<>();
            constraints.add(primaryKey);

            createTable.setConstraints(constraints);
        } else if ("CONSTRAINT".equalsIgnoreCase(token)) {
            trim();
            ParseResult<String> parseResult = parseToken(b -> isSpace(b), (byte)'(', (byte)' ');
            String nameToken = parseResult.getToken();
            if (nameToken == null || nameToken.length() == 0) {
                throw new RuntimeException("CONSTRAINT name not set");
            }
            trim();
            parseResult = parseToken(b -> isSpace(b), (byte)'(', (byte)' ');
            String constraintToken = parseResult.getToken();
            if (constraintToken == null || constraintToken.length() == 0) {
                throw new RuntimeException("CONSTRAINT not set");
            }
            parseTableContrait(constraintToken, nameToken, createTable);
        }
    }

    private List<String> parseColumns(String text) {
        text = text.trim();
        List<String> columns = new ArrayList<>();
        int _pos = 0;
        int oldPos = _pos;
        while (_pos < text.length()) {
            // 忽略空格是","
            for (;_pos<text.length();_pos++) {
                char c = text.charAt(_pos);
                if (!isSpace(c) && c != ',') {
                    break;
                }
            }
            oldPos = _pos;
            byte escapeByte = 0;
            if (escapeByte().contains((byte)text.charAt(_pos))) {
                escapeByte = (byte)text.charAt(_pos);
                _pos++;
                String columnName = parseEscapeByteToken(text, _pos, escapeByte);
                columns.add(columnName);
                _pos += columnName.length() + 1;
            } else {
                for (;_pos<text.length();_pos++) {
                    char c = text.charAt(_pos);
                    if (isSpace(c) || c == ',') {
                        String columnName = text.substring(oldPos, _pos);
                        columns.add(columnName);
                        _pos++;
                        break;
                    } else if (_pos == text.length()-1) {
                        String columnName = text.substring(oldPos);
                        columns.add(columnName);
                    }
                }
            }
        }
        return columns;
    }

    private String parseEscapeByteToken(String text, int start, byte escapeByte) {
        int _pos   = start;
        int oldPos = start;
        char c;
        for (;_pos<text.length();_pos++) {
            c = text.charAt(_pos);
            if (escapeByte == (byte)c) {
                return text.substring(oldPos, _pos);
            }
        }
        throw new RuntimeException(text + " not end with [" + (char)escapeByte + "]");
    }

    private void checkIfNotExists(CreateTable createTable) {
        trim();
        String token = nextToken();
        if (!"NOT".equalsIgnoreCase(token)) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start \"NOT EXISTS\"");
        }
        trim();
        token = nextToken();
        if (!"EXISTS".equalsIgnoreCase(token)) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start \"NOT EXISTS\"");
        }
        createTable.setIfNotExist(true);
    }

    private ParseResult parseColumnType() {
        trim();
        String  type           = null;
        boolean hasTypeOption  = false;
        boolean columnFinished = false;
        for (int _pos=pos;_pos<data.length;_pos++) {
            byte b = data[_pos];
            if (isSpace(b)) {
                type = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                pos = _pos;
                trim();
                _pos = pos;
                if (_pos >= data.length) {
                    break;
                }

                b = data[_pos];
                if (b == '(') {
                    hasTypeOption = true;
                    _pos++;
                    pos = _pos;
                    break;
                } else if (b == ',') {
                    pos = _pos;
                    columnFinished = true;
                } else if (b == ')') {
                    pos = _pos;
                    columnFinished = true;
                } else {
                    pos = _pos;
                    break;
                }
            } else if (b == '(') {
                type = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                hasTypeOption = true;
                _pos++;
                pos = _pos;
                break;
            } else if (b == ',') {
                type = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                columnFinished = true;
                pos = _pos;
                break;
            } else if (b == ')') {
                type = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                columnFinished = true;
                pos = _pos;
                break;
            }
        }
        if ((type == null || type.trim().length() == 0) && !columnFinished) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " column type is error");
        }
        Set<DataType> dataTypes = enableDataTypes();
        DataType      dataType  = null;
        boolean       isType    = false;
        if (dataTypes == null || dataTypes.isEmpty()) {
            throw new RuntimeException("enable DataTypes can't empty!");
        } else {
            try {
                dataType = DataType.valueOf(type.toUpperCase(Locale.ENGLISH));
                isType = dataTypes.contains(dataType);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        String option = null;
        if (hasTypeOption) {
            trim();
            int oldPos = pos;
            int _pos   = pos;
            byte b;
            for (_pos=pos;_pos<data.length;_pos++) {
                b = data[_pos];
                if (b == ')') {
                    option = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                    pos = _pos+1;
                    break;
                }
            }
            if (option == null || option.trim().length() == 0) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " column type is error");
            }
        }
        trim();
        if (pos == data.length - 1 && (data[pos] == ',' ||data[pos] == ')')) {
            columnFinished = true;
        }
        ParseResult<DataType> result = new ParseResult();
        if (isType) {
            if (option != null && option.trim().length() > 0) {
                String[] options = option.split(",");
                if (options.length == 1) {
                    dataType.setPrecision(Integer.parseInt(options[0].trim()));
                } else if (options.length == 2) {
                    dataType.setPrecision(Integer.parseInt(options[0].trim()));
                    dataType.setScale(Integer.parseInt(options[1].trim()));
                }
            }
            result.setSuccess(true);
            result.setValue(dataType);
        } else {
            result.setSuccess(false);
            result.setToken(type);
        }
        result.setColumnFinished(columnFinished);

        return result;
    }

    /**
     * 支持的表类型,如TEMP，TEMPORARY等关键字
     * @return
     */
    public abstract Set<String> enableTableType();

    public abstract Set<String> enableTableConstraints();

    public abstract Set<DataType> enableDataTypes();

}
