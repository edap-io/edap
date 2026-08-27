package io.edap.data.jdbc.util;

import java.sql.Array;
import java.sql.SQLException;
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
        String methodName = name.toString();
        if (methodName.startsWith("to[")) {
            methodName = "toArray" + methodName.substring(3);
        }
        if (methodName.endsWith(";")) {
            methodName = methodName.substring(0, methodName.length() - 1);
        }
        return methodName;
    }

    public static java.time.LocalDate toJavaTimeLocalDate(java.sql.Date sqlDate) {
        if (sqlDate == null) {
            return null;
        }
        return sqlDate.toLocalDate();
    }

    public static java.sql.Date toJavaSqlDate(java.time.LocalDate localDate) {
        if (localDate == null) {
            return null;
        }
        return java.sql.Date.valueOf(localDate);
    }

    public static java.time.LocalTime toJavaTimeLocalTime(java.sql.Time sqlTime) {
        if (sqlTime == null) {
            return null;
        }
        return sqlTime.toLocalTime();
    }

    public static java.sql.Time toJavaSqlTime(java.time.LocalTime localTime) {
        if (localTime == null) {
            return null;
        }
        return java.sql.Time.valueOf(localTime);
    }

    public static java.time.LocalDateTime toJavaTimeLocalDateTime(java.sql.Timestamp sqlts) {
        if (sqlts == null) {
            return null;
        }
        return sqlts.toLocalDateTime();
    }

    public static java.sql.Timestamp toJavaSqlTimestamp(java.time.LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return java.sql.Timestamp.valueOf(localDateTime);
    }

    public static java.sql.Timestamp toJavaSqlTimestamp(java.util.Date date) {
        if (date == null) {
            return null;
        }
        return new java.sql.Timestamp(date.getTime());
    }

    public static java.util.Date toJavaUtilDate(java.sql.Timestamp sqlDate) {
        if (sqlDate == null) {
            return null;
        }
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

    public static long[] toArrayJ(Object obj) {
        try {
            Long[] vals = ((Long[]) ((Array) (obj)).getArray());
            long[] arrays = new long[vals.length];
            for (int i=0;i<vals.length;i++) {
                arrays[i] = vals[i];
            }
            return arrays;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static String[] toArrayLjavaLangString(Object obj) {
        try {
            if (obj == null) {
                return new String[0];
            }
            return ((String[]) ((Array) (obj)).getArray());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static char toC(String s) {
        return s.charAt(0);
    }

}
