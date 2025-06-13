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
import com.easyea.dbtools.model.ColumnDefine;
import com.easyea.dbtools.model.CreateTable;
import com.easyea.dbtools.tablecontraits.PrimaryKey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public abstract class CreateTableSqlParser {

    private byte[] data;
    private int    pos      = 0;
    private int    rowNum   = 1;   // 当前解析的sql行数
    private int    columNum = 1;   // 当前解析的列数

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
                token = nextToken();
            }
        }
        if (!"TABLE".equalsIgnoreCase(token)) {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        }
        token = nextToken();
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
        token = nextToken();
        if ("AS".equalsIgnoreCase(token)) {
            trim();
            createTable.setSelectStmt(new String(data, pos, data.length-pos, StandardCharsets.UTF_8));

            return createTable;
        }

        if (token.length() == 1) {
            if (!token.equals("(")) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " not start \"(\"");
            }
            trim();
            token = nextToken();
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
            } else {
                if (tableContraits != null && tableContraits.contains(token.toUpperCase(Locale.ENGLISH))) {
                    parseTableContrait(token, createTable);
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
        if (typeResult.success) {
            columnDefine.setDataType(typeResult.value);
        }

        List<ColumnDefine> defines = createTable.getColumns();
        if (defines == null) {
            defines = new ArrayList<>();
            createTable.setColumns(defines);
        }
        defines.add(columnDefine);
    }

    private void parseTableContrait(String token, CreateTable createTable) {
        if ("PRIMARY".equalsIgnoreCase(token)) {
            trim();
            String key = null;
            boolean hasKeyColumns = false;
            int _pos=pos;
            for (;_pos<data.length;_pos++) {
                byte b = data[_pos];
                if (isSpace(b)) {
                    pos = _pos;
                    key = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                    trim();
                    _pos = pos;
                    b = data[_pos];
                    if (b == '(') {
                        hasKeyColumns = true;
                        _pos++;
                        break;
                    }
                } else if (b == '(') {
                    key = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                    hasKeyColumns = true;
                    _pos++;
                    break;
                }
            }
            if (key == null || "KEY".equalsIgnoreCase(key)) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " PRIMARY KEY not set");
            }
            if (!hasKeyColumns) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " PRIMARY KEY not set");
            }
            int oldPos = _pos;
            String option = null;
            for (_pos=pos;_pos<data.length;_pos++) {
                byte b = data[_pos];
                if (b == ')') {
                    option = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                    break;
                }
            }
            if (option == null) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " PRIMARY KEY not set");
            }
            PrimaryKey primaryKey = new PrimaryKey();

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
            if (text.charAt(_pos) == (char) escapeByte()) {
                _pos++;
                String columnName = parseEscapeByteToken(text, _pos, escapeByte());
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
            if (c == (char) escapeByte()) {
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
        createTable.setCreateOption("IF NOT EXISTS");
    }

    static class ParseResult<T> {
        private boolean success;
        private T value;
        private String  token;
    }

    private ParseResult parseColumnType() {
        trim();
        String  type          = null;
        boolean hasTypeOption = false;
        for (int _pos=pos;_pos<data.length;_pos++) {
            byte b = data[_pos];
            if (isSpace(b)) {
                pos = _pos;
                type = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                trim();
                _pos = pos;
                b = data[_pos];
                if (b == '(') {
                    hasTypeOption = true;
                    _pos++;
                    break;
                }
            } else if (b == '(') {
                type = new String(data, pos, _pos-pos, StandardCharsets.UTF_8);
                hasTypeOption = true;
                _pos++;
                break;
            }
        }
        if (type == null) {
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
            int _pos, oldPos = pos;
            byte b;
            for (_pos=pos;_pos<data.length;_pos++) {
                b = data[_pos];
                if (b == ')') {
                    option = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                    break;
                }
            }
            if (option == null) {
                throw new RuntimeException("row " + rowNum + ":" + columNum + " column type is error");
            }
        }
        ParseResult<DataType> result = new ParseResult();
        if (isType) {
            result.success = true;
            result.value = dataType;
        } else {
            result.success = false;
            result.token   = type;
        }

        return result;
    }

    private String nextToken() {
        int oldPos = pos;
        for (int _pos=pos;_pos<data.length;_pos++) {
            byte b = data[_pos];
            if (isSpace(b)) {
                pos = _pos;
                return new String(data, oldPos, _pos-oldPos);
            }
        }
        return new String(data, pos, data.length - pos);
    }

    private boolean isSpace(byte b) {
        return b == ' ' || b == '\r' || b == '\n' || b == '\t';
    }

    private boolean isSpace(char b) {
        return b == ' ' || b == '\r' || b == '\n' || b == '\t';
    }

    private void checkCreateStart() {
        trim();
        int _pos = pos;
        byte b = data[_pos];
        if (b != 'C' && b != 'c') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            _pos++;
        }
        b = data[_pos];
        if (b != 'R' && b != 'r') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            _pos++;
        }
        b = data[_pos];
        if (b != 'E' && b != 'e') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            _pos++;
        }
        b = data[_pos];
        if (b != 'A' && b != 'a') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            _pos++;
        }
        b = data[_pos];
        if (b != 'T' && b != 't') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            _pos++;
        }
        b = data[_pos];
        if (b != 'E' && b != 'e') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            _pos++;
        }
        b = data[_pos];
        if (isSpace(b)) {
            pos = _pos + 1;
        } else {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        }
    }

    private int trim() {
        byte c;
        int oldPos = pos;
        while (pos < data.length) {
            c = data[pos];
            switch (c) {
                case ' ' :
                case '\t':
                case '\r':
                    pos++;
                    columNum++;
                    break;
                case '\n':
                    rowNum++;
                    columNum = 1;
                    break;
                default:
                    int len = pos - oldPos;
                    pos--;
                    return len;
            }
            pos++;
        }
        return pos - oldPos;
    }

    /**
     * 支持的表类型,如TEMP，TEMPORARY等关键字
     * @return
     */
    public abstract Set<String> enableTableType();

    public abstract Set<String> enableTableConstraints();

    public abstract Set<DataType> enableDataTypes();

    public abstract byte escapeByte();
}
