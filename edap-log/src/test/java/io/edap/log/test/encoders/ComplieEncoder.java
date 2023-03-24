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

package io.edap.log.test.encoders;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.encoders.AbstractEncoder;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.EncoderPatternParser;
import io.edap.log.helps.EncoderPatternToken;
import io.edap.util.StringUtil;

import java.io.OutputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class ComplieEncoder extends AbstractEncoder {

    static final ThreadLocal<ByteArrayBuilder> LOCAL_BYTE_ARRAY_BUILDER =
            new ThreadLocal<ByteArrayBuilder>() {
                @Override
                protected ByteArrayBuilder initialValue() {
                    return new ByteArrayBuilder();
                }
            };

    private Converter[] converters;
    private String[] keywords;

    public ComplieEncoder(String pattern) throws ParseException {
        super(pattern);
        initConverters();
    }

    private void initConverters() throws ParseException {
        EncoderPatternParser parser = new EncoderPatternParser(pattern);
        List<EncoderPatternToken> tokens = parser.parse();
        List<String> keywordList = new ArrayList<>();
        List<Converter> converterList = new ArrayList<>();
        for (EncoderPatternToken token : tokens) {
            Converter converter = getConverter(token);
            if (converter != null) {
                keywordList.add(token.getKeyword());
                converterList.add(converter);
            }
        }
        keywords = keywordList.toArray(new String[0]);
        converters = converterList.toArray(new Converter[0]);
    }

    @Override
    public void encode(OutputStream out, LogEvent logEvent) {
        ByteArrayBuilder builder = LOCAL_BYTE_ARRAY_BUILDER.get();
        for (int i=0;i<converters.length;i++) {
            Converter converter = converters[i];
            String keyword = keywords[i];
            if (StringUtil.isEmpty(keyword)) {
                converter.convertTo(builder, null);
                continue;
            }
            switch (keyword) {
                case "d":
                    converter.convertTo(builder, logEvent.getLogTime());
                    break;
                case "p":
                    converter.convertTo(builder, logEvent.getLevel());
                    break;
                case "t":
                    converter.convertTo(builder, logEvent.getThreadName());
                    break;
                case "c":
                    converter.convertTo(builder, logEvent.getLoggerName());
                    break;
                case "m":
                    converter.convertTo(builder, logEvent);
                    break;
                case "n":

                case "C":

            }
        }
        try {
            builder.writeTo(out);
        } catch (Throwable e) {

        }
    }
}
