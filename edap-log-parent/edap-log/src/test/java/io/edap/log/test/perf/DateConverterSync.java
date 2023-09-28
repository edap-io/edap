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

import io.edap.log.LogEvent;
import io.edap.log.converter.DateConverter;
import io.edap.log.helps.ByteArrayBuilder;

import java.nio.charset.StandardCharsets;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DateConverterSync implements DateConverter {

    private final String format;

    private StringBuffer buf;
    private FieldPosition fieldPosition;

    private SimpleDateFormat timeF;

    long lastTimestamp = -1;
    byte[] timeBytes;

    public DateConverterSync(String format) {
        this.format = format;
        long time = System.currentTimeMillis();
        this.buf = new StringBuffer();
        this.fieldPosition = new FieldPosition(0);
        SimpleDateFormat timeF = new SimpleDateFormat(format);
        this.timeF = timeF;
        byte[] bytes = timeF.format(new Date(time), buf, fieldPosition)
                .toString().getBytes(StandardCharsets.UTF_8);
        this.timeBytes = bytes;
    }

    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {
        long mills = logEvent.getLogTime();
        synchronized (this) {
            if (lastTimestamp != mills) {
                buf.delete(0, buf.length());
                byte[] bs = timeF.format(mills, buf, fieldPosition)
                        .toString().getBytes(StandardCharsets.UTF_8);
                out.append(bs);
                this.timeBytes = bs;
            } else {
                out.append(timeBytes);
            }
        }
    }

}
