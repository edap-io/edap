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

package io.edap.log.encoders;

import io.edap.log.Converter;
import io.edap.log.Encoder;
import io.edap.log.converter.TextConverter;
import io.edap.log.helps.EncoderPatternToken;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractEncoder implements Encoder {

    protected String pattern;
    private Map<String, Converter> supportConverters = new ConcurrentHashMap<>();
    private Map<String, Converter> textConverters = new ConcurrentHashMap<>();

    public AbstractEncoder(String pattern) {
        this.pattern = pattern;
    }

    protected Converter getConverter(EncoderPatternToken token) {
        if (token.getType() == EncoderPatternToken.TokenType.ENCODER_FUNC) {
            return supportConverters.get(token.getPattern());
        }
        Converter converter = textConverters.get(token.getPattern());
        return converter;
    }
}
