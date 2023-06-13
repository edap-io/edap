package io.edap.log.helps;

import io.edap.util.EdapTime;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 */
public class LogTime {

    private volatile long curMills;
    private Map<SimpleDateFormat, byte[]> logTimes;
    private SimpleDateFormat defaultFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private Charset dftCharset = Charset.forName("utf-8");

    private volatile byte[] defaultTime;

    private LogTime() {
        long cur = EdapTime.instance().currentTimeMillis();
        curMills = cur;
        logTimes = new ConcurrentHashMap<>();
        defaultTime = defaultFormat.format(new Date(cur)).getBytes();
        setCurrent(cur);

        EdapTime.instance().addCallback(this::setCurrent, 0, 1, TimeUnit.MILLISECONDS);
    }

    private void setCurrent(final long currentMillis) {
        curMills = currentMillis;
        Date now = new Date(currentMillis);
        defaultTime = defaultFormat.format(now).getBytes();
        logTimes.keySet().forEach(k -> {
            logTimes.put(k, k.format(now).getBytes(dftCharset));
        });
    }

    public byte[] getLogTime() {
        return defaultTime;
    }

    public byte[] getTime(SimpleDateFormat timeFormat) {
        if (timeFormat == null) {
            timeFormat = defaultFormat;
        }
        byte[] time = logTimes.get(timeFormat);
        if (time != null) {
            return time;
        }
        time = timeFormat.format(new Date(curMills)).getBytes(dftCharset);
        logTimes.putIfAbsent(timeFormat, time);
        return time;
    }

    public static final LogTime instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final LogTime INSTANCE = new LogTime();
    }
}
