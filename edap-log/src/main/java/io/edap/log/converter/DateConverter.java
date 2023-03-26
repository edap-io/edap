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

import java.time.ZoneId;
import java.util.Locale;

import static io.edap.log.helpers.Util.printError;
import static io.edap.log.util.LogUtil.*;

public interface DateConverter extends Converter<LogEvent> {

    class TimeZoneInfo {
        public String format;
        public ZoneId zoneId;
        public Locale locale;
    }

    static TimeZoneInfo parseTimeZoneInfo(String pattern) {
        TimeZoneInfo info = new TimeZoneInfo();
        if (pattern.charAt(0) == '%') {
            pattern = pattern.substring(1);
        } else {
            info.format = pattern;
            info.zoneId = ZoneId.systemDefault();
            info.locale = Locale.getDefault();
            return info;
        }
        int index = pattern.indexOf("{");
        String timeFormat;
        String[] options;
        if (index == -1) {
            options = new String[0];
        } else {
            options = splitString(pattern.substring(index+1, pattern.length()-1), ",");
        }
        ZoneId zoneId = null;
        Locale locale = null;
        if (options.length > 0) {
            timeFormat = options[0];
        } else {
            timeFormat = null;
        }
        if (options.length > 1) {
            try {
                zoneId = ZoneId.of(options[1]);
            } catch (Throwable t) {
                printError("Parse zoneId error", t);
            }
        }
        if (options.length > 2) {

            try {
                Locale[] ls = Locale.getAvailableLocales();
                for (Locale lc : ls) {
                    if (lc.toLanguageTag().equalsIgnoreCase(options[2])) {
                        locale = lc;
                        break;
                    }
                }
                if (locale == null) {
                    throw new RuntimeException(options[2] + " not supported!");
                }
            } catch (Throwable t) {
                printError("Parse Local error", t);
            }
        }
        if (zoneId == null) {
            zoneId = ZoneId.systemDefault();
        }
        if (locale == null) {
            locale = Locale.getDefault();
        }
        if (timeFormat == null) {
            timeFormat = ISO8601_PATTERN;
        } else {
            if (ISO8601_STR.equals(timeFormat)) {
                timeFormat = ISO8601_PATTERN;
            }
        }
        info.format = timeFormat;
        info.locale = locale;
        info.zoneId = zoneId;
        return info;
    }


}
