package io.edap.data.jdbc.util;

import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;

public class Convertor {

    public static String getConvertMethodName(String destClassName) {
        if (destClassName.startsWith("L") && destClassName.endsWith(";")) {
            destClassName = destClassName.substring(1, destClassName.length()-1);
        }
        StringBuilder name = new StringBuilder("to");
        int index = destClassName.indexOf("/");
        int start = 0;
        while (index != -1) {
            String packName = destClassName.substring(start, index);
            name.append(packName.substring(0,1).toUpperCase(Locale.ENGLISH));
            name.append(packName.substring(1));
            start = index + 1;
            index = destClassName.indexOf("/", start);
        }
        name.append(destClassName.substring(start, start+1).toUpperCase(Locale.ENGLISH));
        name.append(destClassName.substring(start+1));
        return name.toString();
    }

    public static java.time.LocalDate toJavaTimeLocalDate(java.sql.Date sqlDate) {
        return sqlDate.toLocalDate();
    }

    public static java.sql.Date toJavaSqlDate(java.time.LocalDate localDate) {
        return java.sql.Date.valueOf(localDate);
    }

    public static java.time.LocalTime toJavaTimeLocalTime(java.sql.Time sqlTime) {
        return sqlTime.toLocalTime();
    }

    public static java.sql.Time toJavaSqlTime(java.time.LocalTime localTime) {
        return java.sql.Time.valueOf(localTime);
    }

    public static java.time.LocalDateTime toJavaTimeLocalDateTime(java.sql.Timestamp sqlts) {
        return sqlts.toLocalDateTime();
    }

    public static java.sql.Timestamp toJavaSqlTimestamp(java.time.LocalDateTime localDateTime) {
        return java.sql.Timestamp.valueOf(localDateTime);
    }

    public static java.sql.Timestamp toJavaSqlTimestamp(java.util.Date date) {
        return new java.sql.Timestamp(date.getTime());
    }

    public static java.util.Date toJavaUtilDate(java.sql.Timestamp sqlDate) {
        return new java.util.Date(sqlDate.getTime());
    }

    public static java.lang.String toJavaLangString(char c) {
        return java.lang.String.valueOf(c);
    }

    public static java.lang.String toJavaLangString(Character c) {
        return String.valueOf(c);
    }

    public static java.lang.Character toJavaLangCharacter(String str) {
        return str.charAt(0);
    }

    public static char toC(String s) {
        return s.charAt(0);
    }

}
