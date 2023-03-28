package io.edap.log.test.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.converter.LineSeparatorConverter;
import io.edap.log.converter.LoggerConverter;
import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestLineSeperatorConverter {

    @Test
    public void testConvert() throws NoSuchFieldException, IllegalAccessException {
        Converter loggerConverter = new LineSeparatorConverter("%logger%n");

        ByteArrayBuilder out = new ByteArrayBuilder();
        LogEvent logEvent = new LogEvent();
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "\n".getBytes());

        loggerConverter = new LineSeparatorConverter("%n", " - [");
        out.reset();
        logEvent = new LogEvent();
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "\n - [".getBytes());

        Field winField = loggerConverter.getClass().getDeclaredField("win");
        winField.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(winField, winField.getModifiers() & ~Modifier.FINAL);
        winField.set(loggerConverter, true);
        out.reset();
        logEvent = new LogEvent();
        loggerConverter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "\r\n - [".getBytes());
    }
}
