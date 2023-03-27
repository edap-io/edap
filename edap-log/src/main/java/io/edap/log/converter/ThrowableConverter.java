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
import io.edap.log.helps.ByteArrayBuilder;

public class ThrowableConverter implements Converter<LogEvent> {

    private final String format;
    private final String nextText;

    public ThrowableConverter(String format) {
        this(format, null);
    }

    public ThrowableConverter(String format, String nextText) {
        this.format = format;
        this.nextText = nextText;
    }

    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {

    }
}