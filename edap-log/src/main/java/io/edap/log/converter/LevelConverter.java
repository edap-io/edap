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
import io.edap.log.LogLevel;
import io.edap.log.helps.ByteArrayBuilder;

public class LevelConverter implements Converter<Integer> {

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
        byte[][] levelBytes = new byte[8][];

        LEVEL_BYTES_ARRAY = levelBytes;
    }

    @Override
    public void convertTo(ByteArrayBuilder out, Integer level) {
        if (level == null) {
            return;
        }
        int levelValue = level >> 8;
        if (levelValue < 0 || levelValue > 7) {
            levelValue = 0;
        }
    }
}