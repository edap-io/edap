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
import java.util.ArrayList;
import java.util.List;

import static io.edap.json.consts.JsonConsts.INVALID_CHAR_FOR_NUMBER;
import static io.edap.json.enums.DataType.STRING;
import static io.edap.json.model.CommentItem.emptyRow;
import static io.edap.json.model.CommentItem.singleLineComment;
import static io.edap.json.util.JsonUtil.*;

public class StringJson5Reader extends StringJsonReader {

    public StringJson5Reader(String jsonStr) {
        super(jsonStr);
    }

    @Override
    public JsonObject readObjectValue() {
        JsonObject jsonObject = new JsonObject();
        // 忽略注释内容
        readComment();
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
                c = firstNotSpaceChar();
                if (c == '}') {
                    break;
                }
                // 忽略注释内容
                readComment();
                key = readKey();
                value = readValue();
                jsonObject.put(key, value);
                c = firstNotSpaceChar();
            } else {
                if (pos < end) {
                    throw new JsonParseException("key and value 后为不符合json字符[" + c + "]");
                } else {
                    throw new JsonParseException("Json没有正确结束");
                }
            }
        }
        return jsonObject;
    }

    /**
     * JSON5的key可以不以引号引起来，使用符合解析JSON的key，并判断Key后是否是":" 如果不是冒号则抛异常
     * @return
     */
    @Override
    protected String readKey(char c) {
        if (c == '"' || c == '\'') {
            pos++;
            String key = readQuotationMarksString(c);
            c = firstNotSpaceChar();
            if (c != ':') {
                throw new JsonParseException("Key and value must use colon split");
            }
            pos++;
            return key;
        } else {
            if (!isIdentifierNameFirst(c)) {
                throw new JsonParseException("Key首字符不符合ECMAScript 5.1 IdentifierName标准");
            }
            int _pos = pos;
            String _json = json;
            _pos++;
            for (;_pos<end;_pos++) {
                c = _json.charAt(_pos);
                if (c == ' ' || c == ':') {
                    String key = _json.substring(pos, _pos);
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
            if (_pos+3 < end && json.charAt(_pos+1) == 'u' && json.charAt(_pos+2) == 'l'
                    && json.charAt(_pos+3) == 'l') {
                pos = _pos + 4;
                return null;
            } else {
                throw new JsonParseException("null 格式错误");
            }
        } else if (c == 'N') {
            int _pos = pos;
            if (pos+2 < end && json.charAt(pos+1) == 'a' && json.charAt(pos+2) == 'N') {
                pos = _pos + 3;
                return Double.NaN;
            } else {
                throw new JsonParseException("NaN 格式错误");
            }
        } else if (c == 'I') { //Infinity
            int _pos = pos;
            if (pos+8 < end && json.charAt(_pos+1) == 'n' && json.charAt(_pos+2) == 'f' && json.charAt(_pos+3) == 'i'
                    && json.charAt(_pos+4) == 'n' && json.charAt(_pos+5) == 'i' && json.charAt(_pos+6) == 't'
                    && json.charAt(_pos+7) == 'y') {
                pos = _pos + 8;
                return Double.POSITIVE_INFINITY;
            } else {
                throw new JsonParseException("Infinity 格式错误");
            } //Infinity
        } else if (c == 't') {
            int _pos = pos;
            String _json = json;
            if (_pos+3 < end && _json.charAt(_pos+1) == 'r' && _json.charAt(_pos+2) == 'u'
                    && _json.charAt(_pos+3) == 'e') {
                pos = _pos + 4;
                return true;
            } else {
                throw new JsonParseException("boolean 格式错误");
            }
        } else if (c == 'f') {
            int _pos = pos;
            String _json = json;
            if (_pos+4 < end && _json.charAt(_pos+1) == 'a' && _json.charAt(_pos+2) == 'l'
                    && _json.charAt(_pos+3) == 's' && _json.charAt(_pos+4) == 'e') {
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
    public NodeType readStart() {
        readComment();
        return super.readStart();
    }

    @Override
    public <T> T readObject(Class<T> valueType) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        JsonDecoder decoder = DECODER_REGISTE.getDecoder(valueType, STRING, JsonVersion.JSON5);
        if (decoder != null) {
            return (T)decoder.decode(this);
        }
        return null;
    }

    @Override
    protected String readQuotationMarksString(char quotation) {
        int _pos = pos;
        String _json = json;
        StringBuilder key = new StringBuilder();
        for (;_pos<end;_pos++) {
            char c = _json.charAt(_pos);
            if (c == quotation && _pos > pos && _json.charAt(_pos-1) != '\\') {
                pos = _pos+1;
                break;
            } else {
                if (c == '\\' && _pos+1 < end) {
                    continue;
                }
                key.append(c);
            }
        }
        return key.toString();
    }

    @Override
    public int keyHash() {
        long hashCode = 0x811c9dc5;
        char c = firstNotSpaceChar();
        boolean keyFinish = false;
        if (c == '"' || c == '\'') {
            pos++;
            int _pos = pos;
            while (_pos < end) {
                c = json.charAt(_pos++);
                if (c == '"') {
                    pos = _pos;
                    keyFinish = true;
                    break;
                }
                hashCode ^= c;
                hashCode *= 0x1000193;
            }
        } else {
            if (!isIdentifierNameFirst(c)) {
                throw new JsonParseException("Key首字符不符合ECMAScript 5.1 IdentifierName标准");
            }
            hashCode ^= c;
            hashCode *= 0x1000193;
            int _pos = pos;
            String _json = json;
            _pos++;
            for (;_pos<end;_pos++) {
                c = _json.charAt(_pos);
                if (c == ' ') {
                    pos = _pos + 1;
                    keyFinish = true;
                    break;
                } else if (c == ':') {
                    pos = _pos;
                    keyFinish = true;
                    break;
                }
                if (!isIdentifierNameOther(c)) {
                    throw new JsonParseException("Key字符不符合ECMAScript 5.1 IdentifierName标准");
                }
                hashCode ^= c;
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

    /**
     * 解析JSON内容之前空行以及注释的内容。
     * @return
     */
    @Override
    public List<CommentItem> readComment() {
        List<CommentItem> items = new ArrayList();

        int _pos = pos;
        String _json = json;
        int commentStart = -1;
        for (;_pos<end;_pos++) {
            char c = _json.charAt(_pos);
            if (c <= ' ' && c != '\n') {
                continue;
            }
            if (c == '\n') {
                if (commentStart == -1) {
                    items.add(emptyRow());
                } else {
                    items.add(singleLineComment(_json.substring(commentStart, _pos)));
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
                if (_json.charAt(_pos+1) == '/') {
                    // 去掉 "//"后面第一个空格
                    if (_pos < end -3 && _json.charAt(_pos+2) == ' ') {
                        commentStart = _pos+3;
                        _pos = _pos + 3;
                    } else {
                        commentStart = _pos+2;
                        _pos = _pos + 2;
                    }
                } else if (_json.charAt(_pos+1) == '*') {
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
        String _json = json;
        if (_json.charAt(pos) == ' ') {
            pos++;
        }
        int start = pos;
        boolean isFinish = false;
        for (;pos<end;pos++) {
            char c = _json.charAt(pos);
            if (c == '*' && pos < end - 1 && _json.charAt(pos+1) == '/') {
                if (start > 0) {
                    comments.add(_json.substring(start, pos).trim());
                }
                this.pos = pos + 2;
                isFinish = true;
                break;
            } else if (c == '*' && pos < end - 1 && _json.charAt(pos+1) == ' ') {
                start = pos + 2;
            } else if (c == '*') {
                start = pos + 1;
            } else if (c == '\n') {
                int commentEnd = pos;
                if (_json.charAt(pos - 1) == '\r') {
                    commentEnd = pos - 1;
                }
                if (start > 0) {
                    comments.add(_json.substring(start, commentEnd));
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
                char c = json.charAt(i);
                if (c == ' ' || c == ',' || c == ']' || c == '}') {
                    String num = json.substring(start, i);
                    pos = i + 1;
                    return Double.parseDouble(num);
                }
            }
            String num = json.substring(start, end);
            pos = end;
            return Double.parseDouble(num);
        }
    }

    private Object readNumber0(boolean isNe) {
        int start = pos;
        char c = json.charAt(pos);
        if (c == 'I') {
            int _pos = pos;
            if (start + 7 < end && json.charAt(_pos+1) == 'n' && json.charAt(_pos+2) == 'f' && json.charAt(_pos+3) == 'i'
                    && json.charAt(_pos+4) == 'n' && json.charAt(_pos+5) == 'i' && json.charAt(_pos+6) == 't'
                    && json.charAt(_pos+7) == 'y') {
                pos = _pos+7;
                return isNe?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
            } else {
                throw new JsonParseException("Infinity format error");
            }
        } else if (c == '.') {
            pos++;
            int dotPos = pos;
            try {
                long div = readLong0(INVALID_CHAR_FOR_NUMBER);
                int len = pos - dotPos;
                double v = ((double) div / POW10[len]);
                return isNe ? -v : v;
            } catch (Exception e) {
                throw new JsonParseException("double类型\".\"没有其他数字");
            }
        }
        long value = readLong0(INVALID_CHAR_FOR_NUMBER);
        c = json.charAt(pos);
        if (c == '.') {
            pos++;
            c = firstNotSpaceChar();
            if (c == ',' || c == ']' || c == '}') {
                return (double)value;
            }
            int dotPos = pos;
            try {
                long div = readLong0(INVALID_CHAR_FOR_NUMBER);
                int len = pos - dotPos;
                double v = value + ((double) div / POW10[len]);
                return isNe ? -v : v;
            } catch (Exception e) {
                throw new JsonParseException("double类型\".\"没有其他数字");
            }
        } else if (c == 'x') {
            if (pos - start == 1) {
                pos++;
                value = 0;
                for (int i=pos;i<end;i++) {
                    int ind = HEX_DIGITS[json.charAt(i)];
                    if (ind == INVALID_CHAR_FOR_NUMBER) {
                        pos = i;
                        return value;
                    }
                    value = (value  << 4) + ind;
                }
            }
        } else {
            return isNe ? -value : value;
        }
        return null;
    }
}
