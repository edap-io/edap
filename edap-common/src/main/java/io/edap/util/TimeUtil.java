/*
 * Copyright 2020 The edap Project
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

package io.edap.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Date;

/**
 * 时间处理的常用函数
 */
public class TimeUtil {

    /**
     * UTC的时区对象
     */
    static ZoneOffset UTC_ZONEOFFSET = ZoneOffset.UTC;

    private TimeUtil() {}

    /**
     * 将Date对象转为时间戳
     * @param date Date对象
     * @return
     */
    public static long timeMillis(Date date) {
        return date.getTime();
    }

    /**
     * 将Calendar的对象转为时间戳
     * @param calendar
     * @return
     */
    public static long timeMillis(Calendar calendar) {
        return calendar.getTimeInMillis();
    }

    /**
     * 将LocalDateTime转为时间戳
     * @param time
     * @return
     */
    public static long timeMillis(LocalDateTime time) {
        return time.toEpochSecond(UTC_ZONEOFFSET);
    }

    /**
     * 将时间戳转为Date对象
     * @param timestamp
     * @return
     */
    public static Date toDate(long timestamp) {
        return new Date(timestamp);
    }
    /**
     * 将时间戳转为Calender对象
     * @param timestamp
     * @return
     */
    public static Calendar toCalendar(long timestamp) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        return cal;
    }
    /**
     * 将时间戳转为LocalDateTime对象
     * @param timestamp
     * @return
     */
    public static LocalDateTime toLocalDateTime(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), UTC_ZONEOFFSET);
    }
}
