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

package io.edap.log.test;

import io.edap.log.LogArgsImpl;
import io.edap.log.helps.EncoderPatternParser;
import io.edap.log.helps.EncoderPatternToken;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.util.List;

import static io.edap.log.helps.EncoderPatternParser.parseKeyword;
import static org.junit.jupiter.api.Assertions.*;

public class TestEncoderPatternParseTest {

    @Test
    public void testBaseParse() throws ParseException {
        String pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-4relative [%thread] %-5level %logger{35} - %msg%n";
        EncoderPatternParser parser = new EncoderPatternParser(pattern);
        List<EncoderPatternToken> tokens = parser.parse();
        assertEquals(tokens.size(), 12);
        int pos = 0;
        int tokenIndex = 0;
        EncoderPatternToken token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%d{yyyy-MM-dd HH:mm:ss.SSS}");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), " ");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%-4relative");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), " [");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();


        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%thread");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "] ");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%-5level");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), " ");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%logger{35}");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), " - ");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%msg");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%n");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);

    }

    @Test
    public void testTextEnd() throws ParseException {
        String pattern = "%d %-4relative [";
        EncoderPatternParser parser = new EncoderPatternParser(pattern);
        List<EncoderPatternToken> tokens = parser.parse();
        assertEquals(tokens.size(), 4);
        int pos = 0;
        int tokenIndex = 0;
        EncoderPatternToken token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%d");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), " ");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), "%-4relative");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.ENCODER_FUNC);
        pos += token.getPattern().length();

        token = tokens.get(tokenIndex++);
        assertEquals(token.getPattern(), " [");
        assertEquals(token.getStartPos(), pos);
        assertEquals(token.getType(), EncoderPatternToken.TokenType.TEXT);
        pos += token.getPattern().length();
    }

    @Test
    public void testInvalidPattern() {
        String pattern = "";
        EncoderPatternParser parser = new EncoderPatternParser(pattern);

        ParseException thrown = assertThrows(ParseException.class,
                () -> {
                    List<EncoderPatternToken> tokens = parser.parse();
                });
        assertTrue(thrown.getMessage().contains("EncoderPattern is null"));
    }

    @Test
    public void testParseKeyword() throws ParseException {
        String token = "%-4relative";
        String keyword = parseKeyword(token);
        assertEquals(keyword, "relative");

        token = "%4relative";
        keyword = parseKeyword(token);
        assertEquals(keyword, "relative");

        token = "%d";
        keyword = parseKeyword(token);
        assertEquals(keyword, "d");

        token = "%logger{35}";
        keyword = parseKeyword(token);
        assertEquals(keyword, "logger");
    }

    @Test
    public void testParseKeywordException() {
        String token = "%-relative";
        ParseException thrown = assertThrows(ParseException.class,
                () -> {
                    String keyword = parseKeyword(token);
                });
        assertTrue(thrown.getMessage().contains("EncoderPattern align set not number"));


        String token2 = "%logger{35";
        thrown = assertThrows(ParseException.class,
                () -> {
                    String keyword = parseKeyword(token2);
                });
        assertTrue(thrown.getMessage().contains("EncoderPattern is abnormal end"));
    }

    @Test
    public void testEncoderPatternToken() throws ParseException {
        EncoderPatternToken ept = new EncoderPatternToken("",
                EncoderPatternToken.TokenType.ENCODER_FUNC, 0);
        ParseException thrown = assertThrows(ParseException.class,
                () -> {
                    String keyword = ept.getKeyword();
                });
        assertTrue(thrown.getMessage().contains("token is not start with '%'"));

        EncoderPatternToken ept3 = new EncoderPatternToken("d",
                EncoderPatternToken.TokenType.ENCODER_FUNC, 0);
        thrown = assertThrows(ParseException.class,
                () -> {
                    String keyword = ept3.getKeyword();
                });
        assertTrue(thrown.getMessage().contains("token is not start with '%'"));

        EncoderPatternToken ept2 = new EncoderPatternToken(null,
                EncoderPatternToken.TokenType.TEXT, 0);
        assertEquals(ept2.getKeyword(), "");

        ept2 = new EncoderPatternToken("%d",
                EncoderPatternToken.TokenType.ENCODER_FUNC, 0);
        assertEquals(ept2.getKeyword(), "d");
        assertEquals(ept2.getKeyword(), "d");
    }
}
