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

import java.nio.charset.StandardCharsets;
import java.util.Set;

public abstract class SqlParser {

    protected byte[] data;
    protected int    pos      = 0;
    protected int    rowNum   = 1;   // 当前解析的sql行数
    protected int    columNum = 1;   // 当前解析的列数

    protected String nextToken() {
        int oldPos = pos;
        if (data[oldPos] == ',') {
            return ",";
        }
        for (int _pos=pos;_pos<data.length;_pos++) {
            byte b = data[_pos];
            if (isSpace(b)) {
                pos = _pos;
                return new String(data, oldPos, _pos-oldPos);
            }
        }
        return new String(data, pos, data.length - pos);
    }

    protected boolean isSpace(byte b) {
        return b == ' ' || b == '\r' || b == '\n' || b == '\t';
    }

    protected boolean isSpace(char b) {
        return b == ' ' || b == '\r' || b == '\n' || b == '\t';
    }

    protected ParseResult<String> parseWithSpaceToken(ByteIsSpace byteIsSpace, byte escapeByte, byte... endBytes) {
        if (escapeByte == data[pos]) {
            int     _pos       = pos + 1;
            int     oldPos     = _pos;
            String  token      = null;
            for (;_pos<data.length;_pos++) {
                byte b = data[_pos];
                if (b == escapeByte) {
                    if (_pos > oldPos + 1 && data[_pos-1] == '\\') {
                    } else {
                        token = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                        pos = _pos + 1;
                        break;
                    }
                }
            }
            if (token == null) {
                throw new RuntimeException("String not finish");
            }
            ParseResult<String> result = new ParseResult<>();
            result.token = token;

            trim();
            if (pos >= data.length) {
                result.columnFinished = false;
                return result;
            }
            byte b = data[pos];
            boolean finish = false;
            for (int i=0;i<endBytes.length;i++) {
                if (b == endBytes[i]) {
                    finish = true;
                    break;
                }
            }
            result.columnFinished = finish;

            return result;
        } else {
            return parseToken(byteIsSpace, endBytes);
        }
    }

    protected ParseResult<String> parseWithSpaceToken(ByteIsSpace byteIsSpace, Set<Byte> escapeBytes, byte... endBytes) {
        if (escapeBytes.contains(data[pos])) {
            byte    escapeByte = data[pos];
            int     _pos       = pos + 1;
            int     oldPos     = _pos;
            String  token      = null;
            for (;_pos<data.length;_pos++) {
                byte b = data[_pos];
                if (b == escapeByte) {
                    if (_pos > oldPos + 1 && data[_pos-1] == '\\') {
                    } else {
                        token = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                        pos = _pos + 1;
                        break;
                    }
                }
            }
            if (token == null) {
                throw new RuntimeException("String not finish");
            }
            ParseResult<String> result = new ParseResult<>();
            result.token   = token;

            trim();
            if (pos >= data.length) {
                result.columnFinished = false;
                return result;
            }
            byte b = data[pos];
            boolean finish = false;
            for (int i=0;i<endBytes.length;i++) {
                if (b == endBytes[i]) {
                    finish = true;
                    result.setEndByte(b);
                    break;
                }
            }
            result.columnFinished = finish;

            return result;
        } else {
            return parseToken(byteIsSpace, endBytes);
        }
    }

    protected ParseResult<String> parseToken(ByteIsSpace byteIsSpace, byte... endBytes) {
        int     _pos     = pos;
        int     oldPos   = pos;
        String  token    = null;
        boolean isFinish = false;
        byte    endBye   = 0;
        for (;_pos<data.length;_pos++) {
            byte b = data[_pos];
            if (byteIsSpace.space(b)) {
                token = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                endBye = ' ';
                pos = _pos;
                break;
            } else {
                boolean finish = false;
                for (int i=0;i<endBytes.length;i++) {
                    if (b == endBytes[i]) {
                        token = new String(data, oldPos, _pos - oldPos, StandardCharsets.UTF_8);
                        pos = _pos;
                        finish = true;
                        endBye = b;
                        break;
                    }
                }
                if (finish) {
                    isFinish = true;
                    break;
                }
            }
        }
        if (endBye == ' ' && endBytes.length > 0) {
            trim();
            if (pos < data.length) {
                byte b = data[pos];
                for (int i = 0; i < endBytes.length; i++) {
                    if (b == endBytes[i]) {
                        endBye = b;
                        break;
                    }
                }
            }
        }

        ParseResult<String> result = new ParseResult<>();
        result.token = token;
        result.setEndByte(endBye);
        if (isFinish) {
            result.columnFinished = isFinish;
            return result;
        }
        if (token == null || token.trim().length() == 0) {
            throw new RuntimeException("token can't is empty");
        }

        result.success = true;

        return result;
    }

    protected int trim() {
        byte c;
        int oldPos = pos;
        while (pos < data.length) {
            c = data[pos];
            switch (c) {
                case ' ' :
                case '\t':
                case '\r':
                    columNum++;
                    break;
                case '\n':
                    rowNum++;
                    columNum = 1;
                    break;
                default:
                    int len = pos - oldPos;
                    return len;
            }
            pos++;
        }
        int len = pos - oldPos;
//        if (pos == data.length) {
//            pos = pos - 1;
//        }
        return len;
    }

    protected void checkCreateStart() {
        trim();
        int _pos = pos;
        byte b = data[_pos];
        if (b != 'C' && b != 'c') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            columNum++;
            _pos++;
        }
        b = data[_pos];
        if (b != 'R' && b != 'r') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            columNum++;
            _pos++;
        }
        b = data[_pos];
        if (b != 'E' && b != 'e') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            columNum++;
            _pos++;
        }
        b = data[_pos];
        if (b != 'A' && b != 'a') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            columNum++;
            _pos++;
        }
        b = data[_pos];
        if (b != 'T' && b != 't') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            columNum++;
            _pos++;
        }
        b = data[_pos];
        if (b != 'E' && b != 'e') {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        } else {
            columNum++;
            _pos++;
        }
        b = data[_pos];
        if (isSpace(b)) {
            pos = _pos + 1;
        } else {
            throw new RuntimeException("row " + rowNum + ":" + columNum + " not start CREATE");
        }
    }

    public abstract Set<Byte> escapeByte();

    public static class ParseResult<T> {
        private boolean success;
        private T       value;
        private String  token;
        private boolean columnFinished;
        private byte    endByte;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public T getValue() {
            return value;
        }

        public void setValue(T value) {
            this.value = value;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isColumnFinished() {
            return columnFinished;
        }

        public void setColumnFinished(boolean columnFinished) {
            this.columnFinished = columnFinished;
        }

        public byte getEndByte() {
            return endByte;
        }

        public void setEndByte(byte endByte) {
            this.endByte = endByte;
        }
    }
}
