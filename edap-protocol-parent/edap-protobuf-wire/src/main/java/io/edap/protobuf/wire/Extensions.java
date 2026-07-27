/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf.wire;

import io.edap.protobuf.wire.exceptions.ProtoParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * protocol buffer 扩展tag的结构定义
 */
public class Extensions {

    /**
     * 扩展开始的Tag编号
     */
    private int startTag;
    /**
     * 扩展结束的Tag编号
     */
    private int endTag;

    private List<Option> options;

    public Extensions setEndTag(int endTag) {
        this.endTag = endTag;
        return this;
    }

    /**
     * 扩展结束的Tag编号
     */
    public int getEndTag() {
        return endTag;
    }

    public Extensions setStartTag(int startTag) {
        this.startTag = startTag;
        return this;
    }

    /**
     * 扩展开始的Tag编号
     */
    public int getStartTag() {
        return startTag;
    }

    public static Extensions parseExtensions(String expression) throws ProtoParseException {
        String exp = expression.trim();
        ExtensionsParser parser = new ExtensionsParser(exp);
        return parser.parse();
//        if (exp.isEmpty()) {
//            throw new ProtoParseException("extensions expression is empty");
//        }
//        int index = exp.indexOf("to");
//        if (index < 0) {
//            throw new ProtoParseException("extensions expression must be start to end");
//        }
//        String sstart = exp.substring(0, index).trim();
//        if (sstart.isEmpty()) {
//            throw new ProtoParseException("extensions expression start empty");
//        }
//        int start = ProtoParser.parseInt(sstart);
//        if (start < 1) {
//            throw new ProtoParseException("extensions expression start cann't be " + start);
//        }
//        String sEnd = exp.substring(index + 2).trim();
//        if (sEnd.isEmpty()) {
//            throw new ProtoParseException("extensions expression end empty");
//        }
//        int end;
//        if ("max".equalsIgnoreCase(sEnd)) {
//            end = WireFormat.MAX_TAG_VALUE;
//        } else {
//            end = ProtoParser.parseInt(sEnd);
//        }
//        if (end <= start) {
//            throw new ProtoParseException("extensions start tag <= end tag");
//        }
//        Extensions ext = new Extensions();
//        ext.setStartTag(start).setEndTag(end);
//        return ext;
    }

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options;
    }

    static class ExtensionsParser {

        char[] data;
        int pos;
        int length;

        public ExtensionsParser(String data) {
            this.data = data.toCharArray();
            this.length = data.length();
        }

        public Extensions parse() throws ProtoParseException {
            if (data.length <= 0) {
                throw new ProtoParseException("extensions expression is empty");
            }
            String token = readToken();
            Extensions extensions = new Extensions();
            try {
                extensions.setStartTag(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                throw new ProtoParseException("Extensions start must be number!");
            }
            if (extensions.getStartTag() < 1) {
                throw new ProtoParseException("extensions expression start cann't be" + extensions.getStartTag());
            }
            trim();
            if (pos >= data.length) {
                return extensions;
            }
            char c = data[pos];
            if (c == 't') {
                token = readToken();
                if (token.equals("to")) {
                    trim();
                    token = readToken();
                    if ("max".equals(token)) {
                        extensions.setEndTag(WireFormat.MAX_TAG_VALUE);
                    } else {
                        try {
                            extensions.setEndTag(Integer.parseInt(token));
                        } catch (NumberFormatException e) {
                            throw new ProtoParseException("Extensions start must be number!");
                        }
                    }
                    trim();
                    if (pos >= data.length) {
                        return extensions;
                    }
                    c = data[pos];
                    if (c == ';') {
                        return extensions;
                    }
                } else {
                    throw new RuntimeException("Extensions 格式错误!");
                }
            } else if (c == '[') {
                pos++;
                List<Option> options = readOptions();
                extensions.setOptions(options);
            } else if (c == ';') {
                pos++;
                return extensions;
            } else {
                throw new ProtoParseException("Extensions 格式错误!");
            }
            if (extensions.getEndTag() > 0 && extensions.getStartTag() > 0
                    && extensions.getEndTag() < extensions.getStartTag()) {
                System.out.println("extensions.getEndTag()=" + extensions.getEndTag() +
                        ",extensions.getStartTag()=" + extensions.getStartTag());
                throw new ProtoParseException("extensions start tag <= end tag");
            }
            return extensions;
        }

        private List<Option> readOptions() throws ProtoParseException {
            List<Option> options = new ArrayList<>();
            while (pos < length) {
                trim();
                char c = data[pos];
                if (c == ']') {
                    break;
                } else if (c == ',') {
                    pos++;
                    trim();
                }
                String token = readToken();
                trim();
                Option option = new Option();
                option.setName(token);
                c = data[pos];
                if (c == '=') {
                    pos++;
                    trim();
                    c = data[pos];
                    if (c == '{') {
                        pos++;
                        option.setValue(readOptionObject());
                        options.add(option);
                    } else {
                        option.setValue(readOptionValue());
                    }
                } else {
                    throw new ProtoParseException("Extensions 格式错误!");
                }
            }
            return options;
        }

        private String readOptionValue() {
            for (int i=pos;i<length;i++) {
                char c = data[i];
                if (c == ',') {
                    pos = i + 1;
                    return new String(data, pos, i - pos);
                }
            }
            throw new RuntimeException("Extensions 格式错误!");
        }

        private String readOptionObject() {
            for (int i=pos;i<length;i++) {
                char c = data[i];
                if (c == '}') {
                    String v = "{" + new String(data, pos, i-pos) + "}";
                    pos = i + 1;
                    return v;
                }
            }
            throw new RuntimeException("Extensions 格式错误!");
        }

        /**
         * 去掉space,tab,'\r'回车
         * @return 去掉空格和tab的个数
         */
        private int trim() {
            char c;
            int oldPos = pos;
            while (pos < data.length) {
                c = data[pos];
                switch (c) {
                    case ' ' :
                    case '\t':
                    case '\r':
                    case '\n':
                        pos++;
                        break;
                    default:
                        return pos - oldPos;
                }
            }
            return pos - oldPos;
        }

        private String readToken() throws ProtoParseException {
            StringBuilder token = new StringBuilder();
            char c;
            char[] _data = data;
            for (int i=pos;i<length;i++) {
                c = _data[i];
                switch (c) {
                    case ' ':
                    case '[':
                    case ';':
                    case '=':
                        pos = i;
                        return token.toString();
                    default:
                        token.append(c);
                }
            }
            pos = data.length;
            return token.toString();
        }
    }
}