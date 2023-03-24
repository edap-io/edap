package io.edap.log.test.encoder;

import io.edap.log.Encoder;
import io.edap.log.LogEvent;
import io.edap.log.converter.CacheDateFormatterConverter;
import io.edap.log.helps.ByteArrayBuilder;

import java.io.OutputStream;

import static io.edap.log.helpers.Util.printError;

public class DemoEncoder implements Encoder {

    static final ThreadLocal<ByteArrayBuilder> LOCAL_BYTE_ARRAY_BUILDER =
            ThreadLocal.withInitial(() -> new ByteArrayBuilder());

    private final String pattern = "%date{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}]  - %msg %ex%n";

    private static CacheDateFormatterConverter CACHE_DATE_CONVERT =
            new CacheDateFormatterConverter("yyyy-MM-dd HH:mm:ss.SSS", "中文");


    @Override
    public void encode(OutputStream out, LogEvent logEvent) {
        ByteArrayBuilder builder = LOCAL_BYTE_ARRAY_BUILDER.get();
        CACHE_DATE_CONVERT.convertTo(builder, logEvent.getLogTime());
        try {
            builder.writeTo(out);
        } catch (Exception e) {
            printError("writeTo error", e);
        }
    }
}
