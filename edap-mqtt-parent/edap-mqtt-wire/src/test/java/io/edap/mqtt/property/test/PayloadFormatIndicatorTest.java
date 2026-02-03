package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.PayloadFormatIndicator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PayloadFormatIndicatorTest {

    @Test
    public void testName() {
        PayloadFormatIndicator pfi = new PayloadFormatIndicator();
        assertEquals(pfi.name(), "Payload Format Indicator");
    }

    @Test
    public void testIdentifier() {
        PayloadFormatIndicator pfi = new PayloadFormatIndicator();
        assertEquals(pfi.identifier(), PropertyType.PAYLOAD_FORMAT_INDICATOR.getType());
    }

    @Test
    public void testValue() {
        PayloadFormatIndicator pfi = new PayloadFormatIndicator();
        assertNotNull(pfi.value());
        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        pfi.value(value);
        assertEquals(value.byteValue(), value.byteValue());
    }
}
