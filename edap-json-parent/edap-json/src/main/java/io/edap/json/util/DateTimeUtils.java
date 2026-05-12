package io.edap.json.util;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class DateTimeUtils {



    public static long toEpochMills(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
