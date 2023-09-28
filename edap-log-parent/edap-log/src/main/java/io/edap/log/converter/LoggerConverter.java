package io.edap.log.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.EncoderPatternToken;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.edap.log.helpers.Util.printError;
import static io.edap.log.util.LogUtil.abbreviateClassName;

public class LoggerConverter implements Converter<LogEvent> {

    private final String format;
    private final String nextText;

    private final int loggerNameLength;

    private final Map<String, Map<Integer, byte[]>> CACHE_LOGGER_BYTES = new ConcurrentHashMap<>();

    public LoggerConverter(String format) {
        this(format, null);
    }

    public LoggerConverter(String format, String nextText) {
        if (!format.startsWith("%")) {
            format = "%" + format;
        }
        EncoderPatternToken token = EncoderPatternToken.of(format, EncoderPatternToken.TokenType.ENCODER_FUNC);
        int len = 0;
        try {
            String keyword = token.getKeyword();
            int keyworkIndex = format.indexOf(keyword);
            if (format.length() > token.getKeyword().length() + 2) {
                String snum = format.substring(keyworkIndex+1+keyword.length(), format.indexOf("}"));
                len = Integer.parseInt(snum);
            }
        } catch (Throwable t) {
            printError("Get Logger length error!", t);
        }
        this.loggerNameLength = len;
        this.format = format;
        this.nextText = nextText;
    }
    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {
        Map<Integer, byte[]> loggerDatas = CACHE_LOGGER_BYTES.get(logEvent.getLoggerName());
        byte[] data = null;
        if (loggerDatas == null) {
            loggerDatas = new ConcurrentHashMap<>();
            CACHE_LOGGER_BYTES.put(logEvent.getLoggerName(), loggerDatas);
        } else {
            data = loggerDatas.get(loggerNameLength);
        }
        if (data != null) {
            out.append(data);
            return;
        }
        String name = abbreviateClassName(logEvent.getLoggerName(), loggerNameLength);
        if (nextText == null) {
            data = name.getBytes(StandardCharsets.UTF_8);
        } else {
            data = (name + nextText).getBytes(StandardCharsets.UTF_8);
        }
        loggerDatas.put(loggerNameLength, data);
        out.append(data);
    }

}
