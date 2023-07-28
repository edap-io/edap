package io.edap.log.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;

public class RelativeTimeConverter implements Converter<LogEvent> {

    private final String format;
    private final String nextText;

    public RelativeTimeConverter(String format) {
        this(format, null);
    }

    public RelativeTimeConverter(String format, String nextText) {
        this.format = format;
        this.nextText = nextText;
    }

    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {

    }
}
