package io.edap.log.util;

import io.edap.log.converter.*;

import java.util.HashMap;
import java.util.Map;

public class LogUtil {

    private LogUtil() {}

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
        map.put("c", clsCls);
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


        KEYWORK_CONVERTERS = map;
    }

    public static Class getKeywordConverter(String key) {
        return KEYWORK_CONVERTERS.get(key);
    }
}
