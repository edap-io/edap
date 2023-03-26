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

package io.edap.log.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.LogLevel;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.StringUtil;

import static io.edap.log.helpers.Util.printError;

public class LevelConverter implements Converter<LogEvent> {

    private static byte[][] LEVEL_BYTES_ARRAY;

    /**
     * 根据Encoder的格式以及该节点后的纯文本构建一个转换器对象。
     * @param encoderPattern
     */
    public LevelConverter(String encoderPattern) {
        this(encoderPattern, null);
    }

    /**
     * 根据Encoder的格式以及该节点后的纯文本构建一个转换器对象。
     * @param encoderPattern
     * @param nextText
     */
    public LevelConverter(String encoderPattern, String nextText) {
        if (StringUtil.isEmpty(encoderPattern)) {
            encoderPattern = "level";
        }
        String format;
        if (encoderPattern.charAt(0) == '%') {
            format = encoderPattern.substring(1);
        } else {
            format = encoderPattern;
        }
        int fillLen = 0;
        if (format.charAt(0) == '-') {
            try {
                String snum = format.substring(1, format.length() - 5);
                fillLen = Integer.parseInt(snum);
            } catch (Exception e) {
                printError("parseInt error", e);
            }
        }
        byte[][] levelBytes = new byte[8][];
        String postfix;
        if (!StringUtil.isEmpty(nextText)) {
            postfix = nextText;
        } else {
            postfix = "";
        }
        levelBytes[0] = (lefFillLenStr("OFF",   fillLen) + postfix).getBytes();
        levelBytes[1] = (lefFillLenStr("TRACE", fillLen) + postfix).getBytes();
        levelBytes[2] = (lefFillLenStr("DEBUG", fillLen) + postfix).getBytes();
        levelBytes[3] = (lefFillLenStr("CONF",  fillLen) + postfix).getBytes();
        levelBytes[4] = (lefFillLenStr("INFO",  fillLen) + postfix).getBytes();
        levelBytes[5] = (lefFillLenStr("WARN",  fillLen) + postfix).getBytes();
        levelBytes[6] = (lefFillLenStr("ERROR", fillLen) + postfix).getBytes();
        levelBytes[7] = (lefFillLenStr("OFF",   fillLen) + postfix).getBytes();
        LEVEL_BYTES_ARRAY = levelBytes;
    }

    private String lefFillLenStr(String levelName, int len) {
        if (len <= 0 || len < levelName.length()) {
            return levelName;
        }
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<len-levelName.length();i++) {
            sb.append(' ');
        }
        sb.append(levelName);
        return sb.toString();
    }

    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {
        int levelValue = logEvent.getLevel() >> 8;
        if (levelValue < 0 || levelValue > 7) {
            levelValue = 0;
        }
        out.append(LEVEL_BYTES_ARRAY[levelValue]);
    }
}
