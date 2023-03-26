package io.edap.log.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;

import java.nio.charset.StandardCharsets;

public class ThreadConverter implements Converter<LogEvent> {

    private final String format;
    private final byte[] nextText;

    public ThreadConverter(String format) {
        this(format, null);
    }

    public ThreadConverter(String format, String nextText) {
        this.format = format;
        this.nextText = nextText.getBytes(StandardCharsets.UTF_8);
    }
    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {
        out.append(logEvent.getThreadName());
        if (nextText != null) {
            out.append(nextText);
        }
    }
}
