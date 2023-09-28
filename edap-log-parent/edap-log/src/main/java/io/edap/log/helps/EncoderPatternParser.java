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

package io.edap.log.helps;

import io.edap.util.StringUtil;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析日志编码器设置的模式字符串，将字符串分为普通文本和Encoder函数Token列表
 */
public class EncoderPatternParser {

    private final String pattern;

    public static final char FUNC_START = '%';

    private int pos;

    public EncoderPatternParser(String pattern) {
        this.pattern = pattern;
        this.pos = 0;
    }

    public static String parseKeyword(String token) throws ParseException {
        if (StringUtil.isEmpty(token) || token.charAt(0) != '%') {
            throw new ParseException("token is not start with '%'", 1);
        }
        String keyword;
        int start = 1;
        if (token.charAt(1) == '-') {
            if (!Character.isDigit(token.charAt(2))) {
                throw new ParseException("EncoderPattern align set not number", 1);
            }
            for (int i=3;i<token.length();i++) {
                char c = token.charAt(i);
                if (!Character.isDigit(c)) {
                    start = i;
                    break;
                }
            }
        } else {
            for (int i=1;i<token.length();i++) {
                char c = token.charAt(i);
                if (!Character.isDigit(c)) {
                    start = i;
                    break;
                }
            }
        }
        int leftBrackets = token.indexOf("{", start);
        if (leftBrackets == -1) {
            keyword = token.substring(start);
        } else {
            int rightBrackets = token.indexOf("}", leftBrackets);
            if (rightBrackets == -1) {
                throw new ParseException("EncoderPattern is abnormal end pos:" + leftBrackets, leftBrackets);
            } else {
                keyword = token.substring(start, leftBrackets);
            }
        }
        return keyword;
    }

    public List<EncoderPatternToken> parse() throws ParseException {
        List<EncoderPatternToken> tokens = new ArrayList<>();
        if (StringUtil.isEmpty(pattern)) {
            throw new ParseException("EncoderPattern is null", 0);
        }
        StringBuilder token = new StringBuilder();
        int tokenStart = pos;
        for (;pos<pattern.length();pos++) {
            char c = pattern.charAt(pos);
            if (c == FUNC_START) {
                if (token.length() > 0) {
                    EncoderPatternToken patternToken = new EncoderPatternToken(token.toString(),
                            EncoderPatternToken.TokenType.TEXT, tokenStart);
                    tokens.add(patternToken);
                    token.delete(0, token.length());
                }
                tokens.add(parseFuncToken());
                tokenStart = pos + 1;
            } else {
                token.append(c);
            }
        }
        if (token.length() > 0) {
            EncoderPatternToken patternToken = new EncoderPatternToken(token.toString(),
                    EncoderPatternToken.TokenType.TEXT, tokenStart);
            tokens.add(patternToken);
        }
        return tokens;
    }

    private EncoderPatternToken parseFuncToken() {
        int tokenStart = pos;
        StringBuilder token = new StringBuilder();
        token.append(pattern.charAt(pos++));
        boolean has = false;
        for (;pos<pattern.length();pos++) {
            char c = pattern.charAt(pos);
            switch (c) {
                case ' ':
                case '[':
                case ']':
                case '.':
                case '%':
                    if (!has) {
                        pos--;
                        return new EncoderPatternToken(token.toString(),
                                EncoderPatternToken.TokenType.ENCODER_FUNC, tokenStart);
                    } else {
                        token.append(c);
                    }
                    break;
                case '}':
                    token.append(c);
                    return new EncoderPatternToken(token.toString(),
                            EncoderPatternToken.TokenType.ENCODER_FUNC, tokenStart);
                case '{':
                    has = true;
                    token.append(c);
                    break;
                default:
                    token.append(c);
            }
        }
        return new EncoderPatternToken(token.toString(),
                EncoderPatternToken.TokenType.ENCODER_FUNC, tokenStart);
    }
}
