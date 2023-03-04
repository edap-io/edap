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

package io.edap.log.test.converter;

import io.edap.log.converter.DateFormatterConverter;
import io.edap.log.test.perf.SimpleDateConverter;
import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestDateFormatterConverter {

    @Test
    public void testConvert() throws InterruptedException {
        long now = System.currentTimeMillis();
        String format = "yyyy-MM-dd HH:mm:ss.SSS";
        SimpleDateFormat datef = new SimpleDateFormat(format);

        ByteArrayBuilder bbuf = new ByteArrayBuilder();
        DateFormatterConverter dateFormatterConverter = new DateFormatterConverter(format);
        bbuf.reset();
        dateFormatterConverter.convertTo(bbuf, now);
        assertArrayEquals(bbuf.toByteArray(), datef.format(new Date(now)).getBytes(StandardCharsets.UTF_8));
        bbuf.reset();
        dateFormatterConverter.convertTo(bbuf, now);
        assertArrayEquals(bbuf.toByteArray(), datef.format(new Date(now)).getBytes(StandardCharsets.UTF_8));

        Thread.sleep(5);
        now = System.currentTimeMillis();
        bbuf.reset();
        dateFormatterConverter.convertTo(bbuf, now);
        assertArrayEquals(bbuf.toByteArray(), datef.format(new Date(now)).getBytes(StandardCharsets.UTF_8));
    }
}
