package io.edap.json.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class DateTimeUtils {



    public static long toEpochMills(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static LocalDateTime toLocalDateTime(long milli) {
        return Instant.ofEpochMilli(milli).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    public static LocalDate toLocalDate(long milli) {
        return Instant.ofEpochMilli(milli).atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
