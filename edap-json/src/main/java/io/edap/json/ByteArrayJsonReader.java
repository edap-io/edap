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
import io.edap.json.model.DataRange;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.edap.json.StringJsonReader.ERROR_JSON_FORMAT;
import static io.edap.json.consts.JsonConsts.END_OF_NUMBER;
import static io.edap.json.consts.JsonConsts.INVALID_CHAR_FOR_NUMBER;
import static io.edap.json.enums.DataType.BYTE_ARRAY;
import static io.edap.json.util.JsonUtil.INT_DIGITS;
import static io.edap.util.Constants.FNV_1a_FACTOR_VAL;
import static io.edap.util.Constants.FNV_1a_INIT_VAL;

public class ByteArrayJsonReader implements JsonReader {

    protected int pos;

    protected byte[] json;
    protected int end;

    private char[] tmpChars = new char[64];

    static ByteArrayDataRange byteArrayDataRange = new ByteArrayDataRange();

    static JsonCodecRegister DECODER_REGISTE = JsonCodecRegister.instance();

    public ByteArrayJsonReader(byte[] bytes) {
        this.json = bytes;
        this.pos = 0;
        this.end = bytes.length;
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
                throw new JsonParseException("key and value 后为不符合json字符[" + (char)c + "]");
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
        for (;_pos<end;_pos++) {
            byte c = json[_pos];
            if (c == (byte) quotation) {
                pos = _pos+1;
                return new String(tmpChars, 0, charLen);
            }
            if ((c ^ '\\') < 1) {
                break;
            }
            tmpChars[charLen++] = (char)c;
        }
        return null;
    }

//    protected String readQuotationMarksString(char quotation) {
//        int _pos = pos;
//        int tmpSize = 0;
//        for (;_pos<end;_pos++) {
//            byte c = json[_pos];
//            if (c == quotation) {
//                String val = new String(json, pos, _pos - pos, StandardCharsets.ISO_8859_1);
//                pos = _pos+1;
//                return val;
//            }
//        }
//        return null;
//    }

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
            int endPos = readNumberValue();
            byte[] nums = new byte[endPos-pos];
            System.arraycopy(json, pos, nums, 0, nums.length);
            pos = endPos;
            return nums;
        }
    }

    protected int readNumberValue() {
        int _pos = pos;
        byte[] _json = json;
        for (;_pos<end;_pos++) {
            byte c = _json[_pos];
            if (c <= ' ' || c == ',' || c == ']' || c == '}') {
                return _pos;
            }
        }
        throw new JsonParseException("Json没有正确结束");
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

//    @Override
//    public int keyHash() {
//        char c = firstNotSpaceChar();
//        if (c != '"') {
//            throw new JsonParseException("Key must start with '\"'!");
//        }
//        pos++;
//        int _pos = pos;
//        byte[] _json = json;
//        long hashCode = 0x811c9dc5;
//        byte b;
//        for (;_pos<end;_pos++) {
//            b = _json[_pos];
//            if (b == '"') {
//                pos = _pos+1;
//                return (int)hashCode;
//            } else {
//                hashCode ^= b;
//                hashCode *= 0x1000193;
//            }
//        }
//        c = firstNotSpaceChar();
//        if (c != ':') {
//            throw new JsonParseException("Key and value must use colon split");
//        }
//        pos++;
//        return 0;
//    }

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

    //@Override
//    public char firstNotSpaceChar() {
////        int _pos = pos;
////        byte[] _json = json;
////        int _end = end;
//        int _pos = pos;
//        byte[] _json = json;
//        byte bb;
//        for (;_pos<end;_pos++) {
//            bb = _json[_pos];
//            if (bb < 0 || bb > ' ') {
//                pos = _pos;
//                return (char)bb;
//            }
//        }
//        return 0;
//    }

    @Override
    public char firstNotSpaceChar() {
        int _pos = pos++;
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
            return -readInt0();
        } else {
            return readInt0();
        }
    }

    public int readInt0() {
        int _pos = pos;
        byte[] _json = json;
        byte c1 = _json[_pos++];
        int c2;
        try {
            int ind = INT_DIGITS[c1];
            if (ind == 0) {
                if (_pos < end) {
                    c2 = _json[_pos];
                    if (INT_DIGITS[c2] == END_OF_NUMBER) {
                        pos = _pos;
                        return 0;
                    }
                }
                throw new JsonParseException("整数不能有前导0的字符");
            } else if (ind == INVALID_CHAR_FOR_NUMBER) {
                throw new JsonParseException("整数不符合规范");
            }
            if (end - _pos > 8) {
                int ind2 = INT_DIGITS[_json[_pos++]];
                if (ind2 == END_OF_NUMBER) {
                    pos = _pos;
                    return ind;
                }
                int ind3 = INT_DIGITS[_json[_pos++]];
                if (ind3 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 10 + ind2;
                }
                int ind4 = INT_DIGITS[_json[_pos++]];
                if (ind4 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 100 + ind2*10 + ind3;
                }
                int ind5 = INT_DIGITS[_json[_pos++]];
                if (ind5 == END_OF_NUMBER) {
                    pos = _pos;
                    return ind * 1000 + ind2*100 + ind3*10 + ind4;
                }
                int ind6 = INT_DIGITS[_json[_pos++]];
                if (ind6 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 10000 + ind2*1000 + ind3*100 + ind4*10 + ind5;
                }
                int ind7 = INT_DIGITS[_json[_pos++]];
                if (ind7 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 100000 + ind2*10000 + ind3*1000 + ind4*100 + ind5*10 + ind6;
                }
                int ind8 = INT_DIGITS[_json[_pos++]];
                if (ind8 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 1000000 + ind2*100000 + ind3*10000 + ind4*1000 + ind5*100 + ind6*10
                            + ind7;
                }
                int ind9 = INT_DIGITS[_json[_pos++]];
                if (ind9 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 10000000 + ind2*1000000 + ind3*100000 + ind4*10000 + ind5*1000
                            + ind6*100 + ind7*10 + ind8;
                }
                int ind10 = INT_DIGITS[_json[_pos++]];
                if (ind10 == END_OF_NUMBER) {
                    pos = _pos - 1;
                    return ind * 100000000 + ind2*10000000 + ind3*1000000 + ind4*100000 + ind5*10000
                            + ind6*1000 + ind7*100 + ind8*10 + ind9;
                }
                pos++;
                return readIntSlowPath(ind);
            } else {
                pos = _pos;
                return readIntSlowPath(ind);
            }
        } catch (Throwable t) {
            if (t instanceof JsonParseException) {
                throw t;
            }
            throw new JsonParseException("整数不符合规范");
        }
    }

    public int readIntSlowPath(int value) {
        int _pos = pos;
        byte[] _json = json;
        for (;_pos<end;_pos++) {
            int ind = INT_DIGITS[_json[_pos]];
            if (ind == END_OF_NUMBER) {
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
        return 0;
    }

    @Override
    public boolean readBoolean() {
        return false;
    }

    @Override
    public void skipValue() {

    }

    @Override
    public float readFloat() {
        return 0;
    }

    @Override
    public double readDouble() {
        return 0;
    }

    @Override
    public void reset() {
        pos = 0;
    }


}
