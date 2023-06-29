package io.edap.data.util;

import java.time.LocalDate;

public class Convertor {

    public static java.time.LocalDate convert(java.sql.Date sqlDate) {
        return sqlDate.toLocalDate();
    }

    public static java.sql.Date convert(java.time.LocalDate localDate) {
        return java.sql.Date.valueOf(localDate);
    }

    public static java.time.LocalTime convert(java.sql.Time sqlTime) {
        return sqlTime.toLocalTime();
    }

    public static java.sql.Time convert(java.time.LocalTime localTime) {
        return java.sql.Time.valueOf(localTime);
    }

    public static java.time.LocalDateTime convert(java.sql.Timestamp sqlts) {
        return sqlts.toLocalDateTime();
    }

    public static java.sql.Timestamp convert(java.time.LocalDateTime localDateTime) {
        return java.sql.Timestamp.valueOf(localDateTime);
    }
}
