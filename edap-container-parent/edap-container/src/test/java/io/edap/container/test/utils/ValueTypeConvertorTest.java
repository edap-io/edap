package io.edap.container.test.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.edap.container.utils.ValueTypeConvertor.convertToInt;
import static io.edap.container.utils.ValueTypeConvertor.convertToLong;
import static org.junit.jupiter.api.Assertions.*;

public class ValueTypeConvertorTest {


    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "1",
            "99",
            "100",
            "999",
            "1000",
            "9999",
            "10000",
            "99999",
            "100000",
            "999999",
            "1000000",
            "9999999",
            "10000000",
            "99999999",
            "100000000",
            "999999999",
            Integer.MAX_VALUE + "",
            "0",
            "-1",
            "-99",
            "-100",
            "-999",
            "-1000",
            "-9999",
            "-10000",
            "-99999",
            "-100000",
            "-999999",
            "-1000000",
            "-9999999",
            "-10000000",
            "-99999999",
            "-100000000",
            "-999999999",
            Integer.MIN_VALUE + "",

    })
    public void testConvertToInt(String v) {
        assertEquals(Integer.parseInt(v), convertToInt(v));
    }

    @Test
    public void testConvertToIntException() {
        String v = null;
        assertEquals(0, convertToInt(v));

        v = "";
        assertEquals(0, convertToInt(v));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "11111111111";
                    convertToInt(v2);
                });
        assertTrue(thrown.getMessage().contains("11111111111 is too large for int"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "-11111111111";
                    convertToInt(v2);
                });
        assertTrue(thrown.getMessage().contains("-11111111111 is too large for int"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "1f11111111";
                    convertToInt(v2);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "-1f11111111";
                    convertToInt(v2);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "1",
            "99",
            "100",
            "999",
            "1000",
            "9999",
            "10000",
            "99999",
            "100000",
            "999999",
            "1000000",
            "9999999",
            "10000000",
            "99999999",
            "100000000",
            "999999999",
            "1000000000",
            "9999999990",
            "10000000000",
            "99999999999",
            "100000000000",
            "999999999999",
            "1000000000000",
            "9999999999999",
            "10000000000000",
            "99999999999999",
            "100000000000000",
            "999999999999999",
            "1000000000000000",
            "9999999999999999",
            "10000000000000000",
            "99999999999999999",
            "100000000000000000",
            "999999999999999999",
            "1000000000000000000",
            Long.MAX_VALUE + "",
            "0",
            "-1",
            "-99",
            "-100",
            "-999",
            "-1000",
            "-9999",
            "-10000",
            "-99999",
            "-100000",
            "-999999",
            "-1000000",
            "-9999999",
            "-10000000",
            "-99999999",
            "-100000000",
            "-999999999",
            Long.MIN_VALUE + "",

    })
    public void testConvertToLong(String v) {
        assertEquals(Long.parseLong(v), convertToLong(v));
    }

    @Test
    public void testConvertToLongException() {
        String v = null;
        assertEquals(0, convertToLong(v));

        v = "";
        assertEquals(0, convertToLong(v));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "11111111111111111111";
                    convertToLong(v2);
                });
        assertTrue(thrown.getMessage().contains("11111111111111111111 is too large for long"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "-111111111111111111111";
                    convertToLong(v2);
                });
        assertTrue(thrown.getMessage().contains("-111111111111111111111 is too large for long"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "1f11111111";
                    convertToLong(v2);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

        thrown = assertThrows(RuntimeException.class,
                () -> {
                    String v2 = "-1f11111111";
                    convertToLong(v2);
                });
        assertTrue(thrown.getMessage().contains("整数不符合规范"));

    }
}
