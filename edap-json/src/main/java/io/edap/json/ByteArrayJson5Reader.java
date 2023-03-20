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
import java.util.List;

import static io.edap.json.enums.DataType.BYTE_ARRAY;
import static io.edap.json.model.CommentItem.emptyRow;
import static io.edap.json.model.CommentItem.singleLineComment;
import static io.edap.json.util.JsonUtil.isIdentifierNameFirst;
import static io.edap.json.util.JsonUtil.isIdentifierNameOther;

public class ByteArrayJson5Reader extends ByteArrayJsonReader {

    public ByteArrayJson5Reader(byte[] bytes) {
        super(bytes);
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

    @Override
    public <T> T readObject(Class<T> valueType) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        JsonDecoder decoder = DECODER_REGISTE.getDecoder(valueType, BYTE_ARRAY, JsonVersion.JSON5);
        if (decoder != null) {
            return (T)decoder.decode(this);
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
