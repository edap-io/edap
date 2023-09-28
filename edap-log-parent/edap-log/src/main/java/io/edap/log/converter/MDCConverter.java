package io.edap.log.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.EncoderPatternToken;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;

import static io.edap.log.helpers.Util.printError;

public class MDCConverter implements Converter<LogEvent> {

    private final String format;
    private final byte[] nextText;
    private final String mdcKey;

    public MDCConverter(String format) {
        this(format, null);
    }

    public MDCConverter(String format, String nextText) {
        if (!format.startsWith("%")) {
            format = "%" + format;
        }
        EncoderPatternToken token = EncoderPatternToken.of(format, EncoderPatternToken.TokenType.ENCODER_FUNC);
        String key = null;
        try {
            int keyworkIndex = format.indexOf(token.getKeyword());
            if (format.length() > token.getKeyword().length() + 2) {
                key = format.substring(keyworkIndex + token.getKeyword().length()+1, format.indexOf("}"));
            }
        } catch (Throwable t) {
            printError("Get Logger length error!", t);
        }
        this.mdcKey = key;
        this.format = format;
        if (!StringUtil.isEmpty(nextText)) {
            this.nextText = nextText.getBytes(StandardCharsets.UTF_8);
        } else {
            this.nextText = null;
        }
    }
    @Override
    public void convertTo(ByteArrayBuilder out, LogEvent logEvent) {
        Object v = logEvent.getMdc()!=null?logEvent.getMdc().get(mdcKey):null;
        if (v != null) {
            out.append(v);
        }
        if (nextText != null) {
            out.append(nextText);
        }
    }
}
