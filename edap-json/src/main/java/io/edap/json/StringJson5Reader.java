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
import io.edap.json.model.CommentItem;

import java.util.ArrayList;
import java.util.List;

import static io.edap.json.model.CommentItem.emptyRow;
import static io.edap.json.model.CommentItem.singleLineComment;
import static io.edap.json.util.JsonUtil.isIdentifierNameFirst;
import static io.edap.json.util.JsonUtil.isIdentifierNameOther;

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
                throw new JsonParseException("key and value 后为不符合json字符[" + c + "]");
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
    public NodeType readStart() {
        readComment();
        return super.readStart();
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

    /**
     * 解析JSON内容之前空行以及注释的内容。
     * @return
     */
    private List<CommentItem> readComment() {
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
}
