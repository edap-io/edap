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

import io.edap.json.enums.CommentItemType;
import io.edap.json.enums.JsonVersion;
import io.edap.json.model.CommentItem;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.edap.json.consts.JsonConsts.INVALID_CHAR_FOR_NUMBER;
import static io.edap.json.enums.DataType.BYTE_ARRAY;
import static io.edap.json.model.CommentItem.emptyRow;
import static io.edap.json.model.CommentItem.singleLineComment;
import static io.edap.json.util.JsonUtil.*;
import static io.edap.json.util.JsonUtil.HEX_DIGITS;

public class ByteArrayJson5Reader extends ByteArrayJsonReader {

    public ByteArrayJson5Reader(byte[] bytes) {
        super(bytes);
    }

    @Override
    public Object readObject() {
        readComment();
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
    public int keyHash() {
        long hashCode = 0x811c9dc5;
        char c = firstNotSpaceChar();
        boolean keyFinish = false;
        byte b;
        if (c == '"' || c == '\'') {
            pos++;
            int _pos = pos;
            while (_pos < end) {
                b = json[_pos++];
                if (b == '"') {
                    pos = _pos;
                    keyFinish = true;
                    break;
                }
                hashCode ^= b;
                hashCode *= 0x1000193;
            }
        } else {
            if (!isIdentifierNameFirst(c)) {
                throw new JsonParseException("Key首字符不符合ECMAScript 5.1 IdentifierName标准");
            }
            hashCode ^= c;
            hashCode *= 0x1000193;
            int _pos = pos;
            _pos++;
            for (;_pos<end;_pos++) {
                b = json[_pos];
                if (b == ' ') {
                    pos = _pos + 1;
                    keyFinish = true;
                    break;
                } else if (b == ':') {
                    pos = _pos;
                    keyFinish = true;
                    break;
                }
                if (!isIdentifierNameOther(b)) {
                    throw new JsonParseException("Key字符不符合ECMAScript 5.1 IdentifierName标准");
                }
                hashCode ^= b;
                hashCode *= 0x1000193;
            }
        }
        if (!keyFinish) {
            throw new JsonParseException("Key 没正常结束");
        }
        c = firstNotSpaceChar();
        if (c != ':') {
            throw new JsonParseException("Key and value must use colon split");
        }
        pos++;
        return (int)hashCode;
    }

    protected String readKey(char c) {
        if (c == '"' || c == '\'') {
            pos++;
            String key = readQuotationMarksString('"');
            c = firstNotSpaceChar();
            if (c != ':') {
                throw new JsonParseException("Key and value must use colon split");
            }
            pos++;
            return key;
        } else {
            if (!isIdentifierNameFirst(c)) {
                throw new JsonParseException("Key[" + c + "]首字符不符合ECMAScript 5.1 IdentifierName标准");
            }
            int _pos = pos;
            _pos++;
            byte b;
            for (;_pos<end;_pos++) {
                b = json[_pos];
                if (b == ' ' || b == ':') {
                    String key = new String(json, pos, _pos-pos);
                    pos = _pos + 1;
                    return key;
                }
                if (!isIdentifierNameOther(c)) {
                    throw new JsonParseException("Key字符不符合ECMAScript 5.1 IdentifierName标准");
                }
            }
        }
        return null;
    }

    @Override
    public <T> T readObject(Class<T> valueType) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        JsonDecoder decoder = DECODER_REGISTE.getDecoder(valueType, BYTE_ARRAY, JsonVersion.JSON5);
        if (decoder != null) {
            return (T)decoder.decode(this);
        }
        return null;
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
        } else if (c == 'N') { //null空指针
            int _pos = pos;
            if (_pos+2 < end && json[_pos+1] == 'a' && json[_pos+2] == 'N') {
                pos = _pos + 3;
                return Double.NaN;
            } else {
                throw new JsonParseException("NaN 格式错误");
            }
        } else if (c == 'I') { //null空指针
            int _pos = pos;
            if (pos+8 < end && json[_pos+1] == 'n' && json[_pos+2] == 'f' && json[_pos+3] == 'i'
                    && json[_pos+4] == 'n' && json[_pos+5] == 'i' && json[_pos+6] == 't'
                    && json[_pos+7] == 'y') {
                pos = _pos + 8;
                return Double.POSITIVE_INFINITY;
            } else {
                throw new JsonParseException("Infinity 格式错误");
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

    @Override
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
                    case '\n':
                        bc = '\n';
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

    @Override
    protected JsonObject readObjectValue() {
        JsonObjectImpl jsonObject = new JsonObjectImpl();
        char c = firstNotSpaceChar();
        if (c == '}') {
            return jsonObject;
        }
        readComment();
        c = firstNotSpaceChar();
        String key = readKey(c);
        Object value = readValue();
        jsonObject.put(key, value);
        c = firstNotSpaceChar();
        while (true) {
            if (c == '}') {
                break;
            } else if (c == ',') {
                pos++;
                readComment();
                c = firstNotSpaceChar();
                if (c == '}') {
                    break;
                }
                key = readKey(c);
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

    protected Object readNumberValue() {
        char c1 = firstNotSpaceChar();
        int start = pos;
        try {
            if (c1 == '-') {
                pos++;
                return readNumber0(true);
            } else if (c1 == '+') {
                pos++;
                return readNumber0(false);
            } else {
                return readNumber0(false);
            }
        } catch (Exception e) {
            for (int i=pos;i<end;i++) {
                byte c = json[i];
                if (isNumberEnd(c)) {
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
        int start = pos;
        byte c = json[pos];
        if (c == 'I') {
            int _pos = pos;
            if (start + 7 < end && json[_pos+1] == 'n' && json[_pos+2] == 'f' && json[_pos+3] == 'i'
                    && json[_pos+4] == 'n' && json[_pos+5] == 'i' && json[_pos+6] == 't'
                    && json[_pos+7] == 'y') {
                pos = _pos+8;
                return isNe?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
            } else {
                throw new JsonParseException("Infinity format error");
            }
        } else if (c == '.') {
            pos++;
            int dotPos = pos;
            long div = readLong0(INVALID_CHAR_FOR_NUMBER);
            int len = pos - dotPos;
            double v = ((double) div / POW10[len]);
            return isNe ? -v : v;
        }
        long value = readLong0(INVALID_CHAR_FOR_NUMBER);
        c = json[pos];
        if (c == '.') {
            pos++;
            c = (byte)firstNotSpaceChar();
            if (isNumberEnd(c)) {
                return (double)value;
            }
            int dotPos = pos;
            long div = readLong0(INVALID_CHAR_FOR_NUMBER);
            int len = pos - dotPos;
            double v = value + ((double) div / POW10[len]);
            return isNe ? -v : v;
        } else if (c == 'x') {
            if (pos - start == 1) {
                pos++;
                value = 0;
                for (int i=pos;i<end;i++) {
                    int ind = HEX_DIGITS[json[i]];
                    if (ind == INVALID_CHAR_FOR_NUMBER) {
                        pos = i;
                        return value;
                    }
                    value = (value  << 4) + ind;
                }
            }
        } else if (!isNumberEnd(c)) {
            throw new RuntimeException("");
        } else {
            return isNe ? -value : value;
        }
        return null;
    }

    /**
     * 解析JSON内容之前空行以及注释的内容。
     * @return
     */
    @Override
    public List<CommentItem> readComment() {
        List<CommentItem> items = new ArrayList();

        int _pos = pos;
        int commentStart = -1;
        for (;_pos<end;_pos++) {
            byte c = json[_pos];
            if (c <= ' ' && c != '\n') {
                continue;
            }
            if (c == '\n') {
                if (commentStart == -1) {
                    items.add(emptyRow());
                } else {
                    items.add(singleLineComment(new String(json, commentStart, _pos-commentStart, StandardCharsets.UTF_8)));
                }
                commentStart = -1;
                continue;
            }
            // 如果是"/"开头
            if (commentStart >= 0) {
                continue;
            } else if (c == '/') {
                if (_pos >= end - 1) {
                    throw new JsonParseException("注释非正常结束");
                }
                if (json[_pos+1] == '/') {
                    // 去掉 "//"后面第一个空格
                    if (_pos < end -3 && json[_pos+2] == ' ') {
                        commentStart = _pos+3;
                        _pos = _pos + 3;
                    } else {
                        commentStart = _pos+2;
                        _pos = _pos + 2;
                    }
                } else if (json[_pos+1] == '*') {
                    items.add(readMultilineCommet(_pos + 2));
                    _pos = pos;
                } else {
                    throw new JsonParseException("注释需要以\"//\"后者\"/*\"开始");
                }
            } else {  //如果不是以"/"则为非注释内容
                pos = _pos;
                break;
            }
        }

        return items;
    }

    private CommentItem readMultilineCommet(int pos) {
        CommentItem item = new CommentItem();
        item.setType(CommentItemType.MULTILINE);
        List<String> comments = new ArrayList<>();
        if (json[pos] == ' ') {
            pos++;
        }
        int start = pos;
        boolean isFinish = false;
        for (;pos<end;pos++) {
            byte c = json[pos];
            if (c == '*' && pos < end - 1 && json[pos+1] == '/') {
                if (start > 0) {
                    comments.add(new String(json, start, pos-start, StandardCharsets.UTF_8).trim());
                }
                this.pos = pos + 2;
                isFinish = true;
                break;
            } else if (c == '*' && pos < end - 1 && json[pos+1] == ' ') {
                start = pos + 2;
            } else if (c == '*') {
                start = pos + 1;
            } else if (c == '\n') {
                int commentEnd = pos;
                if (json[pos - 1] == '\r') {
                    commentEnd = pos - 1;
                }
                if (start > 0) {
                    comments.add(new String(json, start, commentEnd-start, StandardCharsets.UTF_8));
                }
                start = -1;
            } else {
                if (start < 0 && c > ' ') {
                    start = pos;
                }
                continue;
            }
        }
        if (!isFinish) {
            throw new JsonParseException("多行注释没正常结束");
        }
        item.setComments(comments);
        return item;
    }
}
