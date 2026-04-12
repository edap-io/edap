package io.edap.log.util;

import io.edap.log.converter.*;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogUtil {

    private LogUtil() {}

    public static final String ISO8601_PATTERN = "yyyy-MM-dd HH:mm:ss,SSS";

    public static final String ISO8601_STR = "ISO8601";

    static final Map<String, Class> KEYWORK_CONVERTERS;

    static {
        Map<String, Class> map = new HashMap<>();
        Class dateCls = CacheDateFormatterConverter.class;
        map.put("d", dateCls);
        map.put("date", dateCls);

        Class msCls = MicrosecondConverter.class;
        map.put("ms", msCls);
        map.put("micros", msCls);

        Class relativeCls = RelativeTimeConverter.class;
        map.put("r", relativeCls);
        map.put("relative", relativeCls);

        Class levelCls = LevelConverter.class;
        map.put("level", levelCls);
        map.put("le", levelCls);
        map.put("p", levelCls);

        Class threadCls = ThreadConverter.class;
        map.put("thread", threadCls);
        map.put("t", threadCls);

        Class loggerCls = LoggerConverter.class;
        map.put("logger", loggerCls);
        map.put("lo", loggerCls);
        map.put("c", loggerCls);

        Class msgCls = MessageConverter.class;
        map.put("m", msgCls);
        map.put("msg", msgCls);
        map.put("message", msgCls);

        Class clsCls = ClassOfCallerConverter.class;
        map.put("C", clsCls);
        map.put("class", clsCls);

        Class methodCls = MethodOfCallerConverter.class;
        map.put("M", methodCls);
        map.put("method", methodCls);

        Class lineCls = LineOfCallerConverter.class;
        map.put("L", lineCls);
        map.put("line", lineCls);

        Class fileCls = FileOfCallerConverter.class;
        map.put("F", fileCls);
        map.put("file", fileCls);

        Class mdcCls = MDCConverter.class;
        map.put("X", mdcCls);
        map.put("mdc", mdcCls);

        Class newLineClass = LineSeparatorConverter.class;
        map.put("n", newLineClass);

        Class exClass = ThrowableConverter.class;
        map.put("ex", exClass);

        KEYWORK_CONVERTERS = map;
    }

    public static Class getKeywordConverter(String key) {
        return KEYWORK_CONVERTERS.get(key);
    }

    public static String[] splitString(String str, String sep) {
        int start = 0;
        List<String> as = new ArrayList<>();
        while (start < str.length()) {
            char c = str.charAt(start);
            if (c == '"') {
                int i = start + 1;
                for (; i < str.length(); i++) {
                    c = str.charAt(i);
                    if (c == '"' && str.charAt(i - 1) != '\\') {
                        as.add(str.substring(start + 1, i));
                        start = i + 1;
                        int index = str.indexOf(sep, start);
                        if (index != -1) {
                            start = index + sep.length();
                        }
                        break;
                    }
                }
                if (i >= str.length()) {
                    throw new RuntimeException("引号不成对");
                }
            } else {
                int index = str.indexOf(sep, start);
                if (index == -1) {
                    as.add(str.substring(start));
                    start = str.length();
                } else {
                    as.add(str.substring(start, index));
                    start = index + sep.length();
                }
            }
        }
        return as.toArray(new String[0]);
    }

    public static String abbreviateClassName(String name, int maxLength) {
        if (maxLength == 0) {
            return name;
        }
        if (StringUtil.isEmpty(name)) {
            return name;
        }
        if (name.length() < maxLength) {
            return name;
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1) {
            return name;
        }
        StringBuilder buf = new StringBuilder();
        int leftLen = maxLength - (name.length() - dotIndex);
        if (leftLen <= 0) {
            return name.substring(dotIndex);
        }
        int start = 0;
        List<Integer> groupLenths = new ArrayList<>();
        int dot;
        while (true) {
            dot = name.indexOf('.', start);
            if (dot != -1) {
                if (start == 0) {
                    groupLenths.add(dot - start);
                } else {
                    groupLenths.add(dot - start + 1);
                }
                start = dot + 1;
            } else {
                break;
            }
        }
        groupLenths.add(name.length() - start + 1);
        start = 0;
        for (int c=0;c<groupLenths.size()-1;c++) {
            if (leftLen <= 0) {
                break;
            }
            if (c == 0) {
                leftLen -= 1;
                buf.append(name.substring(0, 1));
                start += groupLenths.get(c);
            } else if (c == groupLenths.size() - 2) {
                int size = groupLenths.get(c);
                if (size <= leftLen) {
                    buf.append(name.substring(start, start+size));
                } else {
                    buf.append(name.substring(start, start+2));
                }
                start += groupLenths.get(c);
                leftLen -= size;
            } else {
                leftLen -= 2;
                buf.append(name.substring(start, start+2));
                start += groupLenths.get(c);
            }
        }
        buf.append(name.substring(dotIndex));
        return buf.toString();
    }
}
