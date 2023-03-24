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

import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class DateFormatterConverter implements DateConverter {

    protected final String format;

    final DateTimeFormatter formatter;

    private final byte[] postfixData;

    public DateFormatterConverter(String format) {
        this(format, null);
    }

    public DateFormatterConverter(String format, String nextText) {
        this.format = format;
        this.formatter = DateTimeFormatter.ofPattern(format)
                .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault());
        if (StringUtil.isEmpty(nextText)) {
            postfixData = null;
        } else {
            postfixData = nextText.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void convertTo(ByteArrayBuilder out, Long mills) {
        if (mills == null) {
            return;
        }
        out.append(formatter.format(Instant.ofEpochMilli(mills)).getBytes(StandardCharsets.UTF_8));
        if (postfixData != null) {
            out.append(postfixData);
        }
    }
}
