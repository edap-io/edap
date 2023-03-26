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

import io.edap.log.converter.DateConverter;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestDateConverter {

    @Test
    public void testParseTimeZoneInfo() {
        String format = "%d{yyyy-MM-dd HH:mm:ss.SSS}";
        DateConverter.TimeZoneInfo tzi = DateConverter.parseTimeZoneInfo(format);
        assertEquals(tzi.zoneId, ZoneId.systemDefault());
        assertEquals(tzi.format, "yyyy-MM-dd HH:mm:ss.SSS");
        assertEquals(tzi.locale, Locale.getDefault());

        format = "%d{yyyy-MM-dd HH:mm:ss,SSS}";
        tzi = DateConverter.parseTimeZoneInfo(format);
        assertEquals(tzi.zoneId, ZoneId.systemDefault());
        assertEquals(tzi.format, "yyyy-MM-dd HH:mm:ss");
        assertEquals(tzi.locale, Locale.getDefault());

        format = "%d{\"yyyy-MM-dd HH:mm:ss,SSS\",GMT+8}";
        tzi = DateConverter.parseTimeZoneInfo(format);
        assertEquals(tzi.zoneId, ZoneId.of("GMT+8"));
        assertEquals(tzi.format, "yyyy-MM-dd HH:mm:ss,SSS");
        assertEquals(tzi.locale, Locale.getDefault());

        format = "%d{\"yyyy-MM-dd HH:mm:ss,SSS\",GMT+8,en}";
        tzi = DateConverter.parseTimeZoneInfo(format);
        assertEquals(tzi.zoneId, ZoneId.of("GMT+8"));
        assertEquals(tzi.format, "yyyy-MM-dd HH:mm:ss,SSS");
        assertEquals(tzi.locale, Locale.ENGLISH);

        format = "%d{\"yyyy-MM-dd HH:mm:ss,SSS\",GMT+8,tttt}";
        tzi = DateConverter.parseTimeZoneInfo(format);
        assertEquals(tzi.zoneId, ZoneId.of("GMT+8"));
        assertEquals(tzi.format, "yyyy-MM-dd HH:mm:ss,SSS");
        assertEquals(tzi.locale, Locale.getDefault());

        format = "%d";
        tzi = DateConverter.parseTimeZoneInfo(format);
        assertEquals(tzi.zoneId, ZoneId.systemDefault());
        assertEquals(tzi.format, "yyyy-MM-dd HH:mm:ss,SSS");
        assertEquals(tzi.locale, Locale.getDefault());
    }
}
