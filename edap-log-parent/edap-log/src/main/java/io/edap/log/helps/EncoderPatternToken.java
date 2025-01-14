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

import java.text.ParseException;

import static io.edap.log.helps.EncoderPatternParser.parseKeyword;

public class EncoderPatternToken {

    /**
     * token类型
     */
    private final TokenType type;
    /**
     * Token的字符串
     */
    private final String pattern;
    /**
     * 关键字开始位置
     */
    private final int startPos;
    /**
     * Encoder格式的关键字
     */
    private String keyword;

    public EncoderPatternToken(String pattern, TokenType type, int pos) {
        this.pattern = pattern;
        this.type = type;
        this.startPos = pos;
    }

    public static EncoderPatternToken of(String pattern, TokenType type) {
        return new EncoderPatternToken(pattern, type, 0);
    }

    /**
     * token类型
     */
    public TokenType getType() {
        return type;
    }

    /**
     * Token的字符串
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * 关键字开始位置
     */
    public int getStartPos() {
        return startPos;
    }

    /**
     * Encoder格式的关键字
     */
    public String getKeyword() throws ParseException {
        if (keyword != null) {
            return keyword;
        }
        if (type != TokenType.ENCODER_FUNC) {
            keyword = "";
            return keyword;
        }
        this.keyword = parseKeyword(pattern);
        return keyword;
    }

    public enum TokenType {
        TEXT, // 普通文本
        ENCODER_FUNC // Encoder的标识符
    }
}
