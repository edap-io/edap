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

package io.edap.log.test.perf;

import io.edap.log.converter.DateConverter;
import io.edap.log.helps.ByteArrayBuilder;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SimpleDateConverter implements DateConverter {

    protected final String format;

    final ThreadLocal<SimpleDateFormat> LOCAL_DATEFORMAT = new ThreadLocal<>();

    public SimpleDateConverter(String format) {
        this.format = format;
    }

    @Override
    public void convertTo(ByteArrayBuilder out, Long mills) {
        if (mills == null) {
            return;
        }
        SimpleDateFormat dateF = LOCAL_DATEFORMAT.get();
        if (dateF == null) {
            dateF = new SimpleDateFormat(format);
            LOCAL_DATEFORMAT.set(dateF);
        }
        out.append(dateF.format(new Date(mills)).getBytes(StandardCharsets.UTF_8));
    }
}
