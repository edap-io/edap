package io.edap.log.test.encoder;

import io.edap.log.AbstractEncoder;
import io.edap.log.Encoder;
import io.edap.log.LogEvent;
import io.edap.log.converter.*;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.TextConverterFactory;

import java.io.OutputStream;

import static io.edap.log.helpers.Util.printError;

public class DemoEncoder extends AbstractEncoder implements Encoder {

    private final String pattern;

    private static CacheDateFormatterConverter CACHE_DATE_CONVERT =
            new CacheDateFormatterConverter("yyyy-MM-dd HH:mm:ss.SSS", "中文\n");

    private static TextConverter TEXT_CONVERTER_1 = TextConverterFactory.getTextConverter(" [");
    private static LevelConverter levelConverter = new LevelConverter("", null);

    private static MessageConverter messageConverter = new MessageConverter("", null);

    private static LoggerConverter loggerConverter = new LoggerConverter("", null);

    private static MicrosecondConverter microsecondConverter = new MicrosecondConverter("", null);

    private static RelativeTimeConverter relativeTimeConverter = new RelativeTimeConverter("", null);

    private static ClassOfCallerConverter classOfCallerConverter = new ClassOfCallerConverter("", null);

    private static MethodOfCallerConverter methodOfCallerConverter = new MethodOfCallerConverter("", null);

    private static LineOfCallerConverter lineOfCallerConverter = new LineOfCallerConverter("", null);

    private static FileOfCallerConverter fileOfCallerConverter = new FileOfCallerConverter("", null);

    private static MDCConverter mdcConverter = new MDCConverter("", null);
    public DemoEncoder() {
        pattern = "%date{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}]  - %msg %ex%n";
    }


    @Override
    public void encode(OutputStream out, LogEvent logEvent) {
        ByteArrayBuilder builder = LOCAL_BYTE_ARRAY_BUILDER.get();
        builder.reset();
        CACHE_DATE_CONVERT.convertTo(builder, logEvent);
        TEXT_CONVERTER_1.convertTo(builder, null);
        levelConverter.convertTo(builder, logEvent);
        messageConverter.convertTo(builder, logEvent);
        loggerConverter.convertTo(builder, logEvent);
        microsecondConverter.convertTo(builder, logEvent);
        relativeTimeConverter.convertTo(builder, logEvent);
        classOfCallerConverter.convertTo(builder, logEvent);
        methodOfCallerConverter.convertTo(builder, logEvent);
        lineOfCallerConverter.convertTo(builder, logEvent);
        fileOfCallerConverter.convertTo(builder, logEvent);
        mdcConverter.convertTo(builder, logEvent);
        try {
            builder.writeTo(out);
        } catch (Exception e) {
            printError("writeTo error", e);
        }
    }
}
