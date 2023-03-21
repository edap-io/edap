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

package io.edap.json;

import io.edap.json.model.ByteArrayDataRange;
import io.edap.json.model.CommentItem;
import io.edap.json.model.DataRange;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.edap.json.StringJsonReader.ERROR_JSON_FORMAT;
import static io.edap.json.consts.JsonConsts.END_OF_NUMBER;
import static io.edap.json.consts.JsonConsts.INVALID_CHAR_FOR_NUMBER;
import static io.edap.json.enums.DataType.BYTE_ARRAY;
import static io.edap.json.util.JsonUtil.*;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class ByteArrayJsonReader implements JsonReader {

    protected int pos;

    protected byte[] json;
    protected int end;

    protected char[] tmpChars;

    static ByteArrayDataRange byteArrayDataRange = new ByteArrayDataRange();

    static JsonCodecRegister DECODER_REGISTE = JsonCodecRegister.instance();

    public ByteArrayJsonReader(byte[] bytes) {
        this(bytes, 64);
    }

    public ByteArrayJsonReader(byte[] bytes, int tmpChars) {
        this.json = bytes;
        this.pos = 0;
        this.end = bytes.length;
        this.tmpChars = new char[tmpChars];
    }

    public void setJsonData(byte[] json) {
        this.json = json;
        this.end = json.length;
    }

    @Override
    public Object readObject() {
        NodeType nodeType = readStart();
        if (nodeType == NodeType.OBJECT) {
            pos++;
            return readObjectValue();
        } else if (nodeType == NodeType.ARRAY) {
            return readArrayValue();
        } else {
            return null;
        }
    }

    @Override
    public <T> T readObject(Class<T> valueType) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        JsonDecoder decoder = DECODER_REGISTE.getDecoder(valueType, BYTE_ARRAY);
        if (decoder != null) {
            return (T)decoder.decode(this);
        }
        return null;
    }

    protected JsonObject readObjectValue() {
        JsonObject jsonObject = new JsonObject();
        char c = firstNotSpaceChar();
        if (c == '}') {
            return jsonObject;
        }
        String key = readKey(c);
        Object value = readValue();
        jsonObject.put(key, value);
        c = firstNotSpaceChar();
        while (true) {
            if (c == '}') {
                break;
            } else if (c == ',') {
                pos++;
                key = readKey();
                value = readValue();
                jsonObject.put(key, value);
                c = firstNotSpaceChar();
            } else {
                if (pos < end) {
                    throw new JsonParseException("key and value 后为不符合json字符[" + (char) c + "]");
                } else {
                    throw new JsonParseException("Json没有正确结束");
                }
            }
        }
        return jsonObject;
    }

    protected String readKey() {
        return readKey(firstNotSpaceChar());
    }

    /**
     * 解析JSON的key，并判断Key后是否是":" 如果不是冒号则抛异常
     * @return
     */
    protected String readKey(char c) {
        if (c != '"') {
            throw new JsonParseException("Key must start with '\"'!");
        }
        pos++;
        String key = readQuotationMarksString('"');
        c = firstNotSpaceChar();
        if (c != ':') {
            throw new JsonParseException("Key and value must use colon split");
        }
        pos++;
        return key;
    }

    protected String readQuotationMarksString(char quotation) {
        int _pos = pos;
        //byte[] _json = json;
        int charLen = 0;
        byte c = 0;
        char[] chars = tmpChars;
        int clen = chars.length;
        try {
            for (; charLen < clen; _pos++) {
                c = json[_pos];
                if (c == (byte) quotation) {
                    pos = _pos + 1;
                    return new String(chars, 0, charLen);
                }
                if ((c ^ '\\') < 1) {
                    break;
                }
                chars[charLen++] = (char) c;
            }
            if (_pos >= end) {
                throw new JsonParseException("JSON string was not closed with a char[" + quotation + "]");
            }
        } catch (ArrayIndexOutOfBoundsException ignore) {
            throw new JsonParseException("JSON string was not closed with a char[" + quotation + "]");
        }
        if (charLen == clen) {
            chars = tmpChars = Arrays.copyOf(chars, clen * 2);
            clen = chars.length;
        }
        int bc;
        while (_pos != end) {
            bc = json[_pos++];
            if (bc == quotation) {
                this.pos = _pos--;
                return new String(chars, 0, charLen);
            }
            if (bc == '\\') {
                if (charLen == clen) {
                    chars = tmpChars = Arrays.copyOf(chars, clen * 2);
                    clen = chars.length;
                }
                bc = json[_pos++];
                switch (bc) {
                    case 'b':
                        bc = '\b';
                        break;
                    case 't':
                        bc = '\t';
                        break;
                    case 'n':
                        bc = '\n';
                        break;
                    case 'f':
                        bc = '\f';
                        break;
                    case 'r':
                        bc = '\r';
                        break;
                    case '"':
                    case '/':
                    case '\\':
                        break;
                    case 'u':
                        bc = (hexToInt(json[_pos++]) << 12) +
                                (hexToInt(json[_pos++]) << 8) +
                                (hexToInt(json[_pos++]) << 4) +
                                hexToInt(json[_pos++]);
                        break;

                    default:
                        throw new JsonParseException("Could not parse String at position: " + (pos - 1)
                                + ". Invalid escape combination detected: '\\" + bc + "'");

                }
            } else if ((bc & 0x80) != 0) {
                if (charLen + 1 == clen) {
                    chars = tmpChars = Arrays.copyOf(chars, clen * 2);
                    clen = chars.length;
                }
                final int u2 = json[_pos++];
                if ((bc & 0xE0) == 0xC0) {
                    bc = ((bc & 0x1F) << 6) + (u2 & 0x3F);
                } else {
                    final int u3 = json[_pos++];
                    if ((bc & 0xF0) == 0xE0) {
                        bc = ((bc & 0x0F) << 12) + ((u2 & 0x3F) << 6) + (u3 & 0x3F);
                    } else {
                        final int u4 = json[_pos++];
                        if ((bc & 0xF8) == 0xF0) {
                            bc = ((bc & 0x07) << 18) + ((u2 & 0x3F) << 12) + ((u3 & 0x3F) << 6) + (u4 & 0x3F);
                        } else {
                            // there are legal 5 & 6 byte combinations, but none are _valid_
                            throw new JsonParseException("Invalid unicode character detected at: " + pos);
                        }

                        if (bc >= 0x10000) {
                            // check if valid unicode
                            if (bc >= 0x110000) {
                                throw new JsonParseException("Invalid unicode character detected at: " + pos);
                            }

                            // split surrogates
                            final int sup = bc - 0x10000;
                            chars[charLen++] = (char) ((sup >>> 10) + 0xd800);
                            chars[charLen++] = (char) ((sup & 0x3ff) + 0xdc00);
                            continue;
                        }
                    }
                }
            } else if (charLen == clen) {
                chars = tmpChars = Arrays.copyOf(chars, clen * 2);
                clen = chars.length;
            }
            chars[charLen++] = (char) bc;
        }
        throw new JsonParseException("JSON string was not closed with a char[" + quotation + "]");

    }

    protected static int hexToInt(final byte value) {
        if (value >= '0' && value <= '9') return value - 0x30;
        if (value >= 'A' && value <= 'F') return value - 0x37;
        if (value >= 'a' && value <= 'f') return value - 0x57;
        throw new JsonParseException("Could not parse unicode escape, expected a hexadecimal digit, got '" + value + "'");
    }

    protected Object readValue() {
        char c = firstNotSpaceChar();
        // 解析字符串
        if (c == '"' || c == '\'') {
            pos++;
            return readQuotationMarksString(c);
        } else if (c == '[') { // 数组对象
            return readArrayValue();
        } else if (c == '{') { // JSON对象
            pos++;
            return readObjectValue();
        } else if (c == 'n') { //null空指针
            int _pos = pos;
            byte[] _json = json;
            if (_pos+3 < end && _json[_pos+1] == 'u' && _json[_pos+2] == 'l'
                    && _json[_pos+3] == 'l') {
                pos = _pos + 4;
                return null;
            } else {
                throw new JsonParseException("null 格式错误");
            }
        } else if (c == 't') {
            int _pos = pos;
            byte[] _json = json;
            if (_pos+3 < end && _json[_pos+1] == 'r' && _json[_pos+2] == 'u'
                    && _json[_pos+3] == 'e') {
                pos = _pos + 4;
                return true;
            } else {
                throw new JsonParseException("boolean 格式错误");
            }
        } else if (c == 'f') {
            int _pos = pos;
            byte[] _json = json;
            if (_pos+4 < end && _json[_pos+1] == 'a' && _json[_pos+2] == 'l'
                    && _json[_pos+3] == 's' && _json[_pos+4] == 'e') {
                pos = _pos + 5;
                return false;
            } else {
                throw new JsonParseException("boolean 格式错误");
            }
        } else {
            return readNumberValue();
        }
    }

    protected Object readNumberValue() {
        char c1 = firstNotSpaceChar();
        int start = pos;
        try {
            if (c1 == '-') {
                pos++;
                return readNumber0(true);
            } else {
                return readNumber0(false);
            }
        } catch (Exception e) {
            for (int i=pos;i<end;i++) {
                byte c = json[i];
                if (c == ' ' || c == ',' || c == ']' || c == '}') {
                    String num = new String(json, start, i-start);
                    pos = i;
                    return Double.parseDouble(num);
                }
            }
            String num = new String(json, start, end-start);
            pos = end;
            return Double.parseDouble(num);
        }
    }

    private Object readNumber0(boolean isNe) {
        long value = readLong0(INVALID_CHAR_FOR_NUMBER);
        byte c = json[pos];
        if (c == '.') {
            pos++;
            int dotPos = pos;
            try {
                long div = readLong0(INVALID_CHAR_FOR_NUMBER);
                int len = pos - dotPos;
                double v = value + ((double) div / POW10[len]);
                return isNe?-v:v;
            } catch (Exception e) {
                throw new JsonParseException("double类型\".\"没有其他数字");
            }
        } else {
            return isNe?-value:value;
        }
    }

    public List<Object> readArrayValue() {
        pos++;
        char startChar = firstNotSpaceChar();
        if (startChar == ']') {
            return Collections.emptyList();
        }
        Object v = readValue();
        List<Object> vs = new ArrayList<>();
        vs.add(v);
        while (true) {
            startChar = firstNotSpaceChar();
            if (startChar == ']') {
                pos++;
                break;
            } else if (startChar == ',') {
                pos++;
                startChar = firstNotSpaceChar();
                if (startChar == ']') {
                    pos++;
                    break;
                } else if (startChar == ',') {
                    continue;
                } else {
                    vs.add(readValue());
                }
            } else {
                throw new JsonParseException("数组格式不正确");
            }
        }
        return vs;
    }

    @Override
    public DataRange readKeyRange() {
        return readKeyRange(firstNotSpaceChar());
    }

    @Override
    public int keyHash() {
        char c = firstNotSpaceChar();
        if (c != '"') {
            throw new JsonParseException("Key must start with '\"'!");
        }
        pos++;
        int _pos = pos;
        long hashCode = 0x811c9dc5;
        byte b;
        while (_pos<end) {
            b = json[_pos++];
            if (b == '"') {
                pos = _pos;
                break;
            }
            hashCode ^= b;
            hashCode *= 0x1000193;
        }
        c = firstNotSpaceChar();
        if (c != ':') {
            throw new JsonParseException("Key and value must use colon split");
        }
        pos++;
        return (int)hashCode;
    }

    /**
     * 解析JSON的key，并判断Key后是否是":" 如果不是冒号则抛异常
     * @return
     */
    protected DataRange readKeyRange(char c) {
        if (c != '"') {
            throw new JsonParseException("Key must start with '\"'!");
        }
        pos++;
        DataRange keyRange = readQuotationMarksDataRange('"');
        c = firstNotSpaceChar();
        if (c != ':') {
            throw new JsonParseException("Key and value must use colon split");
        }
        pos++;
        return keyRange;
    }

    protected DataRange readQuotationMarksDataRange(char quotation) {
        int _pos = pos;
        long hashCode = FNV_1a_INIT_VAL;
        byte[] _json = json;
        while (_pos<end) {
            byte c = _json[_pos++];
            if (c == quotation) {
                byteArrayDataRange.fill(_json, pos, _pos-1, (int)hashCode);
                pos = _pos;
                return byteArrayDataRange;
            } else {
                hashCode ^= c;
                hashCode *= FNV_1a_FACTOR_VAL;
            }
        }
        throw new JsonParseException("Key没有正确结束");
    }

    @Override
    public NodeType readStart() {
        char startChar = firstNotSpaceChar();
        if (startChar == '{') {
            return NodeType.OBJECT;
        } else if (startChar == '[') {
            return NodeType.ARRAY;
        } else if (startChar == 0) {
            return NodeType.EMPTY;
        }
        throw ERROR_JSON_FORMAT;
    }

    @Override
    public void nextPos(int count) {
        if (pos + count <= end) {
            pos += count;
        } else {
            throw new IndexOutOfBoundsException("pos + count > " + end);
        }
    }

    @Override
    public char firstNotSpaceChar() {
        if (end - pos == 0) {
            return 0;
        }
        int _pos = pos;
        //byte[] _json = json;
        byte c;
        c = json[_pos];
        if (c < 0 || c > ' ') {
            pos = _pos;
            return (char)c;
        }
        _pos++;
        if (_pos < end) {
            c = json[_pos];
            if (c < 0 || c > ' ') {
                pos = _pos;
                return (char)c;
            }
            _pos++;
        }
        if (_pos < end) {
            c = json[_pos];
            if (c < 0 || c > ' ') {
                pos = _pos;
                return (char)c;
            }
            _pos++;
        }
        if (_pos < end) {
            c = json[_pos];
            if (c < 0 || c > ' ') {
                pos = _pos;
                return (char)c;
            }
            _pos++;
        }
        if (_pos < end) {
            c = json[_pos];
            if (c < 0 || c > ' ') {
                pos = _pos;
                return (char)c;
            }
            _pos++;
        }
        if (_pos < end) {
            c = json[_pos];
            if (c < 0 || c > ' ') {
                pos = _pos;
                return (char)c;
            }
            _pos++;
        }
        for (;_pos<end;_pos++) {
            c = json[_pos];
            if (c < 0 || c > ' ') {
                pos = _pos;
                return (char)c;
            }
        }
        return 0;
    }

    @Override
    public String readString() {
        char c = firstNotSpaceChar();
        // 解析字符串
        if (c == '"') {
            pos++;
            return readQuotationMarksString(c);
        }
        throw new JsonParseException("字符串没有使用引号");
    }

    @Override
    public int readInt() {
        char c1 = firstNotSpaceChar();
        if (c1 == '-') {
            pos++;
            return -readInt0(INVALID_CHAR_FOR_NUMBER);
        } else {
            return readInt0(INVALID_CHAR_FOR_NUMBER);
        }
    }

    public int readInt0() {
        return readInt0(INVALID_CHAR_FOR_NUMBER);
    }

    public int readInt0(int endType) {
        int _pos = pos;
        byte c1 = json[_pos++];
        int c2;
        try {
            int ind = INT_DIGITS[c1];
            if (ind == 0) {
                if (_pos < end) {
                    c2 = json[_pos];
                    if (INT_DIGITS[c2] == endType) {
                        pos = _pos;
                        return 0;
                    }
                }
                throw new JsonParseException("整数不能有前导0的字符");
            } else if (ind == INVALID_CHAR_FOR_NUMBER) {
                throw new JsonParseException("整数不符合规范");
            }
            if (end - _pos > 8) {
                int ind2 = INT_DIGITS[json[_pos++]];
                if (ind2 == endType) {
                    pos = _pos;
                    return ind;
                }
                int ind3 = INT_DIGITS[json[_pos++]];
                if (ind3 == endType) {
                    pos = _pos - 1;
                    return ind * 10 + ind2;
                }
                int ind4 = INT_DIGITS[json[_pos++]];
                if (ind4 == endType) {
                    pos = _pos - 1;
                    return ind * 100 + ind2*10 + ind3;
                }
                int ind5 = INT_DIGITS[json[_pos++]];
                if (ind5 == endType) {
                    pos = _pos;
                    return ind * 1000 + ind2*100 + ind3*10 + ind4;
                }
                int ind6 = INT_DIGITS[json[_pos++]];
                if (ind6 == endType) {
                    pos = _pos - 1;
                    return ind * 10000 + ind2*1000 + ind3*100 + ind4*10 + ind5;
                }
                int ind7 = INT_DIGITS[json[_pos++]];
                if (ind7 == endType) {
                    pos = _pos - 1;
                    return ind * 100000 + ind2*10000 + ind3*1000 + ind4*100 + ind5*10 + ind6;
                }
                int ind8 = INT_DIGITS[json[_pos++]];
                if (ind8 == endType) {
                    pos = _pos - 1;
                    return ind * 1000000 + ind2*100000 + ind3*10000 + ind4*1000 + ind5*100 + ind6*10
                            + ind7;
                }
                int ind9 = INT_DIGITS[json[_pos++]];
                if (ind9 == endType) {
                    pos = _pos - 1;
                    return ind * 10000000 + ind2*1000000 + ind3*100000 + ind4*10000 + ind5*1000
                            + ind6*100 + ind7*10 + ind8;
                }
                int ind10 = INT_DIGITS[json[_pos++]];
                if (ind10 == endType) {
                    pos = _pos - 1;
                    return ind * 100000000 + ind2*10000000 + ind3*1000000 + ind4*100000 + ind5*10000
                            + ind6*1000 + ind7*100 + ind8*10 + ind9;
                }
                pos++;
                return readIntSlowPath(ind, endType);
            } else {
                pos = _pos;
                return readIntSlowPath(ind, endType);
            }
        } catch (Throwable t) {
            if (t instanceof JsonParseException) {
                throw t;
            }
            throw new JsonParseException("整数不符合规范");
        }
    }

    public int readIntSlowPath(int value, int endType) {
        int _pos = pos;
        for (;_pos<end;_pos++) {
            int ind = INT_DIGITS[json[_pos]];
            if (ind == endType) {
                pos = _pos;
                return value;
            }
            value = (value << 3) + (value << 1) + ind;
            if (value < 0) {
                throw new JsonParseException("value is too large for int");
            }
        }
        throw new JsonParseException("整数没有正确结束");
    }

    @Override
    public long readLong() {
        char c1 = firstNotSpaceChar();
        if (c1 == '-') {
            pos++;
            return -readLong0(INVALID_CHAR_FOR_NUMBER);
        } else {
            return readLong0(INVALID_CHAR_FOR_NUMBER);
        }
    }

    public long readLong0() {
        return readLong0(INVALID_CHAR_FOR_NUMBER);
    }

    public long readLong0(int endType) {
        int _pos = pos;
        byte c1 = json[_pos++];
        int c2;
        try {
            long ind = INT_DIGITS[c1];
            if (ind == 0) {
                if (_pos < end) {
                    c2 = json[_pos];
                    if (INT_DIGITS[c2] == endType) {
                        pos = _pos;
                        return 0;
                    }
                }
                throw new JsonParseException("整数不能有前导0的字符");
            } else if (ind == INVALID_CHAR_FOR_NUMBER) {
                throw new JsonParseException("整数不符合规范");
            }
            if (end - _pos > 8) {
                int ind2 = INT_DIGITS[json[_pos++]];
                if (ind2 == endType) {
                    pos = _pos - 1;
                    return ind;
                }
                int ind3 = INT_DIGITS[json[_pos++]];
                if (ind3 == endType) {
                    pos = _pos - 1;
                    return ind * 10 + ind2;
                }
                int ind4 = INT_DIGITS[json[_pos++]];
                if (ind4 == endType) {
                    pos = _pos - 1;
                    return ind * 100 + ind2*10 + ind3;
                }
                int ind5 = INT_DIGITS[json[_pos++]];
                if (ind5 == endType) {
                    pos = _pos;
                    return ind * 1000 + ind2*100 + ind3*10 + ind4;
                }
                int ind6 = INT_DIGITS[json[_pos++]];
                if (ind6 == endType) {
                    pos = _pos - 1;
                    return ind * 10000 + ind2*1000 + ind3*100 + ind4*10 + ind5;
                }
                int ind7 = INT_DIGITS[json[_pos++]];
                if (ind7 == endType) {
                    pos = _pos - 1;
                    return ind * 100000 + ind2*10000 + ind3*1000 + ind4*100 + ind5*10 + ind6;
                }
                int ind8 = INT_DIGITS[json[_pos++]];
                if (ind8 == endType) {
                    pos = _pos - 1;
                    return ind * 1000000 + ind2*100000 + ind3*10000 + ind4*1000 + ind5*100 + ind6*10
                            + ind7;
                }
                int ind9 = INT_DIGITS[json[_pos++]];
                if (ind9 == endType) {
                    pos = _pos - 1;
                    return ind * 10000000 + ind2*1000000 + ind3*100000 + ind4*10000 + ind5*1000
                            + ind6*100 + ind7*10 + ind8;
                }
                int ind10 = INT_DIGITS[json[_pos++]];
                if (ind10 == endType) {
                    pos = _pos - 1;
                    return ind * 100000000 + ind2*10000000 + ind3*1000000 + ind4*100000 + ind5*10000
                            + ind6*1000 + ind7*100 + ind8*10 + ind9;
                } else {
                    pos = _pos--;
                    ind = ind * 1000000000 + ind2*100000000 + ind3*10000000 + ind4*1000000 + ind5*100000
                            + ind6*10000 + ind7*1000 + ind8*100 + ind9*10 + ind10;
                }
                return readLongSlowPath(ind, endType);
            } else {
                pos = _pos;
                return readLongSlowPath(ind, endType);
            }
        } catch (Throwable t) {
            if (t instanceof JsonParseException) {
                throw t;
            }
            throw new JsonParseException("整数不符合规范");
        }
    }

    private long readLongSlowPath(long value, int endType) {
        int _pos = pos;
        for (;_pos<end;_pos++) {
            int ind = INT_DIGITS[json[_pos]];
            if (ind == endType) {
                pos = _pos;
                return value;
            }
            value = (value << 3) + (value << 1) + ind;
            if (value < 0) {
                throw new JsonParseException("value is too large for long");
            }
        }
        throw new JsonParseException("整数没有正确结束");
    }

    @Override
    public boolean readBoolean() {
        char c = firstNotSpaceChar();
        if (c == 't') {
            int _pos = pos;
            if (_pos+3 < end && json[_pos+1] == 'r' && json[_pos+2] == 'u'
                    && json[_pos+3] == 'e') {
                pos = _pos + 4;
                return true;
            } else {
                throw new JsonParseException("boolean 格式错误");
            }
        } else if (c == 'f') {
            int _pos = pos;
            if (_pos+4 < end && json[_pos+1] == 'a' && json[_pos+2] == 'l'
                    && json[_pos+3] == 's' && json[_pos+4] == 'e') {
                pos = _pos + 5;
                return false;
            } else {
                throw new JsonParseException("boolean 格式错误");
            }
        }
        throw new JsonParseException("boolean 格式错误");
    }

    @Override
    public void skipValue() {
        int c = firstNotSpaceChar();
        if (c == '"') {
            skipStringValue('"');
        } else if (c == '{') {
            pos++;
            skipObjectValue();
        } else if (c == '[') {
            pos++;
            skipArrayValue();
        } else {
            int _pos = pos;
            for (;_pos<end;_pos++) {
                c = json[_pos];
                if (c == ',' || c == '}' || c == ']') {
                    pos = _pos;
                    return;
                }
            }
        }
    }

    private void skipArrayValue() {
        skipValue();
        char c = firstNotSpaceChar();
        while (true) {
            if (c == ']') {
                pos++;
                return;
            } else if (c == ',') {
                pos++;
                skipValue();
                c = firstNotSpaceChar();
            } else {
                throw new JsonParseException("数组格式错误");
            }
        }
    }

    private void skipObjectValue() {
        char c = firstNotSpaceChar();
        if (c == '}') {
            return;
        }
        skipKey(c);
        skipValue();
        c = firstNotSpaceChar();
        while (true) {
            if (c == '}') {
                pos++;
                return;
            } else if (c == ',') {
                pos++;
                skipKey(firstNotSpaceChar());
                skipValue();
                c = firstNotSpaceChar();
            } else {
                throw new JsonParseException("key and value 后为不符合json字符[" + (char)c + "]");
            }
        }
    }

    protected void skipKey(char c) {
        if (c != '"') {
            throw new JsonParseException("Key must start with '\"'!");
        }
        pos++;
        skipStringValue(c);
        c = firstNotSpaceChar();
        if (c != ':') {
            throw new JsonParseException("Key and value must use colon split");
        }
        pos++;
    }

    public void skipStringValue(char quotation) {
        int _pos = pos;
        for (;_pos<end;_pos++) {
            byte c = json[_pos];
            if (c == quotation && _pos > pos && json[_pos-1] != '\\') {
                pos = _pos+1;
                break;
            } else {
                if (c == '\\' && _pos+1 < end) {
                    continue;
                }
            }
        }
    }

    @Override
    public float readFloat() {
        char c1 = firstNotSpaceChar();
        int startPos = pos;
        if (c1 == '-') {
            pos++;
            return -readFloat0(startPos);
        } else {
            float v = readFloat0(startPos);
            return v;
        }
    }

    @Override
    public List<CommentItem> readComment() {
        throw new UnsupportedOperationException("Standard json not support comments");
    }

    private float readFloat0(int startPos) {
        long value = readLong0(INVALID_CHAR_FOR_NUMBER);
        byte c = json[pos];
        if (c == '.') {
            pos++;
            int dotPos = pos;
            try {
                long div = readLong0(INVALID_CHAR_FOR_NUMBER);
                int len = pos - dotPos;
                return (value + ((float) div / POW10[len]));
            } catch (Exception e) {
                throw new JsonParseException("double类型\".\"没有其他数字");
            }
        } else {
            return value;
        }
    }

    @Override
    public double readDouble() {
        char c1 = firstNotSpaceChar();
        int startPos = pos;
        if (c1 == '-') {
            pos++;
            return -readDouble0(startPos);
        } else {
            double v = readDouble0(startPos);
            return v;
        }
    }

    private double readDouble0(int startPos) {
        long value = readLong0(INVALID_CHAR_FOR_NUMBER);
        byte c = json[pos];
        if (c == '.') {
            pos++;
            int dotPos = pos;
            try {
                long div = readLong0(INVALID_CHAR_FOR_NUMBER);
                int len = pos - dotPos;
                return value + ((double) div / POW10[len]);
            } catch (Exception e) {
                throw new JsonParseException("double类型\".\"没有其他数字");
            }
        } else {
            return value;
        }
    }

    @Override
    public void reset() {
        pos = 0;
    }


}
